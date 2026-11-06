package com.synergic.cloakroom.domain.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterTestPageTest {

    private fun texts(doc: PrintDocument) = doc.lines.filterIsInstance<PrintLine.Text>().map { it.text }

    @Test
    fun `58 mm page has a full-width ruler, the rupee sign and a QR`() {
        val doc = PrinterTestPage.format("RPP02N", 58, "03-11-2026 12:30")
        assertEquals(32, doc.charsPerLine)
        assertTrue(texts(doc).contains("12345678901234567890123456789012"))
        assertTrue(texts(doc).any { it.contains("₹70.00") })
        assertTrue(texts(doc).any { it.contains("RPP02N") })
        assertEquals(1, doc.lines.count { it is PrintLine.Qr })
        assertTrue(texts(doc).all { it.length <= 32 })
    }

    @Test
    fun `80 mm page uses 48 characters`() {
        val doc = PrinterTestPage.format("Printer", 80, "03-11-2026 12:30")
        assertEquals(48, doc.charsPerLine)
        assertTrue(texts(doc).contains("1234567890".repeat(5).take(48)))
    }
}
