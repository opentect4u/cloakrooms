package com.synergic.cloakroom.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.synergic.cloakroom.ui.components.CompactField
import com.synergic.cloakroom.ui.components.ErrorBanner
import com.synergic.cloakroom.ui.components.LoadingBox
import java.math.BigDecimal
import java.math.RoundingMode

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
    val advance by viewModel.advance.collectAsStateWithLifecycle()

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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ErrorBanner(form.message)
            AdvanceBadge(on = s.advanceFlag, hours = s.advanceHours, amount = advance)

            Section("Passenger") {
                CompactField(form.pnr, viewModel::onPnr, label("PNR", s.pnrRequired), form.errors[CheckinField.PNR],
                    KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next))
                CompactField(form.name, viewModel::onName, label("Passenger name", true), form.errors[CheckinField.NAME],
                    KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
                CompactField(form.mobile, viewModel::onMobile, label("Mobile number", true), form.errors[CheckinField.MOBILE],
                    KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next))
            }

            Section("Bags") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallLabel("Bags * (max ${s.maxBagsPerReceipt})")
                        Stepper(
                            value = form.bags, onValueChange = viewModel::onBags, onStep = viewModel::stepBags,
                            error = form.errors[CheckinField.BAGS], lessLabel = "One bag less", moreLabel = "One bag more",
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallLabel("Extra bags (free)")
                        Stepper(
                            value = form.extraBags, onValueChange = viewModel::onExtraBags, onStep = viewModel::stepExtraBags,
                            error = form.errors[CheckinField.EXTRA_BAGS], lessLabel = "One extra bag less", moreLabel = "One extra bag more",
                        )
                    }
                }
            }

            Section("ID proof") {
                IdProofDropdown(
                    options = idProofs,
                    selected = form.idProofId,
                    label = label("ID proof type", s.idProofRequired),
                    error = form.errors[CheckinField.ID_PROOF],
                    onSelect = viewModel::onIdProof,
                )
                CompactField(
                    form.idNumber, viewModel::onIdNumber, label("ID number", s.idProofRequired), form.errors[CheckinField.ID_NUMBER],
                    KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    hint = if (s.idMaskFlag || idProofs.any { it.id == form.idProofId && it.masked }) "Only the last 4 characters are saved and printed." else null,
                )
            }

            BigButton(
                text = if (s.checkinPrintFlag) "Save & print receipt" else "Save",
                onClick = viewModel::save,
                loading = form.saving,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Advance status (Settings > Billing, set by the Admin) and, when on, the cash to collect now. */
@Composable
private fun AdvanceBadge(on: Boolean, hours: Int, amount: BigDecimal?) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (on) colors.primaryContainer else colors.surfaceVariant,
        contentColor = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant,
    ) {
        Text(
            when {
                !on -> "Advance: Off"
                amount != null -> "Collect advance ₹${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()} in cash (first $hours h)"
                else -> "Advance: On · $hours hour(s)"
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** A titled group of fields in an outlined card. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SmallLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/** A number with − / + buttons on each side, and its error underneath. */
@Composable
private fun Stepper(
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    error: String?,
    lessLabel: String,
    moreLabel: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalIconButton(onClick = { onStep(-1) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Remove, contentDescription = lessLabel)
        }
        CompactField(
            value = value,
            onChange = onValueChange,
            label = null,
            error = null,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(onClick = { onStep(1) }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Add, contentDescription = moreLabel)
        }
    }
    error?.let { ErrorText(it) }
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
        CompactField(
            value = options.firstOrNull { it.id == selected }?.name.orEmpty(),
            onChange = {},
            label = label,
            error = error,
            readOnly = true,
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
