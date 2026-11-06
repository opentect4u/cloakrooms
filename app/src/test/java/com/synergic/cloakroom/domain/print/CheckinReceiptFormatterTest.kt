package com.synergic.cloakroom.domain.print

import com.synergic.cloakroom.domain.qr.ReceiptQr
import com.synergic.cloakroom.domain.qr.ReceiptQrData
import com.synergic.cloakroom.domain.settings
import com.synergic.cloakroom.domain.settings.SettingKeys.PAPER_WIDTH
import com.synergic.cloakroom.domain.settings.SettingKeys.QR_FLAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CheckinReceiptFormatterTest {

    private val receipt = CheckinReceipt(
        customerName = "Demo Cloak Room",
        headerLines = listOf("Demo Cloak Room", "Platform 1, Howrah Railway Station"),
        footerLines = listOf("Thank you! Keep this slip safe to collect your luggage."),
        userCode = "U02",
        userName = "Counter User",
        idProofName = "Aadhaar Card",
        idNumber = "XXXX9012",
        qr = ReceiptQrData(
            transId = "t-1", receiptNo = "U02-00001", receiptDt = "2026-11-02", customerId = 1, userIdIn = 2,
            dateTimeIn = "2026-11-02 06:30:00", bags = 2, name = "Ravi Kumar", mobile = "9876543210", pnr = "4512345678",
        ),
    )

    private fun texts(doc: PrintDocument) = doc.lines.filterIsInstance<PrintLine.Text>().map { it.text }

    @Test
    fun `58 mm receipt fits 32 characters and holds the details`() {
        val doc = CheckinReceiptFormatter.format(receipt, settings())
        assertEquals(32, doc.charsPerLine)
        assertEquals(58, doc.paperWidthMm)
        assertEquals("Receipt U02-00001", doc.title)
        val rows = texts(doc)
        assertTrue(rows.all { it.length <= 32 })
        val all = rows.joinToString(" ") { it.trim() } // long text wraps over rows
        listOf("Demo Cloak Room", "LUGGAGE RECEIPT", "U02-00001", "02-11-2026 12:00 PM", "Ravi Kumar", "9876543210",
            "4512345678", "Aadhaar Card XXXX9012", "Payment at check-out.").forEach {
            assertTrue("missing: $it", all.contains(it))
        }
        assertTrue(rows.any { it.startsWith("Bags") && it.endsWith("2") })
        assertTrue(all.contains("Rs.50 per bag for the first 24 h"))
    }

    @Test
    fun `80 mm receipt uses 48 characters`() {
        val doc = CheckinReceiptFormatter.format(receipt, settings(PAPER_WIDTH to "80"))
        assertEquals(48, doc.charsPerLine)
        assertTrue(texts(doc).all { it.length <= 48 })
    }

    @Test
    fun `QR carries the receipt and never the ID number`() {
        val qr = CheckinReceiptFormatter.format(receipt, settings()).lines.filterIsInstance<PrintLine.Qr>().single()
        assertEquals(receipt.qr, ReceiptQr.decode(qr.content))
        assertFalse(qr.content.contains("9012"))
        assertFalse(qr.content.contains("Aadhaar"))
    }

    @Test
    fun `no QR when QR_FLAG is off`() {
        val doc = CheckinReceiptFormatter.format(receipt, settings(QR_FLAG to "N"))
        assertTrue(doc.lines.none { it is PrintLine.Qr })
    }

    @Test
    fun `customer name is used when there are no header lines`() {
        val doc = CheckinReceiptFormatter.format(receipt.copy(headerLines = emptyList(), footerLines = emptyList()), settings())
        assertEquals("Demo Cloak Room", texts(doc).first().trim())
    }

    @Test
    fun `empty PNR prints a dash and no ID prints nothing`() {
        val r = receipt.copy(idProofName = null, idNumber = "", qr = receipt.qr.copy(pnr = ""))
        val rows = texts(CheckinReceiptFormatter.format(r, settings()))
        assertTrue(rows.any { it.startsWith("PNR") && it.endsWith("-") })
        assertTrue(rows.none { it.startsWith("ID ") })
    }

    @Test
    fun `layout pads pairs, centres and wraps long text`() {
        val p = PrintLayout(20)
        p.pair("Bags", "3")
        p.center("Hi")
        p.pair("Name", "A very long passenger name")
        p.left("supercalifragilisticexpialidocious")
        val rows = p.build("t", 58).lines.filterIsInstance<PrintLine.Text>().map { it.text }
        assertEquals("Bags               3", rows[0])
        assertEquals("         Hi", rows[1])
        assertEquals("Name", rows[2])
        assertTrue(rows.all { it.length <= 20 })
        assertEquals("supercalifragilistic", rows[rows.size - 2])
    }

    @Test
    fun `rupees keeps whole amounts short`() {
        assertEquals("Rs.50", CheckinReceiptFormatter.rupees(BigDecimal("50.00")))
        assertEquals("Rs.12.50", CheckinReceiptFormatter.rupees(BigDecimal("12.5")))
    }
}
