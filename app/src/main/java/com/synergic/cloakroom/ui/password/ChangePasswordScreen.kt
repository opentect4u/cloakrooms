package com.synergic.cloakroom.ui.password

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.BigButton
import com.synergic.cloakroom.ui.components.ErrorBanner
import com.synergic.cloakroom.ui.components.PasswordField

/**
 * [forced] = first login: there is no way back, only Save or Logout.
 * Otherwise [onDone] is called after a successful change and on Back.
 */
@Composable
fun ChangePasswordScreen(
    forced: Boolean,
    onDone: () -> Unit = {},
    viewModel: ChangePasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done && !forced) onDone() }

    Scaffold(
        topBar = { AppTopBar("Change password", onBack = if (forced) null else onDone) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (forced) {
                Text(
                    "Please choose a new password to continue (first login, or your password was reset by an admin).",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            ErrorBanner(state.error)
            PasswordField(state.current, viewModel::onCurrentChange, "Current password")
            PasswordField(state.new, viewModel::onNewChange, "New password")
            PasswordField(
                state.confirm, viewModel::onConfirmChange, "Confirm new password",
                imeAction = ImeAction.Done, onDone = viewModel::save,
            )
            Text("8 to 128 characters, with at least one letter and one digit.", style = MaterialTheme.typography.bodySmall)
            BigButton(
                text = "Save password",
                onClick = viewModel::save,
                enabled = state.current.isNotEmpty() && state.new.isNotEmpty() && state.confirm.isNotEmpty(),
                loading = state.saving,
            )
            if (forced) {
                OutlinedButton(onClick = viewModel::logout, modifier = Modifier.padding(top = 8.dp)) { Text("Logout") }
            }
        }
    }
}
