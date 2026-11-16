package com.synergic.cloakroom.domain.print

import com.synergic.cloakroom.domain.billing.money
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.time.DateTimes
import java.math.BigDecimal
import java.time.Duration

/**
 * Everything printed on a bill. Amounts, rates, block length and GST come from the td_receipt
 * snapshot, never from today's settings, so a reprint always matches the original bill.
 */
data class BillPrint(
    val customerName: String,
    val gstNo: String?,
    /** md_header_footer H / F lines with out_flag = Y. */
    val headerLines: List<String>,
    val footerLines: List<String>,
    val billNo: String,
    val receiptNo: String,
    /** UTC db text. */
    val dateTimeIn: String,
    val dateTimeOut: String,
    val userCode: String,
    val userName: String,
    val name: String,
    val mobile: String,
    val pnr: String,
    val bags: Int,
    /** Extra bags stored free of charge (printed, not in the amounts). */
    val extraBags: Int = 0,
    val blocks: Int,
    val blockHours: Int,
    val firstBlockRate: BigDecimal,
    val nextBlockRate: BigDecimal,
    val baseAmt: BigDecimal,
    val penaltyAmt: BigDecimal,
    val taxableAmt: BigDecimal,
    val gstFlag: Boolean,
    val gstPct: BigDecimal,
    val cgst: BigDecimal,
    val sgst: BigDecimal,
    val igst: BigDecimal,
    val netAmt: BigDecimal,
    val payMode: PayMode,
    /** Logo (md_header_footer L row with out_flag = Y), black-and-white PNG; printed first. */
    val logo: ByteArray? = null,
    /** UPI payment link for the net amount (UPI bills only); printed as a QR above the footer. */
    val upiLink: String? = null,
)

object BillFormatter {

    fun format(b: BillPrint, paperWidthMm: Int): PrintDocument {
        val p = PrintLayout(PrintLayout.charsFor(paperWidthMm))
        b.logo?.let { p.image(it) }
        b.headerLines.ifEmpty { listOf(b.customerName) }.forEachIndexed { i, line -> p.center(line, bold = i == 0) }
        p.divider()
        p.center(if (b.gstFlag) "TAX INVOICE" else "BILL", bold = true)
        if (b.gstFlag && !b.gstNo.isNullOrBlank()) p.center("GSTIN: ${b.gstNo}")
        p.divider()
        p.pair("Bill No", b.billNo, bold = true)
        p.pair("Date", DateTimes.displayText(b.dateTimeOut))
        p.pair("Receipt No", b.receiptNo)
        p.pair("Counter", "${b.userCode} ${b.userName}")
        p.divider()
        p.pair("Name", b.name)
        p.pair("Mobile", b.mobile)
        p.pair("PNR", b.pnr.ifEmpty { "-" })
        p.pair("Bags", b.bags.toString())
        if (b.extraBags > 0) p.pair("Extra bags (free)", b.extraBags.toString())
        p.pair("In", DateTimes.displayText(b.dateTimeIn))
        p.pair("Out", DateTimes.displayText(b.dateTimeOut))
        p.pair("Time stored", storedTime(b.dateTimeIn, b.dateTimeOut))
        p.divider()

        val h = b.blockHours
        p.pair("First $h h: ${b.bags} x ${amount(b.firstBlockRate)}", amount(b.firstBlockRate * BigDecimal(b.bags)))
        if (b.blocks > 1) {
            val next = b.blocks - 1
            p.pair(
                "Next $next x $h h: ${b.bags} x ${amount(b.nextBlockRate)}",
                amount(b.nextBlockRate * BigDecimal(b.bags) * BigDecimal(next)),
            )
        }
        p.pair("Rent", amount(b.baseAmt))
        if (b.penaltyAmt.signum() > 0) p.pair("Penalty", amount(b.penaltyAmt))
        if (b.gstFlag) {
            p.pair("Taxable value", amount(b.taxableAmt))
            if (b.igst.signum() > 0) {
                p.pair("IGST ${pct(b.gstPct)}%", amount(b.igst))
            } else {
                val half = b.gstPct.divide(BigDecimal(2))
                p.pair("CGST ${pct(half)}%", amount(b.cgst))
                p.pair("SGST ${pct(half)}%", amount(b.sgst))
            }
        }
        p.divider()
        p.pair("TOTAL", "Rs.${amount(b.netAmt)}", bold = true)
        if (b.gstFlag && isInclusive(b)) p.left("(GST included in the rate)")
        p.pair("Paid by", if (b.payMode == PayMode.UPI) "UPI" else "Cash")
        if (b.upiLink != null) {
            p.divider()
            p.center("Scan to pay by UPI")
            p.qr(b.upiLink)
        }
        if (b.footerLines.isNotEmpty()) {
            p.divider()
            b.footerLines.forEach { p.center(it) }
        }
        return p.build("Bill ${b.billNo}", paperWidthMm)
    }

    /** Inclusive bills collect exactly rent + penalty; exclusive ones add the GST on top. */
    fun isInclusive(b: BillPrint): Boolean = (b.cgst + b.sgst + b.igst).signum() == 0 ||
        b.netAmt.money().compareTo((b.baseAmt + b.penaltyAmt).money()) == 0

    /** "26 h 05 m". */
    fun storedTime(dateTimeIn: String, dateTimeOut: String): String {
        val minutes = Duration.between(DateTimes.fromDbText(dateTimeIn), DateTimes.fromDbText(dateTimeOut)).toMinutes().coerceAtLeast(0)
        return "%d h %02d m".format(minutes / 60, minutes % 60)
    }

    private fun amount(a: BigDecimal): String = a.money().toPlainString()

    private fun pct(p: BigDecimal): String = p.stripTrailingZeros().toPlainString()
}
