package com.synergic.cloakroom.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.domain.checkin.CheckinField
import com.synergic.cloakroom.domain.repository.IdProof
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.BigButton
import com.synergic.cloakroom.ui.components.ErrorBanner
import com.synergic.cloakroom.ui.components.LoadingBox

/** Check-in form. On save, [onSaved] opens the receipt preview. */
@Composable
fun CheckinScreen(
    onBack: () -> Unit,
    onSaved: (transId: String) -> Unit,
    viewModel: CheckinViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val idProofs by viewModel.idProofs.collectAsStateWithLifecycle()

    LaunchedEffect(form.savedTransId) {
        form.savedTransId?.let {
            viewModel.onReceiptOpened()
            onSaved(it)
        }
    }

    Scaffold(topBar = { AppTopBar("Check-in", onBack = onBack) }) { padding ->
        val s = settings
        if (s == null) {
            LoadingBox(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        fun label(text: String, required: Boolean) = if (required) "$text *" else text

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorBanner(form.message)
            Field(form.pnr, viewModel::onPnr, label("PNR", s.pnrRequired), form.errors[CheckinField.PNR],
                KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next))
            Field(form.name, viewModel::onName, label("Passenger name", true), form.errors[CheckinField.NAME],
                KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
            Field(form.mobile, viewModel::onMobile, label("Mobile number", true), form.errors[CheckinField.MOBILE],
                KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next))

            Text("Number of bags * (max ${s.maxBagsPerReceipt})")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = { viewModel.stepBags(-1) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "One bag less")
                }
                OutlinedTextField(
                    value = form.bags,
                    onValueChange = viewModel::onBags,
                    singleLine = true,
                    isError = form.errors[CheckinField.BAGS] != null,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(onClick = { viewModel.stepBags(1) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "One bag more")
                }
            }
            form.errors[CheckinField.BAGS]?.let { ErrorText(it) }

            IdProofDropdown(
                options = idProofs,
                selected = form.idProofId,
                label = label("ID proof type", s.idProofRequired),
                error = form.errors[CheckinField.ID_PROOF],
                onSelect = viewModel::onIdProof,
            )
            Field(
                form.idNumber, viewModel::onIdNumber, label("ID number", s.idProofRequired), form.errors[CheckinField.ID_NUMBER],
                KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                hint = if (s.idMaskFlag || idProofs.any { it.id == form.idProofId && it.masked }) "Only the last 4 characters are saved and printed." else null,
            )

            BigButton(
                text = if (s.checkinPrintFlag) "Save & print receipt" else "Save",
                onClick = viewModel::save,
                loading = form.saving,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboard: KeyboardOptions,
    hint: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdProofDropdown(
    options: List<IdProof>,
    selected: Long?,
    label: String,
    error: String?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.id == selected }?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { proof ->
                DropdownMenuItem(
                    text = { Text(proof.name) },
                    onClick = { onSelect(proof.id); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
