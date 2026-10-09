package dev.transferflow.domain

enum class TransferField {
    RECIPIENT_NAME,
    IBAN,
    AMOUNT,
}

enum class ValidationCode {
    REQUIRED,
    INVALID_IBAN,
    SELF_TRANSFER,
    INVALID_AMOUNT,
    NON_POSITIVE_AMOUNT,
    AMOUNT_LIMIT,
    INSUFFICIENT_FUNDS,
    AMOUNT_OVERFLOW,
    NAME_TOO_LONG,
}

data class ValidationIssue(val field: TransferField, val code: ValidationCode)

sealed interface ReviewResult {
    data class Valid(val review: TransferReview) : ReviewResult

    data class Invalid(val issues: List<ValidationIssue>) : ReviewResult
}

fun validateDraft(
    draft: TransferDraft,
    account: AccountSnapshot,
    policy: TransferPolicy,
): ReviewResult {
    val issues = mutableListOf<ValidationIssue>()
    val name = draft.recipientName.trim()
    issues += nameIssues(name)

    val iban = DutchIban.parse(draft.ibanInput)
    when {
        draft.ibanInput.isBlank() -> issues += issue(TransferField.IBAN, ValidationCode.REQUIRED)
        iban == null -> issues += issue(TransferField.IBAN, ValidationCode.INVALID_IBAN)
        iban == account.iban -> issues += issue(TransferField.IBAN, ValidationCode.SELF_TRANSFER)
    }

    val amount =
        when (val parsed = parseAmount(draft.amountInput)) {
            is AmountParse.Value ->
                parsed.amount.also { issues += amountIssues(it, account, policy) }
            AmountParse.Invalid -> {
                val code =
                    if (draft.amountInput.isBlank()) ValidationCode.REQUIRED
                    else ValidationCode.INVALID_AMOUNT
                issues += issue(TransferField.AMOUNT, code)
                null
            }
            AmountParse.Overflow -> {
                issues += issue(TransferField.AMOUNT, ValidationCode.AMOUNT_OVERFLOW)
                null
            }
        }

    if (issues.isNotEmpty()) return ReviewResult.Invalid(issues.toList())
    return ReviewResult.Valid(
        TransferReview(
            account.id,
            Recipient(name, checkNotNull(iban)),
            checkNotNull(amount),
            policy.fee,
        ),
    )
}

/**
 * Revalidates the exact review that was displayed. Payer and fee mismatches are contract errors, so
 * callers cannot silently submit a different request from the one the user confirmed.
 */
fun validateReview(
    review: TransferReview,
    account: AccountSnapshot,
    policy: TransferPolicy,
): List<ValidationIssue> {
    require(review.payerAccountId == account.id) { "Review payer does not match the account" }
    require(review.fee == policy.fee) { "Review fee does not match the displayed policy" }
    return buildList {
        addAll(nameIssues(review.recipient.name))
        if (review.recipient.iban == account.iban)
            add(issue(TransferField.IBAN, ValidationCode.SELF_TRANSFER))
        addAll(amountIssues(review.amount, account, policy))
    }
}

private fun nameIssues(name: String): List<ValidationIssue> =
    when {
        name.isBlank() -> listOf(issue(TransferField.RECIPIENT_NAME, ValidationCode.REQUIRED))
        name.codePointCount(0, name.length) > 70 ->
            listOf(issue(TransferField.RECIPIENT_NAME, ValidationCode.NAME_TOO_LONG))
        else -> emptyList()
    }

private fun amountIssues(
    amount: Euro,
    account: AccountSnapshot,
    policy: TransferPolicy,
): List<ValidationIssue> = buildList {
    if (amount.minor == 0L) {
        add(issue(TransferField.AMOUNT, ValidationCode.NON_POSITIVE_AMOUNT))
        return@buildList
    }
    if (amount.minor > policy.maximumAmount.minor)
        add(issue(TransferField.AMOUNT, ValidationCode.AMOUNT_LIMIT))
    val total =
        try {
            amount + policy.fee
        } catch (_: ArithmeticException) {
            add(issue(TransferField.AMOUNT, ValidationCode.AMOUNT_OVERFLOW))
            return@buildList
        }
    if (total.minor > account.availableBalance.minor) {
        add(issue(TransferField.AMOUNT, ValidationCode.INSUFFICIENT_FUNDS))
    }
}

private fun issue(field: TransferField, code: ValidationCode) = ValidationIssue(field, code)
