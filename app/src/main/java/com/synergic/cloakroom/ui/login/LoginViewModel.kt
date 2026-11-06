package com.synergic.cloakroom.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.auth.LoginResult
import com.synergic.cloakroom.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val userId: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** 426 UPDATE_REQUIRED: this version may not log in; show the download. */
    val update: LoginResult.UpdateRequired? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUserIdChange(value: String) = _state.update { it.copy(userId = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun dismissUpdate() = _state.update { it.copy(update = null) }

    /** On success the session changes and the app leaves this screen by itself. */
    fun login() {
        val current = _state.value
        if (current.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { authRepository.login(current.userId, current.password) }
                .getOrElse { LoginResult.Failure(it.message ?: "Login failed.") }
            _state.update {
                when (result) {
                    is LoginResult.Success -> LoginUiState()
                    is LoginResult.Failure -> it.copy(loading = false, password = "", error = result.message)
                    is LoginResult.UpdateRequired -> it.copy(loading = false, password = "", update = result)
                }
            }
        }
    }
}
