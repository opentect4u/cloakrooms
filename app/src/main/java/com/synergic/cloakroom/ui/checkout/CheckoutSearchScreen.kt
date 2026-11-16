package com.synergic.cloakroom.ui.checkout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.domain.repository.BillRepository
import com.synergic.cloakroom.domain.repository.CheckoutBag
import com.synergic.cloakroom.domain.repository.QrLookup
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.bagText
import com.synergic.cloakroom.ui.components.ErrorBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where to go next: a bag on this device, or a bag known only from its QR. */
sealed interface CheckoutTarget {
    data class Local(val transId: String) : CheckoutTarget
    data class FromQr(val qrText: String) : CheckoutTarget
}

data class CheckoutSearchState(
    val query: String = "",
    val results: List<CheckoutBag> = emptyList(),
    val searched: Boolean = false,
    val scanning: Boolean = false,
    val message: String? = null,
    val open: CheckoutTarget? = null,
)

@HiltViewModel
class CheckoutSearchViewModel @Inject constructor(
    private val billRepository: BillRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CheckoutSearchState())
    val state: StateFlow<CheckoutSearchState> = _state.asStateFlow()
    private var searchJob: Job? = null

    fun onQuery(q: String) {
        _state.update { it.copy(query = q, message = null) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300) // wait until the operator stops typing
            search()
        }
    }

    fun search() {
        val q = _state.value.query
        viewModelScope.launch {
            val results = runCatching { billRepository.search(q) }.getOrElse { emptyList() }
            _state.update { it.copy(results = results, searched = q.isNotBlank()) }
        }
    }

    fun startScan() = _state.update { it.copy(scanning = true, message = null) }
    fun stopScan() = _state.update { it.copy(scanning = false) }

    fun onScanned(text: String) {
        _state.update { it.copy(scanning = false) }
        viewModelScope.launch {
            val message = when (val r = runCatching { billRepository.lookupQr(text) }.getOrNull()) {
                is QrLookup.Found -> {
                    val target = if (r.bag.fromQr == null) CheckoutTarget.Local(r.bag.transId) else CheckoutTarget.FromQr(text)
                    _state.update { it.copy(open = target) }
                    null
                }
                QrLookup.OtherCustomer -> "This receipt belongs to another cloak room."
                QrLookup.NotAReceipt, null -> "This QR is not a cloak room receipt. Try again or search instead."
            }
            _state.update { it.copy(message = message) }
        }
    }

    fun onOpened() = _state.update { it.copy(open = null) }

    /** Called when the screen is shown again, so a bag checked out meanwhile shows as out. */
    fun refresh() {
        if (_state.value.query.isNotBlank()) search()
    }
}

/** Check-out step 1: find the bag by search or by scanning the receipt QR. */
@Composable
fun CheckoutSearchScreen(
    onBack: () -> Unit,
    onOpen: (CheckoutTarget) -> Unit,
    viewModel: CheckoutSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.open) {
        state.open?.let {
            viewModel.onOpened()
            onOpen(it)
        }
    }

    if (state.scanning) {
        QrScanner(onResult = viewModel::onScanned, onClose = viewModel::stopScan)
        return
    }

    Scaffold(topBar = { AppTopBar("Check-out", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Button(
                    onClick = viewModel::startScan,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Text("  Scan receipt QR", style = MaterialTheme.typography.titleLarge)
                }
            }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQuery,
                    label = { Text("PNR, mobile, name or receipt no.") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { ErrorBanner(state.message) }
            if (state.searched && state.results.isEmpty()) {
                item { Text("No bags found for \"${state.query}\".", style = MaterialTheme.typography.bodyLarge) }
            }
            items(state.results, key = { it.transId }) { bag ->
                BagCard(bag, onClick = { onOpen(CheckoutTarget.Local(bag.transId)) })
            }
        }
    }
}

@Composable
private fun BagCard(bag: CheckoutBag, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bag.receiptNo, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                AssistChip(onClick = onClick, label = { Text(if (bag.isOut) "Out · ${bag.billNo ?: ""}" else "In store") })
            }
            Text("${bag.name} · ${bag.mobile}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "PNR ${bag.pnr.ifEmpty { "-" }} · ${bagText(bag.bags, bag.extraBags)} · in ${DateTimes.displayText(bag.dateTimeIn)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
