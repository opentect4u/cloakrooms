package com.synergic.cloakroom.domain.print

import com.synergic.cloakroom.domain.billing.money
import com.synergic.cloakroom.domain.report.CollectionLine
import com.synergic.cloakroom.domain.report.ReportData
import com.synergic.cloakroom.domain.settings.PayMode
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

/** Collection report for the thermal printer or PDF: summary, GST breakup and the list of bills. */
object ReportFormatter {

    private val DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy")
    private val TIME = DateTimeFormatter.ofPattern("hh:mm a")

    /**
     * [userLabel] e.g. "All users" or "U02 Counter User"; [shiftLabel] e.g. "All shifts" or
     * "Morning (06:00–14:00)"; [headerLines] are H lines with report_flag = Y.
     */
    fun format(
        data: ReportData,
        customerName: String,
        headerLines: List<String>,
        footerLines: List<String>,
        userLabel: String,
        paperWidthMm: Int,
        shiftLabel: String = "All shifts",
        logo: ByteArray? = null,
    ): PrintDocument {
        val p = PrintLayout(PrintLayout.charsFor(paperWidthMm))
        logo?.let { p.image(it) }
        headerLines.ifEmpty { listOf(customerName) }.forEachIndexed { i, line -> p.center(line, bold = i == 0) }
        p.divider()
        p.center("COLLECTION REPORT", bold = true)
        val f = data.filter
        if (f.wholeDays) {
            p.center(if (f.from == f.to) f.from.format(DATE) else "${f.from.format(DATE)} to ${f.to.format(DATE)}")
        } else {
            p.center("From ${f.from.format(DATE)} ${f.fromTime.format(TIME)}")
            p.center("To ${f.to.format(DATE)} ${f.toTime.format(TIME)}")
        }
        p.center(userLabel)
        p.center(shiftLabel)
        p.divider()
        p.pair("Check-ins", "${data.checkins.size} (${data.bagsCheckedIn} bags)")
        if (data.extraBagsCheckedIn > 0) p.pair("Extra bags (free)", data.extraBagsCheckedIn.toString())
        p.pair("Bills", data.bills.size.toString())
        p.divider()
        data.byPayMode.forEach { line ->
            p.left(if (line.payMode == PayMode.UPI) "UPI" else "Cash", bold = true)
            breakup(p, line)
        }
        p.left("TOTAL", bold = true)
        breakup(p, data.total)
        if (data.advanceCollected.signum() > 0 || data.total.advAmt.signum() > 0) {
            p.pair("Advance at check-in", amt(data.advanceCollected))
            p.pair("Balance at check-out", amt(data.total.balAmt))
        }
        p.pair("Collected", "Rs.${amt(data.totalCollected)}", bold = true)
        if (data.bills.isNotEmpty()) {
            p.divider()
            p.left("Bills", bold = true)
            data.bills.forEach { b ->
                p.pair("${b.billNo} ${if (b.payMode == PayMode.UPI) "UPI" else "Cash"}", amt(b.netAmt))
            }
        }
        if (footerLines.isNotEmpty()) {
            p.divider()
            footerLines.forEach { p.center(it) }
        }
        val title = "Report ${data.filter.from}" + if (data.filter.to != data.filter.from) "_${data.filter.to}" else ""
        return p.build(title, paperWidthMm)
    }

    private fun breakup(p: PrintLayout, l: CollectionLine) {
        p.pair("  Bills", l.bills.toString())
        p.pair("  Rent", amt(l.baseAmt))
        if (l.penaltyAmt.signum() > 0) p.pair("  Penalty", amt(l.penaltyAmt))
        p.pair("  Taxable", amt(l.taxableAmt))
        if (l.cgst.signum() > 0 || l.sgst.signum() > 0) {
            p.pair("  CGST", amt(l.cgst))
            p.pair("  SGST", amt(l.sgst))
        }
        if (l.igst.signum() > 0) p.pair("  IGST", amt(l.igst))
        p.pair("  Amount", amt(l.netAmt))
        if (l.advAmt.signum() > 0) {
            p.pair("  Less advance", amt(l.advAmt))
            p.pair("  Collected", amt(l.balAmt))
        }
    }

    private fun amt(a: BigDecimal) = a.money().toPlainString()
}
