package dev.transferflow.app

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.transferflow.data.DemoControls
import dev.transferflow.data.DemoScenario
import dev.transferflow.domain.*
import java.math.BigDecimal
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

@Composable
fun TransferFlowScreen(viewModel: TransferFlowViewModel, controls: DemoControls? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollState = remember(state.screen) { ScrollState(initial = 0) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val screenTitle =
        stringResource(
            when (state.screen) {
                JourneyScreen.OVERVIEW -> R.string.overview_subtitle
                JourneyScreen.FORM -> R.string.form_title
                JourneyScreen.REVIEW -> R.string.review_title
                JourneyScreen.OUTCOME -> R.string.step_status
            },
        )
    LaunchedEffect(state.screen) {
        if (state.screen != JourneyScreen.FORM) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    BackHandler(state.screen != JourneyScreen.OVERVIEW) { viewModel.back() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canGoBack =
                        state.screen != JourneyScreen.OVERVIEW &&
                            (state.screen != JourneyScreen.OUTCOME ||
                                state.attempt?.isTerminal() == true)
                    if (canGoBack) {
                        IconButton(
                            onClick = viewModel::back,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("back"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                "⇄",
                                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize()
                .padding(insets)
                .imePadding()
                .verticalScroll(scrollState)
                .semantics { paneTitle = screenTitle }
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                stringResource(R.string.demo_marker),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            state.message?.let { Notice(stringResource(messageResource(it)), error = true) }
            if (!state.initialized) {
                Loading(stringResource(R.string.loading_account))
            } else {
                when (state.screen) {
                    JourneyScreen.OVERVIEW ->
                        Overview(state, viewModel::newTransfer, viewModel::refresh)
                    JourneyScreen.FORM -> TransferForm(state, viewModel)
                    JourneyScreen.REVIEW -> Review(state, viewModel::confirm, viewModel::back)
                    JourneyScreen.OUTCOME -> Outcome(state, viewModel)
                }
            }
            if (BuildConfig.DEBUG && controls != null) DeveloperControls(controls)
        }
    }
}

@Composable
private fun Overview(state: JourneyState, onNewTransfer: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.testTag("screen_overview"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            stringResource(R.string.overview_heading),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.testTag("overview_heading").semantics { heading() },
        )
        val dashboard = state.dashboard
        if (dashboard == null) {
            Loading(stringResource(R.string.loading_account))
            OutlinedButton(onClick = onRefresh, enabled = !state.busy) {
                Text(stringResource(R.string.refresh_account))
            }
            return@Column
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(dashboard.account.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.available_balance),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    money(dashboard.account.availableBalance),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.testTag("available_balance"),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .15f),
                )
                SummaryRow(
                    stringResource(R.string.booked_balance),
                    money(dashboard.account.bookedBalance),
                )
                Text(groupIban(dashboard.account.iban), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.cached_at, date(dashboard.account.cachedAtEpochMillis)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        PrimaryButton(R.string.start_transfer, "new_transfer", !state.busy, onNewTransfer)
        Text(
            stringResource(R.string.demo_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.recent_activity),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        if (dashboard.transactions.isEmpty()) {
            Text(
                stringResource(R.string.activity_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            dashboard.transactions.take(10).forEach { transaction ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(18.dp)
                            .testTag("transaction_${transaction.operationId.value}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            transaction.recipient.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                R.string.transaction_amount,
                                money(transaction.amount + transaction.fee),
                            ),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            date(transaction.bookedAtEpochMillis),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (transaction.fee != Euro.ZERO)
                            Text(
                                stringResource(R.string.transaction_fee, money(transaction.fee)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("refresh_account"),
        ) {
            Text(stringResource(R.string.refresh_account))
        }
    }
}

@Composable
private fun TransferForm(state: JourneyState, viewModel: TransferFlowViewModel) {
    Column(Modifier.testTag("screen_form"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepLabel(R.string.step_recipient)
        Text(
            stringResource(R.string.form_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.testTag("form_heading").semantics { heading() },
        )
        Text(
            stringResource(R.string.form_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Input(
            value = state.draft.recipientName,
            label = R.string.recipient_name,
            placeholder = R.string.recipient_name_hint,
            tag = "recipient_name",
            issue = state.issues.firstOrNull { it.field == TransferField.RECIPIENT_NAME },
            revealErrorVersion =
                state.validationVersion.takeIf {
                    state.issues.firstOrNull()?.field == TransferField.RECIPIENT_NAME
                },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        ) {
            viewModel.editDraft(name = it)
        }
        Input(
            value = state.draft.ibanInput,
            label = R.string.recipient_iban,
            placeholder = R.string.recipient_iban_hint,
            tag = "recipient_iban",
            issue = state.issues.firstOrNull { it.field == TransferField.IBAN },
            revealErrorVersion =
                state.validationVersion.takeIf {
                    state.issues.firstOrNull()?.field == TransferField.IBAN
                },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                ),
        ) {
            viewModel.editDraft(iban = it)
        }
        Input(
            value = state.draft.amountInput,
            label = R.string.amount_label,
            placeholder = R.string.amount_hint,
            tag = "amount",
            issue = state.issues.firstOrNull { it.field == TransferField.AMOUNT },
            revealErrorVersion =
                state.validationVersion.takeIf {
                    state.issues.firstOrNull()?.field == TransferField.AMOUNT
                },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        ) {
            viewModel.editDraft(amount = it)
        }
        Text(
            stringResource(R.string.amount_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.dashboard?.let {
            Text(
                stringResource(R.string.available_hint, money(it.account.availableBalance)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        PrimaryButton(
            R.string.review_transfer,
            "review_transfer",
            state.dashboard != null && !state.busy,
            viewModel::reviewTransfer,
        )
        if (BuildConfig.DEBUG) {
            TextButton(
                onClick = {
                    viewModel.editDraft(
                        name = "Alex Morgan",
                        iban = "NL25DEMO0000000002",
                        amount = "25.00",
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("fill_sample"),
            ) {
                Text(stringResource(R.string.sample_details))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Input(
    value: String,
    @StringRes label: Int,
    @StringRes placeholder: Int,
    tag: String,
    issue: ValidationIssue?,
    revealErrorVersion: Int?,
    keyboardOptions: KeyboardOptions,
    onChange: (String) -> Unit,
) {
    val errorText = issue?.let { stringResource(validationResource(it.code)) }
    val focusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(revealErrorVersion) {
        if (revealErrorVersion != null && issue != null) {
            focusRequester.requestFocus()
            bringIntoViewRequester.bringIntoView()
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(placeholder)) },
        keyboardOptions = keyboardOptions,
        singleLine = true,
        isError = issue != null,
        supportingText =
            errorText?.let { text ->
                { Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            },
        modifier =
            Modifier.fillMaxWidth()
                .testTag(tag)
                .focusRequester(focusRequester)
                .bringIntoViewRequester(bringIntoViewRequester)
                .semantics { if (errorText != null) error(errorText) },
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun Review(state: JourneyState, onConfirm: () -> Unit, onEdit: () -> Unit) {
    val review = state.review ?: return
    Column(Modifier.testTag("screen_review"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        StepLabel(R.string.step_review)
        Text(
            stringResource(R.string.review_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.testTag("review_heading").semantics { heading() },
        )
        Text(
            stringResource(R.string.review_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReviewCard(review)
        Notice(stringResource(R.string.confirmation_notice))
        PrimaryButton(R.string.confirm_transfer, "confirm_transfer", !state.busy, onConfirm)
        OutlinedButton(
            onClick = onEdit,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("edit_transfer"),
        ) {
            Text(stringResource(R.string.edit_transfer))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ReviewCard(review: TransferReview) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.to_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                review.recipient.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("frozen_recipient"),
            )
            Text(
                groupIban(review.recipient.iban),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("frozen_iban"),
            )
            HorizontalDivider()
            SummaryRow(
                stringResource(R.string.amount_summary),
                money(review.amount),
                "frozen_amount",
            )
            SummaryRow(stringResource(R.string.fee_summary), money(review.fee), "frozen_fee")
            HorizontalDivider()
            Text(
                stringResource(R.string.total_summary),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                money(review.amount + review.fee),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("frozen_total"),
            )
        }
    }
}

@Composable
private fun Outcome(state: JourneyState, viewModel: TransferFlowViewModel) {
    val attempt = state.attempt
    Column(Modifier.testTag("screen_outcome"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        StepLabel(R.string.step_status)
        if (attempt == null) {
            Loading(stringResource(R.string.status_wait))
            return@Column
        }
        val status = attempt.status
        val operation = (status as? AttemptStatus.Confirmed)?.operation
        val success = operation?.status == OperationStatus.Succeeded
        val heading =
            when (status) {
                AttemptStatus.Prepared -> R.string.prepared_title
                AttemptStatus.Submitting -> R.string.submitting_title
                is AttemptStatus.Unknown -> R.string.unknown_title
                is AttemptStatus.Confirmed ->
                    when (operation?.status) {
                        OperationStatus.Succeeded -> R.string.success_title
                        OperationStatus.Pending -> R.string.pending_title
                        is OperationStatus.Rejected -> R.string.rejected_title
                        null -> error("Confirmed attempt requires an operation")
                    }
            }
        val body =
            when (status) {
                AttemptStatus.Prepared -> R.string.prepared_body
                AttemptStatus.Submitting -> R.string.submitting_body
                is AttemptStatus.Unknown -> R.string.unknown_body
                is AttemptStatus.Confirmed ->
                    when (val outcome = status.operation.status) {
                        OperationStatus.Succeeded -> R.string.success_body
                        OperationStatus.Pending -> R.string.pending_body
                        is OperationStatus.Rejected -> rejectionResource(outcome.reason)
                    }
            }
        if (success) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
        }
        Text(
            stringResource(heading),
            style = MaterialTheme.typography.headlineMedium,
            modifier =
                Modifier.testTag("outcome_title").semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
        )
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status == AttemptStatus.Submitting || state.busy)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        ReviewCard(attempt.request.review)
        operation?.let {
            Text(
                stringResource(R.string.operation_reference),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                it.id.value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("operation_reference"),
            )
        }
        when {
            status == AttemptStatus.Prepared ->
                PrimaryButton(
                    R.string.send_prepared,
                    "send_prepared",
                    !state.busy,
                    viewModel::sendPrepared,
                )
            status is AttemptStatus.Unknown -> {
                PrimaryButton(
                    R.string.check_status,
                    "check_status",
                    !state.busy,
                    viewModel::checkStatus,
                )
                OutlinedButton(
                    onClick = viewModel::retrySafely,
                    enabled = !state.busy,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("retry_safely"),
                ) {
                    Text(stringResource(R.string.retry_safely))
                }
            }
            operation?.status == OperationStatus.Pending ->
                PrimaryButton(
                    R.string.check_status,
                    "check_status",
                    !state.busy,
                    viewModel::checkStatus,
                )
            attempt.isTerminal() -> {
                PrimaryButton(
                    R.string.back_to_account,
                    "back_to_account",
                    !state.busy,
                    viewModel::overview,
                )
                OutlinedButton(
                    onClick = viewModel::newTransfer,
                    enabled = !state.busy,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("another_transfer"),
                ) {
                    Text(stringResource(R.string.start_transfer))
                }
            }
        }
    }
}

@Composable
private fun DeveloperControls(controls: DemoControls) {
    val scenario by controls.scenario.collectAsStateWithLifecycle()
    val offline by controls.offline.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = 48.dp).testTag("demo_controls"),
        ) {
            Text(stringResource(R.string.demo_controls))
        }
        if (expanded) {
            Text(
                stringResource(R.string.demo_controls_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DemoScenario.entries.forEach { value ->
                    FilterChip(
                        selected = scenario == value,
                        onClick = { controls.setScenario(value) },
                        label = { Text(stringResource(scenarioResource(value))) },
                        leadingIcon =
                            if (scenario == value)
                                ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) })
                            else null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("scenario_${value.name}"),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.simulated_offline), modifier = Modifier.weight(1f))
                Switch(
                    checked = offline,
                    onCheckedChange = controls::setOffline,
                    modifier =
                        Modifier.testTag("simulated_offline").semantics {
                            contentDescription = "Simulated offline"
                        },
                )
            }
        }
    }
}

@Composable
private fun StepLabel(@StringRes resource: Int) {
    Text(
        stringResource(resource),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PrimaryButton(
    @StringRes label: Int,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(stringResource(label))
    }
}

@Composable
private fun SummaryRow(label: String, value: String, tag: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            modifier = if (tag.isEmpty()) Modifier else Modifier.testTag(tag),
        )
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color =
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.weight(1f).semantics { if (error) liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun Loading(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun money(euro: Euro, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getCurrencyInstance(locale)
        .apply {
            currency = Currency.getInstance("EUR")
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        .format(BigDecimal.valueOf(euro.minor, 2))

private fun groupIban(iban: DutchIban): String = iban.value.chunked(4).joinToString(" ")

private fun date(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

@StringRes
private fun validationResource(code: ValidationCode): Int =
    when (code) {
        ValidationCode.REQUIRED -> R.string.error_required
        ValidationCode.INVALID_IBAN -> R.string.error_iban
        ValidationCode.SELF_TRANSFER -> R.string.error_self
        ValidationCode.INVALID_AMOUNT -> R.string.error_amount
        ValidationCode.NON_POSITIVE_AMOUNT -> R.string.error_positive
        ValidationCode.AMOUNT_LIMIT -> R.string.error_limit
        ValidationCode.INSUFFICIENT_FUNDS -> R.string.error_funds
        ValidationCode.AMOUNT_OVERFLOW -> R.string.error_overflow
        ValidationCode.NAME_TOO_LONG -> R.string.error_name_length
    }

@StringRes
private fun rejectionResource(reason: RejectionReason): Int =
    when (reason) {
        RejectionReason.INSUFFICIENT_FUNDS -> R.string.reject_balance
        RejectionReason.TRANSFER_LIMIT -> R.string.reject_limit
        RejectionReason.DEMO_REJECTED -> R.string.reject_demo
    }

@StringRes
private fun messageResource(message: JourneyMessage): Int =
    when (message) {
        JourneyMessage.OFFLINE -> R.string.offline_message
        JourneyMessage.LOAD_FAILED -> R.string.load_failed_message
        JourneyMessage.REFRESH_FAILED -> R.string.refresh_failed_message
        JourneyMessage.ACTION_FAILED -> R.string.action_failed_message
    }

@StringRes
private fun scenarioResource(scenario: DemoScenario): Int =
    when (scenario) {
        DemoScenario.SUCCESS -> R.string.scenario_success
        DemoScenario.PENDING -> R.string.scenario_pending
        DemoScenario.REJECTED -> R.string.scenario_rejected
        DemoScenario.TIMEOUT_AFTER_ACCEPTANCE -> R.string.scenario_timeout
        DemoScenario.SLOW -> R.string.scenario_slow
    }
