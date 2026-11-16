package com.synergic.cloakroom.ui.printer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.data.print.BluetoothPrinterPort
import com.synergic.cloakroom.data.print.PosPrinter
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.print.PrintResult
import com.synergic.cloakroom.domain.print.PrinterTestPage
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.settings.PrinterType
import com.synergic.cloakroom.domain.settings.SettingKeys
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrinterSetupUiState(
    val bluetooth: BluetoothPrinterPort.State = BluetoothPrinterPort.State.READY,
    val devices: List<BluetoothPrinterPort.PairedDevice> = emptyList(),
    /** BT_PRINTER: the chosen printer's address (or name); empty = none chosen. */
    val chosen: String = "",
    val printerType: PrinterType? = null,
    /** Admins save the default for everyone; normal users save for their own login. */
    val isAdmin: Boolean = false,
    /** PAPER_FEED_MM as saved, and as typed in the field (test prints use the typed value). */
    val savedFeedMm: Int? = null,
    val feedText: String = "",
    val feedError: String? = null,
    /** Limits of PAPER_FEED_MM, from its md_setting row. */
    val feedMin: Int = 0,
    val feedMax: Int? = null,
    val manualAddress: String = "",
    val manualError: String? = null,
    /** Address being test-printed or saved; buttons are disabled meanwhile. */
    val busyAddress: String? = null,
    val message: String? = null,
)

/**
 * Printer setup (every user): pick a paired Bluetooth printer (or type its address), test-print
 * on it, and save it (BT_PRINTER + PRINTER_TYPE = Bluetooth). An admin's choice is the default
 * for everyone; a normal user's choice applies to their own login only.
 */
@HiltViewModel
class PrinterSetupViewModel @Inject constructor(
    private val bluetooth: BluetoothPrinterPort,
    private val settingsRepository: SettingsRepository,
    private val time: TimeSource,
    sessionProvider: SessionProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(PrinterSetupUiState(isAdmin = sessionProvider.current?.isAdmin == true))
    val state: StateFlow<PrinterSetupUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            settingsRepository.currentSettings.collect { s ->
                if (s != null) _state.update {
                    // A newly saved feed replaces the field; an unsaved edit is kept otherwise.
                    val feedText = if (it.savedFeedMm != s.paperFeedMm) s.paperFeedMm.toString() else it.feedText
                    it.copy(chosen = s.btPrinter, printerType = s.printerType, savedFeedMm = s.paperFeedMm, feedText = feedText)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.observePrintDefinitions().collect { defs ->
                defs.firstOrNull { it.name == SettingKeys.PAPER_FEED_MM }?.let { d ->
                    _state.update { it.copy(feedMin = d.minValue?.toInt() ?: 0, feedMax = d.maxValue?.toInt()) }
                }
            }
        }
    }

    fun onFeedText(text: String) = _state.update { it.copy(feedText = text.filter(Char::isDigit).take(3), feedError = null) }

    /** The typed feed when it is a number within the setting's limits; else null with an error shown. */
    private fun validFeed(): Int? {
        val s = _state.value
        val mm = s.feedText.toIntOrNull()
        if (mm != null && mm >= s.feedMin && (s.feedMax == null || mm <= s.feedMax)) return mm
        _state.update { it.copy(feedError = "Enter ${s.feedMin}–${s.feedMax ?: "…"} mm") }
        return null
    }

    fun saveFeed() {
        if (_state.value.busyAddress != null) return
        val mm = validFeed() ?: return
        _state.update { it.copy(busyAddress = TYPE_SAVE, message = null) }
        viewModelScope.launch {
            val message = runCatching { settingsRepository.updatePrintSettings(mapOf(SettingKeys.PAPER_FEED_MM to mm.toString())) }
                .fold(onSuccess = { "Blank paper after print saved: $mm mm." }, onFailure = { e -> e.message ?: "Could not save." })
            _state.update { it.copy(busyAddress = null, message = message) }
        }
    }

    /** Reads Bluetooth state and paired devices again (on opening and when the screen comes back). */
    fun refresh() = _state.update { it.copy(bluetooth = bluetooth.state(), devices = bluetooth.pairedDevices()) }

    fun onManualAddress(text: String) = _state.update {
        it.copy(manualAddress = text.uppercase().filter { c -> c.isLetterOrDigit() || c == ':' }.take(17), manualError = null)
    }

    /** The typed address in capitals, or null (with an error shown) when it is not valid. */
    private fun validManualAddress(): String? {
        val mac = _state.value.manualAddress.trim()
        if (BluetoothPrinterPort.isValidAddress(mac)) return mac
        _state.update { it.copy(manualError = "Enter the address like 00:11:22:AA:BB:CC") }
        return null
    }

    fun testManual() { validManualAddress()?.let { testPrint(it, it) } }

    fun useManual() { validManualAddress()?.let { use(it, it) } }

    fun testPrint(address: String, name: String) {
        if (_state.value.busyAddress != null) return
        val feedMm = validFeed() ?: return
        _state.update { it.copy(busyAddress = address, message = null) }
        viewModelScope.launch {
            val paper = settingsRepository.currentSettings.value?.paperWidthMm ?: 58
            val page = PrinterTestPage.format(name, paper, DateTimes.displayText(DateTimes.toDbText(time.now())))
            val message = when (val r = bluetooth.sendTo(address, PosPrinter.encode(page, copies = 1, feedMm = feedMm))) {
                PrintResult.Printed -> "Test page sent to $name. If it printed well, press \"Use this printer\"."
                PrintResult.PreviewOnly -> "Nothing was printed."
                is PrintResult.Failed -> r.message
            }
            _state.update { it.copy(busyAddress = null, message = message) }
        }
    }

    /** Saves [address] as the app's printer and switches PRINTER_TYPE to Bluetooth. */
    fun use(address: String, name: String) {
        if (_state.value.busyAddress != null) return
        _state.update { it.copy(busyAddress = address, message = null) }
        viewModelScope.launch {
            val message = runCatching {
                settingsRepository.savePrinterChoice(PrinterType.BLUETOOTH, address)
            }.fold(
                onSuccess = {
                    if (_state.value.isAdmin) "$name is now the printer for everyone. Receipts and bills print on it."
                    else "$name is now your printer. Your receipts and bills print on it."
                },
                onFailure = { e -> e.message ?: "Could not save the printer." },
            )
            _state.update { it.copy(busyAddress = null, message = message) }
        }
    }

    /** Saves the printer type (Auto, Bluetooth, built-in, USB, None) and keeps the chosen Bluetooth printer. */
    fun setPrinterType(type: PrinterType) {
        val current = _state.value
        if (current.busyAddress != null || type == current.printerType) return
        _state.update { it.copy(busyAddress = TYPE_SAVE, message = null) }
        viewModelScope.launch {
            val message = runCatching { settingsRepository.savePrinterChoice(type, current.chosen) }
                .fold(onSuccess = { "Printer type saved." }, onFailure = { e -> e.message ?: "Could not save the printer type." })
            _state.update { it.copy(busyAddress = null, message = message) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object {
        /** busyAddress while the printer type is being saved (no device is busy). */
        const val TYPE_SAVE = "type"
    }
}
