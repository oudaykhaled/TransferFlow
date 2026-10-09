package dev.transferflow.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.transferflow.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** The journal is the authority after confirmation; saved state contains only editable UI state. */
class TransferFlowViewModel(
    private val repository: TransferRepository,
    private val savedStateHandle: SavedStateHandle,
    private val policy: TransferPolicy = TransferPolicy.DEFAULT,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            JourneyState(
                draft =
                    TransferDraft(
                        savedStateHandle[NAME] ?: "",
                        savedStateHandle[IBAN] ?: "",
                        savedStateHandle[AMOUNT] ?: "",
                    ),
            ),
        )
    val state: StateFlow<JourneyState> = _state.asStateFlow()
    private val actionMutex = Mutex()
    private var attemptCollection: Job? = null

    init {
        viewModelScope.launch {
            try {
                repository.observeDashboard().collect { dashboard ->
                    _state.update { it.copy(dashboard = dashboard) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(message = JourneyMessage.LOAD_FAILED) }
            }
        }
        viewModelScope.launch {
            try {
                val unresolved = repository.findUnresolvedAttempt()
                when {
                    unresolved != null -> watchAttempt(unresolved.request.key, unresolved)
                    savedStateHandle.get<String>(KEY) != null ->
                        watchAttempt(RequestKey(checkNotNull(savedStateHandle[KEY])))
                    savedStateHandle.get<String>(SCREEN) in
                        setOf(JourneyScreen.FORM.name, JourneyScreen.REVIEW.name) ->
                        _state.update { it.copy(screen = JourneyScreen.FORM) }
                }
                _state.update { it.copy(initialized = true) }
                refreshDashboard()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(initialized = true, message = JourneyMessage.LOAD_FAILED) }
            }
        }
    }

    fun newTransfer() {
        val current = _state.value
        if (!current.initialized || current.busy || current.attempt?.isTerminal() == false) return
        attemptCollection?.cancel()
        savedStateHandle[KEY] = null
        saveDraft(TransferDraft("", "", ""))
        show(JourneyScreen.FORM)
        _state.update {
            it.copy(attempt = null, review = null, issues = emptyList(), message = null)
        }
    }

    fun editDraft(name: String? = null, iban: String? = null, amount: String? = null) {
        val current = _state.value
        if (current.screen != JourneyScreen.FORM || current.busy) return
        saveDraft(
            current.draft.copy(
                recipientName = name ?: current.draft.recipientName,
                ibanInput = iban ?: current.draft.ibanInput,
                amountInput = amount ?: current.draft.amountInput,
            ),
        )
        _state.update { it.copy(issues = emptyList(), message = null) }
    }

    fun reviewTransfer() {
        val current = _state.value
        if (current.screen != JourneyScreen.FORM || current.busy) return
        val account = current.dashboard?.account ?: return
        when (val result = validateDraft(current.draft, account, policy)) {
            is ReviewResult.Invalid ->
                _state.update {
                    it.copy(issues = result.issues, validationVersion = it.validationVersion + 1)
                }
            is ReviewResult.Valid -> {
                _state.update {
                    it.copy(review = result.review, issues = emptyList(), message = null)
                }
                show(JourneyScreen.REVIEW)
            }
        }
    }

    fun back() {
        val current = _state.value
        if (current.busy) return
        when (current.screen) {
            JourneyScreen.REVIEW -> {
                _state.update { it.copy(review = null, message = null) }
                show(JourneyScreen.FORM)
            }
            JourneyScreen.FORM -> show(JourneyScreen.OVERVIEW)
            JourneyScreen.OUTCOME -> if (current.attempt?.isTerminal() == true) overview()
            JourneyScreen.OVERVIEW -> Unit
        }
    }

    fun overview() {
        val current = _state.value
        if (current.busy || current.attempt?.isTerminal() == false) return
        savedStateHandle[KEY] = null
        show(JourneyScreen.OVERVIEW)
    }

    fun confirm() {
        val current = _state.value
        if (current.screen != JourneyScreen.REVIEW || current.busy) return
        val frozenReview = current.review ?: return
        act {
            when (val result = repository.prepare(frozenReview)) {
                is PrepareResult.Created -> {
                    watchAttempt(result.attempt.request.key, result.attempt)
                    repository.submitPrepared(result.attempt.request.key)
                }
                is PrepareResult.AlreadyActive ->
                    watchAttempt(result.attempt.request.key, result.attempt)
                is PrepareResult.Invalid -> {
                    _state.update {
                        it.copy(
                            issues = result.issues,
                            review = null,
                            validationVersion = it.validationVersion + 1,
                        )
                    }
                    show(JourneyScreen.FORM)
                }
                PrepareResult.Offline -> _state.update { it.copy(message = JourneyMessage.OFFLINE) }
            }
        }
    }

    fun sendPrepared() {
        val attempt = _state.value.attempt ?: return
        if (attempt.status != AttemptStatus.Prepared) return
        act { repository.submitPrepared(attempt.request.key) }
    }

    fun checkStatus() {
        val attempt = _state.value.attempt ?: return
        if (
            attempt.isTerminal() ||
                attempt.status is AttemptStatus.Prepared ||
                attempt.status is AttemptStatus.Submitting
        )
            return
        act { repository.checkStatus(attempt.request.key) }
    }

    fun retrySafely() {
        val attempt = _state.value.attempt ?: return
        if (attempt.status !is AttemptStatus.Unknown) return
        act { repository.retryUnknown(attempt.request.key) }
    }

    fun refresh() = act { refreshDashboard() }

    private suspend fun refreshDashboard() {
        try {
            repository.refreshDashboard()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _state.update { it.copy(message = JourneyMessage.REFRESH_FAILED) }
        }
    }

    /**
     * A single action gate covers validation, persistence, and transport; rapid taps cannot race.
     */
    private fun act(block: suspend () -> Unit) {
        if (!_state.value.initialized || !actionMutex.tryLock()) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(message = JourneyMessage.ACTION_FAILED) }
            } finally {
                _state.update { it.copy(busy = false) }
                actionMutex.unlock()
            }
        }
    }

    private fun watchAttempt(key: RequestKey, initial: TransferAttempt? = null) {
        savedStateHandle[KEY] = key.value
        show(JourneyScreen.OUTCOME)
        if (initial != null)
            _state.update { it.copy(attempt = initial, review = initial.request.review) }
        attemptCollection?.cancel()
        // Subscribe before transport can produce a result, including synchronous demo responses.
        attemptCollection =
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeAttempt(key).collect { attempt ->
                    if (attempt != null) {
                        _state.update {
                            it.copy(attempt = attempt, review = attempt.request.review)
                        }
                    } else if (initial == null) {
                        _state.update { it.copy(message = JourneyMessage.LOAD_FAILED) }
                    }
                }
            }
    }

    private fun saveDraft(draft: TransferDraft) {
        savedStateHandle[NAME] = draft.recipientName
        savedStateHandle[IBAN] = draft.ibanInput
        savedStateHandle[AMOUNT] = draft.amountInput
        _state.update { it.copy(draft = draft) }
    }

    private fun show(screen: JourneyScreen) {
        savedStateHandle[SCREEN] = screen.name
        _state.update { it.copy(screen = screen) }
    }

    private companion object {
        const val NAME = "draft.name"
        const val IBAN = "draft.iban"
        const val AMOUNT = "draft.amount"
        const val SCREEN = "journey.screen"
        const val KEY = "journey.requestKey"
    }
}

enum class JourneyScreen {
    OVERVIEW,
    FORM,
    REVIEW,
    OUTCOME,
}

enum class JourneyMessage {
    OFFLINE,
    LOAD_FAILED,
    REFRESH_FAILED,
    ACTION_FAILED,
}

data class JourneyState(
    val initialized: Boolean = false,
    val dashboard: DashboardSnapshot? = null,
    val screen: JourneyScreen = JourneyScreen.OVERVIEW,
    val draft: TransferDraft = TransferDraft("", "", ""),
    val review: TransferReview? = null,
    val attempt: TransferAttempt? = null,
    val issues: List<ValidationIssue> = emptyList(),
    val validationVersion: Int = 0,
    val busy: Boolean = false,
    val message: JourneyMessage? = null,
)
