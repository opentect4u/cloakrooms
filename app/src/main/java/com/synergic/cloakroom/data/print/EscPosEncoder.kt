package com.synergic.cloakroom.data.print

import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.print.PrintLine
import java.io.ByteArrayOutputStream

/**
 * Turns a [PrintDocument] into ESC/POS commands understood by most 58/80 mm thermal printers.
 * Text is sent as plain ASCII (other characters become '?'); QR codes use the GS ( k command.
 */
object EscPosEncoder {

    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LF = 0x0A

    /** A picture as 1-bit rows: [widthBytes] bytes per row, MSB = leftmost dot, 1 = black. */
    class MonoRaster(val widthBytes: Int, val height: Int, val data: ByteArray)

    /**
     * [rasterize] turns a PNG logo into dots (Android code, passed in so this stays testable);
     * without it, pictures are skipped.
     */
    fun encode(document: PrintDocument, copies: Int, rasterize: ((ByteArray) -> MonoRaster?)? = null): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(copies.coerceAtLeast(1)) {
            out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte())) // initialise
            document.lines.forEach { line ->
                when (line) {
                    is PrintLine.Text -> {
                        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), if (line.bold) 1 else 0))
                        out.write(ascii(line.text))
                        out.write(LF)
                    }
                    PrintLine.Divider -> {
                        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
                        out.write(ascii("-".repeat(document.charsPerLine)))
                        out.write(LF)
                    }
                    is PrintLine.Qr -> qr(out, line.content)
                    is PrintLine.Image -> rasterize?.invoke(line.png)?.let { image(out, it) }
                }
            }
            out.write(byteArrayOf(ESC.toByte(), 'd'.code.toByte(), 4)) // feed 4 lines
            out.write(byteArrayOf(GS.toByte(), 'V'.code.toByte(), 66, 0)) // partial cut (ignored without a cutter)
        }
        return out.toByteArray()
    }

    /**
     * A whole document already drawn as dots (see [ThermalRaster]): initialise, then the picture
     * in bands of [BAND_ROWS] rows (GS v 0, as in the GC039 SDK), then paper feed to tear off.
     */
    fun encodeRaster(raster: MonoRaster, copies: Int, feedMm: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte()))
        repeat(copies.coerceAtLeast(1)) {
            var y = 0
            while (y < raster.height) {
                val rows = minOf(BAND_ROWS, raster.height - y)
                out.write(
                    byteArrayOf(
                        GS.toByte(), 'v'.code.toByte(), '0'.code.toByte(), 0,
                        (raster.widthBytes % 256).toByte(), (raster.widthBytes / 256).toByte(),
                        (rows % 256).toByte(), (rows / 256).toByte(),
                    )
                )
                out.write(raster.data, y * raster.widthBytes, rows * raster.widthBytes)
                y += rows
            }
            feed(out, feedMm)
        }
        return out.toByteArray()
    }

    /** ESC J paper feed of [mm] millimetres (PAPER_FEED_MM), in steps of at most [MAX_FEED_DOTS] dots. */
    private fun feed(out: ByteArrayOutputStream, mm: Int) {
        var dots = mm.coerceAtLeast(0) * DOTS_PER_MM
        while (dots > 0) {
            val step = minOf(dots, MAX_FEED_DOTS)
            out.write(byteArrayOf(ESC.toByte(), 'J'.code.toByte(), step.toByte()))
            dots -= step
        }
    }

    /** Rows per GS v 0 command; small bands keep each command within the printer's buffer. */
    const val BAND_ROWS = 64

    /** Thermal printers print 203 dpi = 8 dots per mm. */
    const val DOTS_PER_MM = 8

    /** ESC J takes one byte; 240 dots (30 mm) per command stays well inside it. */
    const val MAX_FEED_DOTS = 240

    private fun qr(out: ByteArrayOutputStream, content: String) {
        val data = content.toByteArray(Charsets.UTF_8)
        fun gsK(vararg bytes: Int) {
            out.write(byteArrayOf(GS.toByte(), '('.code.toByte(), 'k'.code.toByte()))
            out.write(bytes.map { it.toByte() }.toByteArray())
        }
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1)) // centre
        gsK(4, 0, 49, 65, 50, 0) // model 2
        gsK(3, 0, 49, 67, 6) // module size 6
        gsK(3, 0, 49, 69, 49) // error correction M
        val len = data.size + 3
        gsK(len % 256, len / 256, 49, 80, 48) // store the data …
        out.write(data)
        gsK(3, 0, 49, 81, 48) // … and print it
        out.write(LF)
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0)) // left
    }

    /** GS v 0: print a raster bit image, centred. */
    private fun image(out: ByteArrayOutputStream, r: MonoRaster) {
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))
        out.write(
            byteArrayOf(
                GS.toByte(), 'v'.code.toByte(), '0'.code.toByte(), 0,
                (r.widthBytes % 256).toByte(), (r.widthBytes / 256).toByte(),
                (r.height % 256).toByte(), (r.height / 256).toByte(),
            )
        )
        out.write(r.data)
        out.write(LF)
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
    }

    private fun ascii(text: String): ByteArray =
        text.map { if (it.code in 32..126) it.code.toByte() else '?'.code.toByte() }.toByteArray()
}
