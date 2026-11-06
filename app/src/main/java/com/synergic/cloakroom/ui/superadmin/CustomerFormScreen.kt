package com.synergic.cloakroom.ui.superadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.domain.repository.CustomerInput
import com.synergic.cloakroom.domain.repository.SuperAdminRepository
import com.synergic.cloakroom.domain.validation.Gstin
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.BigButton
import com.synergic.cloakroom.ui.components.LoadingBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Form fields as typed; keys of [errors] are the API field names (customer_name, pin_code, …). */
data class CustomerFormState(
    val loading: Boolean = false,
    val name: String = "",
    val address: String = "",
    val state: String = "",
    val pinCode: String = "",
    val mobileNo: String = "",
    val email: String = "",
    val gstNo: String = "",
    val upiId: String = "",
    val userLimit: String = "1",
    val active: Boolean = true,
    val remarks: String = "",
    val renewalDate: String? = null,
    val errors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val saving: Boolean = false,
    /** Set after a successful create: the new customer's id. */
    val createdId: Long? = null,
    /** Set after a successful edit. */
    val saved: Boolean = false,
)

@HiltViewModel
class CustomerFormViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SuperAdminRepository,
) : ViewModel() {

    /** 0 = new customer. */
    val customerId: Long = savedState.get<Long>("id") ?: 0L
    val isNew get() = customerId == 0L

    private val _state = MutableStateFlow(CustomerFormState(loading = !isNew))
    val state: StateFlow<CustomerFormState> = _state.asStateFlow()

    init {
        if (!isNew) {
            viewModelScope.launch {
                runCatching { repository.summary(customerId).customer }
                    .onSuccess { c ->
                        _state.update {
                            it.copy(
                                loading = false, name = c.name, address = c.address, state = c.state.orEmpty(),
                                pinCode = c.pinCode, mobileNo = c.mobileNo, email = c.email.orEmpty(), gstNo = c.gstNo.orEmpty(),
                                upiId = c.upiId.orEmpty(), userLimit = c.userLimit.toString(), active = c.active,
                                remarks = c.remarks.orEmpty(), renewalDate = c.renewalDate,
                            )
                        }
                    }
                    .onFailure { e -> _state.update { it.copy(loading = false, message = e.screenMessage()) } }
            }
        }
    }

    fun edit(change: (CustomerFormState) -> CustomerFormState, field: String) =
        _state.update { change(it).copy(errors = it.errors - field, message = null) }

    fun save() {
        val s = _state.value
        val errors = buildMap {
            if (s.name.isBlank()) put("customer_name", "Enter the name")
            if (s.address.isBlank()) put("customer_addr", "Enter the address")
            if (!Regex("^\\d{6}$").matches(s.pinCode.trim())) put("pin_code", "6 digits")
            if (s.mobileNo.isBlank()) put("mobile_no", "Enter the mobile number")
            val gst = s.gstNo.trim().uppercase()
            if (gst.isNotEmpty() && !Gstin.isValid(gst)) put("gst_no", Gstin.FORMAT_MESSAGE)
            val limit = s.userLimit.trim().toIntOrNull()
            if (limit == null || limit !in 1..1000) put("user_limit", "1 to 1000")
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, message = "Please correct the fields marked in red.") }
            return
        }
        val input = CustomerInput(
            name = s.name, address = s.address, state = s.state, pinCode = s.pinCode, mobileNo = s.mobileNo,
            email = s.email, gstNo = s.gstNo, upiId = s.upiId, userLimit = s.userLimit.trim().toInt(),
            active = s.active, remarks = s.remarks,
        )
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            runCatching { if (isNew) repository.createCustomer(input) else repository.updateCustomer(customerId, input) }
                .onSuccess { c -> _state.update { it.copy(saving = false, createdId = if (isNew) c.id else null, saved = !isNew) } }
                .onFailure { e ->
                    val fieldErrors = (e as? ServerException)?.fieldErrors.orEmpty()
                    _state.update { it.copy(saving = false, errors = fieldErrors, message = e.screenMessage()) }
                }
        }
    }
}

/** Add or edit a customer. The renewal date is set through registrations, not here. */
@Composable
fun CustomerFormScreen(
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
    viewModel: CustomerFormViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.createdId, s.saved) {
        s.createdId?.let(onCreated)
        if (s.saved) onBack()
    }

    Scaffold(topBar = { AppTopBar(if (viewModel.isNew) "Add customer" else "Edit customer", onBack = onBack) }) { padding ->
        if (s.loading) {
            LoadingBox(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            @Composable
            fun Field(
                label: String, value: String, key: String,
                keyboard: KeyboardOptions = KeyboardOptions.Default, singleLine: Boolean = true,
            ) = OutlinedTextField(
                value = value,
                onValueChange = { v -> viewModel.edit({ onChangeState(it, key, v) }, key) },
                label = { Text(label) },
                isError = key in s.errors,
                supportingText = s.errors[key]?.let { { Text(it) } },
                singleLine = singleLine,
                keyboardOptions = keyboard,
                modifier = Modifier.fillMaxWidth(),
            )

            Field("Name *", s.name, "customer_name")
            Field("Address *", s.address, "customer_addr", singleLine = false)
            Field("State", s.state, "state")
            Field("PIN code *", s.pinCode, "pin_code", KeyboardOptions(keyboardType = KeyboardType.Number))
            Field("Mobile *", s.mobileNo, "mobile_no", KeyboardOptions(keyboardType = KeyboardType.Phone))
            Field("Email", s.email, "email", KeyboardOptions(keyboardType = KeyboardType.Email))
            Field("GSTIN", s.gstNo, "gst_no", KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            Field("UPI ID", s.upiId, "upi_id")
            Field("User limit (licensed users) *", s.userLimit, "user_limit", KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Active", style = MaterialTheme.typography.titleMedium)
                    Text("Inactive customers cannot log in.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.active, onCheckedChange = { v -> viewModel.edit({ it.copy(active = v) }, "active_flag") })
            }
            Field("Remarks", s.remarks, "remarks", singleLine = false)
            Text(
                "Renewal date: ${s.renewalDate ?: "not set"} — it is the end date of the latest registration (customer details → Registrations).",
                style = MaterialTheme.typography.bodySmall,
            )
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BigButton(text = if (viewModel.isNew) "Create customer" else "Save changes", onClick = viewModel::save, loading = s.saving)
        }
    }
}

private fun onChangeState(s: CustomerFormState, key: String, v: String): CustomerFormState = when (key) {
    "customer_name" -> s.copy(name = v)
    "customer_addr" -> s.copy(address = v)
    "state" -> s.copy(state = v)
    "pin_code" -> s.copy(pinCode = v.filter { it.isDigit() }.take(6))
    "mobile_no" -> s.copy(mobileNo = v.take(20))
    "email" -> s.copy(email = v.trim())
    "gst_no" -> s.copy(gstNo = v.uppercase().filterNot { it.isWhitespace() }.take(15))
    "upi_id" -> s.copy(upiId = v.trim())
    "user_limit" -> s.copy(userLimit = v.filter { it.isDigit() }.take(4))
    "remarks" -> s.copy(remarks = v.take(255))
    else -> s
}
