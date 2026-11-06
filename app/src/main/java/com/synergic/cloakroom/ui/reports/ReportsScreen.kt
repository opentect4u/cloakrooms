package com.synergic.cloakroom.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.domain.report.BillRow
import com.synergic.cloakroom.domain.report.CheckinRow
import com.synergic.cloakroom.domain.report.CollectionLine
import com.synergic.cloakroom.domain.report.ReportAccess
import com.synergic.cloakroom.domain.report.ReportData
import com.synergic.cloakroom.domain.report.ReportShift
import com.synergic.cloakroom.domain.report.ReportUser
import com.synergic.cloakroom.domain.report.SyncExceptionRow
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.BigButton
import com.synergic.cloakroom.ui.components.ErrorBanner
import com.synergic.cloakroom.ui.components.LoadingBox
import com.synergic.cloakroom.ui.components.PasswordField
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(onBack: () -> Unit, title: String = "Reports", viewModel: ReportsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(s.message) {
        s.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(s.shareIntent) {
        s.shareIntent?.let {
            context.startActivity(it)
            viewModel.onShareStarted()
        }
    }

    val ready = s.access is ReportAccess.Allowed && !s.locked
    Scaffold(
        topBar = {
            AppTopBar(title, onBack = onBack, actions = {
                if (ready) {
                    IconButton(onClick = viewModel::print) { Icon(Icons.Default.Print, "Print") }
                    IconButton(onClick = viewModel::sharePdf) { Icon(Icons.Default.Share, "Share PDF") }
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            s.access == null -> LoadingBox(Modifier.fillMaxSize().padding(padding))
            s.access == ReportAccess.Denied -> Text(
                "Reports are switched off for counter users. Ask an admin.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            s.locked -> PasswordGate(s.passwordError, viewModel::unlock, Modifier.padding(padding))
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                Filters(
                    from = s.from,
                    to = s.to,
                    users = s.users,
                    canChooseUser = (s.access as ReportAccess.Allowed).canChooseUser,
                    userId = s.userId,
                    shifts = s.shifts,
                    shiftId = s.shiftId,
                    onFrom = viewModel::setFrom,
                    onTo = viewModel::setTo,
                    onUser = viewModel::setUser,
                    onShift = viewModel::setShift,
                )
                if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val data = s.data
                val showExceptions = (s.access as ReportAccess.Allowed).showExceptions
                val titles = listOf(
                    "Check-ins (${data?.checkins?.size ?: 0})",
                    "Bills (${data?.bills?.size ?: 0})",
                    "Collection",
                ) + if (showExceptions) listOf("Exceptions (${s.exceptions?.size ?: 0})") else emptyList()
                PrimaryScrollableTabRow(selectedTabIndex = tab.coerceAtMost(titles.lastIndex), edgePadding = 0.dp) {
                    titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                when {
                    tab == 3 && showExceptions -> ExceptionList(s.exceptions, s.resolving, viewModel::resolve)
                    data == null -> Unit
                    tab == 0 -> CheckinList(data.checkins)
                    tab == 1 -> BillList(data.bills)
                    else -> Collection(data)
                }
            }
        }
    }
}

/** Rows the server could not take as sent; the admin checks them and marks them resolved. */
@Composable
private fun ExceptionList(rows: List<SyncExceptionRow>?, resolving: Long?, onResolve: (SyncExceptionRow) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            rows == null -> item { Text("Loading…") }
            rows.isEmpty() -> item { Text("No open sync exceptions. Every entry the counters sent was accepted.") }
            else -> item {
                Text(
                    "Entries the server could not take as sent. Check each one, correct it in the records if needed, then mark it resolved.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(rows.orEmpty(), key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(
                            (if (e.isBill) "Bill " else "Check-in ") + e.ref.ifEmpty { e.transId.take(8) },
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(DateTimes.displayText(e.createdAt), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(e.reason, color = MaterialTheme.colorScheme.error)
                    if (e.userName.isNotEmpty()) Text("Sent by ${e.userName}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onResolve(e) }, enabled = resolving == null) {
                        Text(if (resolving == e.id) "Marking…" else "Mark resolved")
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordGate(error: String?, onUnlock: (String) -> Unit, modifier: Modifier) {
    var password by rememberSaveable { mutableStateOf("") }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Enter the report password", style = MaterialTheme.typography.titleLarge)
        ErrorBanner(error)
        PasswordField(password, { password = it }, "Report password", imeAction = ImeAction.Done, onDone = { onUnlock(password) })
        BigButton("Open reports", onClick = { onUnlock(password) }, enabled = password.isNotEmpty())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Filters(
    from: LocalDate,
    to: LocalDate,
    users: List<ReportUser>,
    canChooseUser: Boolean,
    userId: Long?,
    shifts: List<ReportShift>,
    shiftId: Long?,
    onFrom: (LocalDate) -> Unit,
    onTo: (LocalDate) -> Unit,
    onUser: (Long?) -> Unit,
    onShift: (Long?) -> Unit,
) {
    var picking by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picking = "from" }, modifier = Modifier.weight(1f)) { Text("From ${from.format(DATE)}") }
            OutlinedButton(onClick = { picking = "to" }, modifier = Modifier.weight(1f)) { Text("To ${to.format(DATE)}") }
        }
        if (canChooseUser) {
            FilterDropdown(
                label = "User",
                selected = userId,
                options = listOf<Pair<Long?, String>>(null to "All users") + users.map { it.id to "${it.userCode} ${it.userName}" },
                onSelect = onUser,
            )
        }
        FilterDropdown(
            label = "Shift",
            selected = shiftId,
            options = listOf<Pair<Long?, String>>(null to "All shifts") + shifts.map { it.id to it.label },
            onSelect = onShift,
        )
    }

    picking?.let { which ->
        val initial = if (which == "from") from else to
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        // The picker works in UTC midnight millis.
                        val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        if (which == "from") onFrom(date) else onTo(date)
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}

/** A read-only dropdown; the option key null means "all". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(label: String, selected: Long?, options: List<Pair<Long?, String>>, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: options.first().second,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key); expanded = false })
            }
        }
    }
}

/** " · Morning", or nothing when the row has no shift. */
private fun shiftSuffix(shift: String) = if (shift.isEmpty()) "" else " · $shift"

@Composable
private fun CheckinList(rows: List<CheckinRow>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) item { Text("No check-ins in this period.") }
        else item { Text("${rows.size} check-in(s), ${rows.sumOf { it.bags }} bag(s)", style = MaterialTheme.typography.titleMedium) }
        items(rows, key = { it.transId }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(r.receiptNo, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (r.isOut) "Out" else "In store")
                    }
                    Text("${r.name} · ${r.mobile} · ${r.bags} bag(s)")
                    Text("${DateTimes.displayText(r.dateTimeIn)} · ${r.userCode}${shiftSuffix(r.shift)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BillList(rows: List<BillRow>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) item { Text("No bills in this period.") }
        items(rows, key = { it.transId }) { b ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(b.billNo, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("₹${money(b.netAmt)}", fontWeight = FontWeight.Bold)
                    }
                    Text("${b.name} · receipt ${b.receiptNo} · ${b.bags} bag(s)")
                    Text(
                        "${DateTimes.displayText(b.dateTimeOut)} · ${b.userCode}${shiftSuffix(b.shift)} · ${if (b.payMode == PayMode.UPI) "UPI" else "Cash"}" +
                            if (b.printed) "" else " · not printed",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Collection(data: ReportData) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (data.bills.isEmpty()) item { Text("No bills in this period.") }
        items(data.byPayMode) { CollectionCard(if (it.payMode == PayMode.UPI) "UPI" else "Cash", it) }
        item { CollectionCard("Total", data.total) }
    }
}

@Composable
private fun CollectionCard(title: String, l: CollectionLine) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Line("Bills", l.bills.toString())
            Line("Rent", money(l.baseAmt))
            Line("Penalty", money(l.penaltyAmt))
            Line("Taxable value", money(l.taxableAmt))
            Line("CGST", money(l.cgst))
            Line("SGST", money(l.sgst))
            Line("IGST", money(l.igst))
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row {
                Text("Collected", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("₹${money(l.netAmt)}", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value)
    }
}

private fun money(a: BigDecimal): String = a.setScale(2, RoundingMode.HALF_UP).toPlainString()
