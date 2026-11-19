package com.synergic.cloakroom.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.report.DashboardData
import com.synergic.cloakroom.domain.repository.AuthRepository
import com.synergic.cloakroom.domain.repository.ReportRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.repository.SubscriptionRepository
import com.synergic.cloakroom.domain.repository.SyncRepository
import com.synergic.cloakroom.domain.subscription.Subscription
import com.synergic.cloakroom.domain.subscription.SubscriptionStatus
import com.synergic.cloakroom.domain.repository.SyncStatus
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.ui.components.AppTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    settingsRepository: SettingsRepository,
    reportRepository: ReportRepository,
    private val syncRepository: SyncRepository,
    subscriptionRepository: SubscriptionRepository,
) : ViewModel() {
    /** Renewal notice (last 10 days) and the expired block, from md_customer.renewal_dt. */
    val subscription: StateFlow<SubscriptionStatus> = subscriptionRepository.observeStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscriptionStatus.Active)

    /** Live: changes as soon as a bag goes in or out, or an admin saves settings. */
    val dashboard: StateFlow<DashboardData?> = reportRepository.observeDashboard()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings?> = settingsRepository.currentSettings

    /** Entries not yet on the server, and the last sync. */
    val pending: StateFlow<Int> = syncRepository.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val syncStatus: StateFlow<SyncStatus> = syncRepository.status

    private val _logoutMessage = MutableStateFlow<String?>(null)
    /** Why logout was refused (entries still waiting to be sent). */
    val logoutMessage: StateFlow<String?> = _logoutMessage.asStateFlow()

    private val _loggingOut = MutableStateFlow(false)
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    fun logout() {
        if (_loggingOut.value) return
        _loggingOut.value = true
        viewModelScope.launch {
            runCatching { authRepository.logout() }.onFailure { _logoutMessage.value = it.message ?: "Logout failed." }
            _loggingOut.value = false
        }
    }

    fun clearLogoutMessage() {
        _logoutMessage.value = null
    }

    fun syncNow() {
        viewModelScope.launch { syncRepository.syncNow() }
    }
}

/**
 * Start screen after login. Admin-only buttons (Users, Masters) are hidden from normal users; their
 * Settings button opens only the Print tab, and Reports only shows for them when REPORT_FLAG = Y.
 * The repositories refuse them as well.
 */
@Composable
fun DashboardScreen(
    session: UserSession,
    onCheckin: () -> Unit,
    onCheckout: () -> Unit,
    onBagsInStore: () -> Unit,
    onReports: () -> Unit,
    onSettings: () -> Unit,
    onUsers: () -> Unit,
    onMasters: () -> Unit,
    onPrinterSetup: () -> Unit,
    onChangePassword: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val logoutMessage by viewModel.logoutMessage.collectAsStateWithLifecycle()
    val subscription by viewModel.subscription.collectAsStateWithLifecycle()
    val expired = subscription is SubscriptionStatus.Expired
    val showReports = session.isAdmin || settings?.reportFlag == true

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Cloak Room",
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Change password") }, onClick = { menuOpen = false; onChangePassword() })
                        DropdownMenuItem(text = { Text("Logout") }, onClick = { menuOpen = false; confirmLogout = true })
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SubscriptionBanner(subscription)
            UserCard(session, onLogout = { confirmLogout = true })
            SyncCard(pending, syncStatus, onSyncNow = viewModel::syncNow)

            // Admins do no counter work (the repositories refuse it too).
            if (settings?.canWorkCounter(session.userType) == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onCheckin,
                        enabled = !expired,
                        modifier = Modifier.weight(1f).heightIn(min = 96.dp),
                    ) { Text("Check-in", style = MaterialTheme.typography.headlineSmall) }
                    Button(
                        onClick = onCheckout,
                        enabled = !expired,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f).heightIn(min = 96.dp),
                    ) { Text("Check-out", style = MaterialTheme.typography.headlineSmall) }
                }
            }

            dashboard?.let { d ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onBagsInStore),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Bags in store now", style = MaterialTheme.typography.titleMedium)
                        Text("${d.bagsInStore}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text("on ${d.receiptsInStore} receipt(s) · tap to see the list", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Today${if (d.allUsers) " (all users)" else " (you)"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Check-ins", d.todayCheckins.toString(), Modifier.weight(1f))
                    StatCard("Bills", d.todayBills.toString(), Modifier.weight(1f))
                }
                d.todayCollection?.let { StatCard("Collection", "₹${money(it)}", Modifier.fillMaxWidth()) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onBagsInStore, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text("Bags in store")
                }
                if (showReports) {
                    FilledTonalButton(onClick = onReports, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text("Reports")
                    }
                }
                // Admins have Settings under Admin; normal users open only its Print tab.
                if (!session.isAdmin) {
                    FilledTonalButton(onClick = onSettings, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text("Settings")
                    }
                }
            }
            FilledTonalButton(onClick = onPrinterSetup, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Printer setup: Bluetooth printer, test print")
            }
            if (session.isAdmin) {
                Text("Admin", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onSettings, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Settings") }
                    FilledTonalButton(onClick = onUsers, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Users") }
                }
                FilledTonalButton(onClick = onMasters, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Masters: ID proofs, header / footer, shifts")
                }
            }
            settings?.let { TariffCard(it) }
        }
    }

    logoutMessage?.let { m ->
        AlertDialog(
            onDismissRequest = viewModel::clearLogoutMessage,
            title = { Text("Cannot log out yet") },
            text = { Text(m) },
            confirmButton = { TextButton(onClick = viewModel::clearLogoutMessage) { Text("OK") } },
        )
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

/** Red when the subscription has ended, amber in its last 10 days; nothing otherwise. */
@Composable
private fun SubscriptionBanner(status: SubscriptionStatus) {
    val text = Subscription.notice(status) ?: return
    val expired = status is SubscriptionStatus.Expired
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (expired) MaterialTheme.colorScheme.errorContainer else WARNING_BG,
            contentColor = if (expired) MaterialTheme.colorScheme.onErrorContainer else WARNING_FG,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (expired) "Subscription expired" else "Renewal due",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val WARNING_BG = Color(0xFFFFF3CD)
private val WARNING_FG = Color(0xFF5C4400)

@Composable
private fun UserCard(session: UserSession, onLogout: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(session.userName, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${session.userId} · ${session.userCode} · ${if (session.isAdmin) "Admin" else "Counter user"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (session.sessionStartedAt.isNotEmpty()) {
                    Text("Since ${DateTimes.displayText(session.sessionStartedAt)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onLogout) { Text("Logout") }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TariffCard(s: AppSettings) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Current tariff", style = MaterialTheme.typography.titleSmall)
            Text(
                com.synergic.cloakroom.domain.billing.RateSlabs.describe(s.rateSlabs, "₹"),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (s.advanceFlag) {
                Text("Advance of the first ${s.advanceHours} h is taken at check-in", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                when {
                    !s.gstFlag -> "No GST"
                    s.gstInclusive -> "Rates include ${s.gstPct.toPlainString()}% GST"
                    else -> "${s.gstPct.toPlainString()}% GST is added on top"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun money(a: BigDecimal): String = a.setScale(2, RoundingMode.HALF_UP).toPlainString()

/** Entries waiting for the server (sync_flag = N) and the last sync; the sync itself runs by itself. */
@Composable
private fun SyncCard(pending: Int, status: SyncStatus, onSyncNow: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        status.running -> "Syncing…"
                        pending == 0 -> "All entries are on the server"
                        pending == 1 -> "1 entry waiting to be sent"
                        else -> "$pending entries waiting to be sent"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    status.lastError ?: status.lastSyncAt?.let { "Last sync ${DateTimes.displayText(it)}" } ?: "Sent by itself when online",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSyncNow, enabled = !status.running) { Text("Sync now") }
        }
    }
}
