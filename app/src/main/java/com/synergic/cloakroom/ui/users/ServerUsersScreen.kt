package com.synergic.cloakroom.ui.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.repository.ManagedUser
import com.synergic.cloakroom.domain.repository.NewUserInput
import com.synergic.cloakroom.domain.repository.ShiftItem
import com.synergic.cloakroom.domain.repository.SuperAdminRepository
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.LoadingBox
import com.synergic.cloakroom.ui.superadmin.ResetPasswordDialog
import com.synergic.cloakroom.ui.superadmin.UserCard
import com.synergic.cloakroom.ui.superadmin.UserFormDialog
import com.synergic.cloakroom.ui.superadmin.screenMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerUsersUiState(
    val loading: Boolean = true,
    val users: List<ManagedUser> = emptyList(),
    val shifts: List<ShiftItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/** The station admin's users on the server: the same calls the super admin uses for a customer. */
@HiltViewModel
class ServerUsersViewModel @Inject constructor(
    private val repository: SuperAdminRepository,
    sessionProvider: SessionProvider,
) : ViewModel() {

    private val customerId: Long = checkNotNull(sessionProvider.current?.customerId)

    private val _state = MutableStateFlow(ServerUsersUiState())
    val state: StateFlow<ServerUsersUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loading = it.users.isEmpty(), error = null) }
        viewModelScope.launch {
            runCatching {
                val users = repository.users(customerId)
                val shifts = repository.shifts(customerId)
                _state.update { it.copy(loading = false, users = users, shifts = shifts) }
            }.onFailure { e -> _state.update { it.copy(loading = false, error = e.screenMessage()) } }
        }
    }

    /** Runs [action], then reloads. Returns true through [onDone] when it worked. */
    private fun act(success: String, onDone: (Boolean) -> Unit = {}, action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { action() }
            _state.update { it.copy(busy = false, message = result.fold({ success }, { e -> e.screenMessage() })) }
            onDone(result.isSuccess)
            if (result.isSuccess) load()
        }
    }

    fun addUser(input: NewUserInput, onDone: (Boolean) -> Unit) =
        act("User ${input.userId} created. They must change the password at first login.", onDone) { repository.addUser(customerId, input) }

    fun updateUser(user: ManagedUser, name: String, code: String, type: String, shiftId: Long?, onDone: (Boolean) -> Unit) =
        act("${user.userId} saved.", onDone) { repository.updateUser(customerId, user.id, name, code, type, shiftId) }

    fun setAllowed(user: ManagedUser, allowed: Boolean) =
        act(if (allowed) "${user.userId} can log in again." else "${user.userId} is blocked and logged out.") {
            repository.setUserAllowed(user.id, allowed)
        }

    fun resetPassword(user: ManagedUser, password: String, onDone: (Boolean) -> Unit) =
        act("New password set for ${user.userId}. They were logged out.", onDone) { repository.resetPassword(user.id, password) }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

/** Admin → Users when logged in to the server; same cards and dialogs as Super Admin → customer → Users. */
@Composable
fun ServerUsersScreen(currentUserId: Long, onBack: () -> Unit, viewModel: ServerUsersViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var addUser by remember { mutableStateOf(false) }
    var editUser by remember { mutableStateOf<ManagedUser?>(null) }
    var resetFor by remember { mutableStateOf<ManagedUser?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(
        topBar = {
            AppTopBar("Users", onBack = onBack, actions = {
                IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, "Refresh") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            s.loading -> LoadingBox(Modifier.fillMaxSize().padding(padding))
            s.error != null && s.users.isEmpty() -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text(s.error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text("Try again") }
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { addUser = true }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Add user")
                }
                if (s.users.isEmpty()) Text("No users yet.")
                s.users.forEach { u ->
                    // Your own account: the server refuses blocking yourself. Force Logout is the
                    // Super Admin's only, so the admin's cards have no such button.
                    val me = u.id == currentUserId
                    UserCard(
                        user = u,
                        busy = s.busy,
                        onEdit = { editUser = u },
                        onBlock = { viewModel.setAllowed(u, allowed = !u.allowed) },
                        onReset = { resetFor = u },
                        isMe = me,
                    )
                }
            }
        }
    }

    if (addUser || editUser != null) {
        val existing = editUser
        UserFormDialog(
            existing = existing,
            shifts = s.shifts,
            busy = s.busy,
            onDismiss = { addUser = false; editUser = null },
            onSave = { userId, name, code, type, password, shiftId ->
                val close: (Boolean) -> Unit = { ok -> if (ok) { addUser = false; editUser = null } }
                if (existing == null) viewModel.addUser(NewUserInput(userId, name, code, type, password, shiftId), close)
                else viewModel.updateUser(existing, name, code, type, shiftId, close)
            },
        )
    }
    resetFor?.let { user ->
        ResetPasswordDialog(
            user = user,
            busy = s.busy,
            onDismiss = { resetFor = null },
            onSave = { pwd -> viewModel.resetPassword(user, pwd) { ok -> if (ok) resetFor = null } },
        )
    }
}
