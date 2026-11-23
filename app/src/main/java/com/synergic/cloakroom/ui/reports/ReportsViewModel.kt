package com.synergic.cloakroom.ui.reports

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.data.print.PdfExporter
import com.synergic.cloakroom.domain.print.PrintResult
import com.synergic.cloakroom.domain.print.Printer
import com.synergic.cloakroom.domain.report.ReportAccess
import com.synergic.cloakroom.domain.report.ReportData
import com.synergic.cloakroom.domain.report.ReportFilter
import com.synergic.cloakroom.domain.report.ReportShift
import com.synergic.cloakroom.domain.report.ReportUser
import com.synergic.cloakroom.domain.repository.ReportRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.SavedStateHandle
import com.synergic.cloakroom.data.repository.RemoteReportRepositoryFactory
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.domain.report.SyncExceptionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class ReportsUiState(
    val access: ReportAccess? = null,
    /** True while the report password has not been entered. */
    val locked: Boolean = false,
    val passwordError: String? = null,
    val from: LocalDate,
    val to: LocalDate,
    /** Local times of the period ends; the defaults cover whole days. */
    val fromTime: LocalTime = ReportFilter.DAY_START,
    val toTime: LocalTime = ReportFilter.DAY_END,
    /** Admin filter; null = all users. Ignored for normal users (always their own). */
    val userId: Long? = null,
    val users: List<ReportUser> = emptyList(),
    /** Shift filter for everyone; null = all shifts. */
    val shiftId: Long? = null,
    val shifts: List<ReportShift> = emptyList(),
    val data: ReportData? = null,
    /** The server's sync exceptions still open (admins, super admin); null = not loaded. */
    val exceptions: List<SyncExceptionRow>? = null,
    /** Exception being marked resolved. */
    val resolving: Long? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val shareIntent: Intent? = null,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    defaultRepository: ReportRepository,
    remoteFactory: RemoteReportRepositoryFactory,
    savedState: SavedStateHandle,
    sessionProvider: SessionProvider,
    private val settingsRepository: SettingsRepository,
    private val printer: Printer,
    private val pdfExporter: PdfExporter,
    time: TimeSource,
) : ViewModel() {

    /** Super admin → customer → Reports (route argument `id`): that customer's reports from the server. */
    private val reportRepository: ReportRepository = savedState.get<Long>("id")
        ?.takeIf { sessionProvider.current?.isSuperAdmin == true }
        ?.let { remoteFactory.create(it) } ?: defaultRepository

    private val today = DateTimes.localDate(time.now())
    private val _state = MutableStateFlow(ReportsUiState(from = today, to = today))
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val access = runCatching { reportRepository.reportAccess() }.getOrDefault(ReportAccess.Denied)
            val users = if ((access as? ReportAccess.Allowed)?.canChooseUser == true) {
                runCatching { reportRepository.reportUsers() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val shifts = if (access is ReportAccess.Allowed) {
                runCatching { reportRepository.reportShifts() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val locked = (access as? ReportAccess.Allowed)?.needsPassword == true
            _state.update { it.copy(access = access, users = users, shifts = shifts, locked = locked) }
            if (access is ReportAccess.Allowed && !locked) {
                load()
                if (access.showExceptions) loadExceptions()
            }
        }
    }

    fun unlock(password: String) {
        viewModelScope.launch {
            if (reportRepository.verifyReportPassword(password)) {
                _state.update { it.copy(locked = false, passwordError = null) }
                load()
                if ((_state.value.access as? ReportAccess.Allowed)?.showExceptions == true) loadExceptions()
            } else {
                _state.update { it.copy(passwordError = "Wrong report password.") }
            }
        }
    }

    fun setFrom(date: LocalDate) = setFrom(date, _state.value.fromTime)

    fun setTo(date: LocalDate) = setTo(date, _state.value.toTime)

    fun setFromTime(time: LocalTime) = setFrom(_state.value.from, time)

    fun setToTime(time: LocalTime) = setTo(_state.value.to, time)

    /** A start after the end moves the end up to it. */
    private fun setFrom(date: LocalDate, time: LocalTime) {
        _state.update {
            val later = date.atTime(time) > it.to.atTime(it.toTime)
            it.copy(from = date, fromTime = time, to = if (later) date else it.to, toTime = if (later) time else it.toTime)
        }
        load()
    }

    /** An end before the start moves the start back to it. */
    private fun setTo(date: LocalDate, time: LocalTime) {
        _state.update {
            val earlier = date.atTime(time) < it.from.atTime(it.fromTime)
            it.copy(to = date, toTime = time, from = if (earlier) date else it.from, fromTime = if (earlier) time else it.fromTime)
        }
        load()
    }

    fun setUser(userId: Long?) {
        _state.update { it.copy(userId = userId) }
        load()
    }

    fun setShift(shiftId: Long?) {
        _state.update { it.copy(shiftId = shiftId) }
        load()
    }

    private fun filter() = _state.value.let { ReportFilter(it.from, it.to, it.userId, it.shiftId, it.fromTime, it.toTime) }

    /** The report being loaded; a new filter cancels it, so an older, slower answer never replaces a newer one. */
    private var loadJob: Job? = null

    fun load() {
        if (_state.value.locked) return
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        val f = filter()
        loadJob = viewModelScope.launch {
            try {
                val data = reportRepository.report(f)
                _state.update { it.copy(loading = false, data = data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerException) {
                // The customer's report password is on (the super admin cannot know it beforehand).
                if (e.code == REPORT_PASSWORD_REQUIRED) _state.update { it.copy(loading = false, locked = true) }
                else _state.update { it.copy(loading = false, message = e.message ?: "Could not load the report.") }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = e.message ?: "Could not load the report.") }
            }
        }
    }

    fun loadExceptions() {
        viewModelScope.launch {
            runCatching { reportRepository.exceptions() }
                .onSuccess { rows -> _state.update { it.copy(exceptions = rows) } }
                .onFailure { e -> _state.update { it.copy(message = e.message ?: "Could not load the exceptions.") } }
        }
    }

    /** PATCH …/resolve on the server, then the list again (resolved ones drop out). */
    fun resolve(row: SyncExceptionRow) {
        if (_state.value.resolving != null) return
        _state.update { it.copy(resolving = row.id) }
        viewModelScope.launch {
            runCatching { reportRepository.resolveException(row.id) }
                .onSuccess { _state.update { it.copy(resolving = null, exceptions = it.exceptions?.filter { e -> e.id != row.id }, message = "Marked resolved.") } }
                .onFailure { e -> _state.update { it.copy(resolving = null, message = e.message ?: "Could not mark it resolved.") } }
        }
    }

    private companion object {
        const val REPORT_PASSWORD_REQUIRED = "REPORT_PASSWORD_REQUIRED"
    }

    fun print() {
        viewModelScope.launch {
            runCatching {
                val doc = reportRepository.reportDocument(filter())
                printer.print(doc, 1) // reports: always one copy (DECISIONS #199)
            }.onSuccess { r ->
                val msg = when (r) {
                    PrintResult.Printed -> "Report printed."
                    PrintResult.PreviewOnly -> "No printer found. Open Dashboard → Printer setup to choose the Bluetooth printer, or use Share PDF."
                    is PrintResult.Failed -> r.message
                }
                _state.update { it.copy(message = msg) }
            }.onFailure { e -> _state.update { it.copy(message = e.message) } }
        }
    }

    fun sharePdf() {
        viewModelScope.launch {
            runCatching {
                val doc = reportRepository.reportDocument(filter())
                pdfExporter.shareIntent(pdfExporter.export(doc), doc.title)
            }.onSuccess { intent -> _state.update { it.copy(shareIntent = intent) } }
                .onFailure { e -> _state.update { it.copy(message = "Could not make the PDF: ${e.message}") } }
        }
    }

    fun onShareStarted() = _state.update { it.copy(shareIntent = null) }
    fun clearMessage() = _state.update { it.copy(message = null) }
}
