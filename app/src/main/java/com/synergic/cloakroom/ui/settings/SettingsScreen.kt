package com.synergic.cloakroom.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.domain.settings.SettingDataType
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.BigButton
import com.synergic.cloakroom.ui.components.LoadingBox
import com.synergic.cloakroom.ui.components.PasswordField

/**
 * Settings: every field is generated from a md_setting row. Normal users get only the Print tab;
 * a super admin sees one customer's settings in the same tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, title: String = "Settings", viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var confirmSave by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val leave = { if (state.changes.isNotEmpty()) confirmDiscard = true else onBack() }
    BackHandler(onBack = leave)

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { AppTopBar(title, onBack = leave) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!state.loading) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        if (state.hasErrors) {
                            Text(
                                "Fix the fields marked in red to save.",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        BigButton(
                            text = when (state.changes.size) {
                                0 -> "No changes"
                                1 -> "Save 1 change"
                                else -> "Save ${state.changes.size} changes"
                            },
                            onClick = { confirmSave = true },
                            enabled = state.canSave,
                            loading = state.saving,
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val tabs = state.tabs
        val tabIndex = selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (tabs.isEmpty()) {
                Text("No settings found.", modifier = Modifier.padding(16.dp))
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == tabIndex,
                        onClick = { selectedTab = index },
                        text = {
                            if (tab.errorCount > 0) {
                                BadgedBox(badge = { Badge { Text(tab.errorCount.toString()) } }) { Text(tab.title) }
                            } else {
                                Text(tab.title)
                            }
                        },
                    )
                }
            }
            if (state.customerMode) {
                Text(
                    "Settings of this cloak room. Fields marked \"Saved on this phone only\" have no API yet; all others are saved on the server.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
            if (state.ownOnly) {
                Text(
                    "Changes here apply to your login only.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tabs[tabIndex].fields, key = { it.def.name }) { field ->
                    SettingCard(field, onValueChange = { viewModel.onValueChange(field.def.name, it) })
                }
            }
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Save these changes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.changes.forEach { line ->
                        Column {
                            Text(line.label, fontWeight = FontWeight.SemiBold)
                            Text("${line.from}  →  ${line.to}")
                        }
                    }
                    HorizontalDivider()
                    Text("They apply at once to all counters. Old bills do not change.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { confirmSave = false; viewModel.save() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Cancel") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("You have ${state.changes.size} unsaved change(s).") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; viewModel.discardChanges(); onBack() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun SettingCard(field: SettingField, onValueChange: (String) -> Unit) {
    val def = field.def
    Card(
        colors = if (field.changed) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier.fillMaxWidth().alpha(if (field.enabled) 1f else 0.5f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (def.dataType) {
                SettingDataType.BOOL -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(def.label, style = MaterialTheme.typography.titleMedium)
                        def.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = field.value == "Y",
                        onCheckedChange = { onValueChange(if (it) "Y" else "N") },
                        enabled = field.enabled,
                    )
                }

                SettingDataType.INT, SettingDataType.DECIMAL, SettingDataType.TEXT -> {
                    Text(def.label, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = onValueChange,
                        enabled = field.enabled,
                        singleLine = true,
                        isError = field.error != null,
                        supportingText = {
                            Text(field.error ?: listOfNotNull(def.description, SettingsForm.rangeHint(def)).joinToString(" · "))
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (def.dataType) {
                                SettingDataType.INT -> KeyboardType.Number
                                SettingDataType.DECIMAL -> KeyboardType.Decimal
                                else -> KeyboardType.Text
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SettingDataType.ENUM -> {
                    Text(def.label, style = MaterialTheme.typography.titleMedium)
                    EnumDropdown(field, onValueChange)
                }

                SettingDataType.PASSWORD -> {
                    Text(def.label, style = MaterialTheme.typography.titleMedium)
                    PasswordField(
                        value = field.value,
                        onValueChange = onValueChange,
                        label = if (def.value.isEmpty()) "New password" else "New password (empty = keep current)",
                        enabled = field.enabled,
                    )
                    Text(
                        field.error ?: listOfNotNull(
                            def.description,
                            if (def.value.isEmpty()) "No password set yet" else "A password is set",
                        ).joinToString(" · "),
                        color = if (field.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (def.dataType == SettingDataType.BOOL && field.error != null) {
                Text(field.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (def.readOnly) {
                Text("Set by Synergic (super admin).", style = MaterialTheme.typography.bodySmall)
            } else if (!field.enabled && field.parentLabel != null) {
                Text("Turn on \"${field.parentLabel}\" to change this.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdown(field: SettingField, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val def = field.def
    ExposedDropdownMenuBox(
        expanded = expanded && field.enabled,
        onExpandedChange = { if (field.enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = SettingsForm.display(def, field.value),
            onValueChange = {},
            readOnly = true,
            enabled = field.enabled,
            isError = field.error != null,
            supportingText = { Text(field.error ?: def.description.orEmpty()) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, field.enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            def.options.forEach { (code, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onValueChange(code); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
