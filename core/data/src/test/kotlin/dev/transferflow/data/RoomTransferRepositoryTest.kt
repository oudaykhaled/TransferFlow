package dev.transferflow.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.transferflow.domain.AccountId
import dev.transferflow.domain.AttemptStatus
import dev.transferflow.domain.DashboardSnapshot
import dev.transferflow.domain.EpochClock
import dev.transferflow.domain.Euro
import dev.transferflow.domain.GatewayOperation
import dev.transferflow.domain.LookupResult
import dev.transferflow.domain.OperationId
import dev.transferflow.domain.OperationStatus
import dev.transferflow.domain.PrepareResult
import dev.transferflow.domain.Recipient
import dev.transferflow.domain.RejectionReason
import dev.transferflow.domain.RequestKey
import dev.transferflow.domain.RequestKeyFactory
import dev.transferflow.domain.SubmitResult
import dev.transferflow.domain.TransferAttempt
import dev.transferflow.domain.TransferGateway
import dev.transferflow.domain.TransferRequest
import dev.transferflow.domain.TransferReview
import dev.transferflow.domain.UncertaintyReason
import dev.transferflow.domain.isTerminal
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomTransferRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefix = "test-${UUID.randomUUID()}"
    private val clients = mutableListOf<ClientDatabase>()
    private val ledgers = mutableListOf<GatewayDatabase>()
    private val controls = DemoControls()
    private val keyCount = AtomicInteger()
    private var now = 1_000L
    private val clock = EpochClock { now }
    private val keys = RequestKeyFactory { RequestKey("request-${keyCount.incrementAndGet()}") }
    private lateinit var client: ClientDatabase
    private lateinit var ledger: GatewayDatabase
    private lateinit var gateway: DemoTransferGateway
    private lateinit var repository: RoomTransferRepository

    @Before
    fun open() {
        client = newClient()
        ledger = newLedger()
        gateway = DemoTransferGateway(ledger, controls, clock)
        repository = repo(gateway)
    }

    @After
    fun close() {
        clients.forEach { it.close() }
        ledgers.forEach { it.close() }
        context.deleteDatabase("$prefix-client.db")
        context.deleteDatabase("$prefix-gateway.db")
    }

    @Test
    fun `timeout acceptance survives closing both stores with one debit and transaction`() =
        runBlocking {
            controls.setScenario(DemoScenario.TIMEOUT_AFTER_ACCEPTANCE)
            val prepared = prepare()
            repository.submitPrepared(prepared.request.key)
            assertTrue(attempt(prepared).status is AttemptStatus.Unknown)
            val accepted = ledger.dao().operations().single().toDomain()
            client.close()
            ledger.close()

            client = newClient()
            ledger = newLedger()
            controls.setScenario(DemoScenario.REJECTED)
            gateway = DemoTransferGateway(ledger, controls, clock)
            repository = repo(gateway)
            val recovered = requireNotNull(repository.findUnresolvedAttempt())
            assertEquals(prepared.request, recovered.request)
            repository.retryUnknown(recovered.request.key)

            assertEquals(accepted, (attempt(prepared).status as AttemptStatus.Confirmed).operation)
            assertEquals(1, ledger.dao().operations().size)
            assertEquals(1, ledger.dao().transactions().size)
            assertEquals(1, client.dao().transactions().size)
            assertEquals(
                DemoSeed.account().bookedBalance - prepared.request.review.amount,
                gateway.dashboard().account.bookedBalance,
            )
            assertNull(repository.findUnresolvedAttempt())
            assertEquals(1, keyCount.get())
        }

    @Test
    fun `two repositories and separate database handles create one active request`() = runBlocking {
        val other = repo(gateway, newClient())
        val results = coroutineScope {
            listOf(
                    async(Dispatchers.Default) { repository.prepare(review()) },
                    async(Dispatchers.Default) { other.prepare(review()) },
                )
                .map { it.await() }
        }
        assertEquals(1, results.count { it is PrepareResult.Created })
        assertEquals(1, results.count { it is PrepareResult.AlreadyActive })
        val created =
            (results.first { it is PrepareResult.Created } as PrepareResult.Created).attempt
        val active =
            (results.first { it is PrepareResult.AlreadyActive } as PrepareResult.AlreadyActive)
                .attempt
        assertEquals(created.request, active.request)
        assertEquals(1, keyCount.get())
    }

    @Test
    fun `duplicate sending across repositories dispatches once`() = runBlocking {
        val submits = AtomicInteger()
        val counted =
            object : TransferGateway by gateway {
                override suspend fun submit(request: TransferRequest): SubmitResult {
                    submits.incrementAndGet()
                    return gateway.submit(request)
                }
            }
        repository = repo(counted)
        val other = repo(counted, newClient())
        val prepared = prepare()
        coroutineScope {
            listOf(
                    async { repository.submitPrepared(prepared.request.key) },
                    async { other.submitPrepared(prepared.request.key) },
                )
                .forEach { it.await() }
        }
        assertEquals(1, submits.get())
        assertEquals(1, ledger.dao().operations().size)
        assertEquals(1, client.dao().transactions().size)
    }

    @Test
    fun `offline confirmation allocates neither key nor journal`() = runBlocking {
        controls.setOffline(true)
        assertEquals(PrepareResult.Offline, repository.prepare(review()))
        assertEquals(0, keyCount.get())
        assertNull(repository.findUnresolvedAttempt())
        assertTrue(ledger.dao().operations().isEmpty())
    }

    @Test
    fun `offline before dispatch remains prepared and reconnect never sends automatically`() =
        runBlocking {
            val prepared = prepare()
            controls.setOffline(true)
            repository.submitPrepared(prepared.request.key)
            assertEquals(AttemptStatus.Prepared, attempt(prepared).status)
            assertTrue(ledger.dao().operations().isEmpty())
            controls.setOffline(false)
            assertEquals(AttemptStatus.Prepared, repository.findUnresolvedAttempt()?.status)
            assertTrue(ledger.dao().operations().isEmpty())
            repository.submitPrepared(prepared.request.key)
            assertTrue(attempt(prepared).isTerminal())
        }

    @Test
    fun `pending survives reopen and settles once despite a new scenario and backwards clock`() =
        runBlocking {
            controls.setScenario(DemoScenario.PENDING)
            val prepared = prepare()
            repository.submitPrepared(prepared.request.key)
            val pending = (attempt(prepared).status as AttemptStatus.Confirmed).operation
            assertEquals(OperationStatus.Pending, pending.status)
            assertFalse(attempt(prepared).isTerminal())
            assertEquals(
                DemoSeed.account().bookedBalance,
                gateway.dashboard().account.bookedBalance,
            )
            assertEquals(
                DemoSeed.account().availableBalance - prepared.request.review.amount,
                gateway.dashboard().account.availableBalance,
            )
            assertTrue(ledger.dao().transactions().isEmpty())
            client.close()
            ledger.close()
            client = newClient()
            ledger = newLedger()
            controls.setScenario(DemoScenario.REJECTED)
            now = 100 // Wall-clock ordering must not prevent a legitimate terminal transition.
            gateway = DemoTransferGateway(ledger, controls, clock)
            repository = repo(gateway)
            assertEquals(
                pending,
                (repository.findUnresolvedAttempt()?.status as AttemptStatus.Confirmed).operation,
            )
            repository.checkStatus(prepared.request.key)
            repository.checkStatus(prepared.request.key)
            val succeeded = (attempt(prepared).status as AttemptStatus.Confirmed).operation
            assertEquals(pending.id, succeeded.id)
            assertEquals(OperationStatus.Succeeded, succeeded.status)
            assertEquals(1, ledger.dao().transactions().size)
            assertEquals(1, client.dao().transactions().size)
            assertEquals(
                gateway.dashboard().account.bookedBalance,
                gateway.dashboard().account.availableBalance,
            )
        }

    @Test
    fun `rejection preserves balances and reason and permits a new transfer`() = runBlocking {
        controls.setScenario(DemoScenario.REJECTED)
        val prepared = prepare()
        repository.submitPrepared(prepared.request.key)
        assertEquals(
            OperationStatus.Rejected(RejectionReason.DEMO_REJECTED),
            (attempt(prepared).status as AttemptStatus.Confirmed).operation.status,
        )
        assertEquals(DemoSeed.account().bookedBalance, gateway.dashboard().account.bookedBalance)
        assertEquals(
            DemoSeed.account().availableBalance,
            gateway.dashboard().account.availableBalance,
        )
        assertTrue(client.dao().transactions().isEmpty())
        assertNull(repository.findUnresolvedAttempt())
        assertTrue(repository.prepare(review()) is PrepareResult.Created)
    }

    @Test
    fun `cancellation after acceptance propagates and persists uncertainty`() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        val delayed =
            object : TransferGateway by gateway {
                override suspend fun submit(request: TransferRequest): SubmitResult {
                    gateway.submit(request)
                    accepted.complete(Unit)
                    awaitCancellation()
                }
            }
        repository = repo(delayed)
        val prepared = prepare()
        val job = launch(Dispatchers.Default) { repository.submitPrepared(prepared.request.key) }
        accepted.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(AttemptStatus.Unknown(UncertaintyReason.CANCELLED), attempt(prepared).status)
        assertEquals(1, ledger.dao().operations().size)
        repository.checkStatus(prepared.request.key)
        assertTrue(attempt(prepared).isTerminal())
        assertEquals(1, ledger.dao().transactions().size)
    }

    @Test
    fun `interrupted submitting repairs to unknown and retains the immutable request`() =
        runBlocking {
            val prepared = prepare()
            assertEquals(1, client.dao().claimAction(prepared.request.key.value, "PREPARED"))
            client.close()
            client = newClient()
            repository = repo(gateway)
            val recovered = requireNotNull(repository.findUnresolvedAttempt())
            assertEquals(prepared.request, recovered.request)
            assertEquals(AttemptStatus.Unknown(UncertaintyReason.CANCELLED), recovered.status)
            assertEquals(1, keyCount.get())
        }

    @Test
    fun `not found is uncertainty and retry looks up before resending the original request`() =
        runBlocking {
            val events = mutableListOf<String>()
            var first = true
            val lost =
                object : TransferGateway by gateway {
                    override suspend fun submit(request: TransferRequest): SubmitResult {
                        events += "submit:${request.key.value}"
                        return if (first) {
                            first = false
                            SubmitResult.Uncertain(UncertaintyReason.TRANSPORT)
                        } else gateway.submit(request)
                    }

                    override suspend fun lookup(payer: AccountId, key: RequestKey): LookupResult {
                        events += "lookup:${key.value}"
                        return gateway.lookup(payer, key)
                    }
                }
            repository = repo(lost)
            val prepared = prepare()
            repository.submitPrepared(prepared.request.key)
            repository.checkStatus(prepared.request.key)
            assertEquals(
                AttemptStatus.Unknown(UncertaintyReason.NOT_FOUND),
                attempt(prepared).status,
            )
            repository.retryUnknown(prepared.request.key)
            assertEquals(
                listOf(
                    "submit:request-1",
                    "lookup:request-1",
                    "lookup:request-1",
                    "submit:request-1",
                ),
                events,
            )
            assertEquals(prepared.request, attempt(prepared).request)
            assertEquals(1, keyCount.get())
            assertEquals(1, ledger.dao().operations().size)
        }

    @Test
    fun `failed lookup never resends or allocates a replacement key`() = runBlocking {
        val submits = AtomicInteger()
        val unavailable =
            object : TransferGateway by gateway {
                override suspend fun submit(request: TransferRequest): SubmitResult {
                    submits.incrementAndGet()
                    return SubmitResult.Uncertain(UncertaintyReason.TRANSPORT)
                }

                override suspend fun lookup(payer: AccountId, key: RequestKey) =
                    LookupResult.Unavailable(UncertaintyReason.SERVER)
            }
        repository = repo(unavailable)
        val prepared = prepare()
        repository.submitPrepared(prepared.request.key)
        repository.retryUnknown(prepared.request.key)
        assertEquals(AttemptStatus.Unknown(UncertaintyReason.SERVER), attempt(prepared).status)
        assertEquals(1, submits.get())
        assertEquals(1, keyCount.get())
        assertTrue(repository.prepare(review()) is PrepareResult.AlreadyActive)
    }

    @Test
    fun `failed or missing lookup preserves known pending`() = runBlocking {
        controls.setScenario(DemoScenario.PENDING)
        val prepared = prepare()
        repository.submitPrepared(prepared.request.key)
        val known = attempt(prepared)
        val unavailable =
            object : TransferGateway by gateway {
                override suspend fun lookup(payer: AccountId, key: RequestKey) =
                    LookupResult.Unavailable(UncertaintyReason.SERVER)
            }
        repo(unavailable).checkStatus(prepared.request.key)
        val missing =
            object : TransferGateway by gateway {
                override suspend fun lookup(payer: AccountId, key: RequestKey) =
                    LookupResult.NotFound
            }
        repo(missing).checkStatus(prepared.request.key)
        assertEquals(known, attempt(prepared))
        assertNotNull(repository.findUnresolvedAttempt())
    }

    @Test
    fun `mismatched full request cannot become confirmed success`() = runBlocking {
        val bad =
            object : TransferGateway by gateway {
                override suspend fun submit(request: TransferRequest): SubmitResult =
                    SubmitResult.Known(
                        GatewayOperation(
                            OperationId("bad-response"),
                            request.copy(review = request.review.copy(amount = Euro(1))),
                            OperationStatus.Succeeded,
                            now,
                        ),
                    )
            }
        repository = repo(bad)
        val prepared = prepare()
        repository.submitPrepared(prepared.request.key)
        assertEquals(
            AttemptStatus.Unknown(UncertaintyReason.INVALID_RESPONSE),
            attempt(prepared).status,
        )
        assertTrue(client.dao().transactions().isEmpty())
        assertNotNull(repository.findUnresolvedAttempt())
    }

    @Test
    fun `conditional writes reject late pending and unknown after terminal success`() =
        runBlocking {
            val prepared = prepare()
            repository.submitPrepared(prepared.request.key)
            val success = attempt(prepared)
            assertEquals(
                0,
                client
                    .dao()
                    .recordOperation(
                        prepared.request.key.value,
                        "PENDING",
                        "late-operation",
                        null,
                        now + 1,
                    ),
            )
            assertEquals(
                0,
                client
                    .dao()
                    .markUnknown(prepared.request.key.value, UncertaintyReason.TRANSPORT.name),
            )
            assertEquals(success, attempt(prepared))
            assertEquals(1, client.dao().transactions().size)
            assertNull(repository.findUnresolvedAttempt())
        }

    @Test
    fun `failed transaction projection rolls back outcome and slot release atomically`() =
        runBlocking {
            val prepared = prepare()
            withContext(Dispatchers.IO) {
                client.openHelper.writableDatabase.execSQL(
                    """
                    CREATE TRIGGER fail_projection BEFORE INSERT ON transactions
                    BEGIN SELECT RAISE(ABORT, 'Synthetic projection failure'); END
                    """
                        .trimIndent(),
                )
            }
            repository.submitPrepared(prepared.request.key)
            assertTrue(attempt(prepared).status is AttemptStatus.Unknown)
            assertNotNull(repository.findUnresolvedAttempt())
            assertTrue(client.dao().transactions().isEmpty())
            assertEquals(1, ledger.dao().transactions().size)
            withContext(Dispatchers.IO) {
                client.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_projection")
            }
            repository.checkStatus(prepared.request.key)
            assertTrue(attempt(prepared).isTerminal())
            assertEquals(1, client.dao().transactions().size)
            assertNull(repository.findUnresolvedAttempt())
        }

    @Test
    fun `failed dashboard refresh preserves cached content and freshness`() = runBlocking {
        repository.refreshDashboard()
        val original = repository.observeDashboard().first()
        now += 50_000
        val failed =
            object : TransferGateway by gateway {
                override suspend fun dashboard(): DashboardSnapshot =
                    throw IOException("Synthetic read failure")
            }
        try {
            repo(failed).refreshDashboard()
            fail("Read failure should reach the caller")
        } catch (_: IOException) {
            assertEquals(original, repository.observeDashboard().first())
        }
    }

    @Test
    fun `confirmation checks balance and fee policy before allocating a key`() = runBlocking {
        assertTrue(
            repository.prepare(review().copy(amount = Euro(2_000_000))) is PrepareResult.Invalid,
        )
        assertEquals(0, keyCount.get())
        try {
            repository.prepare(review().copy(fee = Euro(1)))
            fail("Fee mismatch must fail explicitly")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, keyCount.get())
        }
    }

    private fun review() =
        TransferReview(
            DemoSeed.account().id,
            Recipient("Demo recipient", DemoSeed.recipientIban),
            Euro(1_234),
            Euro.ZERO,
        )

    private suspend fun prepare() = (repository.prepare(review()) as PrepareResult.Created).attempt

    private suspend fun attempt(prepared: TransferAttempt) =
        requireNotNull(repository.observeAttempt(prepared.request.key).first())

    private fun newClient() =
        Room.databaseBuilder(context, ClientDatabase::class.java, "$prefix-client.db")
            .build()
            .also { clients += it }

    private fun newLedger() =
        Room.databaseBuilder(context, GatewayDatabase::class.java, "$prefix-gateway.db")
            .build()
            .also { ledgers += it }

    private fun repo(transport: TransferGateway, store: ClientDatabase = client) =
        RoomTransferRepository(store, transport, controls, clock, keys, coordinationKey = prefix)
}
