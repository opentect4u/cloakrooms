package com.synergic.cloakroom.ui.masters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.domain.repository.CustomerProfile
import com.synergic.cloakroom.domain.repository.HeaderFooterItem
import com.synergic.cloakroom.domain.repository.IdProofItem
import com.synergic.cloakroom.domain.repository.LineType
import com.synergic.cloakroom.domain.repository.ShiftItem
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.BigButton
import com.synergic.cloakroom.ui.components.LoadingBox
import com.synergic.cloakroom.ui.print.LogoImage

/** Something the admin is about to remove, waiting for confirmation. */
private sealed interface Removal {
    data class Proof(val item: IdProofItem) : Removal
    data class Line(val item: HeaderFooterItem) : Removal
    data class Shift(val item: ShiftItem) : Removal
}

private const val TAB_CLOAK_ROOM = 0
private const val TAB_ID_PROOFS = 1
private const val TAB_LINES = 2

/**
 * The cloak room's details (md_customer). Name, address, PIN, GSTIN and UPI ID are editable;
 * mobile, email, renewal date and user limit are set by the Synergic super admin and only shown.
 */
@Composable
private fun CustomerForm(profile: CustomerProfile, viewModel: MastersViewModel) {
    // Re-initialise when the saved values change (e.g. after Save).
    var name by rememberSaveable(profile) { mutableStateOf(profile.name) }
    var address by rememberSaveable(profile) { mutableStateOf(profile.address) }
    var pin by rememberSaveable(profile) { mutableStateOf(profile.pinCode) }
    var gst by rememberSaveable(profile) { mutableStateOf(profile.gstNo) }
    var upi by rememberSaveable(profile) { mutableStateOf(profile.upiId) }
    var saving by remember { mutableStateOf(false) }
    val changed = name != profile.name || address != profile.address || pin != profile.pinCode ||
        gst != profile.gstNo || upi != profile.upiId

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Cloak room name *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it }, label = { Text("Address *") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN code *") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        GstinField(gst) { gst = it }
        UpiIdField(upi) { upi = it }
        BigButton(
            text = if (changed) "Save details" else "No changes",
            enabled = changed && name.isNotBlank() && address.isNotBlank() && pin.length == 6,
            loading = saving,
            onClick = {
                saving = true
                viewModel.saveCustomer(name, address, pin, gst, upi) { saving = false }
            },
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Set by Synergic", style = MaterialTheme.typography.titleMedium)
                ReadOnly("Mobile", profile.mobile)
                ReadOnly("Email", profile.email.ifEmpty { "-" })
                ReadOnly("Renewal date", profile.renewalDt.ifEmpty { "-" })
                ReadOnly("User limit", profile.userLimit.toString())
                Text("Contact Synergic support to change these.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GstinField(value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value, { onChange(it.uppercase().take(15)) }, label = { Text("GSTIN") }, singleLine = true,
    supportingText = { Text("Printed on GST bills. Leave empty if not registered.") },
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun UpiIdField(value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value, { onChange(it.trim()) }, label = { Text("UPI ID") }, singleLine = true,
    supportingText = { Text("Used for the payment QR at check-out and on UPI bills, e.g. name@bank. Empty = no UPI QR.") },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    modifier = Modifier.fillMaxWidth(),
)

private const val SET_BY_SYNERGIC = "Set by Synergic (super admin). Contact Synergic support to change these."

/**
 * The station admin's view of the cloak room details: GSTIN and UPI ID are editable, the rest is
 * set by the super admin and only shown.
 */
@Composable
private fun CustomerDetails(profile: CustomerProfile, viewModel: MastersViewModel) {
    var gst by rememberSaveable(profile) { mutableStateOf(profile.gstNo) }
    var upi by rememberSaveable(profile) { mutableStateOf(profile.upiId) }
    var saving by remember { mutableStateOf(false) }
    val changed = gst != profile.gstNo || upi != profile.upiId

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GstinField(gst) { gst = it }
        UpiIdField(upi) { upi = it }
        BigButton(
            text = if (changed) "Save GSTIN and UPI ID" else "No changes",
            enabled = changed,
            loading = saving,
            onClick = {
                saving = true
                viewModel.saveCustomer(profile.name, profile.address, profile.pinCode, gst, upi) { saving = false }
            },
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(profile.address, style = MaterialTheme.typography.bodyMedium)
                ReadOnly("PIN code", profile.pinCode)
                ReadOnly("Mobile", profile.mobile)
                ReadOnly("Email", profile.email.ifEmpty { "-" })
                ReadOnly("Renewal date", profile.renewalDt.ifEmpty { "-" })
                ReadOnly("User limit", profile.userLimit.toString())
            }
        }
        Text("Details above: $SET_BY_SYNERGIC", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadOnly(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Admin → Masters: cloak room details, ID proof types, header/footer/logo lines and shifts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MastersScreen(onBack: () -> Unit, title: String = "Masters", viewModel: MastersViewModel = hiltViewModel()) {
    val customer by viewModel.customer.collectAsStateWithLifecycle()
    val proofs by viewModel.idProofs.collectAsStateWithLifecycle()
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val shifts by viewModel.shifts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // null = closed; for adding, an item with id 0 / null.
    var editProof by remember { mutableStateOf<IdProofItem?>(null) }
    var editLine by remember { mutableStateOf<HeaderFooterItem?>(null) }
    var editShift by remember { mutableStateOf<ShiftItem?>(null) }
    var removal by remember { mutableStateOf<Removal?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { AppTopBar(title, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab != TAB_CLOAK_ROOM && !(tab == TAB_ID_PROOFS && viewModel.setBySynergic)) {
                ExtendedFloatingActionButton(
                    onClick = {
                        when (tab) {
                            TAB_ID_PROOFS -> editProof = IdProofItem(0, "")
                            TAB_LINES -> editLine = HeaderFooterItem(0, LineType.HEADER, "", null, onReceipt = true, onBill = true, onReport = true)
                            else -> editShift = ShiftItem(0, "", "", "")
                        }
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(listOf("", "Add ID proof", "Add line", "Add shift")[tab]) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                listOf("Cloak room", "ID proofs", "Header / footer", "Shifts").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            val content: Any? = when (tab) {
                TAB_CLOAK_ROOM -> customer
                TAB_ID_PROOFS -> proofs
                TAB_LINES -> lines
                else -> shifts
            }
            if (content == null) {
                LoadingBox(Modifier.fillMaxSize())
                return@Column
            }
            if (tab == TAB_CLOAK_ROOM) {
                if (viewModel.setBySynergic) CustomerDetails(customer!!, viewModel) else CustomerForm(customer!!, viewModel)
                return@Column
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    TAB_ID_PROOFS -> {
                        if (viewModel.idProofsShared) {
                            item {
                                Text(
                                    "ID proof types are the same for every cloak room: a change here applies to all customers.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (viewModel.setBySynergic) {
                            item { Text(SET_BY_SYNERGIC, style = MaterialTheme.typography.bodyMedium) }
                            proofList(proofs.orEmpty(), onEdit = null, onRemove = null)
                        } else {
                            proofList(proofs.orEmpty(), onEdit = { editProof = it }, onRemove = { removal = Removal.Proof(it) })
                        }
                    }
                    TAB_LINES -> lineList(
                        lines.orEmpty(),
                        onEdit = { editLine = it },
                        onMove = viewModel::moveLine,
                        onRemove = { removal = Removal.Line(it) },
                    )
                    else -> shiftList(shifts.orEmpty(), onEdit = { editShift = it }, onRemove = { removal = Removal.Shift(it) })
                }
            }
        }
    }

    editProof?.let { item ->
        IdProofDialog(item, onDismiss = { editProof = null }) { name, done ->
            viewModel.saveIdProof(item.id.takeIf { it != 0L }, name) { ok -> if (ok) editProof = null; done(ok) }
        }
    }
    editLine?.let { item ->
        LineDialog(item, viewModel, onDismiss = { editLine = null }) { edited, done ->
            viewModel.saveLine(edited) { ok -> if (ok) editLine = null; done(ok) }
        }
    }
    editShift?.let { item ->
        ShiftDialog(item, onDismiss = { editShift = null }) { name, from, to, done ->
            viewModel.saveShift(item.id.takeIf { it != 0L }, name, from, to) { ok -> if (ok) editShift = null; done(ok) }
        }
    }
    removal?.let { r ->
        val what = when (r) {
            is Removal.Proof -> "ID proof \"${r.item.name}\""
            is Removal.Line -> if (r.item.type == LineType.LOGO) "the logo" else "the line \"${r.item.text}\""
            is Removal.Shift -> "shift \"${r.item.name}\""
        }
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text("Remove $what?") },
            text = { Text("Old receipts and bills keep showing it. New ones will not use it.") },
            confirmButton = {
                TextButton(onClick = {
                    when (r) {
                        is Removal.Proof -> viewModel.deleteIdProof(r.item)
                        is Removal.Line -> viewModel.deleteLine(r.item)
                        is Removal.Shift -> viewModel.deleteShift(r.item)
                    }
                    removal = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("Cancel") } },
        )
    }
}

/** [onEdit] / [onRemove] null: shown only (set by Synergic). */
private fun LazyListScope.proofList(items: List<IdProofItem>, onEdit: ((IdProofItem) -> Unit)?, onRemove: ((IdProofItem) -> Unit)?) {
    if (items.isEmpty()) item { Empty("No ID proof types. Add one, or turn off \"ID proof required\" in Settings.") }
    items(items, key = { it.id }) { p ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                onEdit?.let { TextButton(onClick = { it(p) }) { Text("Edit") } }
                onRemove?.let { TextButton(onClick = { it(p) }) { Text("Remove") } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.lineList(
    items: List<HeaderFooterItem>,
    onEdit: (HeaderFooterItem) -> Unit,
    onMove: (HeaderFooterItem, Boolean) -> Unit,
    onRemove: (HeaderFooterItem) -> Unit,
) {
    item {
        Text(
            "Printed in this order. Headers go at the top (the logo first), footers at the bottom.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (items.isEmpty()) item { Empty("No lines. Receipts then show only the cloak room name.") }
    items(items, key = { it.id }) { line ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = { onEdit(line) }, label = { Text(line.type.label) })
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { onMove(line, true) }) { Icon(Icons.Default.ArrowUpward, "Move up") }
                        IconButton(onClick = { onMove(line, false) }) { Icon(Icons.Default.ArrowDownward, "Move down") }
                    }
                }
                if (line.type == LineType.LOGO) {
                    line.image?.let { LogoImage(it, Modifier.fillMaxWidth()) }
                } else {
                    Text(line.text, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    listOfNotNull(
                        "Receipt".takeIf { line.onReceipt },
                        "Bill".takeIf { line.onBill },
                        "Report".takeIf { line.onReport },
                    ).joinToString(" · ").ifEmpty { "Not printed anywhere" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEdit(line) }) { Text("Edit") }
                    OutlinedButton(onClick = { onRemove(line) }) { Text("Remove") }
                }
            }
        }
    }
}

private fun LazyListScope.shiftList(items: List<ShiftItem>, onEdit: (ShiftItem) -> Unit, onRemove: (ShiftItem) -> Unit) {
    if (items.isEmpty()) item { Empty("No shifts. Check-ins and bills are then saved without a shift.") }
    items(items, key = { it.id }) { s ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${s.fromTime} – ${s.toTime}${if (s.toTime <= s.fromTime) " (next day)" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { onEdit(s) }) { Text("Edit") }
                TextButton(onClick = { onRemove(s) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun IdProofDialog(item: IdProofItem, onDismiss: () -> Unit, onSave: (String, (Boolean) -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf(item.name) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item.id == 0L) "Add ID proof" else "Edit ID proof") },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Ration Card") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                onSave(name) { ok -> if (!ok) saving = false }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LineDialog(
    item: HeaderFooterItem,
    viewModel: MastersViewModel,
    onDismiss: () -> Unit,
    onSave: (HeaderFooterItem, (Boolean) -> Unit) -> Unit,
) {
    var type by remember { mutableStateOf(item.type) }
    var text by rememberSaveable { mutableStateOf(item.text) }
    var image by remember { mutableStateOf(item.image) }
    var onReceipt by rememberSaveable { mutableStateOf(item.onReceipt) }
    var onBill by rememberSaveable { mutableStateOf(item.onBill) }
    var onReport by rememberSaveable { mutableStateOf(item.onReport) }
    var saving by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.loadLogo(it) { bytes -> image = bytes } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item.id == 0L) "Add line" else "Edit line") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                }
                if (type == LineType.LOGO) {
                    image?.let { LogoImage(it, Modifier.fillMaxWidth()) }
                    OutlinedButton(onClick = { picker.launch("image/*") }) {
                        Text(if (image == null) "Choose picture" else "Choose another picture")
                    }
                    Text("It is turned into black and white for the thermal printer.", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(text, { text = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                }
                Toggle("On check-in receipt", onReceipt) { onReceipt = it }
                Toggle("On bill", onBill) { onBill = it }
                Toggle("On report", onReport) { onReport = it }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                onSave(item.copy(type = type, text = text, image = image, onReceipt = onReceipt, onBill = onBill, onReport = onReport)) { ok ->
                    if (!ok) saving = false
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ShiftDialog(item: ShiftItem, onDismiss: () -> Unit, onSave: (String, String, String, (Boolean) -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf(item.name) }
    var from by rememberSaveable { mutableStateOf(item.fromTime) }
    var to by rememberSaveable { mutableStateOf(item.toTime) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item.id == 0L) "Add shift" else "Edit shift") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Morning") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        from, { from = it }, label = { Text("From (HH:MM)") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        to, { to = it }, label = { Text("To (HH:MM)") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("24-hour clock. A shift may end the next day, e.g. 22:00 – 06:00.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                onSave(name, from, to) { ok -> if (!ok) saving = false }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
