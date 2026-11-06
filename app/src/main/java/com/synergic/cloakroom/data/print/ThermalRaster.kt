package com.synergic.cloakroom.data.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.synergic.cloakroom.domain.print.PrintDocument

/**
 * Draws a whole receipt or bill as one black-and-white picture at the printer's dot width, so the
 * paper looks exactly like the preview (₹ sign, logo and QR included) on any ESC/POS printer.
 */
object ThermalRaster {

    /** Printable dots per line at 203 dpi: 58 mm paper → 384, 80 mm paper → 576. */
    fun dotsFor(paperWidthMm: Int): Int = if (paperWidthMm >= 80) 576 else 384

    fun render(document: PrintDocument): EscPosEncoder.MonoRaster {
        val width = dotsFor(document.paperWidthMm)
        val painter = DocumentPainter(document, width, margin = 0, antiAlias = false)
        val bitmap = Bitmap.createBitmap(width, maxOf(1, painter.height), Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(bitmap))
        return toMonoRaster(bitmap)
    }

    /** One bit per dot (1 = black), rows packed MSB first. Dark pixels print black. */
    fun toMonoRaster(bitmap: Bitmap): EscPosEncoder.MonoRaster {
        val widthBytes = (bitmap.width + 7) / 8
        val data = ByteArray(widthBytes * bitmap.height)
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) {
                if (Color.red(row[x]) < THRESHOLD) {
                    val i = y * widthBytes + x / 8
                    data[i] = (data[i].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
        }
        return EscPosEncoder.MonoRaster(widthBytes, bitmap.height, data)
    }

    private const val THRESHOLD = 128
}
