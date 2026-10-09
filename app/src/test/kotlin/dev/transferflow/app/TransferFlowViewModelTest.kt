package dev.transferflow.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.transferflow.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransferFlowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    private fun vm(
        repo: FakeRepository,
        state: SavedStateHandle = SavedStateHandle(),
    ): TransferFlowViewModel =
        TransferFlowViewModel(repo, state).also { vm ->
            stores += ViewModelStore().apply { put("journey", vm) }
        }

    @Test
    fun `review and back edit allocate no request and confirm preserves displayed values`() =
        runTest(dispatcher) {
            val repo = FakeRepository()
            val vm = vm(repo)
            advanceUntilIdle()
            enterReview(vm)
            assertEquals(0, repo.prepareCalls)
            vm.back()
            vm.editDraft(name = "Robin", amount = "14,30")
            vm.reviewTransfer()
            val displayed = vm.state.value.review
            vm.confirm()
            advanceUntilIdle()
            assertEquals(displayed, repo.requests.single().review)
            assertEquals(Euro(1430), repo.requests.single().review.amount)
            assertEquals("Robin", repo.requests.single().review.recipient.name)
        }

    @Test
    fun `duplicate confirmation is serialized and request observation precedes transport`() =
        runTest(dispatcher) {
            val repo = FakeRepository().apply { prepareGate = CompletableDeferred() }
            val vm = vm(repo)
            advanceUntilIdle()
            enterReview(vm)
            val frozen = vm.state.value.review
            vm.confirm()
            vm.confirm()
            runCurrent()
            assertTrue(vm.state.value.busy)
            vm.editDraft(amount = "999.00")
            repo.prepareGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, repo.prepareCalls)
            assertEquals(1, repo.submitCalls)
            assertEquals(frozen, repo.requests.single().review)
            assertTrue(repo.observedBeforeSubmit)
        }

    @Test
    fun `offline confirmation preserves review and creates no attempt`() =
        runTest(dispatcher) {
            val repo = FakeRepository().apply { offline = true }
            val vm = vm(repo)
            advanceUntilIdle()
            enterReview(vm)
            val review = vm.state.value.review
            vm.confirm()
            advanceUntilIdle()
            assertEquals(JourneyScreen.REVIEW, vm.state.value.screen)
            assertEquals(JourneyMessage.OFFLINE, vm.state.value.message)
            assertEquals(review, vm.state.value.review)
            assertTrue(repo.requests.isEmpty())
            repo.offline = false
            vm.confirm()
            advanceUntilIdle()
            assertEquals(1, repo.requests.size)
        }

    @Test
    fun `unresolved recovery takes priority over saved draft and never sends prepared automatically`() =
        runTest(dispatcher) {
            val repo =
                FakeRepository().apply {
                    attempt.value = TransferAttempt(request(), 123, AttemptStatus.Prepared)
                }
            val saved =
                SavedStateHandle(
                    mapOf("draft.name" to "Unsaved other person", "journey.screen" to "FORM"),
                )
            val vm = vm(repo, saved)
            advanceUntilIdle()
            assertEquals(JourneyScreen.OUTCOME, vm.state.value.screen)
            assertEquals("Alex Morgan", vm.state.value.review!!.recipient.name)
            assertEquals(0, repo.submitCalls)
            vm.newTransfer()
            vm.back()
            assertEquals(JourneyScreen.OUTCOME, vm.state.value.screen)
            vm.sendPrepared()
            advanceUntilIdle()
            assertEquals(1, repo.submitCalls)
        }

    @Test
    fun `unknown retry retains immutable request and does lookup first`() =
        runTest(dispatcher) {
            val original = request()
            val repo =
                FakeRepository().apply {
                    attempt.value =
                        TransferAttempt(
                            original,
                            123,
                            AttemptStatus.Unknown(UncertaintyReason.TRANSPORT),
                        )
                }
            val vm = vm(repo)
            advanceUntilIdle()
            vm.retrySafely()
            vm.retrySafely()
            advanceUntilIdle()
            assertEquals(listOf("lookup", "retry"), repo.retryActions)
            assertEquals(original, repo.requests.single())
            assertEquals(original.key, vm.state.value.attempt!!.request.key)
        }

    @Test
    fun `cancellation leaves uncertainty and never displays rejection or generic action failure`() =
        runTest(dispatcher) {
            val repo = FakeRepository().apply { cancelAfterDispatch = true }
            val vm = vm(repo)
            advanceUntilIdle()
            enterReview(vm)
            vm.confirm()
            advanceUntilIdle()
            assertEquals(
                AttemptStatus.Unknown(UncertaintyReason.CANCELLED),
                vm.state.value.attempt!!.status,
            )
            assertNull(vm.state.value.message)
            assertFalse(vm.state.value.busy)
        }

    @Test
    fun `pending permits status checking but cannot submit again or start replacement`() =
        runTest(dispatcher) {
            val original = request()
            val repo =
                FakeRepository().apply {
                    attempt.value =
                        TransferAttempt(
                            original,
                            123,
                            AttemptStatus.Confirmed(operation(original, OperationStatus.Pending)),
                        )
                }
            val vm = vm(repo)
            advanceUntilIdle()
            vm.retrySafely()
            vm.sendPrepared()
            vm.newTransfer()
            assertEquals(JourneyScreen.OUTCOME, vm.state.value.screen)
            assertEquals(0, repo.submitCalls)
            vm.checkStatus()
            advanceUntilIdle()
            assertEquals(1, repo.checkCalls)
            assertTrue(vm.state.value.attempt!!.isTerminal())
        }

    @Test
    fun `restored review returns to editable form without preparing a request`() =
        runTest(dispatcher) {
            val repo = FakeRepository()
            val vm =
                vm(
                    repo,
                    SavedStateHandle(mapOf("draft.name" to "Robin", "journey.screen" to "REVIEW")),
                )
            advanceUntilIdle()
            assertEquals(JourneyScreen.FORM, vm.state.value.screen)
            assertEquals("Robin", vm.state.value.draft.recipientName)
            assertEquals(0, repo.prepareCalls)
        }

    @Test
    fun `invalid draft has actionable field issues without preparing`() =
        runTest(dispatcher) {
            val repo = FakeRepository()
            val vm = vm(repo)
            advanceUntilIdle()
            vm.newTransfer()
            vm.editDraft(name = "Alex", iban = "not an iban", amount = "12.345")
            vm.reviewTransfer()
            assertEquals(JourneyScreen.FORM, vm.state.value.screen)
            assertEquals(
                setOf(TransferField.IBAN, TransferField.AMOUNT),
                vm.state.value.issues.map { it.field }.toSet(),
            )
            assertEquals(0, repo.prepareCalls)
        }

    @Test
    fun `repeated invalid review requests reveal the error again`() =
        runTest(dispatcher) {
            val vm = vm(FakeRepository())
            advanceUntilIdle()
            vm.newTransfer()
            vm.reviewTransfer()
            val first = vm.state.value.validationVersion
            vm.reviewTransfer()
            assertEquals(first + 1, vm.state.value.validationVersion)
            assertEquals(TransferField.RECIPIENT_NAME, vm.state.value.issues.first().field)
        }

    private fun enterReview(vm: TransferFlowViewModel) {
        vm.newTransfer()
        vm.editDraft(name = "Alex Morgan", iban = "NL25DEMO0000000002", amount = "25.00")
        vm.reviewTransfer()
        assertEquals(JourneyScreen.REVIEW, vm.state.value.screen)
    }
}

private fun request() =
    TransferRequest(
        RequestKey("retained-key"),
        TransferReview(
            AccountId("demo-account"),
            Recipient("Alex Morgan", checkNotNull(DutchIban.parse("NL25DEMO0000000002"))),
            Euro(2500),
            Euro.ZERO,
        ),
    )

private fun operation(request: TransferRequest, status: OperationStatus) =
    GatewayOperation(OperationId("demo-operation"), request, status, 456)

/**
 * Models the repository's durable state transitions rather than returning unrelated canned values.
 */
private class FakeRepository : TransferRepository {
    val attempt = MutableStateFlow<TransferAttempt?>(null)
    private val dashboard =
        MutableStateFlow(
            DashboardSnapshot(
                AccountSnapshot(
                    AccountId("demo-account"),
                    "Everyday account",
                    checkNotNull(DutchIban.parse("NL52DEMO0000000001")),
                    Euro(245000),
                    Euro(245000),
                    123,
                ),
                emptyList(),
            ),
        )
    var offline = false
    var prepareGate: CompletableDeferred<Unit>? = null
    var prepareCalls = 0
    var submitCalls = 0
    var checkCalls = 0
    var cancelAfterDispatch = false
    var observedBeforeSubmit = false
    private val observedKeys = mutableListOf<RequestKey>()
    val requests = mutableListOf<TransferRequest>()
    val retryActions = mutableListOf<String>()

    override fun observeDashboard(): Flow<DashboardSnapshot> = dashboard

    override fun observeAttempt(key: RequestKey): Flow<TransferAttempt?> {
        observedKeys += key
        return attempt
    }

    override suspend fun findUnresolvedAttempt(): TransferAttempt? =
        attempt.value?.takeUnless { it.isTerminal() }

    override suspend fun refreshDashboard() = Unit

    override suspend fun prepare(review: TransferReview): PrepareResult {
        prepareCalls++
        prepareGate?.await()
        attempt.value
            ?.takeUnless { it.isTerminal() }
            ?.let {
                return PrepareResult.AlreadyActive(it)
            }
        if (offline) return PrepareResult.Offline
        val request = TransferRequest(RequestKey("retained-key"), review)
        val prepared = TransferAttempt(request, 123, AttemptStatus.Prepared)
        attempt.value = prepared
        return PrepareResult.Created(prepared)
    }

    override suspend fun submitPrepared(key: RequestKey) {
        val current = checkNotNull(attempt.value)
        if (current.status != AttemptStatus.Prepared || offline) return
        submitCalls++
        observedBeforeSubmit = key in observedKeys
        requests += current.request
        attempt.value = current.copy(status = AttemptStatus.Submitting)
        if (cancelAfterDispatch) {
            attempt.value =
                current.copy(status = AttemptStatus.Unknown(UncertaintyReason.CANCELLED))
            throw CancellationException("Interrupted transport")
        }
        attempt.value =
            current.copy(
                status =
                    AttemptStatus.Confirmed(operation(current.request, OperationStatus.Succeeded)),
            )
    }

    override suspend fun checkStatus(key: RequestKey) {
        checkCalls++
        val current = checkNotNull(attempt.value)
        attempt.value =
            current.copy(
                status =
                    AttemptStatus.Confirmed(operation(current.request, OperationStatus.Succeeded)),
            )
    }

    override suspend fun retryUnknown(key: RequestKey) {
        val current = checkNotNull(attempt.value)
        if (current.status !is AttemptStatus.Unknown) return
        retryActions += "lookup"
        retryActions += "retry"
        requests += current.request
        attempt.value =
            current.copy(
                status =
                    AttemptStatus.Confirmed(operation(current.request, OperationStatus.Succeeded)),
            )
    }
}
