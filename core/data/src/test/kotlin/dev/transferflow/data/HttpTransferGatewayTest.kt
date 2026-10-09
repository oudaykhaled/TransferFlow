package dev.transferflow.data

import dev.transferflow.domain.Euro
import dev.transferflow.domain.LookupResult
import dev.transferflow.domain.OperationId
import dev.transferflow.domain.Recipient
import dev.transferflow.domain.RequestKey
import dev.transferflow.domain.SubmitResult
import dev.transferflow.domain.TransferRequest
import dev.transferflow.domain.TransferReview
import dev.transferflow.domain.UncertaintyReason
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HttpTransferGatewayTest {
    private val server = MockWebServer()
    private val request =
        TransferRequest(
            RequestKey("request-123"),
            TransferReview(
                DemoSeed.account().id,
                Recipient("Demo recipient", DemoSeed.recipientIban),
                Euro(1_234),
                Euro(25),
            ),
        )
    private lateinit var gateway: HttpTransferGateway

    @Before
    fun start() {
        server.start()
        val client =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        gateway = HttpTransferGateway(server.url("/"), client)
    }

    @After fun stop() = server.shutdown()

    @Test
    fun `POST carries the same idempotency key integer cents and EUR`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(body()))
        val result = gateway.submit(request) as SubmitResult.Known
        assertEquals(request, result.operation.request)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/transfers", recorded.path)
        assertEquals(request.key.value, recorded.getHeader("Idempotency-Key"))
        val json = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("1234", json.getValue("amountMinor").jsonPrimitive.content)
        assertFalse(json.getValue("amountMinor").jsonPrimitive.isString)
        assertEquals("25", json.getValue("feeMinor").jsonPrimitive.content)
        assertEquals("EUR", json.getValue("currency").jsonPrimitive.content)
        assertEquals(request.key.value, json.getValue("key").jsonPrimitive.content)
    }

    @Test
    fun `known status mappings preserve operation and rejection reason`() = runBlocking {
        listOf(
                Triple(200, "SUCCEEDED", null),
                Triple(202, "PENDING", null),
                Triple(422, "REJECTED", "INSUFFICIENT_FUNDS"),
            )
            .forEach { (code, status, reason) ->
                server.enqueue(MockResponse().setResponseCode(code).setBody(body(status, reason)))
                val result = gateway.submit(request) as SubmitResult.Known
                assertEquals(operationStatus(status, reason), result.operation.status)
                assertEquals(OperationId("operation-1"), result.operation.id)
            }
    }

    @Test
    fun `key conflict is distinct from an authoritative rejection`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("conflict"))
        assertEquals(SubmitResult.KeyConflict, gateway.submit(request))
    }

    @Test
    fun `unstructured errors and server failures remain uncertain`() = runBlocking {
        listOf(400, 401, 403, 429, 500, 503).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("not an operation"))
            assertEquals(SubmitResult.Uncertain(UncertaintyReason.SERVER), gateway.submit(request))
        }
    }

    @Test
    fun `malformed mismatched or semantically invalid success bodies remain uncertain`() =
        runBlocking {
            val canonical = body()
            val invalid =
                listOf(
                    "not-json",
                    canonical.replace("request-123", "different-key"),
                    canonical.replace("demo-current-account", "different-payer"),
                    canonical.replace("Demo recipient", "Different recipient"),
                    canonical.replace("1234", "1235"),
                    canonical.replace("EUR", "USD"),
                    canonical.replace("SUCCEEDED", "UNKNOWN"),
                    canonical.replace("operation-1", ""),
                    canonical.replace("1234", "-1"),
                    canonical.replace("NL25DEMO0000000002", "NL00DEMO0000000002"),
                )
            invalid.forEach { value ->
                server.enqueue(MockResponse().setResponseCode(201).setBody(value))
                assertEquals(
                    SubmitResult.Uncertain(UncertaintyReason.INVALID_RESPONSE),
                    gateway.submit(request),
                )
            }
        }

    @Test
    fun `HTTP status must agree with the operation state`() = runBlocking {
        listOf(201 to body("PENDING"), 202 to body(), 422 to body()).forEach { (code, value) ->
            server.enqueue(MockResponse().setResponseCode(code).setBody(value))
            assertEquals(
                SubmitResult.Uncertain(UncertaintyReason.INVALID_RESPONSE),
                gateway.submit(request),
            )
        }
    }

    @Test
    fun `lookup sends payer and original key and validates both on response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body()))
        assertTrue(gateway.lookup(request.review.payerAccountId, request.key) is LookupResult.Found)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals(
            "/transfers/by-key/request-123?payerAccountId=demo-current-account",
            recorded.path,
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(body().replace("request-123", "other-key")),
        )
        assertEquals(
            LookupResult.Unavailable(UncertaintyReason.INVALID_RESPONSE),
            gateway.lookup(request.review.payerAccountId, request.key),
        )
    }

    @Test
    fun `lookup not found and unavailable never become rejection`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            LookupResult.NotFound,
            gateway.lookup(request.review.payerAccountId, request.key),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(
            LookupResult.Unavailable(UncertaintyReason.SERVER),
            gateway.lookup(request.review.payerAccountId, request.key),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("malformed"))
        assertEquals(
            LookupResult.Unavailable(UncertaintyReason.INVALID_RESPONSE),
            gateway.lookup(request.review.payerAccountId, request.key),
        )
    }

    @Test
    fun `transport failure remains unknown`() = runBlocking {
        server.shutdown()
        assertEquals(SubmitResult.Uncertain(UncertaintyReason.TRANSPORT), gateway.submit(request))
        assertEquals(
            LookupResult.Unavailable(UncertaintyReason.TRANSPORT),
            gateway.lookup(request.review.payerAccountId, request.key),
        )
    }

    @Test
    fun `cancelling HTTP work propagates cancellation instead of a rejection`() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody(body()))
        val job = async(Dispatchers.Default) { gateway.submit(request) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `dashboard decodes exact money and rejects duplicate operation IDs`() = runBlocking {
        val transaction =
            TransactionDto(
                "operation-1",
                "Demo recipient",
                DemoSeed.recipientIban.value,
                1_234,
                25,
                1_000,
                "EUR",
            )
        val account = DemoSeed.account(2_000)
        val dto =
            DashboardDto(
                AccountDto(
                    account.id.value,
                    account.displayName,
                    account.iban.value,
                    account.bookedBalance.minor,
                    account.availableBalance.minor,
                    2_000,
                    "EUR",
                ),
                listOf(transaction),
            )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Json.encodeToString(dto)))
        assertEquals(Euro(1_234), gateway.dashboard().transactions.single().amount)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    Json.encodeToString(dto.copy(transactions = listOf(transaction, transaction))),
                ),
        )
        try {
            gateway.dashboard()
            fail("Duplicate IDs must invalidate a dashboard response")
        } catch (_: IllegalArgumentException) {
            // No partial snapshot is returned to the repository.
        }
    }

    @Test
    fun `dashboard rejects individually valid money whose total overflows`() = runBlocking {
        val account = DemoSeed.account(2_000)
        val dto =
            DashboardDto(
                AccountDto(
                    account.id.value,
                    account.displayName,
                    account.iban.value,
                    account.bookedBalance.minor,
                    account.availableBalance.minor,
                    2_000,
                    "EUR",
                ),
                listOf(
                    TransactionDto(
                        "overflow-operation",
                        "Demo recipient",
                        DemoSeed.recipientIban.value,
                        Long.MAX_VALUE,
                        1,
                        1_000,
                        "EUR",
                    ),
                ),
            )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Json.encodeToString(dto)))
        try {
            gateway.dashboard()
            fail("An overflowing total must invalidate the complete dashboard response")
        } catch (_: ArithmeticException) {
            // The repository receives no snapshot to cache or mark fresh.
        }
    }

    private fun body(status: String = "SUCCEEDED", reason: String? = null) =
        Json.encodeToString(
            OperationDto("operation-1", RequestDto.from(request), status, 1_000, reason),
        )
}
