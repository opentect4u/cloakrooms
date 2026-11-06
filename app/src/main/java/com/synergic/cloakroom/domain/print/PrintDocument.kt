package com.synergic.cloakroom.domain.print

/**
 * A receipt, bill or report laid out for a thermal printer: monospace rows already wrapped and
 * padded to [charsPerLine]. Every printer (preview, PDF, ESC/POS) prints the same rows.
 */
data class PrintDocument(
    /** Used for the PDF file name, e.g. "Receipt U02-00001". */
    val title: String,
    /** 58 or 80. */
    val paperWidthMm: Int,
    val charsPerLine: Int,
    val lines: List<PrintLine>,
)

sealed interface PrintLine {
    /** One row of at most charsPerLine characters. */
    data class Text(val text: String, val bold: Boolean = false) : PrintLine

    /** A full-width line of dashes. */
    data object Divider : PrintLine

    /** A centred QR code. */
    data class Qr(val content: String) : PrintLine

    /** A centred black-and-white picture (the logo from md_header_footer), as PNG bytes. */
    class Image(val png: ByteArray) : PrintLine {
        override fun equals(other: Any?) = other is Image && png.contentEquals(other.png)
        override fun hashCode() = png.contentHashCode()
    }
}

sealed interface PrintResult {
    data object Printed : PrintResult

    /** No physical printer: the document was only shown on screen (and can be shared as PDF). */
    data object PreviewOnly : PrintResult

    data class Failed(val message: String) : PrintResult
}

/** Sends a document to a printer. The app uses one implementation at a time. */
interface Printer {
    suspend fun print(document: PrintDocument, copies: Int): PrintResult
}

/** Builds wrapped and padded rows for a given line width. */
class PrintLayout(val width: Int) {
    private val rows = mutableListOf<PrintLine>()

    fun center(text: String, bold: Boolean = false) = wrap(text).forEach {
        val pad = (width - it.length) / 2
        rows += PrintLine.Text(" ".repeat(pad) + it, bold)
    }

    fun left(text: String, bold: Boolean = false) = wrap(text).forEach { rows += PrintLine.Text(it, bold) }

    /** "Label        value"; when both do not fit on one row, the value goes right-aligned below. */
    fun pair(label: String, value: String, bold: Boolean = false) {
        if (label.length + 1 + value.length <= width) {
            rows += PrintLine.Text(label + " ".repeat(width - label.length - value.length) + value, bold)
        } else {
            left(label, bold)
            wrap(value).forEach { rows += PrintLine.Text(it.padStart(width), bold) }
        }
    }

    fun divider() { rows += PrintLine.Divider }
    fun blank() { rows += PrintLine.Text("") }
    fun qr(content: String) { rows += PrintLine.Qr(content) }
    fun image(png: ByteArray) { rows += PrintLine.Image(png) }

    fun build(title: String, paperWidthMm: Int) = PrintDocument(title, paperWidthMm, width, rows.toList())

    /** Word wrap; words longer than the line are cut. */
    fun wrap(text: String): List<String> {
        val result = mutableListOf<String>()
        text.lines().forEach { paragraph ->
            var line = ""
            paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
                var w = word
                while (w.length > width) {
                    if (line.isNotEmpty()) { result += line; line = "" }
                    result += w.take(width)
                    w = w.drop(width)
                }
                line = when {
                    line.isEmpty() -> w
                    line.length + 1 + w.length <= width -> "$line $w"
                    else -> { result += line; w }
                }
            }
            if (line.isNotEmpty() || paragraph.isBlank()) result += line
        }
        return result
    }

    companion object {
        /** Characters per row of the common thermal printers (Font A). */
        fun charsFor(paperWidthMm: Int): Int = if (paperWidthMm >= 80) 48 else 32
    }
}
