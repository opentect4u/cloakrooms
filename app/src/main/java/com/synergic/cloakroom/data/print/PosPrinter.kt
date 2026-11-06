package com.synergic.cloakroom.data.print

import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.print.PrintResult
import com.synergic.cloakroom.domain.print.Printer
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.settings.PrinterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The printer the app uses. The PRINTER_TYPE setting picks a paired Bluetooth printer, the POS's
 * built-in serial printer (GC039), a USB printer (GC039ES), the first one found (Auto, in that
 * order), or none. Without a printer the document stays on screen as a preview
 * ([PreviewPrinter]) and can be shared as a PDF.
 *
 * The document is printed as one picture ([ThermalRaster]), so the paper matches the preview.
 */
@Singleton
class PosPrinter internal constructor(
    private val settingsRepository: SettingsRepository,
    private val bluetooth: PrinterPort,
    private val serial: PrinterPort,
    private val usb: PrinterPort,
    private val preview: Printer,
) : Printer {

    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        bluetooth: BluetoothPrinterPort,
        serial: SerialPrinterPort,
        usb: UsbPrinterPort,
        preview: PreviewPrinter,
    ) : this(settingsRepository, bluetooth as PrinterPort, serial as PrinterPort, usb as PrinterPort, preview as Printer)

    /** One print at a time: the printer cannot mix two jobs. */
    private val mutex = Mutex()

    override suspend fun print(document: PrintDocument, copies: Int): PrintResult = mutex.withLock {
        val type = (settingsRepository.currentSettings.value ?: settingsRepository.getSettings(null)).printerType
        val port: PrinterPort = when (type) {
            PrinterType.NONE -> return preview.print(document, copies)
            PrinterType.AUTO -> {
                val ports = listOf(bluetooth, serial, usb)
                ports.firstOrNull { it.isPresent() }
                    ?: return ports.firstNotNullOfOrNull { it.unavailableReason() }?.let { PrintResult.Failed(it) }
                        ?: preview.print(document, copies)
            }
            PrinterType.BLUETOOTH -> bluetooth
            // The chosen port reports itself when it cannot find its printer.
            PrinterType.BUILT_IN_SERIAL -> serial
            PrinterType.USB -> usb
        }
        port.send(encode(document, copies))
    }

    companion object {
        /** The document as one ESC/POS picture, ready for any [PrinterPort]. */
        suspend fun encode(document: PrintDocument, copies: Int): ByteArray = withContext(Dispatchers.Default) {
            EscPosEncoder.encodeRaster(ThermalRaster.render(document), copies)
        }
    }
}
