package com.synergic.cloakroom.ui.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import com.synergic.cloakroom.domain.repository.ShiftItem
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.domain.auth.UserSummary
import com.synergic.cloakroom.domain.auth.UserTypes
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.ErrorBanner
import com.synergic.cloakroom.ui.components.LoadingBox
import com.synergic.cloakroom.ui.components.PasswordField

/** What a confirmation dialog is asking about. */
private sealed interface Confirm {
    data class Block(val user: UserSummary, val block: Boolean) : Confirm
    data class ForceLogout(val user: UserSummary) : Confirm
}

/** Admin → Users. [currentUserId] is md_user.id of the logged-in admin (no block/force logout on self). */
@Composable
fun UsersScreen(
    currentUserId: Long,
    onBack: () -> Unit,
    viewModel: UsersViewModel = hiltViewModel(),
) {
    val users by viewModel.users.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val shifts by viewModel.shifts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UserSummary?>(null) }
    var resetting by remember { mutableStateOf<UserSummary?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { AppTopBar("Users", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("Add user") },
            )
        },
    ) { padding ->
        val list = users
        if (list == null) {
            LoadingBox(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (list.isEmpty()) item { Text("No users.") }
            items(list, key = { it.id }) { user ->
                UserCard(
                    user = user,
                    isSelf = user.id == currentUserId,
                    onEdit = { editing = user },
                    onResetPassword = { resetting = user },
                    onBlock = { confirm = Confirm.Block(user, block = user.allowed) },
                    onForceLogout = { confirm = Confirm.ForceLogout(user) },
                )
            }
        }
    }

    if (showAdd) {
        UserFormDialog(
            existing = null,
            isSelf = false,
            shifts = shifts,
            onDismiss = { showAdd = false },
            onSave = { userId, name, type, password, shiftId, done ->
                viewModel.addUser(userId, name, type, password, shiftId) { ok -> if (ok) showAdd = false; done(ok) }
            },
        )
    }
    editing?.let { user ->
        UserFormDialog(
            existing = user,
            isSelf = user.id == currentUserId,
            shifts = shifts,
            onDismiss = { editing = null },
            onSave = { _, name, type, _, shiftId, done ->
                viewModel.updateUser(user.id, name, type, shiftId) { ok -> if (ok) editing = null; done(ok) }
            },
        )
    }
    resetting?.let { user ->
        ResetPasswordDialog(
            user = user,
            onDismiss = { resetting = null },
            onSave = { password, done ->
                viewModel.resetPassword(user.id, password) { ok -> if (ok) resetting = null; done(ok) }
            },
        )
    }
    confirm?.let { c ->
        val (title, text, action) = when (c) {
            is Confirm.Block -> Triple(
                if (c.block) "Block ${c.user.userId}?" else "Unblock ${c.user.userId}?",
                if (c.block) "The user cannot log in until an admin unblocks them."
                else "The user can log in again. Wrong-password count is reset to 0.",
                if (c.block) "Block" else "Unblock",
            )
            is Confirm.ForceLogout -> Triple(
                "Force logout ${c.user.userId}?",
                "Ends the session that started ${c.user.sessionStartedAt?.let(DateTimes::displayText) ?: ""}. " +
                    "Use this when the user's device is lost or broken.",
                "Force Logout",
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    when (c) {
                        is Confirm.Block -> viewModel.setBlocked(c.user, c.block)
                        is Confirm.ForceLogout -> viewModel.forceLogout(c.user)
                    }
                    confirm = null
                }) { Text(action) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserCard(
    user: UserSummary,
    isSelf: Boolean,
    onEdit: () -> Unit,
    onResetPassword: () -> Unit,
    onBlock: () -> Unit,
    onForceLogout: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${user.userName}${if (isSelf) " (you)" else ""}",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "${user.userId} · ${user.userCode} · ${if (user.isAdmin) "Admin" else "Counter user"}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (user.allowed) "Active" else "Blocked") })
                AssistChip(
                    onClick = {},
                    label = {
                        Text(user.sessionStartedAt?.let { "Logged in since ${DateTimes.displayText(it)}" } ?: "Not logged in")
                    },
                )
            }
            if (!user.isAdmin) {
                Text(
                    "Login shift: ${user.shiftLabel ?: "any time"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (user.failedAttempts > 0) {
                Text("Wrong passwords: ${user.failedAttempts}", style = MaterialTheme.typography.bodyMedium)
            }
            if (user.mustChangePassword) {
                Text("Must change password at next login", style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                OutlinedButton(onClick = onResetPassword) { Text("Reset password") }
                if (!isSelf) {
                    OutlinedButton(onClick = onBlock) { Text(if (user.allowed) "Block" else "Unblock") }
                    if (user.isLoggedIn) OutlinedButton(onClick = onForceLogout) { Text("Force Logout") }
                }
            }
        }
    }
}

/** Add ([existing] = null) or edit a user. [onSave] gets a callback to re-enable the button on failure. */
@Composable
private fun UserFormDialog(
    existing: UserSummary?,
    isSelf: Boolean,
    shifts: List<ShiftItem>,
    onDismiss: () -> Unit,
    onSave: (userId: String, name: String, type: String, password: String, shiftId: Long?, done: (Boolean) -> Unit) -> Unit,
) {
    var userId by rememberSaveable { mutableStateOf(existing?.userId ?: "") }
    var name by rememberSaveable { mutableStateOf(existing?.userName ?: "") }
    var type by rememberSaveable { mutableStateOf(existing?.userType ?: UserTypes.OPERATOR) }
    var password by rememberSaveable { mutableStateOf("") }
    var shiftId by rememberSaveable { mutableStateOf(existing?.shiftId) }
    var saving by remember { mutableStateOf(false) }
    val adding = existing == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adding) "Add user" else "Edit ${existing?.userId}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (adding) {
                    OutlinedTextField(
                        value = userId,
                        onValueChange = { userId = it },
                        label = { Text("User ID (login)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == UserTypes.OPERATOR,
                        onClick = { type = UserTypes.OPERATOR },
                        enabled = !isSelf,
                        label = { Text("Counter user") },
                    )
                    FilterChip(
                        selected = type == UserTypes.ADMIN,
                        onClick = { type = UserTypes.ADMIN },
                        enabled = !isSelf,
                        label = { Text("Admin") },
                    )
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
                    Text("The user must change it at first login.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && name.isNotBlank() && (!adding || (userId.isNotBlank() && password.isNotEmpty())),
                onClick = {
                    saving = true
                    onSave(userId, name, type, password, shiftId) { ok -> if (!ok) saving = false }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ResetPasswordDialog(
    user: UserSummary,
    onDismiss: () -> Unit,
    onSave: (password: String, done: (Boolean) -> Unit) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val mismatch = confirm.isNotEmpty() && password != confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset password of ${user.userId}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasswordField(password, { password = it }, "New password")
                PasswordField(confirm, { confirm = it }, "Confirm new password")
                if (mismatch) ErrorBanner("The passwords do not match.")
                Text("The user must change it at the next login.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && password.isNotEmpty() && password == confirm,
                onClick = {
                    saving = true
                    onSave(password) { ok -> if (!ok) saving = false }
                },
            ) { Text("Reset") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Login shift" choice: any time, or one active shift. Also used by Super Admin → customer → Users. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShiftDropdown(shifts: List<ShiftItem>, selected: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf<Pair<Long?, String>>(null to "Any time") + shifts.map { it.id to "${it.name} (${it.fromTime}–${it.toTime})" }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "Any time",
            onValueChange = {},
            readOnly = true,
            label = { Text("Login shift") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}
