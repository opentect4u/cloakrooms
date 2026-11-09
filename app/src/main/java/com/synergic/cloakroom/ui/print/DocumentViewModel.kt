package com.synergic.cloakroom.ui.print

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.data.print.PdfExporter
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.print.PrintResult
import com.synergic.cloakroom.domain.print.Printer
import com.synergic.cloakroom.domain.repository.BillRepository
import com.synergic.cloakroom.domain.repository.LuggageRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which document of a transaction to show. */
enum class DocumentKind(val route: String) { RECEIPT("receipt"), BILL("bill") }

data class DocumentUiState(
    val loading: Boolean = true,
    val document: PrintDocument? = null,
    val printing: Boolean = false,
    val message: String? = null,
    /** A share sheet to open (Share PDF); the screen clears it after starting it. */
    val shareIntent: Intent? = null,
)

/**
 * Preview of a check-in receipt or a bill. Route arguments: `kind`, `transId`, and `print` = true
 * when the document must be printed once on opening (after Save at check-in when
 * CHECKIN_PRINT_FLAG = Y, or after "Bill Print" at check-out).
 */
@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val luggageRepository: LuggageRepository,
    private val billRepository: BillRepository,
    private val settingsRepository: SettingsRepository,
    private val printer: Printer,
    private val pdfExporter: PdfExporter,
    sessionProvider: SessionProvider,
) : ViewModel() {

    private val transId: String = checkNotNull(savedState["transId"])
    val kind: DocumentKind = DocumentKind.entries.first { it.route == savedState.get<String>("kind") }

    /** False for admins (they do no counter work): no "New check-in" / "Next check-out" button. */
    val canWorkCounter: StateFlow<Boolean> = settingsRepository.currentSettings
        .map { s -> s?.canWorkCounter(sessionProvider.current?.userType.orEmpty()) == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _state = MutableStateFlow(DocumentUiState())
    val state: StateFlow<DocumentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val document = runCatching {
                when (kind) {
                    DocumentKind.RECEIPT -> luggageRepository.checkinReceipt(transId)
                    DocumentKind.BILL -> billRepository.billDocument(transId)
                }
            }.getOrNull()
            _state.update { it.copy(loading = false, document = document) }
            if (document != null && savedState.get<Boolean>("print") == true) {
                savedState["print"] = false // only once, also after a screen rotation
                val settings = settingsRepository.currentSettings.value ?: settingsRepository.getSettings(null)
                val allowed = kind == DocumentKind.BILL || settings.checkinPrintFlag
                if (allowed) print(manual = false)
            }
        }
    }

    fun print() = print(manual = true)

    private fun print(manual: Boolean) {
        val document = _state.value.document ?: return
        if (_state.value.printing) return
        _state.update { it.copy(printing = true, message = null) }
        viewModelScope.launch {
            val settings = settingsRepository.currentSettings.value
            val copies = when (kind) {
                DocumentKind.RECEIPT -> settings?.checkinPrintCopies
                DocumentKind.BILL -> settings?.checkoutPrintCopies
            } ?: 1
            val message = when (val r = printer.print(document, copies)) {
                PrintResult.Printed -> if (copies == 1) "Printed." else "$copies copies printed."
                // Without a printer the preview on screen is the result; say so only when asked.
                PrintResult.PreviewOnly -> if (manual) "No printer found. Open Dashboard → Printer setup to choose the Bluetooth printer, or use Share PDF." else null
                is PrintResult.Failed -> r.message
            }
            _state.update { it.copy(printing = false, message = message) }
        }
    }

    fun sharePdf() {
        val document = _state.value.document ?: return
        viewModelScope.launch {
            runCatching { pdfExporter.export(document) }
                .onSuccess { file -> _state.update { it.copy(shareIntent = pdfExporter.shareIntent(file, document.title)) } }
                .onFailure { e -> _state.update { it.copy(message = "Could not make the PDF: ${e.message}") } }
        }
    }

    fun onShareStarted() = _state.update { it.copy(shareIntent = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
