package com.synergic.cloakroom.data.print

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.print.PrintLine
import com.synergic.cloakroom.domain.print.PrintResult
import com.synergic.cloakroom.domain.print.Printer
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.settings
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.PrinterType
import com.synergic.cloakroom.domain.settings.SettingDefinition
import com.synergic.cloakroom.domain.settings.SettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PRINTER_TYPE decides where a document goes; without a printer it stays a preview. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PosPrinterTest {

    private val doc = PrintDocument(
        title = "Receipt U02-00001",
        paperWidthMm = 58,
        charsPerLine = 32,
        lines = listOf(PrintLine.Text("Demo Cloak Room", bold = true), PrintLine.Divider, PrintLine.Text("Amount  ₹70.00")),
    )

    private class FakePort(private val present: Boolean, private val reason: String? = null) : PrinterPort {
        val sent = mutableListOf<ByteArray>()
        override fun isPresent() = present
        override fun unavailableReason() = reason
        override suspend fun send(data: ByteArray): PrintResult {
            sent += data
            return if (present) PrintResult.Printed else PrintResult.Failed("not found")
        }
    }

    private class FakeSettings(value: AppSettings) : SettingsRepository {
        override val currentSettings: StateFlow<AppSettings?> = MutableStateFlow(value)
        override fun observeSettings(userId: Long?): Flow<AppSettings> = flowOf(currentSettings.value!!)
        override suspend fun getSettings(userId: Long?): AppSettings = currentSettings.value!!
        override fun observeDefinitions(): Flow<List<SettingDefinition>> = flowOf(emptyList())
        override suspend fun updateSettings(changes: Map<String, String>): Int = error("not used")
        override fun observePrintDefinitions(): Flow<List<SettingDefinition>> = flowOf(emptyList())
        override suspend fun updatePrintSettings(changes: Map<String, String>): Int = error("not used")
        override suspend fun savePrinterChoice(printerType: PrinterType, btPrinter: String): Int = error("not used")
    }

    private val preview: Printer = PreviewPrinter()

    private fun printer(type: String, serial: FakePort, usb: FakePort, bluetooth: FakePort = FakePort(present = false)) =
        PosPrinter(FakeSettings(settings(SettingKeys.PRINTER_TYPE to type)), bluetooth, serial, usb, preview)

    @Test
    fun `auto prefers a paired Bluetooth printer`() = runTest {
        val bluetooth = FakePort(present = true)
        val serial = FakePort(present = true)
        assertEquals(PrintResult.Printed, printer("A", serial, FakePort(false), bluetooth).print(doc, 1))
        assertEquals(1, bluetooth.sent.size)
        assertTrue(serial.sent.isEmpty())
    }

    @Test
    fun `bluetooth chosen prints on Bluetooth only`() = runTest {
        val bluetooth = FakePort(present = true)
        val serial = FakePort(present = true)
        assertEquals(PrintResult.Printed, printer("B", serial, FakePort(true), bluetooth).print(doc, 1))
        assertEquals(1, bluetooth.sent.size)
        assertTrue(serial.sent.isEmpty())
    }

    @Test
    fun `auto tells what to do when the permission is missing instead of the preview message`() = runTest {
        val bluetooth = FakePort(present = false, reason = "allow Nearby devices")
        assertEquals(
            PrintResult.Failed("allow Nearby devices"),
            printer("A", FakePort(false), FakePort(false), bluetooth).print(doc, 1),
        )
    }

    @Test
    fun `auto prints on the built-in printer when it is there`() = runTest {
        val serial = FakePort(present = true)
        val usb = FakePort(present = true)
        assertEquals(PrintResult.Printed, printer("A", serial, usb).print(doc, 1))
        assertEquals(1, serial.sent.size)
        assertTrue(usb.sent.isEmpty())
    }

    @Test
    fun `auto uses USB when there is no built-in printer`() = runTest {
        val serial = FakePort(present = false)
        val usb = FakePort(present = true)
        assertEquals(PrintResult.Printed, printer("A", serial, usb).print(doc, 1))
        assertEquals(1, usb.sent.size)
    }

    @Test
    fun `auto without any printer keeps the preview`() = runTest {
        val serial = FakePort(present = false)
        val usb = FakePort(present = false)
        assertEquals(PrintResult.PreviewOnly, printer("A", serial, usb).print(doc, 1))
        assertTrue(serial.sent.isEmpty() && usb.sent.isEmpty())
    }

    @Test
    fun `none never sends anything`() = runTest {
        val serial = FakePort(present = true)
        assertEquals(PrintResult.PreviewOnly, printer("N", serial, FakePort(true)).print(doc, 1))
        assertTrue(serial.sent.isEmpty())
    }

    @Test
    fun `a chosen printer that is missing is reported, not hidden`() = runTest {
        val r = printer("S", FakePort(present = false), FakePort(present = true)).print(doc, 1)
        assertTrue(r is PrintResult.Failed)
    }

    @Test
    fun `the document is sent as a 384-dot picture on 58 mm paper`() = runTest {
        val serial = FakePort(present = true)
        printer("S", serial, FakePort(false)).print(doc, 2)
        val bytes = serial.sent.single()
        // GS v 0 with 48 bytes (384 dots) per row.
        val header = byteArrayOf(0x1D, 'v'.code.toByte(), '0'.code.toByte(), 0, 48, 0)
        val starts = (0..bytes.size - header.size).count { i -> header.indices.all { bytes[i + it] == header[it] } }
        assertTrue("expected bands for 2 copies, found $starts", starts >= 2)
    }
}
