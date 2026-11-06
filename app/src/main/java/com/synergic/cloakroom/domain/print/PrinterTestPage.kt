package com.synergic.cloakroom.domain.print

/**
 * A short page to check a printer (Printer setup → Test print): width ruler, ₹ sign, bold text
 * and a QR, so the user sees at once whether the paper width and the picture print are right.
 */
object PrinterTestPage {

    /** [dateTime] is already formatted for display (IST). */
    fun format(printerName: String, paperWidthMm: Int, dateTime: String): PrintDocument {
        val width = PrintLayout.charsFor(paperWidthMm)
        val p = PrintLayout(width)
        p.center("PRINTER TEST", bold = true)
        p.divider()
        p.pair("Printer", printerName)
        p.pair("Paper", "$paperWidthMm mm ($width characters)")
        p.pair("Time", dateTime)
        p.divider()
        // One full row: the last digit must be at the right edge of the paper.
        p.left("1234567890".repeat(width / 10 + 1).take(width))
        p.pair("Amount", "₹70.00", bold = true)
        p.blank()
        p.qr("Cloak Room printer test")
        p.center("If you can read this, the printer works.")
        return p.build("Printer test", paperWidthMm)
    }
}
