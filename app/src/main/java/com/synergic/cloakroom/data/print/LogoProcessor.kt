package com.synergic.cloakroom.data.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a picture chosen by the admin into the logo stored in md_header_footer.image: at most
 * [MAX_WIDTH] dots wide (fits 58 mm printers), pure black and white, as PNG.
 */
@Singleton
class LogoProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** @throws IllegalArgumentException when the file is not a picture. */
    suspend fun fromUri(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("This file is not a picture.")
        toMonochromePng(source)
    }

    private fun toMonochromePng(source: Bitmap): ByteArray {
        val width = minOf(MAX_WIDTH, source.width)
        val height = maxOf(1, source.height * width / source.width)
        // Draw on white first, so transparent parts of a PNG logo become paper, not black.
        val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(scaled).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(source, width, height, true), 0f, 0f, null)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val luminance = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            pixels[i] = if (luminance < THRESHOLD) Color.BLACK else Color.WHITE
        }
        scaled.setPixels(pixels, 0, width, 0, 0, width, height)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    companion object {
        /** 58 mm thermal printers have 384 dots per line. */
        const val MAX_WIDTH = 384
        private const val THRESHOLD = 128

        /** For ESC/POS: one bit per dot (1 = black), rows packed MSB first. */
        fun rasterize(png: ByteArray): EscPosEncoder.MonoRaster? {
            val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
            return ThermalRaster.toMonoRaster(bitmap)
        }
    }
}
