package com.synergic.cloakroom.domain.print

import com.synergic.cloakroom.domain.billing.RateSlabs
import com.synergic.cloakroom.domain.settings.PayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class BillFormatterTest {

    private val bill = BillPrint(
        customerName = "Demo Cloak Room",
        gstNo = "19ABCDE1234F1Z5",
        headerLines = listOf("Demo Cloak Room"),
        footerLines = listOf("Thank you!"),
        billNo = "U02-00001",
        receiptNo = "U02-00007",
        dateTimeIn = "2026-11-02 06:30:00",
        dateTimeOut = "2026-11-03 06:31:00",
        userCode = "U02",
        userName = "Counter User",
        name = "Ravi Kumar",
        mobile = "9876543210",
        pnr = "4512345678",
        bags = 1,
        blocks = 2,
        slabs = RateSlabs.fromTwoRate(24, BigDecimal("50"), BigDecimal("20")),
        baseAmt = BigDecimal("70.00"),
        penaltyAmt = BigDecimal("0.00"),
        taxableAmt = BigDecimal("59.32"),
        gstFlag = true,
        gstPct = BigDecimal("18.00"),
        cgst = BigDecimal("5.34"),
        sgst = BigDecimal("5.34"),
        igst = BigDecimal("0.00"),
        netAmt = BigDecimal("70.00"),
        payMode = PayMode.CASH,
    )

    private fun rows(b: BillPrint = bill, width: Int = 58) =
        BillFormatter.format(b, width).lines.filterIsInstance<PrintLine.Text>().map { it.text }

    private fun row(rows: List<String>, label: String) = rows.first { it.startsWith(label) }

    @Test
    fun `GST bill is a tax invoice with the full breakup`() {
        val r = rows()
        assertTrue(r.all { it.length <= 32 })
        val all = r.joinToString(" ") { it.trim() }
        listOf("TAX INVOICE", "GSTIN: 19ABCDE1234F1Z5", "U02-00001", "U02-00007", "Ravi Kumar", "Thank you!").forEach {
            assertTrue("missing $it", all.contains(it))
        }
        assertFalse("no price-range lines", r.any { it.startsWith("0-24 h") || it.startsWith("24-48 h") })
        assertTrue(row(r, "Rent").endsWith("Rs.70.00"))
        assertFalse("no advance lines without an advance", r.any { it.startsWith("Less advance") || it.startsWith("BALANCE") })
        assertTrue(row(r, "Taxable value").endsWith("59.32"))
        assertTrue(row(r, "CGST 9%").endsWith("5.34"))
        assertTrue(row(r, "SGST 9%").endsWith("5.34"))
        assertTrue(row(r, "TOTAL").endsWith("Rs.70.00"))
        assertTrue(row(r, "Time stored").endsWith("24 h 01 m"))
        assertTrue(row(r, "Paid by").endsWith("Cash"))
        assertTrue(all.contains("(GST included in the rate)"))
        assertFalse(r.any { it.startsWith("Penalty") })
    }

    @Test
    fun `IGST bill shows one tax line`() {
        val r = rows(bill.copy(cgst = BigDecimal("0.00"), sgst = BigDecimal("0.00"), igst = BigDecimal("10.68")))
        assertTrue(row(r, "IGST 18%").endsWith("10.68"))
        assertFalse(r.any { it.startsWith("CGST") })
    }

    @Test
    fun `exclusive GST bill has no included note`() {
        val b = bill.copy(taxableAmt = BigDecimal("70.00"), cgst = BigDecimal("6.30"), sgst = BigDecimal("6.30"), netAmt = BigDecimal("82.60"))
        assertFalse(BillFormatter.isInclusive(b))
        assertFalse(rows(b).joinToString(" ").contains("GST included"))
    }

    @Test
    fun `bill without GST is a plain bill`() {
        val b = bill.copy(gstFlag = false, gstPct = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, taxableAmt = BigDecimal("70.00"))
        val all = rows(b).joinToString(" ")
        assertTrue(all.contains("BILL"))
        assertFalse(all.contains("TAX INVOICE"))
        assertFalse(all.contains("GSTIN"))
        assertFalse(all.contains("CGST"))
    }

    @Test
    fun `penalty and UPI are printed, with the UPI QR above the footer`() {
        val link = "upi://pay?pa=a%40b&pn=X&am=118.00&cu=INR&tn=n"
        val b = bill.copy(penaltyAmt = BigDecimal("48.00"), netAmt = BigDecimal("118.00"), payMode = PayMode.UPI, upiLink = link)
        val r = rows(b)
        assertTrue(row(r, "Penalty").endsWith("48.00"))
        assertTrue(row(r, "Paid by").endsWith("UPI"))
        assertTrue(r.none { it.startsWith("UPI Ref") })
        val lines = BillFormatter.format(b, 58).lines
        val qr = lines.indexOf(PrintLine.Qr(link))
        assertTrue(qr > 0)
        if (b.footerLines.isNotEmpty()) {
            assertTrue(qr < lines.indexOfFirst { it is PrintLine.Text && it.text.trim() == b.footerLines.first() })
        }
    }

    @Test
    fun `cash bill has no QR`() {
        assertTrue(BillFormatter.format(bill, 58).lines.none { it is PrintLine.Qr })
    }

    @Test
    fun `one block has no next-block line and 80 mm uses 48 characters`() {
        val r = rows(bill.copy(blocks = 1, baseAmt = BigDecimal("50.00")), width = 80)
        assertTrue(r.all { it.length <= 48 })
        assertFalse(r.any { it.startsWith("Next") })
    }

    @Test
    fun `stored time is hours and minutes`() {
        assertEquals("50 h 00 m", BillFormatter.storedTime("2026-11-01 00:00:00", "2026-11-03 02:00:00"))
        assertEquals("0 h 02 m", BillFormatter.storedTime("2026-11-01 00:00:00", "2026-11-01 00:02:30"))
    }
}
