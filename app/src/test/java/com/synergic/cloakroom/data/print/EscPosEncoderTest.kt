package com.synergic.cloakroom.data.print

import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.print.PrintLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosEncoderTest {

    private val doc = PrintDocument(
        title = "t",
        paperWidthMm = 58,
        charsPerLine = 32,
        lines = listOf(PrintLine.Text("Rs.50 ₹", bold = true), PrintLine.Divider, PrintLine.Qr("{\"v\":1}")),
    )

    private fun ByteArray.indexOf(part: ByteArray): Int =
        (0..size - part.size).firstOrNull { i -> part.indices.all { this[i + it] == part[it] } } ?: -1

    @Test
    fun `starts with init and ends with a cut`() {
        val bytes = EscPosEncoder.encode(doc, 1)
        assertEquals(listOf<Byte>(0x1B, '@'.code.toByte()), bytes.take(2))
        assertEquals(listOf<Byte>(0x1D, 'V'.code.toByte(), 66, 0), bytes.takeLast(4))
    }

    @Test
    fun `text is ASCII and non-ASCII becomes a question mark`() {
        val bytes = EscPosEncoder.encode(doc, 1)
        assertTrue(bytes.indexOf("Rs.50 ?".toByteArray()) >= 0)
        assertTrue(bytes.indexOf("-".repeat(32).toByteArray()) >= 0)
    }

    @Test
    fun `QR data is stored with its length`() {
        val bytes = EscPosEncoder.encode(doc, 1)
        val data = "{\"v\":1}".toByteArray()
        val store = byteArrayOf(0x1D, '('.code.toByte(), 'k'.code.toByte(), (data.size + 3).toByte(), 0, 49, 80, 48) + data
        assertTrue(bytes.indexOf(store) >= 0)
    }

    @Test
    fun `copies repeat the document`() {
        val one = EscPosEncoder.encode(doc, 1)
        assertEquals(one.size * 3, EscPosEncoder.encode(doc, 3).size)
    }

    @Test
    fun `logo is sent as a raster image when a rasterizer is given`() {
        val withLogo = doc.copy(lines = listOf(PrintLine.Image(byteArrayOf(9))) + doc.lines)
        val raster = EscPosEncoder.MonoRaster(widthBytes = 2, height = 3, data = ByteArray(6) { 0x55 })
        val bytes = EscPosEncoder.encode(withLogo, 1) { raster }
        val header = byteArrayOf(0x1D, 'v'.code.toByte(), '0'.code.toByte(), 0, 2, 0, 3, 0) + ByteArray(6) { 0x55 }
        assertTrue(bytes.indexOf(header) >= 0)
        // Without a rasterizer the picture is skipped.
        assertEquals(EscPosEncoder.encode(doc, 1).size, EscPosEncoder.encode(withLogo, 1).size)
    }

    @Test
    fun `raster document is sent in bands with every dot row exactly once`() {
        val height = EscPosEncoder.BAND_ROWS + 10
        val data = ByteArray(48 * height) { it.toByte() }
        val bytes = EscPosEncoder.encodeRaster(EscPosEncoder.MonoRaster(48, height, data), 1)

        assertEquals(listOf<Byte>(0x1B, '@'.code.toByte()), bytes.take(2))
        val firstBand = byteArrayOf(0x1D, 'v'.code.toByte(), '0'.code.toByte(), 0, 48, 0, EscPosEncoder.BAND_ROWS.toByte(), 0) +
            data.copyOfRange(0, 48 * EscPosEncoder.BAND_ROWS)
        val secondBand = byteArrayOf(0x1D, 'v'.code.toByte(), '0'.code.toByte(), 0, 48, 0, 10, 0) +
            data.copyOfRange(48 * EscPosEncoder.BAND_ROWS, data.size)
        assertEquals(2, bytes.indexOf(firstBand))
        assertEquals(2 + firstBand.size, bytes.indexOf(secondBand))
        // Ends with paper feed (ESC J) to tear off.
        assertEquals(listOf<Byte>(0x1B, 'J'.code.toByte(), 48), bytes.takeLast(3))
    }

    @Test
    fun `raster copies repeat the picture but initialise once`() {
        val raster = EscPosEncoder.MonoRaster(48, 5, ByteArray(48 * 5))
        val one = EscPosEncoder.encodeRaster(raster, 1)
        assertEquals(2 + (one.size - 2) * 3, EscPosEncoder.encodeRaster(raster, 3).size)
    }
}
