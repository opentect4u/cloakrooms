package com.synergic.cloakroom.ui.superadmin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.repository.AuthRepository
import com.synergic.cloakroom.domain.repository.Customer
import com.synergic.cloakroom.domain.repository.SuperAdminRepository
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.LoadingBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CustomersUiState(
    val loading: Boolean = true,
    val search: String = "",
    val customers: List<Customer> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val repository: SuperAdminRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CustomersUiState())
    val state: StateFlow<CustomersUiState> = _state.asStateFlow()

    fun onSearch(text: String) = _state.update { it.copy(search = text) }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.customers(_state.value.search) }
                .onSuccess { list -> _state.update { it.copy(loading = false, customers = list) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.screenMessage()) } }
        }
    }

    fun logout() {
        viewModelScope.launch { runCatching { authRepository.logout() } }
    }
}

/** Super Admin home: all customers (cloak rooms) with search; tap one for its details. */
@Composable
fun CustomersScreen(
    session: UserSession,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onChangePassword: () -> Unit,
    viewModel: CustomersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    // Also refreshes after adding or editing a customer.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            AppTopBar("Super Admin", actions = {
                IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Change password") }, onClick = { menuOpen = false; onChangePassword() })
                    DropdownMenuItem(text = { Text("Logout") }, onClick = { menuOpen = false; confirmLogout = true })
                }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add customer") })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Logged in as ${session.userName} (${session.userId})",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::onSearch,
                label = { Text("Search customers") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.load() }),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            when {
                state.loading && state.customers.isEmpty() -> LoadingBox(Modifier.fillMaxSize())
                state.error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::load) { Text("Try again") }
                }
                state.customers.isEmpty() -> Text(
                    if (state.search.isBlank()) "No customers yet. Press \"Add customer\"." else "No customer matches \"${state.search}\".",
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.customers, key = { it.id }) { c -> CustomerCard(c, onClick = { onOpen(c.id) }) }
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Logout?") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.logout() }) { Text("Logout") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CustomerCard(c: Customer, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                AssistChip(onClick = onClick, label = { Text(if (c.active) "Active" else "Inactive") })
            }
            Text(listOfNotNull(c.address, c.state).joinToString(", "), style = MaterialTheme.typography.bodySmall)
            Text("ID ${c.id} · Users ${c.userLimit} · Renewal ${c.renewalDate ?: "not set"}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
