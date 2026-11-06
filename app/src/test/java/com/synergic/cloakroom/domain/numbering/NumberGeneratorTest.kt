package com.synergic.cloakroom.domain.numbering

import com.synergic.cloakroom.domain.settings.ReceiptReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class NumberGeneratorTest {

    private val nov2 = LocalDate.of(2026, 11, 2)

    @Test
    fun `period keys follow RECEIPT_RESET`() {
        assertEquals("2026-11-02", NumberGenerator.periodKey(ReceiptReset.DAILY, nov2))
        assertEquals("2026-11", NumberGenerator.periodKey(ReceiptReset.MONTHLY, nov2))
        assertEquals("FY2026-27", NumberGenerator.periodKey(ReceiptReset.FINANCIAL_YEAR, nov2))
        assertEquals("ALL", NumberGenerator.periodKey(ReceiptReset.NEVER, nov2))
    }

    @Test
    fun `financial year runs April to March`() {
        assertEquals("FY2025-26", NumberGenerator.periodKey(ReceiptReset.FINANCIAL_YEAR, LocalDate.of(2026, 3, 31)))
        assertEquals("FY2026-27", NumberGenerator.periodKey(ReceiptReset.FINANCIAL_YEAR, LocalDate.of(2026, 4, 1)))
        assertEquals("FY2099-00", NumberGenerator.periodKey(ReceiptReset.FINANCIAL_YEAR, LocalDate.of(2099, 12, 1)))
    }

    @Test
    fun `number format is user code and 5 digits`() {
        assertEquals("U02-00001", NumberGenerator.format("U02", 1))
        assertEquals("U03-00125", NumberGenerator.format("U03", 125))
        assertEquals("U01-123456", NumberGenerator.format("U01", 123456))
        assertThrows(IllegalArgumentException::class.java) { NumberGenerator.format("U01", 0) }
    }
}
