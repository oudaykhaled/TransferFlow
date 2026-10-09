package dev.transferflow.domain

/** An exact, nonnegative euro amount expressed in cents. */
@JvmInline
value class Euro(val minor: Long) {
    init {
        require(minor >= 0) { "Money cannot be negative" }
    }

    operator fun plus(other: Euro): Euro = Euro(Math.addExact(minor, other.minor))

    operator fun minus(other: Euro): Euro = Euro(Math.subtractExact(minor, other.minor))

    companion object {
        val ZERO: Euro = Euro(0)

        /** Returns null for invalid precision, syntax, or a value outside the supported range. */
        fun parse(input: String): Euro? = (parseAmount(input) as? AmountParse.Value)?.amount
    }
}

internal sealed interface AmountParse {
    data class Value(val amount: Euro) : AmountParse

    data object Invalid : AmountParse

    data object Overflow : AmountParse
}

private val amountPattern = Regex("[0-9]+(?:[.,][0-9]{1,2})?")

internal fun parseAmount(input: String): AmountParse {
    val normalized = input.trim()
    if (!amountPattern.matches(normalized)) return AmountParse.Invalid

    val separator = normalized.indexOfFirst { it == '.' || it == ',' }
    val wholeText = if (separator == -1) normalized else normalized.substring(0, separator)
    // Ignore leading zeros before range checking: they do not change the value.
    val whole =
        wholeText.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: return AmountParse.Overflow
    val fractionText = if (separator == -1) "" else normalized.substring(separator + 1)
    val fraction =
        when (fractionText.length) {
            0 -> 0L
            1 -> fractionText.toLong() * 10
            else -> fractionText.toLong()
        }
    return try {
        AmountParse.Value(Euro(Math.addExact(Math.multiplyExact(whole, 100), fraction)))
    } catch (_: ArithmeticException) {
        AmountParse.Overflow
    }
}
