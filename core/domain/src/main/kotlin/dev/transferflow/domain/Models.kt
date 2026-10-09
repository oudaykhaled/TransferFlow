package dev.transferflow.domain

import java.util.Locale

@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "Account ID cannot be blank" }
    }
}

@JvmInline
value class RequestKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Request key cannot be blank" }
    }
}

@JvmInline
value class OperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Operation ID cannot be blank" }
    }
}

/** A normalized Dutch IBAN whose structure and MOD97 checksum have been verified. */
@JvmInline
value class DutchIban private constructor(val value: String) {
    companion object {
        private val structure = Regex("NL[0-9]{2}[A-Z]{4}[0-9]{10}")

        fun parse(input: String): DutchIban? {
            val normalized = input.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
            if (!structure.matches(normalized)) return null
            val rearranged = normalized.substring(4) + normalized.substring(0, 4)
            var remainder = 0
            for (character in rearranged) {
                val number = if (character in '0'..'9') character - '0' else character - 'A' + 10
                remainder =
                    if (number < 10) {
                        (remainder * 10 + number) % 97
                    } else {
                        (remainder * 100 + number) % 97
                    }
            }
            return if (remainder == 1) DutchIban(normalized) else null
        }
    }
}

data class Recipient(val name: String, val iban: DutchIban)

data class TransferDraft(val recipientName: String, val ibanInput: String, val amountInput: String)

data class TransferPolicy(val maximumAmount: Euro, val fee: Euro) {
    companion object {
        val DEFAULT: TransferPolicy =
            TransferPolicy(maximumAmount = Euro(10_000_000), fee = Euro.ZERO)
    }
}

data class AccountSnapshot(
    val id: AccountId,
    val displayName: String,
    val iban: DutchIban,
    val bookedBalance: Euro,
    val availableBalance: Euro,
    val cachedAtEpochMillis: Long,
)

data class TransferReview(
    val payerAccountId: AccountId,
    val recipient: Recipient,
    val amount: Euro,
    val fee: Euro,
)

data class TransferRequest(val key: RequestKey, val review: TransferReview)

enum class RejectionReason {
    INSUFFICIENT_FUNDS,
    TRANSFER_LIMIT,
    DEMO_REJECTED,
}

sealed interface OperationStatus {
    data object Pending : OperationStatus

    data object Succeeded : OperationStatus

    data class Rejected(val reason: RejectionReason) : OperationStatus
}

data class GatewayOperation(
    val id: OperationId,
    val request: TransferRequest,
    val status: OperationStatus,
    val updatedAtEpochMillis: Long,
)

enum class UncertaintyReason {
    TRANSPORT,
    CANCELLED,
    INVALID_RESPONSE,
    SERVER,
    KEY_CONFLICT,
    NOT_FOUND,
}

sealed interface AttemptStatus {
    data object Prepared : AttemptStatus

    data object Submitting : AttemptStatus

    data class Unknown(val reason: UncertaintyReason) : AttemptStatus

    data class Confirmed(val operation: GatewayOperation) : AttemptStatus
}

data class TransferAttempt(
    val request: TransferRequest,
    val createdAtEpochMillis: Long,
    val status: AttemptStatus,
)

data class TransactionRecord(
    val operationId: OperationId,
    val recipient: Recipient,
    val amount: Euro,
    val fee: Euro,
    val bookedAtEpochMillis: Long,
)

data class DashboardSnapshot(
    val account: AccountSnapshot,
    val transactions: List<TransactionRecord>,
)

/** Pending is authoritative but still unresolved; uncertainty never releases an active request. */
fun TransferAttempt.isTerminal(): Boolean =
    (status as? AttemptStatus.Confirmed)?.operation?.status?.isTerminal() == true

fun OperationStatus.isTerminal(): Boolean =
    this is OperationStatus.Succeeded || this is OperationStatus.Rejected
