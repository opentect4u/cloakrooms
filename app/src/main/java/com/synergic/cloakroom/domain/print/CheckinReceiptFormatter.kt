package com.synergic.cloakroom.domain.print

import com.synergic.cloakroom.domain.qr.ReceiptQr
import com.synergic.cloakroom.domain.qr.ReceiptQrData
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.time.DateTimes
import java.math.BigDecimal

/** Everything printed on the check-in receipt. [idNumber] is the stored (masked) value. */
data class CheckinReceipt(
    val customerName: String,
    /** md_header_footer H lines with in_flag = Y, in order. */
    val headerLines: List<String>,
    /** md_header_footer F lines with in_flag = Y, in order. */
    val footerLines: List<String>,
    val userCode: String,
    val userName: String,
    val idProofName: String?,
    val idNumber: String,
    val qr: ReceiptQrData,
    /** Logo (md_header_footer L row with in_flag = Y), black-and-white PNG; printed first. */
    val logo: ByteArray? = null,
)

/** Lays out the check-in receipt (no payment: the bill is made at check-out). */
object CheckinReceiptFormatter {

    fun format(r: CheckinReceipt, settings: AppSettings): PrintDocument {
        val q = r.qr
        val p = PrintLayout(PrintLayout.charsFor(settings.paperWidthMm))

        r.logo?.let { p.image(it) }
        val header = r.headerLines.ifEmpty { listOf(r.customerName) }
        header.forEachIndexed { i, line -> p.center(line, bold = i == 0) }
        p.divider()
        p.center("LUGGAGE RECEIPT", bold = true)
        p.divider()
        p.pair("Receipt No", q.receiptNo, bold = true)
        p.pair("Date/Time", DateTimes.displayText(q.dateTimeIn))
        p.pair("Counter", "${r.userCode} ${r.userName}")
        p.divider()
        p.pair("Name", q.name)
        p.pair("Mobile", q.mobile)
        p.pair("PNR", q.pnr.ifEmpty { "-" })
        p.pair("Bags", q.bags.toString(), bold = true)
        if (q.extraBags > 0) p.pair("Extra bags (free)", q.extraBags.toString(), bold = true)
        if (r.idProofName != null && r.idNumber.isNotEmpty()) p.pair("ID", "${r.idProofName} ${r.idNumber}")
        p.divider()
        p.left(
            "Rate: ${rupees(settings.firstBlockRate)} per bag for the first ${settings.blockHours} h, " +
                "${rupees(settings.nextBlockRate)} per bag for each further ${settings.blockHours} h."
        )
        p.left("Payment at check-out.")
        if (settings.qrFlag) {
            p.blank()
            p.qr(ReceiptQr.encode(q))
        }
        if (r.footerLines.isNotEmpty()) {
            p.divider()
            r.footerLines.forEach { p.center(it) }
        }
        return p.build("Receipt ${q.receiptNo}", settings.paperWidthMm)
    }

    /** "Rs.50" or "Rs.12.50". Thermal printers often lack the ₹ sign, so print documents use "Rs.". */
    fun rupees(amount: BigDecimal): String {
        val plain = amount.stripTrailingZeros()
        return "Rs." + if (plain.scale() <= 0) plain.toPlainString() else amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    }
}
