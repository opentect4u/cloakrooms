package com.synergic.cloakroom.ui.print

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.BigButton
import com.synergic.cloakroom.ui.components.LoadingBox

/**
 * Preview of a receipt or bill with Print and Share PDF. [nextLabel] / [onNext] start the next
 * transaction ("New check-in", "Next check-out"), shown only to users who may do counter work;
 * [onDone] goes back to the start screen.
 */
@Composable
fun DocumentScreen(
    nextLabel: String,
    onNext: () -> Unit,
    onDone: () -> Unit,
    viewModel: DocumentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canWorkCounter by viewModel.canWorkCounter.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    BackHandler(onBack = onDone)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.shareIntent) {
        state.shareIntent?.let {
            context.startActivity(it)
            viewModel.onShareStarted()
        }
    }

    val title = if (viewModel.kind == DocumentKind.BILL) "Bill" else "Receipt"
    Scaffold(
        topBar = { AppTopBar(title, onBack = onDone) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.document != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = viewModel::print,
                                enabled = !state.printing,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Text("  Print")
                            }
                            OutlinedButton(onClick = viewModel::sharePdf, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Text("  Share PDF")
                            }
                        }
                        if (canWorkCounter) BigButton(text = nextLabel, onClick = onNext)
                        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Done") }
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.fillMaxSize().padding(padding))
            state.document == null -> Text(
                "$title not found.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                PrintDocumentView(state.document!!)
            }
        }
    }
}
