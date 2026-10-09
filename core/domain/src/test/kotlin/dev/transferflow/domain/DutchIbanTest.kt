package dev.transferflow.domain

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DutchIbanTest {
    @Test
    fun `valid Dutch structure and checksum is accepted`() {
        assertEquals("NL91ABNA0417164300", DutchIban.parse("NL91ABNA0417164300")?.value)
    }

    @Test
    fun `case and whitespace are normalized`() {
        assertEquals(
            DutchIban.parse("NL91ABNA0417164300"),
            DutchIban.parse(" nl91\tabna 0417\n1643\u00a000 "),
        )
    }

    @Test
    fun `normalization is independent of the default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("NL51DIMO0000000001", DutchIban.parse("nl51dimo0000000001")?.value)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a valid checksum does not establish a real bank account`() {
        // DEMO is deliberately synthetic. The parser verifies format, not ownership or existence.
        assertNotNull(DutchIban.parse("NL52DEMO0000000001"))
    }

    @Test
    fun `changed check digits or account digits are rejected`() {
        assertNull(DutchIban.parse("NL92ABNA0417164300"))
        assertNull(DutchIban.parse("NL91ABNA0417164301"))
    }

    @Test
    fun `only Dutch fixed length ASCII structure is accepted`() {
        listOf(
                "",
                "DE89370400440532013000",
                "NL9ABNA0417164300",
                "NL091ABNA0417164300",
                "NL91ABN0417164300",
                "NL91ABNAA0417164300",
                "NL91ABNA041716430",
                "NL91ABNA04171643000",
                "NL91ABN10417164300",
                "NL٩١ABNA0417164300",
                "NL91ABNA０417164300",
                "NL91ABNA04171643A0",
            )
            .forEach { assertNull(it, DutchIban.parse(it)) }
    }
}
