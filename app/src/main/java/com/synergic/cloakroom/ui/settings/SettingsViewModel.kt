package com.synergic.cloakroom.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.repository.CustomerSettingsRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.settings.SettingDefinition
import com.synergic.cloakroom.domain.settings.SettingsValidationException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val loading: Boolean = true,
    val tabs: List<SettingsTab> = emptyList(),
    val changes: List<ChangeLine> = emptyList(),
    val saving: Boolean = false,
    /** Normal user: only the Print tab, saved for their own login. */
    val ownOnly: Boolean = false,
    /** Settings on the server: super admin on a customer (route argument `id`) or the station admin. */
    val customerMode: Boolean = false,
) {
    val hasErrors: Boolean get() = tabs.any { it.errorCount > 0 }
    val canSave: Boolean get() = changes.isNotEmpty() && !hasErrors && !saving
}

/**
 * Normal users see only the Print tab; their changes apply to their own login. The station admin
 * (online only) and a super admin opened on a customer (route argument `id`) see that customer's
 * settings: §8 keys and the rate on the server, the others kept on this phone.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val customerSettings: CustomerSettingsRepository,
    savedState: SavedStateHandle,
    sessionProvider: SessionProvider,
) : ViewModel() {

    private val isAdmin = sessionProvider.current?.isAdmin == true

    /** The customer whose settings are on the server: the one a super admin opened, or the admin's own. */
    private val customerId: Long? = sessionProvider.current?.let { s ->
        when {
            s.isSuperAdmin -> savedState.get<Long>("id")
            s.isAdmin -> s.customerId
            else -> null
        }
    }

    private val definitions = MutableStateFlow<List<SettingDefinition>?>(null)
    private val drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val serverErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val saving = MutableStateFlow(false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<SettingsUiState> =
        combine(definitions, drafts, serverErrors, saving) { defs, d, errors, isSaving ->
            if (defs == null) {
                SettingsUiState()
            } else {
                SettingsUiState(
                    loading = false,
                    tabs = SettingsForm.tabs(defs, d, errors),
                    changes = SettingsForm.changeLines(defs, d),
                    saving = isSaving,
                    ownOnly = !isAdmin && customerId == null,
                    customerMode = customerId != null,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        if (customerId != null) {
            loadCustomer(customerId)
        } else {
            viewModelScope.launch {
                val source = if (isAdmin) repository.observeDefinitions() else repository.observePrintDefinitions()
                source.collect { definitions.value = it }
            }
        }
    }

    private fun loadCustomer(id: Long) {
        viewModelScope.launch {
            runCatching { customerSettings.definitions(id) }
                .onSuccess { definitions.value = it }
                .onFailure { e ->
                    definitions.value = definitions.value ?: emptyList()
                    _message.value = e.message ?: "Could not load the settings."
                }
        }
    }

    fun onValueChange(name: String, value: String) {
        val defs = definitions.value ?: return
        drafts.update { SettingsForm.edit(defs, it, name, value) }
        serverErrors.update { it - name }
    }

    fun discardChanges() {
        drafts.value = emptyMap()
        serverErrors.value = emptyMap()
    }

    fun save() {
        if (!state.value.canSave) return
        saving.value = true
        viewModelScope.launch {
            try {
                val count = when {
                    customerId != null -> customerSettings.save(customerId, drafts.value)
                    isAdmin -> repository.updateSettings(drafts.value)
                    else -> repository.updatePrintSettings(drafts.value)
                }
                drafts.value = emptyMap()
                serverErrors.value = emptyMap()
                _message.value = if (count == 1) "1 setting saved." else "$count settings saved."
                if (customerId != null) loadCustomer(customerId)
            } catch (e: SettingsValidationException) {
                serverErrors.value = e.errors
                _message.value = "Some values are not valid. Please check the fields marked in red."
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not save the settings."
            } finally {
                saving.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
