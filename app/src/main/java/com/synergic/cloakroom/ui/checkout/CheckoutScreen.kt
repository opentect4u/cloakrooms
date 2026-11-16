package com.synergic.cloakroom.ui.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synergic.cloakroom.domain.billing.BillResult
import com.synergic.cloakroom.domain.checkout.CheckoutField
import com.synergic.cloakroom.domain.print.BillFormatter
import com.synergic.cloakroom.domain.repository.CheckoutBag
import com.synergic.cloakroom.domain.settings.GstType
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.ui.components.AppTopBar
import com.synergic.cloakroom.ui.components.BigButton
import com.synergic.cloakroom.ui.components.ErrorBanner
import com.synergic.cloakroom.ui.components.LoadingBox
import com.synergic.cloakroom.ui.print.QrImage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Check-out step 2: bag details, live bill, penalty, Cash/UPI, Bill Print / Out Without Bill Print. */
@Composable
fun CheckoutScreen(
    onBack: () -> Unit,
    onSaved: (transId: String, print: Boolean) -> Unit,
    onViewBill: (transId: String) -> Unit,
    viewModel: CheckoutViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(s.savedTransId) {
        s.savedTransId?.let {
            viewModel.onBillOpened()
            onSaved(it, s.savedPrint)
        }
    }

    Scaffold(topBar = { AppTopBar("Check-out", onBack = onBack) }) { padding ->
        val bag = s.bag
        val settings = s.settings
        if (s.loading || settings == null) {
            LoadingBox(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (bag == null) {
                Text("This bag was not found.", style = MaterialTheme.typography.titleMedium)
                return@Column
            }
            BagDetails(bag, s.now)
            if (bag.isOut) {
                ErrorBanner("This bag is already checked out${bag.billNo?.let { " (bill $it)" } ?: ""}.")
                if (bag.billNo != null) BigButton(text = "View bill", onClick = { onViewBill(bag.transId) })
                return@Column
            }
            ErrorBanner(s.message)

            if (settings.penaltyFlag) {
                OutlinedTextField(
                    value = s.penalty,
                    onValueChange = viewModel::onPenalty,
                    label = { Text("Penalty (₹, max ${money(settings.penaltyMaxAmt)})") },
                    singleLine = true,
                    isError = s.errors[CheckoutField.PENALTY] != null,
                    supportingText = s.errors[CheckoutField.PENALTY]?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            s.bill?.let { BillCard(it) }

            Text("Payment", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = s.payMode == PayMode.CASH,
                    onClick = { viewModel.onPayMode(PayMode.CASH) },
                    label = { Text("Cash", style = MaterialTheme.typography.titleMedium) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                if (settings.upiEnabled) {
                    FilterChip(
                        selected = s.payMode == PayMode.UPI,
                        onClick = { viewModel.onPayMode(PayMode.UPI) },
                        label = { Text("UPI", style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            s.errors[CheckoutField.PAY_MODE]?.let { ErrorBanner(it) }

            if (s.payMode == PayMode.UPI) {
                val link = s.upiLink
                if (link != null && s.bill != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Scan to pay ₹${money(s.bill!!.netAmt)}", style = MaterialTheme.typography.titleMedium)
                            Text("To ${s.payee?.vpa}", style = MaterialTheme.typography.bodyMedium)
                            QrImage(link, Modifier.fillMaxWidth().padding(top = 8.dp), sizeDp = 220)
                        }
                    }
                } else if (s.payee == null) {
                    ErrorBanner("No UPI ID is set for this cloak room, so no payment QR can be shown.")
                }
            }

            BigButton(
                text = "Bill Print",
                onClick = { viewModel.requestCheckout(printBill = true) },
                enabled = s.bill != null,
                loading = s.saving,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (settings.outWithoutPrintFlag) {
                OutlinedButton(
                    onClick = { viewModel.requestCheckout(printBill = false) },
                    enabled = s.bill != null && !s.saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text("Out Without Bill Print", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }

    s.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirm,
            title = { Text("Collect ₹${money(c.net)} by ${if (c.payMode == PayMode.UPI) "UPI" else "cash"}?") },
            text = {
                Text(
                    if (c.printBill) "The bag is checked out and the bill is printed."
                    else "The bag is checked out and the bill is saved without printing."
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmCheckout) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirm) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BagDetails(bag: CheckoutBag, now: Instant) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(bag.receiptNo, style = MaterialTheme.typography.headlineSmall)
            Text("${bag.name} · ${bag.mobile}", style = MaterialTheme.typography.bodyLarge)
            Text("PNR ${bag.pnr.ifEmpty { "-" }} · ${bag.bags} bag(s)", style = MaterialTheme.typography.bodyLarge)
            if (bag.extraBags > 0) {
                // Easy to see at the counter, so the extra bags are handed back too.
                Text(
                    "+ ${bag.extraBags} extra bag(s) (free, not in the bill)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (bag.idProofName != null && bag.idNumber.isNotEmpty()) {
                Text("${bag.idProofName} ${bag.idNumber}", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Checked in ${DateTimes.displayText(bag.dateTimeIn)}", style = MaterialTheme.typography.bodyMedium)
            if (!bag.isOut) {
                Text(
                    "Stored ${BillFormatter.storedTime(bag.dateTimeIn, DateTimes.toDbText(now))}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (bag.fromQr != null) {
                Text("Checked in on another counter (from QR).", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BillCard(b: BillResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Bill", style = MaterialTheme.typography.titleMedium)
            Line("${b.blocks} × ${b.blockHours} h for ${b.bags} bag(s)", "")
            Line("First ${b.blockHours} h: ${b.bags} × ₹${money(b.firstBlockRate)}", money(b.firstBlockRate * BigDecimal(b.bags)))
            if (b.blocks > 1) {
                Line(
                    "Next ${b.blocks - 1} × ${b.blockHours} h: ${b.bags} × ₹${money(b.nextBlockRate)}",
                    money(b.nextBlockRate * BigDecimal(b.bags) * BigDecimal(b.blocks - 1)),
                )
            }
            Line("Rent", money(b.baseAmt))
            if (b.penaltyAmt.signum() > 0) Line("Penalty", money(b.penaltyAmt))
            if (b.gstFlag) {
                Line("Taxable value", money(b.taxableAmt))
                if (b.gstType == GstType.IGST) {
                    Line("IGST ${pct(b.gstPct)}%", money(b.igst))
                } else {
                    val half = b.gstPct.divide(BigDecimal(2))
                    Line("CGST ${pct(half)}%", money(b.cgst))
                    Line("SGST ${pct(half)}%", money(b.sgst))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row {
                Text("TOTAL", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("₹${money(b.netAmt)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (b.gstFlag) {
                Text(
                    if (b.gstInclusive) "GST included in the rate" else "GST added on top of the rate",
                    style = MaterialTheme.typography.bodySmall,
                )
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

private fun pct(p: BigDecimal): String = p.stripTrailingZeros().toPlainString()
