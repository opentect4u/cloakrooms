package com.synergic.cloakroom.ui.checkout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.billing.BillCalculationException
import com.synergic.cloakroom.domain.billing.BillCalculator
import com.synergic.cloakroom.domain.billing.BillResult
import com.synergic.cloakroom.domain.checkout.CheckoutField
import com.synergic.cloakroom.domain.repository.BillRepository
import com.synergic.cloakroom.domain.repository.CheckoutBag
import com.synergic.cloakroom.domain.repository.CheckoutRequest
import com.synergic.cloakroom.domain.repository.CheckoutResult
import com.synergic.cloakroom.domain.repository.QrLookup
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.billing.Tariff
import com.synergic.cloakroom.domain.billing.withTariff
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import com.synergic.cloakroom.domain.upi.UpiLink
import com.synergic.cloakroom.domain.upi.UpiPayee
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject

/** The operator pressed Bill Print or Out Without Bill Print; [net] is the amount to confirm. */
data class ConfirmCheckout(val printBill: Boolean, val net: BigDecimal, val payMode: PayMode)

data class CheckoutUiState(
    val loading: Boolean = true,
    val bag: CheckoutBag? = null,
    val settings: AppSettings? = null,
    /** The server's rate / GST for this bag (§7). */
    val tariff: Tariff? = null,
    val payee: UpiPayee? = null,
    val now: Instant = Instant.EPOCH,
    val penalty: String = "",
    val payMode: PayMode = PayMode.CASH,
    /** Live bill at [now]; null while the penalty is not valid. */
    val bill: BillResult? = null,
    val upiLink: String? = null,
    val errors: Map<CheckoutField, String> = emptyMap(),
    val saving: Boolean = false,
    val message: String? = null,
    val confirm: ConfirmCheckout? = null,
    /** Set after the save: open the bill (print = Bill Print pressed). */
    val savedTransId: String? = null,
    val savedPrint: Boolean = false,
)

/** Route arguments: `transId` (bag on this device) or `qr` (raw QR text of a bag from another counter). */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val billRepository: BillRepository,
    settingsRepository: SettingsRepository,
    private val time: TimeSource,
) : ViewModel() {

    private val _state = MutableStateFlow(CheckoutUiState(now = time.now()))
    val state: StateFlow<CheckoutUiState> = _state.asStateFlow()

    init {
        val transId: String? = savedState["transId"]
        val qr: String? = savedState["qr"]
        viewModelScope.launch {
            val bag = runCatching {
                when {
                    !qr.isNullOrEmpty() -> (billRepository.lookupQr(qr) as? QrLookup.Found)?.bag
                    !transId.isNullOrEmpty() -> billRepository.find(transId)
                    else -> null
                }
            }.getOrNull()
            val payee = runCatching { billRepository.upiPayee() }.getOrNull()
            val tariff = bag?.let { b -> runCatching { billRepository.tariff(b.receiptDt) }.getOrNull() }
            _state.update { it.copy(loading = false, bag = bag, payee = payee, tariff = tariff) }
            recompute()
        }
        viewModelScope.launch {
            settingsRepository.currentSettings.filterNotNull().collect { s ->
                _state.update {
                    val first = it.settings == null
                    val mode = if (first) s.defaultPayMode else it.payMode
                    it.copy(settings = s, payMode = if (mode == PayMode.UPI && !s.upiEnabled) PayMode.CASH else mode)
                }
                recompute()
            }
        }
        // The bill grows with time: refresh it while the screen is open.
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                _state.update { it.copy(now = time.now()) }
                recompute()
            }
        }
    }

    fun onPenalty(v: String) {
        _state.update { it.copy(penalty = v.filter { c -> c.isDigit() || c == '.' }.take(10), errors = it.errors - CheckoutField.PENALTY - CheckoutField.AMOUNT) }
        recompute()
    }

    fun onPayMode(mode: PayMode) {
        _state.update { it.copy(payMode = mode, errors = it.errors - CheckoutField.PAY_MODE) }
        recompute()
    }

    /** Bill Print (true) or Out Without Bill Print (false): recalculates now and asks to confirm the amount. */
    fun requestCheckout(printBill: Boolean) {
        _state.update { it.copy(now = time.now()) }
        recompute()
        val s = _state.value
        val bill = s.bill ?: return
        _state.update { it.copy(confirm = ConfirmCheckout(printBill, bill.netAmt, s.payMode), message = null) }
    }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    fun confirmCheckout() {
        val s = _state.value
        val confirm = s.confirm ?: return
        val bag = s.bag ?: return
        if (s.saving) return
        _state.update { it.copy(confirm = null, saving = true) }
        viewModelScope.launch {
            val result = runCatching {
                billRepository.checkOut(CheckoutRequest(bag, s.penalty, s.payMode, confirm.printBill, confirm.net))
            }
            _state.update {
                result.fold(
                    onSuccess = { r ->
                        when (r) {
                            is CheckoutResult.Saved -> it.copy(saving = false, savedTransId = r.transId, savedPrint = confirm.printBill)
                            is CheckoutResult.Invalid -> it.copy(
                                saving = false,
                                errors = r.errors,
                                message = r.errors[CheckoutField.AMOUNT]
                                    ?: r.errors[CheckoutField.BAG] ?: "Please correct the fields marked in red.",
                            )
                        }
                    },
                    onFailure = { e -> it.copy(saving = false, message = e.message ?: "Could not save the bill.") },
                )
            }
            recompute()
        }
    }

    fun onBillOpened() = _state.update { it.copy(savedTransId = null) }

    private fun recompute() = _state.update { s ->
        val bag = s.bag
        val settings = s.settings
        if (bag == null || settings == null || bag.isOut) return@update s.copy(bill = null, upiLink = null)
        val penalty = s.penalty.ifBlank { "0" }.toBigDecimalOrNull()
        val (bill, error) = when {
            penalty == null -> null to "Enter the penalty as a number."
            else -> try {
                BillCalculator.calculate(DateTimes.fromDbText(bag.dateTimeIn), s.now, bag.bags, penalty, settings.withTariff(s.tariff)) to null
            } catch (e: BillCalculationException) {
                null to e.message
            }
        }
        val errors = if (error != null) s.errors + (CheckoutField.PENALTY to error) else s.errors - CheckoutField.PENALTY
        val link = if (bill != null && s.payMode == PayMode.UPI && s.payee != null) {
            UpiLink.build(s.payee, bill.netAmt, "Cloak room ${bag.receiptNo}")
        } else {
            null
        }
        s.copy(bill = bill, upiLink = link, errors = errors)
    }

    private companion object {
        const val TICK_MS = 15_000L
    }
}
