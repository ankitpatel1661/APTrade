package com.aptrade.android.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.l10n.L10n
import kotlin.math.abs

/** Modal price-alert sheet — the Compose-Android port of desktop's `PriceAlertSheet.kt` /
 *  macOS's `PriceAlertSheet.swift`, using a Material3 [ModalBottomSheet] (mirrors this app's
 *  `TradeSheet` anatomy rather than desktop's dim-scrim overlay Box, since a bottom sheet is
 *  the native Android idiom for this kind of modal form).
 *
 *  Shows: asset header, an existing-alerts list (bell/delete, struck-through once triggered),
 *  a segmented Price above / Price below / % move control, a labeled decimal field, and an
 *  "Add Alert" button gated on [AlertFormState.isValid]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceAlertSheet(
    symbol: String,
    name: String,
    currentPriceText: String?,
    existing: List<PriceAlert>,
    onCreate: (AlertCondition) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var kind by remember { mutableStateOf(AlertKind.Below) }
    var priceText by remember { mutableStateOf("") }
    var percentText by remember { mutableStateOf("5") }

    val form = AlertFormState(kind, priceText, percentText)
    val canAdd = form.isValid()
    val addAlert = {
        form.toCondition()?.let(onCreate)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        symbol,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                currentPriceText?.let {
                    AssistChip(onClick = {}, label = { Text(trf(L10n.Key.CurrentPriceFormat, "$$it")) })
                }
            }

            if (existing.isNotEmpty()) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (alert in existing) {
                        ExistingAlertRow(alert = alert, onDelete = { onDelete(alert.id) })
                    }
                }
                HorizontalDivider()
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = kind == AlertKind.Above,
                    onClick = { kind = AlertKind.Above },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                ) { Text(tr(L10n.Key.PriceAboveKind)) }
                SegmentedButton(
                    selected = kind == AlertKind.Below,
                    onClick = { kind = AlertKind.Below },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                ) { Text(tr(L10n.Key.PriceBelowKind)) }
                SegmentedButton(
                    selected = kind == AlertKind.Percent,
                    onClick = { kind = AlertKind.Percent },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                ) { Text(tr(L10n.Key.PercentMoveKind)) }
            }

            when (kind) {
                AlertKind.Above, AlertKind.Below -> OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text(tr(L10n.Key.TargetPriceLabel)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                AlertKind.Percent -> OutlinedTextField(
                    value = percentText,
                    onValueChange = { percentText = it },
                    label = { Text(tr(L10n.Key.DailyMoveLabel)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TextButton(
                onClick = addAlert,
                enabled = canAdd,
                modifier = Modifier.align(Alignment.End),
            ) { Text(tr(L10n.Key.AddAlert)) }
        }
    }
}

@Composable
private fun ExistingAlertRow(alert: PriceAlert, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            alertSummary(alert.condition),
            style = MaterialTheme.typography.bodyMedium,
            color = if (alert.isTriggered) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (alert.isTriggered) {
                androidx.compose.ui.text.style.TextDecoration.LineThrough
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Localized existing-alert row text — mirrors desktop `PriceAlertSheet.kt`'s
 *  `alertSummary`, computed here rather than via `AlertCondition.summary` (the shared
 *  commonMain getter is deliberately English-only). */
private fun alertSummary(condition: AlertCondition): String = when (condition) {
    is AlertCondition.PriceAbove -> trf(L10n.Key.PriceAboveSummaryFormat, condition.threshold.formatted)
    is AlertCondition.PriceBelow -> trf(L10n.Key.PriceBelowSummaryFormat, condition.threshold.formatted)
    is AlertCondition.PercentChange -> trf(L10n.Key.PercentMoveSummaryFormat, abs(condition.magnitude))
}
