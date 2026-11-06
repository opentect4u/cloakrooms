package com.synergic.cloakroom.ui.superadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.auth.UserTypes
import com.synergic.cloakroom.domain.repository.CustomerSummary
import com.synergic.cloakroom.domain.repository.ManagedUser
import com.synergic.cloakroom.domain.repository.NewUserInput
import com.synergic.cloakroom.domain.repository.Registration
import com.synergic.cloakroom.domain.repository.RegistrationInput
import com.synergic.cloakroom.domain.repository.ShiftItem
import com.synergic.cloakroom.domain.repository.SuperAdminRepository
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.ErrorBanner
import com.synergic.cloakroom.ui.components.LoadingBox
import com.synergic.cloakroom.ui.components.PasswordField
import com.synergic.cloakroom.ui.users.ShiftDropdown
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class CustomerDetailUiState(
    val loading: Boolean = true,
    val summary: CustomerSummary? = null,
    val registrations: List<Registration> = emptyList(),
    val users: List<ManagedUser> = emptyList(),
    /** The customer's shifts, for the Login shift choice. */
    val shifts: List<ShiftItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SuperAdminRepository,
) : ViewModel() {

    val customerId: Long = checkNotNull(savedState.get<Long>("id"))

    private val _state = MutableStateFlow(CustomerDetailUiState())
    val state: StateFlow<CustomerDetailUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loading = it.summary == null, error = null) }
        viewModelScope.launch {
            runCatching {
                val summary = repository.summary(customerId)
                val regs = repository.registrations(customerId)
                val users = repository.users(customerId)
                val shifts = repository.shifts(customerId)
                _state.update { it.copy(loading = false, summary = summary, registrations = regs, users = users, shifts = shifts) }
            }.onFailure { e -> _state.update { it.copy(loading = false, error = e.screenMessage()) } }
        }
    }

    /** Runs [action], then reloads everything. Returns true through [onDone] when it worked. */
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

    fun addRegistration(input: RegistrationInput, onDone: (Boolean) -> Unit) =
        act("Registration ${input.regNo} added. Renewal date updated.", onDone) { repository.addRegistration(customerId, input) }

    fun addUser(input: NewUserInput, onDone: (Boolean) -> Unit) =
        act("User ${input.userId} created.", onDone) { repository.addUser(customerId, input) }

    fun updateUser(user: ManagedUser, name: String, code: String, type: String, shiftId: Long?, onDone: (Boolean) -> Unit) =
        act("${user.userId} saved.", onDone) { repository.updateUser(customerId, user.id, name, code, type, shiftId) }

    fun setAllowed(user: ManagedUser, allowed: Boolean) =
        act(if (allowed) "${user.userId} can log in again." else "${user.userId} is blocked and logged out.") {
            repository.setUserAllowed(user.id, allowed)
        }

    fun resetPassword(user: ManagedUser, password: String, onDone: (Boolean) -> Unit) =
        act("New password set for ${user.userId}. They were logged out.", onDone) { repository.resetPassword(user.id, password) }

    fun forceLogout(user: ManagedUser) = act("${user.userId} was logged out.") { repository.forceLogout(user.id) }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

/** One customer: summary, Registrations (renewal date) and Users (create, block, reset, force logout). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onSettings: (Long) -> Unit,
    onMasters: (Long) -> Unit,
    onReports: (Long) -> Unit,
    viewModel: CustomerDetailViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var addRegistration by remember { mutableStateOf(false) }
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
            AppTopBar(s.summary?.customer?.name ?: "Customer", onBack = onBack, actions = {
                IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = { onEdit(viewModel.customerId) }) { Icon(Icons.Default.Edit, "Edit customer") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val summary = s.summary
        when {
            s.loading -> LoadingBox(Modifier.fillMaxSize().padding(padding))
            summary == null -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text(s.error ?: "Customer not found.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text("Try again") }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                SummaryCard(summary)
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSettings(viewModel.customerId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Settings")
                    }
                    OutlinedButton(onClick = { onMasters(viewModel.customerId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Masters")
                    }
                    OutlinedButton(onClick = { onReports(viewModel.customerId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Reports")
                    }
                }
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Registrations (${s.registrations.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Users (${s.users.size})") })
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (tab == 0) {
                        OutlinedButton(onClick = { addRegistration = true }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text("Add registration (sets the renewal date)")
                        }
                        if (s.registrations.isEmpty()) Text("No registration yet: the customer has no renewal date.")
                        s.registrations.forEach { RegistrationCard(it) }
                    } else {
                        OutlinedButton(onClick = { addUser = true }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text("Add user")
                        }
                        Text(
                            "Licensed users: ${summary.usersAllowed} of ${summary.customer.userLimit} in use.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (s.users.isEmpty()) Text("No users yet. Create the cloak room's admin first.")
                        s.users.forEach { u ->
                            UserCard(
                                user = u,
                                busy = s.busy,
                                onEdit = { editUser = u },
                                onBlock = { viewModel.setAllowed(u, allowed = !u.allowed) },
                                onReset = { resetFor = u },
                                onForceLogout = { viewModel.forceLogout(u) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (addRegistration) {
        RegistrationDialog(
            busy = s.busy,
            defaultUsers = s.summary?.customer?.userLimit ?: 1,
            onDismiss = { addRegistration = false },
            onSave = { input -> viewModel.addRegistration(input) { ok -> if (ok) addRegistration = false } },
        )
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

@Composable
private fun SummaryCard(s: CustomerSummary) {
    val c = s.customer
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${c.name} · ID ${c.id} · ${if (c.active) "Active" else "Inactive"}", style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(c.address, c.state, c.pinCode).joinToString(", "), style = MaterialTheme.typography.bodySmall)
            Text("Mobile ${c.mobileNo}${c.email?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            if (c.gstNo != null || c.upiId != null) {
                Text(listOfNotNull(c.gstNo?.let { "GSTIN $it" }, c.upiId?.let { "UPI $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            Text("Renewal date: ${c.renewalDate ?: "not set"}", style = MaterialTheme.typography.bodyLarge)
            Text("User limit: ${c.userLimit} · Users ${s.usersTotal} (${s.admins} admin, ${s.operators} counter) · Logged in now ${s.activeSessions}")
            Text("Payments: ${s.paymentsCount} · ₹${s.paymentsTotal}${s.lastPaymentDate?.let { " · last $it" } ?: ""}")
            Text("Bags: ${s.bagsTotal} in total, ${s.bagsInStore} in store")
        }
    }
}

@Composable
private fun RegistrationCard(r: Registration) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.regNo, style = MaterialTheme.typography.titleSmall)
            Text("${r.startDate} to ${r.endDate} · ${r.userCount} users")
            r.remarks?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Same card as Admin → Users: name, login · code · type, status chips, login shift, actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UserCard(
    user: ManagedUser,
    busy: Boolean,
    onEdit: () -> Unit,
    onBlock: () -> Unit,
    onReset: () -> Unit,
    /** Super Admin only; null (the station admin's Users screen) = no Force Logout button. */
    onForceLogout: (() -> Unit)? = null,
    /** True on the logged-in admin's own card: no Block. */
    isMe: Boolean = false,
) {
    val isAdmin = user.userType == UserTypes.ADMIN
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(user.userName, style = MaterialTheme.typography.titleLarge)
            Text(
                "${user.userId} · ${user.userCode} · ${if (isAdmin) "Admin" else "Counter user"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (user.allowed) "Active" else "Blocked") })
                AssistChip(
                    onClick = {},
                    label = { Text(user.sessionStartedAt?.let { "Logged in since ${DateTimes.displayText(it)}" } ?: "Not logged in") },
                )
            }
            if (!isAdmin) {
                Text("Login shift: ${user.shiftLabel ?: "any time"}", style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
                OutlinedButton(onClick = onReset, enabled = !busy) { Text("Reset password") }
                if (!isMe) OutlinedButton(onClick = onBlock, enabled = !busy) { Text(if (user.allowed) "Block" else "Unblock") }
                if (onForceLogout != null && !isMe && user.sessionStartedAt != null) {
                    OutlinedButton(onClick = onForceLogout, enabled = !busy) { Text("Force Logout") }
                }
            }
        }
    }
}

private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

@Composable
private fun RegistrationDialog(busy: Boolean, defaultUsers: Int, onDismiss: () -> Unit, onSave: (RegistrationInput) -> Unit) {
    val today = remember { LocalDate.now(ZoneId.of("Asia/Kolkata")) }
    var regNo by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(today.toString()) }
    var end by remember { mutableStateOf(today.plusYears(1).minusDays(1).toString()) }
    var users by remember { mutableStateOf(defaultUsers.toString()) }
    var remarks by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add registration") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(regNo, { regNo = it.take(30) }, label = { Text("Registration no. *") }, singleLine = true)
                OutlinedTextField(start, { start = it.take(10) }, label = { Text("Start date (YYYY-MM-DD) *") }, singleLine = true)
                OutlinedTextField(end, { end = it.take(10) }, label = { Text("End date = renewal date *") }, singleLine = true)
                OutlinedTextField(
                    users, { users = it.filter(Char::isDigit).take(4) }, label = { Text("Users *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(remarks, { remarks = it }, label = { Text("Remarks") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val count = users.toIntOrNull()
                error = when {
                    regNo.isBlank() -> "Enter the registration number."
                    !DATE.matches(start) || !DATE.matches(end) -> "Dates must look like 2026-11-03."
                    end < start -> "The end date must be on or after the start date."
                    count == null || count !in 1..1000 -> "Users must be 1 to 1000."
                    else -> null
                }
                if (error == null) onSave(RegistrationInput(regNo, start, end, count!!, remarks))
            }) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Add ([existing] = null) or edit a user — the same form as Admin → Users, plus the user code
 * (receipt prefix), which the server needs.
 */
@Composable
internal fun UserFormDialog(
    existing: ManagedUser?,
    shifts: List<ShiftItem>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (userId: String, name: String, code: String, type: String, password: String, shiftId: Long?) -> Unit,
) {
    val adding = existing == null
    var userId by rememberSaveable { mutableStateOf(existing?.userId ?: "") }
    var name by rememberSaveable { mutableStateOf(existing?.userName ?: "") }
    var code by rememberSaveable { mutableStateOf(existing?.userCode ?: "") }
    var type by rememberSaveable { mutableStateOf(existing?.userType ?: UserTypes.OPERATOR) }
    var password by rememberSaveable { mutableStateOf("") }
    var shiftId by rememberSaveable { mutableStateOf(existing?.shiftId) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) "Add user" else "Edit ${existing?.userId}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (adding) {
                    OutlinedTextField(
                        value = userId, onValueChange = { userId = it.trim().take(100) }, label = { Text("User ID (login)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(100) }, label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code, onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(10) },
                    label = { Text("User code (receipt prefix, e.g. U01)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == UserTypes.OPERATOR, onClick = { type = UserTypes.OPERATOR }, label = { Text("Counter user") })
                    FilterChip(selected = type == UserTypes.ADMIN, onClick = { type = UserTypes.ADMIN }, label = { Text("Admin") })
                }
                if (type == UserTypes.OPERATOR) {
                    ShiftDropdown(shifts, shiftId, onSelect = { shiftId = it })
                    Text(
                        if (shiftId == null) "Can log in at any time." else "Can log in only during this shift.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (adding) {
                    PasswordField(password, { password = it }, "First password")
                    Text("At least 8 characters with a letter and a digit.", style = MaterialTheme.typography.bodySmall)
                }
                ErrorBanner(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && code.isNotBlank() && (!adding || (userId.isNotBlank() && password.isNotEmpty())),
                onClick = {
                    error = when {
                        adding && !Regex("^[A-Za-z0-9._@-]{3,100}$").matches(userId) -> "User ID: 3–100 letters, digits or . _ @ -"
                        adding && !validPassword(password) -> "Password: at least 8 characters with a letter and a digit."
                        else -> null
                    }
                    if (error == null) onSave(userId, name, code, type, password, if (type == UserTypes.OPERATOR) shiftId else null)
                },
            ) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Same as Admin → Users → Reset password: new password typed twice. */
@Composable
internal fun ResetPasswordDialog(user: ManagedUser, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && password != confirm
    val weak = password.isNotEmpty() && !validPassword(password)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset password of ${user.userId}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasswordField(password, { password = it }, "New password")
                PasswordField(confirm, { confirm = it }, "Confirm new password")
                if (mismatch) ErrorBanner("The passwords do not match.")
                else if (weak) ErrorBanner("At least 8 characters with a letter and a digit.")
                Text("The user is logged out and must log in with the new password.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && validPassword(password) && password == confirm,
                onClick = { onSave(password) },
            ) { Text(if (busy) "Saving…" else "Reset") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** API_GUIDE.md §5 password rule: 8–128 characters, at least one letter and one digit. */
internal fun validPassword(p: String): Boolean = p.length in 8..128 && p.any(Char::isLetter) && p.any(Char::isDigit)
