package dev.transferflow.app

import dev.transferflow.domain.Euro
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormattingTest {
    @Test
    fun `EUR retains cents when the locale normally uses a zero decimal currency`() {
        assertEquals("€12.34", money(Euro(1234), Locale.JAPAN))
        assertEquals("€0.01", money(Euro(1), Locale.JAPAN))
        assertEquals("€0.00", money(Euro.ZERO, Locale.JAPAN))
    }

    @Test
    fun `comma decimal locales preserve exact cents`() {
        assertEquals("12,34\u00a0€", money(Euro(1234), Locale.GERMANY))
        assertEquals("0,01\u00a0€", money(Euro(1), Locale.GERMANY))
    }

    @Test
    fun `large money amounts never pass through binary floating point`() {
        assertEquals("€92,233,720,368,547,758.07", money(Euro(Long.MAX_VALUE), Locale.US))
    }
}
