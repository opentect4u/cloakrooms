package com.synergic.cloakroom.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.checkin.CheckinField
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.repository.CheckinResult
import com.synergic.cloakroom.domain.repository.IdProof
import com.synergic.cloakroom.domain.repository.LuggageRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CheckinForm(
    val pnr: String = "",
    val name: String = "",
    val mobile: String = "",
    val bags: String = "1",
    /** Extra bags, not billed. */
    val extraBags: String = "0",
    val idProofId: Long? = null,
    val idNumber: String = "",
    val errors: Map<CheckinField, String> = emptyMap(),
    val saving: Boolean = false,
    val message: String? = null,
    /** Set after a successful save; the screen then opens the receipt. */
    val savedTransId: String? = null,
)

@HiltViewModel
class CheckinViewModel @Inject constructor(
    private val luggageRepository: LuggageRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(CheckinForm())
    val form: StateFlow<CheckinForm> = _form.asStateFlow()

    val settings: StateFlow<AppSettings?> = settingsRepository.currentSettings

    val idProofs: StateFlow<List<IdProof>> = luggageRepository.observeIdProofs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun edit(field: CheckinField?, change: (CheckinForm) -> CheckinForm) =
        _form.update { change(it).copy(errors = if (field == null) it.errors else it.errors - field, message = null) }

    fun onPnr(v: String) = edit(CheckinField.PNR) { it.copy(pnr = v) }
    fun onName(v: String) = edit(CheckinField.NAME) { it.copy(name = v) }
    fun onMobile(v: String) = edit(CheckinField.MOBILE) { it.copy(mobile = v.filter { c -> c.isDigit() }.take(10)) }
    fun onBags(v: String) = edit(CheckinField.BAGS) { it.copy(bags = v.filter { c -> c.isDigit() }.take(3)) }
    fun onIdProof(id: Long) = edit(CheckinField.ID_PROOF) { it.copy(idProofId = id) }
    fun onIdNumber(v: String) = edit(CheckinField.ID_NUMBER) { it.copy(idNumber = v) }

    /** The +/- buttons; the limit comes from MAX_BAGS_PER_RECEIPT. */
    fun stepBags(delta: Int) {
        val max = settings.value?.maxBagsPerReceipt ?: return
        val now = _form.value.bags.toIntOrNull() ?: 0
        onBags((now + delta).coerceIn(1, max).toString())
    }

    fun onExtraBags(v: String) = edit(CheckinField.EXTRA_BAGS) { it.copy(extraBags = v.filter { c -> c.isDigit() }.take(3)) }

    /** The +/- buttons for extra bags: 0 up to MAX_BAGS_PER_RECEIPT. */
    fun stepExtraBags(delta: Int) {
        val max = settings.value?.maxBagsPerReceipt ?: return
        val now = _form.value.extraBags.toIntOrNull() ?: 0
        onExtraBags((now + delta).coerceIn(0, max).toString())
    }

    fun save() {
        val f = _form.value
        if (f.saving) return
        _form.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val result = runCatching {
                luggageRepository.checkIn(CheckinInput(f.pnr, f.name, f.mobile, f.bags, f.idProofId, f.idNumber, extraBags = f.extraBags))
            }
            _form.update {
                result.fold(
                    onSuccess = { r ->
                        when (r) {
                            is CheckinResult.Saved -> CheckinForm(savedTransId = r.transId)
                            is CheckinResult.Invalid -> it.copy(saving = false, errors = r.errors, message = "Please correct the fields marked in red.")
                        }
                    },
                    onFailure = { e -> it.copy(saving = false, message = e.message ?: "Could not save the check-in.") },
                )
            }
        }
    }

    /** Called once the screen has opened the receipt, so going back shows an empty form. */
    fun onReceiptOpened() = _form.update { it.copy(savedTransId = null) }
}
