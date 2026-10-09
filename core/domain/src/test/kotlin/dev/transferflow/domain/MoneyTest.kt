package dev.transferflow.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test
    fun `whole values and either decimal separator are exact`() {
        mapOf("12" to 1_200L, "12.3" to 1_230L, "12,34" to 1_234L, "0.01" to 1L).forEach {
            (input, expected) ->
            assertEquals(input, Euro(expected), Euro.parse(input))
        }
    }

    @Test
    fun `outer whitespace and leading zeros preserve the value`() {
        assertEquals(Euro(1_203), Euro.parse(" \t00012,03\n"))
        assertEquals(Euro(1), Euro.parse("0".repeat(100) + "0.01"))
    }

    @Test
    fun `zero is valid money for balances and fees`() {
        listOf("0", "00", "0.0", "0,00").forEach { assertEquals(Euro.ZERO, Euro.parse(it)) }
    }

    @Test
    fun `precision grouping signs exponents and internal whitespace are rejected`() {
        listOf(
                "",
                " ",
                "1,234",
                "12.",
                "12,",
                ".12",
                ",12",
                "1e2",
                "1E2",
                "-1",
                "+1",
                "1,234.56",
                "1.234,56",
                "1 234",
                "1_234",
                "12.345",
                "12..3",
                "12,,3",
                "1\n2",
            )
            .forEach { assertNull(it, Euro.parse(it)) }
    }

    @Test
    fun `amount syntax accepts ASCII digits only`() {
        listOf("١٢.٣٤", "１２.３４", "12．34", "12٫34").forEach { assertNull(it, Euro.parse(it)) }
    }

    @Test
    fun `largest supported amount is accepted exactly`() {
        assertEquals(Euro(Long.MAX_VALUE), Euro.parse("92233720368547758.07"))
        assertEquals(Euro(Long.MAX_VALUE), Euro.parse("92233720368547758,07"))
    }

    @Test
    fun `amount conversion detects overflow rather than wrapping or rounding`() {
        listOf("92233720368547758.08", "92233720368547759", "9223372036854775808", "9".repeat(100))
            .forEach { assertNull(it, Euro.parse(it)) }
    }

    @Test
    fun `arithmetic preserves exact cents and rejects overflow`() {
        assertEquals(Euro(3), Euro(1) + Euro(2))
        assertEquals(Euro(1), Euro(3) - Euro(2))
        assertThrows(ArithmeticException::class.java) { Euro(Long.MAX_VALUE) + Euro(1) }
    }

    @Test
    fun `negative construction and subtraction are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Euro(-1) }
        assertThrows(IllegalArgumentException::class.java) { Euro(1) - Euro(2) }
    }
}
