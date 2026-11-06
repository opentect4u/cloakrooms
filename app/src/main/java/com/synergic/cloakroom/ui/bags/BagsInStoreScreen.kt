package com.synergic.cloakroom.ui.bags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.print.BillFormatter
import com.synergic.cloakroom.domain.report.CheckinRow
import com.synergic.cloakroom.data.repository.RemoteReportRepositoryFactory
import com.synergic.cloakroom.domain.repository.LuggageRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.LoadingBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class BagsInStoreState(
    val query: String = "",
    /** Null while loading. */
    val bags: List<CheckinRow>? = null,
    val totalReceipts: Int = 0,
)

@HiltViewModel
class BagsInStoreViewModel @Inject constructor(
    luggageRepository: LuggageRepository,
    remoteReports: RemoteReportRepositoryFactory,
    private val time: TimeSource,
    settingsRepository: SettingsRepository,
    sessionProvider: SessionProvider,
) : ViewModel() {
    private val query = MutableStateFlow("")

    /** False for admins (they do no counter work): no "Check out" button. */
    val canCheckout: StateFlow<Boolean> = settingsRepository.currentSettings
        .map { s -> s?.canWorkCounter(sessionProvider.current?.userType.orEmpty()) == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The station admin (online only) sees all counters' bags from the server; counter users the phone's list. */
    private val source = sessionProvider.current?.takeIf { it.isAdmin && it.customerId != null }
        ?.let { remoteReports.own.observeBagsInStore() } ?: luggageRepository.observeBagsInStore()

    val state: StateFlow<BagsInStoreState> = combine(source, query) { all, q ->
        val needle = q.trim().lowercase()
        val shown = if (needle.isEmpty()) all else all.filter {
            listOf(it.receiptNo, it.name, it.mobile, it.pnr).any { field -> field.lowercase().contains(needle) }
        }
        BagsInStoreState(q, shown, all.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BagsInStoreState())

    val queryText: StateFlow<String> = query.asStateFlow()

    fun onQuery(q: String) {
        query.value = q
    }

    fun nowText(): String = DateTimes.toDbText(time.now())
}

/** Every bag still in store (all users), with search. Each bag can be checked out or its receipt reprinted. */
@Composable
fun BagsInStoreScreen(
    onBack: () -> Unit,
    onCheckout: (transId: String) -> Unit,
    onReprint: (transId: String) -> Unit,
    viewModel: BagsInStoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val canCheckout by viewModel.canCheckout.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppTopBar("Bags in store", onBack = onBack) }) { padding ->
        val bags = state.bags
        if (bags == null) {
            LoadingBox(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val now = viewModel.nowText()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQuery,
                    label = { Text("Search PNR, mobile, name or receipt no.") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "${bags.sumOf { it.bags }} bag(s) on ${bags.size} of ${state.totalReceipts} receipt(s)",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.totalReceipts == 0) {
                item { Text("No bags in store.", style = MaterialTheme.typography.bodyLarge) }
            } else if (bags.isEmpty()) {
                item { Text("No bag matches \"$query\".", style = MaterialTheme.typography.bodyLarge) }
            }
            items(bags, key = { it.transId }) { bag ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${bag.receiptNo} · ${bag.bags} bag(s)", style = MaterialTheme.typography.titleLarge)
                        Text("${bag.name} · ${bag.mobile}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "PNR ${bag.pnr.ifEmpty { "-" }} · in ${DateTimes.displayText(bag.dateTimeIn)} by ${bag.userCode}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Stored ${BillFormatter.storedTime(bag.dateTimeIn, now)}", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (canCheckout) Button(onClick = { onCheckout(bag.transId) }) { Text("Check out") }
                            OutlinedButton(onClick = { onReprint(bag.transId) }) { Text("Reprint receipt") }
                        }
                    }
                }
            }
        }
    }
}
