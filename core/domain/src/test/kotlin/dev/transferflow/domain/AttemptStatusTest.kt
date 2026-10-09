package dev.transferflow.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptStatusTest {
    private val request =
        TransferRequest(
            RequestKey("request-1"),
            TransferReview(
                AccountId("account-1"),
                Recipient("Demo recipient", checkNotNull(DutchIban.parse("NL52DEMO0000000001"))),
                Euro(100),
                Euro.ZERO,
            ),
        )

    @Test
    fun `identifiers reject empty and blank values`() {
        listOf("", " ", "\t\n").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { AccountId(value) }
            assertThrows(IllegalArgumentException::class.java) { RequestKey(value) }
            assertThrows(IllegalArgumentException::class.java) { OperationId(value) }
        }
    }

    @Test
    fun `local states including uncertainty are unresolved`() {
        val states =
            listOf(
                AttemptStatus.Prepared,
                AttemptStatus.Submitting,
                *UncertaintyReason.entries.map(AttemptStatus::Unknown).toTypedArray(),
            )
        states.forEach { assertFalse(TransferAttempt(request, 1, it).isTerminal()) }
    }

    @Test
    fun `an authoritative pending operation remains unresolved`() {
        assertFalse(attempt(OperationStatus.Pending).isTerminal())
    }

    @Test
    fun `success and each recorded rejection are terminal`() {
        assertTrue(attempt(OperationStatus.Succeeded).isTerminal())
        RejectionReason.entries.forEach {
            assertTrue(attempt(OperationStatus.Rejected(it)).isTerminal())
        }
    }

    private fun attempt(status: OperationStatus) =
        TransferAttempt(
            request,
            1,
            AttemptStatus.Confirmed(
                GatewayOperation(OperationId("operation-1"), request, status, 2),
            ),
        )
}
