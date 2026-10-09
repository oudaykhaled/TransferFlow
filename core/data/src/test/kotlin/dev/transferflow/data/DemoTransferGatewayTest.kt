package dev.transferflow.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.transferflow.domain.AccountId
import dev.transferflow.domain.EpochClock
import dev.transferflow.domain.Euro
import dev.transferflow.domain.LookupResult
import dev.transferflow.domain.OperationStatus
import dev.transferflow.domain.Recipient
import dev.transferflow.domain.RejectionReason
import dev.transferflow.domain.RequestKey
import dev.transferflow.domain.SubmitResult
import dev.transferflow.domain.TransferPolicy
import dev.transferflow.domain.TransferRequest
import dev.transferflow.domain.TransferReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DemoTransferGatewayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room.inMemoryDatabaseBuilder(context, GatewayDatabase::class.java).build()
    private val controls = DemoControls()
    private val clock = EpochClock { 1_000 }
    private val gateway = DemoTransferGateway(database, controls, clock)

    @After fun close() = database.close()

    @Test
    fun `identical replay returns the same operation without a second money movement`() =
        runBlocking {
            val request = request()
            val first = gateway.submit(request) as SubmitResult.Known
            controls.setScenario(DemoScenario.REJECTED)
            val replay = gateway.submit(request) as SubmitResult.Known
            assertEquals(first, replay)
            assertEquals(1, database.dao().operations().size)
            assertEquals(1, database.dao().transactions().size)
            assertEquals(
                DemoSeed.account().bookedBalance - request.review.amount,
                gateway.dashboard().account.bookedBalance,
            )
        }

    @Test
    fun `same payer and key with changed canonical payload conflicts without moving funds`() =
        runBlocking {
            val request = request()
            gateway.submit(request)
            val balance = gateway.dashboard().account
            val changes =
                listOf(
                    request.copy(review = request.review.copy(amount = Euro(2_000))),
                    request.copy(
                        review =
                            request.review.copy(
                                recipient = request.review.recipient.copy(name = "Different name"),
                            ),
                    ),
                    request.copy(review = request.review.copy(fee = Euro(1))),
                )
            changes.forEach { assertEquals(SubmitResult.KeyConflict, gateway.submit(it)) }
            assertEquals(balance, gateway.dashboard().account)
            assertEquals(1, database.dao().operations().size)
            assertEquals(1, database.dao().transactions().size)
        }

    @Test
    fun `concurrent different keys atomically check authoritative available funds`() = runBlocking {
        val outcomes = coroutineScope {
            listOf(
                    async(Dispatchers.Default) { gateway.submit(request("one", 800_000)) },
                    async(Dispatchers.Default) { gateway.submit(request("two", 800_000)) },
                )
                .map { (it.await() as SubmitResult.Known).operation.status }
        }
        assertEquals(1, outcomes.count { it == OperationStatus.Succeeded })
        assertEquals(
            1,
            outcomes.count { it == OperationStatus.Rejected(RejectionReason.INSUFFICIENT_FUNDS) },
        )
        assertEquals(Euro(448_055), gateway.dashboard().account.availableBalance)
        assertEquals(2, database.dao().operations().size)
        assertEquals(1, database.dao().transactions().size)
    }

    @Test
    fun `amount limit rejection is durable and changes no money`() = runBlocking {
        val limited =
            DemoTransferGateway(database, controls, clock, TransferPolicy(Euro(100), Euro.ZERO))
        val request = request()
        val result = limited.submit(request) as SubmitResult.Known
        assertEquals(
            OperationStatus.Rejected(RejectionReason.TRANSFER_LIMIT),
            result.operation.status,
        )
        assertEquals(
            result.operation,
            (limited.lookup(request.review.payerAccountId, request.key) as LookupResult.Found)
                .operation,
        )
        assertEquals(DemoSeed.account().bookedBalance, limited.dashboard().account.bookedBalance)
        assertTrue(database.dao().transactions().isEmpty())
    }

    @Test
    fun `nonzero fee is debited once and retained in transaction history`() = runBlocking {
        val fee = Euro(35)
        val withFee =
            DemoTransferGateway(database, controls, clock, TransferPolicy(Euro(10_000_000), fee))
        val request = request().copy(review = request().review.copy(fee = fee))
        withFee.submit(request)
        withFee.submit(request)
        assertEquals(
            DemoSeed.account().bookedBalance - (request.review.amount + fee),
            withFee.dashboard().account.bookedBalance,
        )
        assertEquals(fee, database.dao().transactions().single().toDomain().fee)
    }

    @Test
    fun `pending replay never reserves twice and repeated lookup never books twice`() =
        runBlocking {
            controls.setScenario(DemoScenario.PENDING)
            val request = request()
            val pending = gateway.submit(request) as SubmitResult.Known
            assertEquals(pending, gateway.submit(request))
            assertEquals(
                DemoSeed.account().availableBalance - request.review.amount,
                gateway.dashboard().account.availableBalance,
            )
            val first =
                gateway.lookup(request.review.payerAccountId, request.key) as LookupResult.Found
            val second =
                gateway.lookup(request.review.payerAccountId, request.key) as LookupResult.Found
            assertEquals(first, second)
            assertEquals(pending.operation.id, first.operation.id)
            assertEquals(OperationStatus.Succeeded, first.operation.status)
            assertEquals(1, database.dao().transactions().size)
            assertEquals(
                gateway.dashboard().account.bookedBalance,
                gateway.dashboard().account.availableBalance,
            )
        }

    @Test
    fun `lookup is scoped to payer and unknown keys remain absent`() = runBlocking {
        val request = request()
        gateway.submit(request)
        assertEquals(LookupResult.NotFound, gateway.lookup(AccountId("other-payer"), request.key))
        assertEquals(
            LookupResult.NotFound,
            gateway.lookup(request.review.payerAccountId, RequestKey("missing")),
        )
    }

    private fun request(key: String = "request", amountMinor: Long = 1_234) =
        TransferRequest(
            RequestKey(key),
            TransferReview(
                DemoSeed.account().id,
                Recipient("Demo recipient", DemoSeed.recipientIban),
                Euro(amountMinor),
                Euro.ZERO,
            ),
        )
}
