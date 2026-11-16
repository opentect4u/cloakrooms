package com.synergic.cloakroom.ui.printer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.data.print.BluetoothPrinterPort
import com.synergic.cloakroom.domain.settings.PrinterType
import com.synergic.cloakroom.ui.components.AppTopBar

/**
 * Printer setup (every user): printer type, Bluetooth state, the phone's paired devices with Test print and
 * "Use this printer", an address typed by hand, and a shortcut to Android's pairing screen.
 */
@Composable
fun PrinterSetupScreen(
    onBack: () -> Unit,
    viewModel: PrinterSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Back from Android's Bluetooth or app settings: pairing or permission may have changed.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    fun openBluetoothSettings() = context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun openAppSettings() = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    )

    Scaffold(
        topBar = {
            AppTopBar("Printer setup", onBack = onBack, actions = {
                IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CurrentPrinterCard(state, onType = viewModel::setPrinterType)
            PaperFeedCard(state, onText = viewModel::onFeedText, onSave = viewModel::saveFeed)

            when (state.bluetooth) {
                BluetoothPrinterPort.State.NO_BLUETOOTH ->
                    Notice("This device has no Bluetooth.")
                BluetoothPrinterPort.State.NO_PERMISSION -> {
                    Notice("Allow \"Nearby devices\" so the app can see and use your Bluetooth printer.")
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Allow Nearby devices") }
                    OutlinedButton(onClick = ::openAppSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Open app permissions")
                    }
                }
                BluetoothPrinterPort.State.OFF -> {
                    Notice("Bluetooth is off. Turn it on to see your printer.")
                    Button(onClick = ::openBluetoothSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Open Bluetooth settings")
                    }
                }
                BluetoothPrinterPort.State.READY -> {
                    Text("Paired Bluetooth devices", style = MaterialTheme.typography.titleMedium)
                    if (state.devices.isEmpty()) {
                        Notice("No paired devices. Switch the printer on, then pair it in Android's Bluetooth settings (PIN is often 0000 or 1234).")
                    }
                    state.devices.forEach { d ->
                        DeviceCard(
                            device = d,
                            chosen = state.chosen.equals(d.address, ignoreCase = true) || state.chosen.equals(d.name, ignoreCase = true),
                            busy = state.busyAddress != null,
                            working = state.busyAddress == d.address,
                            onTest = { viewModel.testPrint(d.address, d.name) },
                            onUse = { viewModel.use(d.address, d.name) },
                        )
                    }
                    OutlinedButton(onClick = ::openBluetoothSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Text("  Pair a new printer")
                    }

                    Text("Add printer by address", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Use this if the printer is not in the list. The address is printed on the printer's self-test page " +
                            "(hold the feed button while switching it on).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = state.manualAddress,
                        onValueChange = viewModel::onManualAddress,
                        label = { Text("Bluetooth address") },
                        placeholder = { Text("00:11:22:AA:BB:CC") },
                        isError = state.manualError != null,
                        supportingText = state.manualError?.let { { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::testManual,
                            enabled = state.busyAddress == null,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) { Text("Test print") }
                        Button(
                            onClick = viewModel::useManual,
                            enabled = state.busyAddress == null,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) { Text("Use this address") }
                    }
                }
            }
        }
    }
}

/** Label and one-line help for each printer type, in screen order. */
private val PRINTER_TYPES = listOf(
    PrinterType.AUTO to ("Auto" to "A paired Bluetooth printer if found, else the POS's built-in printer"),
    PrinterType.BLUETOOTH to ("Bluetooth" to "The Bluetooth printer chosen below"),
    PrinterType.BUILT_IN_SERIAL to ("Built-in (GC039)" to "POS with a built-in serial printer"),
    PrinterType.USB to ("USB (GC039ES)" to "POS with a built-in USB printer"),
    PrinterType.NONE to ("None" to "No printing: preview and Share PDF only"),
)

@Composable
private fun CurrentPrinterCard(state: PrinterSetupUiState, onType: (PrinterType) -> Unit) {
    val chosenName = state.devices.firstOrNull { it.address.equals(state.chosen, ignoreCase = true) }?.name
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Printer type", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                PRINTER_TYPES.forEach { (type, text) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(
                            selected = state.printerType == type,
                            enabled = state.busyAddress == null,
                            role = Role.RadioButton,
                            onClick = { onType(type) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.printerType == type, onClick = null, enabled = state.busyAddress == null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(text.first, style = MaterialTheme.typography.bodyLarge)
                            Text(text.second, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(
                "Bluetooth printer: " + when {
                    state.chosen.isEmpty() -> "not chosen (found automatically)"
                    chosenName != null -> "$chosenName (${state.chosen})"
                    else -> state.chosen
                }
            )
            Text(
                if (state.isAdmin) "Your choice here is the printer for every user (users can still pick their own)."
                else "Your choice here applies to your login only.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** PAPER_FEED_MM: blank paper after each print. Test print uses the typed value before it is saved. */
@Composable
private fun PaperFeedCard(state: PrinterSetupUiState, onText: (String) -> Unit, onSave: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Blank paper after print", style = MaterialTheme.typography.titleMedium)
            Text(
                "If the printer leaves a long empty strip after each print, lower this. If the last line " +
                    "stays inside the printer, raise it. Press Test print to try, then Save.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.feedText,
                    onValueChange = onText,
                    label = { Text("mm") },
                    isError = state.feedError != null,
                    supportingText = state.feedError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onSave,
                    enabled = state.busyAddress == null && state.feedText != state.savedFeedMm?.toString(),
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: BluetoothPrinterPort.PairedDevice,
    chosen: Boolean,
    busy: Boolean,
    working: Boolean,
    onTest: () -> Unit,
    onUse: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (chosen) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (device.looksLikePrinter) Icons.Default.Print else Icons.Default.Bluetooth, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                }
                if (chosen) Icon(Icons.Default.CheckCircle, contentDescription = "Chosen printer", tint = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (working) "Printing…" else "Test print")
                }
                Button(onClick = onUse, enabled = !busy && !chosen, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (chosen) "In use" else "Use this printer")
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(16.dp))
    }
}
