package com.synergic.cloakroom.ui.password

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChangePasswordUiState(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChangePasswordUiState())
    val state: StateFlow<ChangePasswordUiState> = _state.asStateFlow()

    fun onCurrentChange(v: String) = _state.update { it.copy(current = v, error = null) }
    fun onNewChange(v: String) = _state.update { it.copy(new = v, error = null) }
    fun onConfirmChange(v: String) = _state.update { it.copy(confirm = v, error = null) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        if (s.new != s.confirm) {
            _state.update { it.copy(error = "The new passwords do not match.") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching { authRepository.changePassword(s.current, s.new) }
                .onSuccess { _state.value = ChangePasswordUiState(done = true) }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message ?: "Could not change the password.") } }
        }
    }

    fun logout() {
        viewModelScope.launch { runCatching { authRepository.logout() } }
    }
}
