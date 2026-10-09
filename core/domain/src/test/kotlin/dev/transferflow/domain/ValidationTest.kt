package dev.transferflow.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    private val account =
        AccountSnapshot(
            id = AccountId("demo-account"),
            displayName = "Demo account",
            iban = checkNotNull(DutchIban.parse("NL91ABNA0417164300")),
            bookedBalance = Euro(20_000),
            availableBalance = Euro(10_000),
            cachedAtEpochMillis = 1,
        )
    private val recipientIban = "NL52DEMO0000000001"
    private val draft = TransferDraft(" Demo recipient ", recipientIban.lowercase(), "12,34")
    private val policy = TransferPolicy(Euro(10_000_000), Euro(25))

    @Test
    fun `valid review freezes normalized recipient exact amount and injected fee`() {
        val review = (validateDraft(draft, account, policy) as ReviewResult.Valid).review
        assertEquals(account.id, review.payerAccountId)
        assertEquals(
            Recipient("Demo recipient", checkNotNull(DutchIban.parse(recipientIban))),
            review.recipient,
        )
        assertEquals(Euro(1_234), review.amount)
        assertEquals(Euro(25), review.fee)
        assertTrue(validateReview(review, account, policy).isEmpty())
    }

    @Test
    fun `empty fields report all required issues together`() {
        val issues = invalid(TransferDraft(" \t", "", "\n"))
        assertEquals(
            listOf(
                ValidationIssue(TransferField.RECIPIENT_NAME, ValidationCode.REQUIRED),
                ValidationIssue(TransferField.IBAN, ValidationCode.REQUIRED),
                ValidationIssue(TransferField.AMOUNT, ValidationCode.REQUIRED),
            ),
            issues,
        )
    }

    @Test
    fun `recipient name boundary is checked after trimming`() {
        assertTrue(
            validateDraft(draft.copy(recipientName = " " + "a".repeat(70) + " "), account, policy)
                is ReviewResult.Valid,
        )
        assertEquals(
            listOf(ValidationIssue(TransferField.RECIPIENT_NAME, ValidationCode.NAME_TOO_LONG)),
            invalid(draft.copy(recipientName = "a".repeat(71))),
        )
    }

    @Test
    fun `Unicode recipient characters are counted as code points`() {
        assertTrue(
            validateDraft(draft.copy(recipientName = "😀".repeat(70)), account, policy)
                is ReviewResult.Valid,
        )
        assertEquals(
            ValidationCode.NAME_TOO_LONG,
            invalid(draft.copy(recipientName = "😀".repeat(71))).single().code,
        )
    }

    @Test
    fun `invalid IBAN and normalized self transfer are rejected`() {
        assertEquals(
            ValidationCode.INVALID_IBAN,
            invalid(draft.copy(ibanInput = "NL00INVALID")).single().code,
        )
        assertEquals(
            ValidationCode.SELF_TRANSFER,
            invalid(draft.copy(ibanInput = " nl91 abna 0417 1643 00 ")).single().code,
        )
    }

    @Test
    fun `zero is distinguished from malformed negative amount`() {
        assertEquals(
            ValidationCode.NON_POSITIVE_AMOUNT,
            invalid(draft.copy(amountInput = "0.00")).single().code,
        )
        assertEquals(
            ValidationCode.INVALID_AMOUNT,
            invalid(draft.copy(amountInput = "-0.01")).single().code,
        )
    }

    @Test
    fun `maximum amount boundary is inclusive`() {
        val richAccount =
            account.copy(bookedBalance = Euro(10_000_025), availableBalance = Euro(10_000_025))
        assertTrue(
            validateDraft(draft.copy(amountInput = "100000.00"), richAccount, policy)
                is ReviewResult.Valid,
        )
        val issues =
            (validateDraft(draft.copy(amountInput = "100000.01"), richAccount, policy)
                    as ReviewResult.Invalid)
                .issues
        assertTrue(
            issues.contains(ValidationIssue(TransferField.AMOUNT, ValidationCode.AMOUNT_LIMIT)),
        )
    }

    @Test
    fun `amount and fee can equal available balance exactly`() {
        assertTrue(
            validateDraft(draft.copy(amountInput = "99.75"), account, policy) is ReviewResult.Valid,
        )
        assertEquals(
            ValidationCode.INSUFFICIENT_FUNDS,
            invalid(draft.copy(amountInput = "99.76")).single().code,
        )
    }

    @Test
    fun `available balance is used rather than booked balance`() {
        assertEquals(
            ValidationCode.INSUFFICIENT_FUNDS,
            invalid(draft.copy(amountInput = "150.00")).single().code,
        )
    }

    @Test
    fun `parsing overflow has an explicit validation code`() {
        assertEquals(
            ValidationCode.AMOUNT_OVERFLOW,
            invalid(draft.copy(amountInput = "92233720368547758.08")).single().code,
        )
    }

    @Test
    fun `amount plus injected fee overflow is reported without arithmetic wrapping`() {
        val largePolicy = TransferPolicy(Euro(Long.MAX_VALUE), Euro(1))
        val richAccount =
            account.copy(
                bookedBalance = Euro(Long.MAX_VALUE),
                availableBalance = Euro(Long.MAX_VALUE),
            )
        val issues =
            (validateDraft(
                    draft.copy(amountInput = "92233720368547758.07"),
                    richAccount,
                    largePolicy,
                )
                    as ReviewResult.Invalid)
                .issues
        assertEquals(
            listOf(ValidationIssue(TransferField.AMOUNT, ValidationCode.AMOUNT_OVERFLOW)),
            issues,
        )
    }

    @Test
    fun `confirmation rechecks a stale balance without changing the displayed review`() {
        val review = (validateDraft(draft, account, policy) as ReviewResult.Valid).review
        assertEquals(
            listOf(ValidationIssue(TransferField.AMOUNT, ValidationCode.INSUFFICIENT_FUNDS)),
            validateReview(review, account.copy(availableBalance = Euro.ZERO), policy),
        )
        assertEquals(Euro(1_234), review.amount)
        assertEquals(Euro(25), review.fee)
    }

    @Test
    fun `confirmation rejects payer or fee mismatches as contract violations`() {
        val review = (validateDraft(draft, account, policy) as ReviewResult.Valid).review
        assertThrows(IllegalArgumentException::class.java) {
            validateReview(
                review.copy(payerAccountId = AccountId("other-account")),
                account,
                policy,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateReview(review.copy(fee = Euro.ZERO), account, policy)
        }
    }

    @Test
    fun `confirmation rechecks recipient and amount rules`() {
        val review = (validateDraft(draft, account, policy) as ReviewResult.Valid).review
        val invalid = review.copy(recipient = Recipient(" ", account.iban), amount = Euro.ZERO)
        assertEquals(
            listOf(
                ValidationIssue(TransferField.RECIPIENT_NAME, ValidationCode.REQUIRED),
                ValidationIssue(TransferField.IBAN, ValidationCode.SELF_TRANSFER),
                ValidationIssue(TransferField.AMOUNT, ValidationCode.NON_POSITIVE_AMOUNT),
            ),
            validateReview(invalid, account, policy),
        )
    }

    @Test
    fun `default policy is a hundred thousand euros with no fee`() {
        assertEquals(Euro(10_000_000), TransferPolicy.DEFAULT.maximumAmount)
        assertEquals(Euro.ZERO, TransferPolicy.DEFAULT.fee)
    }

    private fun invalid(candidate: TransferDraft): List<ValidationIssue> =
        (validateDraft(candidate, account, policy) as ReviewResult.Invalid).issues
}
