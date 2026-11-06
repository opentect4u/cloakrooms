package com.synergic.cloakroom.ui.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.auth.UserSummary
import com.synergic.cloakroom.domain.repository.AuthRepository
import com.synergic.cloakroom.domain.repository.ShiftItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UsersViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    /** Result or error of the last action, shown in a snackbar. */
    val message: StateFlow<String?> = _message.asStateFlow()

    val users: StateFlow<List<UserSummary>?> = authRepository.observeUsers()
        .catch { e ->
            _message.value = e.message
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clearMessage() {
        _message.value = null
    }

    /** Runs [action]; returns true through [onResult] when it succeeded, so a dialog can close. */
    private fun run(successMessage: String, onResult: (Boolean) -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { _message.value = successMessage; onResult(true) }
                .onFailure { _message.value = it.message ?: "Something went wrong."; onResult(false) }
        }
    }

    /** Active shifts for the "Login shift" dropdown. */
    val shifts: StateFlow<List<ShiftItem>> = flow { emit(authRepository.shiftOptions()) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addUser(userId: String, userName: String, userType: String, password: String, shiftId: Long?, onResult: (Boolean) -> Unit) =
        run("User added. They must change the password at first login.", onResult) {
            authRepository.addUser(userId, userName, userType, password, shiftId)
        }

    fun updateUser(id: Long, userName: String, userType: String, shiftId: Long?, onResult: (Boolean) -> Unit) =
        run("User saved.", onResult) { authRepository.updateUser(id, userName, userType, shiftId) }

    fun setBlocked(user: UserSummary, blocked: Boolean) =
        run(if (blocked) "${user.userId} is blocked." else "${user.userId} is unblocked.") {
            authRepository.setBlocked(user.id, blocked)
        }

    fun resetPassword(id: Long, password: String, onResult: (Boolean) -> Unit) =
        run("Password reset. The user must change it at the next login.", onResult) {
            authRepository.resetPassword(id, password)
        }

    fun forceLogout(user: UserSummary) =
        run("${user.userId} is logged out.") { authRepository.forceLogout(user.id) }
}
