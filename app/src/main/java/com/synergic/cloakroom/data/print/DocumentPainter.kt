package com.synergic.cloakroom.data.print

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.print.PrintLine

/**
 * Draws a [PrintDocument] on a canvas [pageWidth] units wide, so the PDF and the thermal printer
 * show the same layout. [antiAlias] = false gives crisp black dots for the printer.
 */
class DocumentPainter(
    private val document: PrintDocument,
    private val pageWidth: Int,
    private val margin: Int,
    antiAlias: Boolean,
) {
    private val contentWidth = pageWidth - 2 * margin
    private val paint = Paint().apply {
        isAntiAlias = antiAlias
        typeface = Typeface.MONOSPACE
        color = Color.BLACK
        // Monospace glyphs are about 0.6 × the text size wide.
        textSize = contentWidth / (document.charsPerLine * 0.6f)
    }
    private val lineHeight = paint.textSize * 1.25f
    private val qrSize = (contentWidth * 0.7f).toInt()

    // Logos are scaled to at most the content width, keeping their shape.
    private val images: Map<PrintLine.Image, Bitmap?> = document.lines.filterIsInstance<PrintLine.Image>()
        .associateWith { BitmapFactory.decodeByteArray(it.png, 0, it.png.size) }

    private fun imageSize(line: PrintLine.Image): Pair<Int, Int> {
        val b = images[line] ?: return 0 to 0
        val w = minOf(contentWidth, b.width)
        return w to b.height * w / b.width
    }

    /** Height of the whole document, margins included. */
    val height: Int = margin * 2 + document.lines.sumOf { line ->
        when (line) {
            is PrintLine.Qr -> qrSize + lineHeight.toDouble()
            is PrintLine.Image -> imageSize(line).second + lineHeight / 2.0
            else -> lineHeight.toDouble()
        }
    }.toInt()

    fun draw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        var y = margin.toFloat()
        document.lines.forEach { line ->
            when (line) {
                is PrintLine.Text -> {
                    paint.isFakeBoldText = line.bold
                    canvas.drawText(line.text, margin.toFloat(), y + paint.textSize, paint)
                    y += lineHeight
                }
                PrintLine.Divider -> {
                    paint.isFakeBoldText = false
                    canvas.drawText("-".repeat(document.charsPerLine), margin.toFloat(), y + paint.textSize, paint)
                    y += lineHeight
                }
                is PrintLine.Qr -> {
                    val bitmap = QrBitmap.encode(line.content, qrSize)
                    val left = (pageWidth - qrSize) / 2
                    canvas.drawBitmap(bitmap, null, Rect(left, y.toInt(), left + qrSize, y.toInt() + qrSize), null)
                    y += qrSize + lineHeight
                }
                is PrintLine.Image -> {
                    val bitmap = images[line]
                    val (w, h) = imageSize(line)
                    if (bitmap != null) {
                        val left = (pageWidth - w) / 2
                        canvas.drawBitmap(bitmap, null, Rect(left, y.toInt(), left + w, y.toInt() + h), null)
                    }
                    y += h + lineHeight / 2
                }
            }
        }
    }
}
