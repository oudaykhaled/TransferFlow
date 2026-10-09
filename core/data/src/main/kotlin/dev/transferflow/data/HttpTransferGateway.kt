package dev.transferflow.data

import dev.transferflow.domain.AccountId
import dev.transferflow.domain.AccountSnapshot
import dev.transferflow.domain.DashboardSnapshot
import dev.transferflow.domain.DutchIban
import dev.transferflow.domain.Euro
import dev.transferflow.domain.GatewayOperation
import dev.transferflow.domain.LookupResult
import dev.transferflow.domain.OperationId
import dev.transferflow.domain.OperationStatus
import dev.transferflow.domain.Recipient
import dev.transferflow.domain.RequestKey
import dev.transferflow.domain.SubmitResult
import dev.transferflow.domain.TransactionRecord
import dev.transferflow.domain.TransferGateway
import dev.transferflow.domain.TransferRequest
import dev.transferflow.domain.TransferReview
import dev.transferflow.domain.UncertaintyReason
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Optional real transport adapter. The default demo never needs a server. */
class HttpTransferGateway(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient = OkHttpClient(),
) : TransferGateway {
    private val json = Json { ignoreUnknownKeys = false }
    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun dashboard(): DashboardSnapshot =
        withContext(Dispatchers.IO) {
            execute(Request.Builder().url(endpoint("dashboard")).build()).use { response ->
                check(response.code == 200) { "Dashboard unavailable" }
                json
                    .decodeFromString<DashboardDto>(requireNotNull(response.body).string())
                    .toDomain()
            }
        }

    override suspend fun submit(request: TransferRequest): SubmitResult =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(RequestDto.from(request)).toRequestBody(jsonMediaType)
            val httpRequest =
                Request.Builder()
                    .url(endpoint("transfers"))
                    .header("Idempotency-Key", request.key.value)
                    .post(body)
                    .build()
            try {
                execute(httpRequest).use { response ->
                    when (response.code) {
                        409 -> SubmitResult.KeyConflict
                        200,
                        201,
                        202,
                        422 -> {
                            val operation = decodeOperation(response)
                            val expectedStatus =
                                when (response.code) {
                                    202 -> operation.status == OperationStatus.Pending
                                    422 -> operation.status is OperationStatus.Rejected
                                    else -> operation.status == OperationStatus.Succeeded
                                }
                            if (operation.request != request || !expectedStatus)
                                SubmitResult.Uncertain(UncertaintyReason.INVALID_RESPONSE)
                            else SubmitResult.Known(operation)
                        }
                        else -> SubmitResult.Uncertain(UncertaintyReason.SERVER)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                SubmitResult.Uncertain(UncertaintyReason.TRANSPORT)
            } catch (_: SerializationException) {
                SubmitResult.Uncertain(UncertaintyReason.INVALID_RESPONSE)
            } catch (_: IllegalArgumentException) {
                SubmitResult.Uncertain(UncertaintyReason.INVALID_RESPONSE)
            } catch (_: ArithmeticException) {
                SubmitResult.Uncertain(UncertaintyReason.INVALID_RESPONSE)
            }
        }

    override suspend fun lookup(payer: AccountId, key: RequestKey): LookupResult =
        withContext(Dispatchers.IO) {
            val url =
                endpoint("transfers", "by-key", key.value)
                    .newBuilder()
                    .addQueryParameter("payerAccountId", payer.value)
                    .build()
            try {
                execute(Request.Builder().url(url).build()).use { response ->
                    when (response.code) {
                        404 -> LookupResult.NotFound
                        200 -> {
                            val operation = decodeOperation(response)
                            if (
                                operation.request.key != key ||
                                    operation.request.review.payerAccountId != payer
                            )
                                LookupResult.Unavailable(UncertaintyReason.INVALID_RESPONSE)
                            else LookupResult.Found(operation)
                        }
                        else -> LookupResult.Unavailable(UncertaintyReason.SERVER)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                LookupResult.Unavailable(UncertaintyReason.TRANSPORT)
            } catch (_: SerializationException) {
                LookupResult.Unavailable(UncertaintyReason.INVALID_RESPONSE)
            } catch (_: IllegalArgumentException) {
                LookupResult.Unavailable(UncertaintyReason.INVALID_RESPONSE)
            } catch (_: ArithmeticException) {
                LookupResult.Unavailable(UncertaintyReason.INVALID_RESPONSE)
            }
        }

    private fun endpoint(vararg segments: String): HttpUrl =
        baseUrl
            .newBuilder()
            .apply {
                segments.forEach(::addPathSegment)
            }
            .build()

    private fun decodeOperation(response: Response): GatewayOperation =
        json.decodeFromString<OperationDto>(requireNotNull(response.body).string()).toDomain()

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, abandonedResponse, _ ->
                            abandonedResponse.close()
                        }
                    }
                },
            )
        }
}

@Serializable
internal data class RequestDto(
    val key: String,
    val payerAccountId: String,
    val recipientName: String,
    val recipientIban: String,
    val amountMinor: Long,
    val feeMinor: Long,
    val currency: String,
) {
    fun toDomain(): TransferRequest {
        require(currency == "EUR" && amountMinor > 0 && feeMinor >= 0)
        require(
            recipientName.isNotBlank() &&
                recipientName == recipientName.trim() &&
                recipientName.codePointCount(0, recipientName.length) <= 70,
        )
        val iban = requireNotNull(DutchIban.parse(recipientIban))
        require(iban.value == recipientIban) { "Response IBAN must be canonical" }
        Euro(amountMinor) + Euro(feeMinor)
        return TransferRequest(
            RequestKey(key),
            TransferReview(
                AccountId(payerAccountId),
                Recipient(recipientName, iban),
                Euro(amountMinor),
                Euro(feeMinor),
            ),
        )
    }

    companion object {
        fun from(request: TransferRequest) =
            RequestDto(
                request.key.value,
                request.review.payerAccountId.value,
                request.review.recipient.name,
                request.review.recipient.iban.value,
                request.review.amount.minor,
                request.review.fee.minor,
                "EUR",
            )
    }
}

@Serializable
internal data class OperationDto(
    val operationId: String,
    val request: RequestDto,
    val status: String,
    val updatedAtEpochMillis: Long,
    val rejectionReason: String? = null,
) {
    fun toDomain(): GatewayOperation {
        require(updatedAtEpochMillis >= 0)
        if (status != "REJECTED") require(rejectionReason == null)
        return GatewayOperation(
            OperationId(operationId),
            request.toDomain(),
            operationStatus(status, rejectionReason),
            updatedAtEpochMillis,
        )
    }
}

@Serializable
internal data class AccountDto(
    val id: String,
    val displayName: String,
    val iban: String,
    val bookedBalanceMinor: Long,
    val availableBalanceMinor: Long,
    val cachedAtEpochMillis: Long,
    val currency: String,
) {
    fun toDomain(): AccountSnapshot {
        require(
            currency == "EUR" &&
                cachedAtEpochMillis >= 0 &&
                availableBalanceMinor <= bookedBalanceMinor,
        )
        return AccountSnapshot(
            AccountId(id),
            displayName,
            requireNotNull(DutchIban.parse(iban)),
            Euro(bookedBalanceMinor),
            Euro(availableBalanceMinor),
            cachedAtEpochMillis,
        )
    }
}

@Serializable
internal data class TransactionDto(
    val operationId: String,
    val recipientName: String,
    val recipientIban: String,
    val amountMinor: Long,
    val feeMinor: Long,
    val bookedAtEpochMillis: Long,
    val currency: String,
) {
    fun toDomain(): TransactionRecord {
        require(currency == "EUR" && amountMinor > 0 && bookedAtEpochMillis >= 0)
        require(
            recipientName.isNotBlank() &&
                recipientName.codePointCount(0, recipientName.length) <= 70,
        )
        val amount = Euro(amountMinor)
        val fee = Euro(feeMinor)
        amount + fee
        return TransactionRecord(
            OperationId(operationId),
            Recipient(recipientName, requireNotNull(DutchIban.parse(recipientIban))),
            amount,
            fee,
            bookedAtEpochMillis,
        )
    }
}

@Serializable
internal data class DashboardDto(val account: AccountDto, val transactions: List<TransactionDto>) {
    fun toDomain(): DashboardSnapshot {
        val records = transactions.map(TransactionDto::toDomain)
        require(records.map { it.operationId }.distinct().size == records.size) {
            "Duplicate transaction IDs"
        }
        return DashboardSnapshot(account.toDomain(), records)
    }
}
