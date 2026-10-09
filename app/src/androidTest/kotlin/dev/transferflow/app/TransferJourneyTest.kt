package dev.transferflow.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.transferflow.data.DemoScenario
import dev.transferflow.data.TransferRuntime
import dev.transferflow.domain.AttemptStatus
import dev.transferflow.domain.Euro
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full Compose journeys use the real repository and both isolated, durable Room stores. */
@RunWith(AndroidJUnit4::class)
class TransferJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var application: TransferFlowApplication
    private lateinit var runtime: TransferRuntime
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var prefix: String

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefix = "journey_${UUID.randomUUID().toString().replace("-", "")}"
        runtime = TransferRuntime.create(application, databasePrefix = prefix)
        application.testRuntime = runtime
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitFor("new_transfer", enabled = true)
    }

    @After
    fun tearDown() {
        activity.close()
        application.testRuntime = null
        runtime.close()
        application.deleteDatabase("$prefix-client.db")
        application.deleteDatabase("$prefix-gateway.db")
    }

    @Test
    fun successShowsFrozenDetailsAndOneHistoryEntry() {
        enterReview()
        compose.onNodeWithTag("frozen_recipient").assertTextEquals("Alex Morgan")
        compose.onNodeWithTag("confirm_transfer").performScrollTo().performClick()
        waitForTitle("Transfer complete")
        val operation =
            compose
                .onNodeWithTag("operation_reference")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .single()
                .text
        activity.recreate()
        waitForTitle("Transfer complete")
        compose.onNodeWithTag("operation_reference").assertTextEquals(operation)
        compose.onNodeWithTag("back_to_account").performScrollTo().performClick()
        waitFor("screen_overview")
        compose.onAllNodesWithTag("transaction_$operation").assertCountEquals(1)
    }

    @Test
    fun invalidFieldsRemainEditableWithErrorSemantics() {
        compose.onNodeWithTag("new_transfer").performScrollTo().performClick()
        compose.onNodeWithTag("recipient_name").performTextInput("Alex")
        compose.onNodeWithTag("recipient_iban").performTextInput("invalid")
        compose.onNodeWithTag("amount").performTextInput("12.345")
        compose.onNodeWithTag("review_transfer").performScrollTo().performClick()
        compose
            .onNodeWithTag("recipient_iban")
            .assert(
                SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.Error,
                ),
            )
        compose
            .onNodeWithTag("amount")
            .assert(
                SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.Error,
                ),
            )
        compose.onNodeWithTag("screen_form").assertExists()
        compose.onNodeWithTag("confirm_transfer").assertDoesNotExist()
    }

    @Test
    fun rejectionKeepsReasonAndPermitsNewTransfer() {
        runtime.controls.setScenario(DemoScenario.REJECTED)
        enterReview()
        compose.onNodeWithTag("confirm_transfer").performScrollTo().performClick()
        waitForTitle("Transfer not completed")
        compose
            .onNodeWithText(
                "This fictional bank declined the transfer for the selected demo scenario. Your balance has not changed.",
            )
            .assertExists()
        compose.onNodeWithTag("another_transfer").performScrollTo().performClick()
        compose.onNodeWithTag("screen_form").assertExists()
    }

    @Test
    fun pendingSurvivesRecreationAndOnlyOffersStatusCheck() {
        runtime.controls.setScenario(DemoScenario.PENDING)
        enterReview()
        compose.onNodeWithTag("confirm_transfer").performScrollTo().performClick()
        waitForTitle("Your transfer is processing")
        compose.onNodeWithTag("retry_safely").assertDoesNotExist()
        compose.onNodeWithTag("another_transfer").assertDoesNotExist()
        activity.recreate()
        waitForTitle("Your transfer is processing")
        compose.onNodeWithTag("check_status").performScrollTo().performClick()
        waitForTitle("Transfer complete")
    }

    @Test
    fun lostResponseRecoversByCheckingOriginalOperation() {
        runtime.controls.setScenario(DemoScenario.TIMEOUT_AFTER_ACCEPTANCE)
        enterReview()
        compose.onNodeWithTag("confirm_transfer").performScrollTo().performClick()
        waitForTitle("Let’s confirm the outcome")
        compose.onNodeWithTag("another_transfer").assertDoesNotExist()
        activity.recreate()
        waitForTitle("Let’s confirm the outcome")
        compose.onNodeWithTag("check_status").performScrollTo().performClick()
        waitForTitle("Transfer complete")
        compose.onNodeWithTag("retry_safely").assertDoesNotExist()
    }

    @Test
    fun lostResponseRetriesOriginalRequestWithoutAnotherDebit() {
        val initialBalance = runBlocking {
            runtime.repository.observeDashboard().first().account.bookedBalance
        }
        runtime.controls.setScenario(DemoScenario.TIMEOUT_AFTER_ACCEPTANCE)
        enterReview()
        compose.onNodeWithTag("confirm_transfer").performScrollTo().performClick()
        waitForTitle("Let’s confirm the outcome")
        val originalKey = runBlocking {
            checkNotNull(runtime.repository.findUnresolvedAttempt()).request.key
        }
        val originalOperationId = runBlocking {
            withTimeout(15_000) {
                runtime.repository
                    .observeDashboard()
                    .first { it.transactions.size == 1 }
                    .transactions
                    .single()
                    .operationId
                    .value
            }
        }
        activity.recreate()
        waitForTitle("Let’s confirm the outcome")
        waitFor("retry_safely", enabled = true)
        compose.onNodeWithTag("retry_safely").performScrollTo().performClick()
        waitForTitle("Transfer complete")
        waitFor("back_to_account", enabled = true)
        compose.onNodeWithTag("operation_reference").assertTextEquals(originalOperationId)
        val resolved = runBlocking {
            checkNotNull(runtime.repository.observeAttempt(originalKey).first()).status
                as AttemptStatus.Confirmed
        }
        assertEquals(originalKey, resolved.operation.request.key)
        compose.onNodeWithTag("back_to_account").performScrollTo().performClick()
        waitFor("screen_overview")
        val expectedBalance = money(initialBalance - Euro(2500))
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("available_balance").fetchSemanticsNodes().any { node ->
                node.config
                    .getOrElse(SemanticsProperties.Text) { emptyList() }
                    .any { it.text == expectedBalance }
            }
        }
        compose.onAllNodesWithTag("transaction_$originalOperationId").assertCountEquals(1)
    }

    @Test
    fun editReviewChangesAmountBeforeAnyConfirmation() {
        enterReview()
        compose.onNodeWithTag("edit_transfer").performScrollTo().performClick()
        compose.onNodeWithTag("amount").performScrollTo().performTextReplacement("14,30")
        compose.onNodeWithTag("review_transfer").performScrollTo().performClick()
        val displayedAmount =
            compose
                .onNodeWithTag("frozen_amount")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .single()
                .text
        assertEquals(money(dev.transferflow.domain.Euro(1430)), displayedAmount)
        compose.onNodeWithTag("confirm_transfer").performScrollTo().performClick()
        waitForTitle("Transfer complete")
        compose.onNodeWithTag("frozen_amount").assertTextEquals(displayedAmount)
    }

    @Test
    fun offlineConfirmationKeepsReviewAndDoesNotSendOnReconnect() {
        enterReview()
        runtime.controls.setOffline(true)
        compose.onNodeWithTag("confirm_transfer").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithText(
                    "You’re in simulated offline mode. Reconnect before confirming this transfer.",
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("screen_review").assertExists()
        runtime.controls.setOffline(false)
        compose.waitForIdle()
        compose.onNodeWithTag("screen_review").assertExists()
        compose.onNodeWithTag("screen_outcome").assertDoesNotExist()
        compose.onNodeWithTag("confirm_transfer").performScrollTo().performClick()
        waitForTitle("Transfer complete")
    }

    @Test
    fun scrolledFormOpensReviewAndOutcomeAtTheirHeadingWithLargeText() {
        useLargeText()
        enterReview()
        // No scroll action is allowed here: the new step must establish its own initial viewport.
        compose.onNodeWithTag("review_heading").assertIsDisplayed()
        compose
            .onNodeWithTag("review_heading")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("confirm_transfer").performScrollTo().performClick()
        waitForTitle("Transfer complete")
        compose.onNodeWithTag("outcome_title").assertIsDisplayed()
    }

    @Test
    fun validationFromBottomWithKeyboardAndLargeTextRevealsFirstError() {
        useLargeText()
        compose.onNodeWithTag("new_transfer").performScrollTo().performClick()
        // Leave the first field empty, and keep the keyboard open on the final field.
        compose.onNodeWithTag("recipient_iban").performScrollTo().performTextInput("invalid")
        compose.onNodeWithTag("amount").performScrollTo().performTextInput("12.345")
        compose.onNodeWithTag("review_transfer").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("recipient_name").fetchSemanticsNodes().any {
                it.config.getOrElse(SemanticsProperties.Focused) { false }
            }
        }
        // These assertions intentionally do not scroll to the error: application behavior must
        // reveal it.
        compose.onNodeWithTag("recipient_name").assertIsFocused().assertIsDisplayed()
        compose
            .onNodeWithTag("recipient_name")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Error, "This field is required."),
            )
        compose.onNodeWithText("This field is required.").assertIsDisplayed()
        compose.onNodeWithTag("screen_review").assertDoesNotExist()
    }

    private fun useLargeText() {
        activity.onActivity { host ->
            val viewModel = TransferFlowViewModel(runtime.repository, SavedStateHandle())
            host.viewModelStore.put("large-text-journey", viewModel)
            host.setContent {
                TransferFlowTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale = 2f),
                    ) {
                        TransferFlowScreen(viewModel)
                    }
                }
            }
        }
        waitFor("new_transfer", enabled = true)
    }

    private fun enterReview() {
        compose.onNodeWithTag("new_transfer").performScrollTo().performClick()
        compose.onNodeWithTag("recipient_name").performTextInput("Alex Morgan")
        compose.onNodeWithTag("recipient_iban").performTextInput("NL25DEMO0000000002")
        compose.onNodeWithTag("amount").performScrollTo().performTextInput("25.00")
        compose.onNodeWithTag("review_transfer").performScrollTo().performClick()
        compose.onNodeWithTag("screen_review").assertExists()
    }

    private fun waitFor(tag: String, enabled: Boolean = false) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { node ->
                !enabled || !node.config.contains(SemanticsProperties.Disabled)
            }
        }
    }

    private fun waitForTitle(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("outcome_title").fetchSemanticsNodes().any { node ->
                node.config
                    .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) {
                        emptyList()
                    }
                    .any { it.text == text }
            }
        }
    }
}
