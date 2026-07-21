package com.aptrade.android.screener

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.ScreenCondition
import com.aptrade.shared.domain.ScreenComparison
import com.aptrade.shared.domain.ScreenerMetric
import com.aptrade.shared.l10n.L10n

// MARK: - Metric / comparison labels
//
// Same key mapping as desktop's `ScreenBuilderDialog.kt` (`screenerMetricLabelKey`/
// `screenerComparisonLabelKey`) — no new L10n keys, every metric already has a builder-picker
// label from the desktop feature.

private fun screenerMetricLabelKey(metric: ScreenerMetric): L10n.Key = when (metric) {
    ScreenerMetric.price -> L10n.Key.MetricPrice
    ScreenerMetric.dayChangePercent -> L10n.Key.MetricDayChange
    ScreenerMetric.rsi14 -> L10n.Key.MetricRsi
    ScreenerMetric.bollingerPercentB -> L10n.Key.MetricPercentB
    ScreenerMetric.bollingerBandwidth -> L10n.Key.MetricBandwidth
    ScreenerMetric.pctTo52wHigh -> L10n.Key.MetricTo52wHigh
    ScreenerMetric.pctTo52wLow -> L10n.Key.MetricTo52wLow
    ScreenerMetric.relativeVolume -> L10n.Key.MetricRelVolume
    ScreenerMetric.pctVsSma50 -> L10n.Key.MetricVsSma50
    ScreenerMetric.pctVsSma200 -> L10n.Key.MetricVsSma200
}

private fun screenerComparisonLabelKey(comparison: ScreenComparison): L10n.Key = when (comparison) {
    ScreenComparison.Above -> L10n.Key.ScreenerAbove
    ScreenComparison.Below -> L10n.Key.ScreenerBelow
}

// MARK: - ScreenBuilderSheet

/**
 * Create/edit sheet for a custom screen: name, an editable list of AND-combined conditions, a
 * live match count against the current snapshot, and Save / (when editing) Delete. Android
 * [ModalBottomSheet] counterpart of desktop `ScreenBuilderDialog.kt`'s `ScreenBuilderDialog` (a
 * fixed 460x600dp [androidx.compose.ui.window.Dialog] there — a near-fullscreen bottom sheet
 * here, following `PieWizardSheet.kt`'s house convention for multi-field forms). All validation
 * lives in [ScreenBuilderModel]; this composable only renders its state and forwards user input.
 *
 * [existingScreen] is `null` for a brand-new screen (the model seeds one blank condition row);
 * non-`null` pre-fills every field from the screen being edited. [onDelete] is `null` unless the
 * caller is editing an existing saved screen — the Delete button (behind a destructive confirm,
 * matching `PlansScreen.kt`'s delete-plan [AlertDialog] precedent) only appears when both
 * `model.isEditing` and [onDelete] are non-null. [onDismiss] is called after a successful Save or
 * a confirmed Delete, so callers only need [onSave]/[onDelete] to do their persistence work —
 * closing the sheet is this composable's job, not theirs.
 *
 * Owns its own [ScreenBuilderModel], `remember`-keyed on [existingScreen] exactly like desktop
 * keys its own model on the same parameter — a fresh key here means a fresh draft each time the
 * sheet opens, never one carried over from a previous aborted create/edit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenBuilderSheet(
    existingScreen: CustomScreen?,
    matchCount: (List<ScreenCondition>) -> Int,
    onDismiss: () -> Unit,
    onSave: (CustomScreen) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val model = remember(existingScreen) { ScreenBuilderModel(existingScreen = existingScreen) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun handleSave() {
        val screen = model.buildScreen() ?: return
        onSave(screen)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            BuilderHeader(isEditing = model.isEditing, onCancel = onDismiss)
            HorizontalDivider()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                NameField(model.name) { model.name = it }
                ConditionsSection(model)
                MatchCountFooter(matchCount(model.matchableConditions))
            }
            HorizontalDivider()
            BuilderActions(
                canSave = model.isValid,
                isEditing = model.isEditing,
                canDelete = onDelete != null,
                onSave = ::handleSave,
                onDeleteRequest = { showDeleteConfirm = true },
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(tr(L10n.Key.ScreenerDeleteScreen)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete?.invoke()
                    onDismiss()
                }) { Text(tr(L10n.Key.ScreenerDeleteScreen), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(tr(L10n.Key.Cancel)) }
            },
        )
    }
}

// MARK: - Header

@Composable
private fun BuilderHeader(isEditing: Boolean, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (isEditing) tr(L10n.Key.ScreenerEditScreen) else tr(L10n.Key.ScreenerNewScreen),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(tr(L10n.Key.Cancel)) }
    }
}

// MARK: - Name

@Composable
private fun NameField(name: String, onNameChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(tr(L10n.Key.ScreenerScreenName)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

// MARK: - Conditions

@Composable
private fun ConditionsSection(model: ScreenBuilderModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (draft in model.conditions) {
            ConditionRow(
                draft = draft,
                canRemove = model.conditions.size > 1,
                onRemove = { model.removeCondition(draft.id) },
            )
        }
        AddConditionButton(onClick = model::addCondition)
    }
}

@Composable
private fun ConditionRow(draft: ScreenBuilderModel.ConditionDraft, canRemove: Boolean, onRemove: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricPicker(selected = draft.metric, onSelect = { draft.metric = it })
            Spacer(Modifier.weight(1f))
            // Removing the last remaining row would leave the builder with zero conditions —
            // always invalid — so the remove action only appears once a second row exists to
            // fall back to.
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = tr(L10n.Key.ScreenerDeleteScreen))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ComparisonPicker(selected = draft.comparison, onSelect = { draft.comparison = it })
            OutlinedTextField(
                value = draft.thresholdText,
                onValueChange = { draft.thresholdText = it },
                placeholder = { Text("0") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Same Material3 [DropdownMenu] shape as desktop's `ScreenBuilderDialog.kt`'s `MetricPicker` —
 *  a clickable anchor row showing the selected label plus a dropdown caret, with a
 *  [DropdownMenu] of every [ScreenerMetric] beneath it — rather than an
 *  `ExposedDropdownMenuBox`, so both platforms' pickers read as the same control for UAT
 *  theming feedback to apply to both. */
@Composable
private fun MetricPicker(selected: ScreenerMetric, onSelect: (ScreenerMetric) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text(
                tr(screenerMetricLabelKey(selected)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(6.dp))
            Text("▾", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (metric in ScreenerMetric.entries) {
                DropdownMenuItem(
                    text = { Text(tr(screenerMetricLabelKey(metric))) },
                    onClick = { onSelect(metric); expanded = false },
                )
            }
        }
    }
}

/** Two-option segmented picker, styled like `PieWizardSheet.kt`'s `CadencePicker`/
 *  `YearsPicker` — the Android idiom for a small fixed-choice picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComparisonPicker(selected: ScreenComparison, onSelect: (ScreenComparison) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.width(150.dp)) {
        ScreenComparison.entries.forEachIndexed { index, comparison ->
            SegmentedButton(
                selected = comparison == selected,
                onClick = { onSelect(comparison) },
                shape = SegmentedButtonDefaults.itemShape(index, ScreenComparison.entries.size),
            ) { Text(tr(screenerComparisonLabelKey(comparison))) }
        }
    }
}

@Composable
private fun AddConditionButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        Text(tr(L10n.Key.ScreenerAddCondition))
    }
}

// MARK: - Match count

@Composable
private fun MatchCountFooter(count: Int) {
    Text(
        trf(L10n.Key.ScreenerMatchCountFmt, count.toString()),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// MARK: - Actions

@Composable
private fun BuilderActions(
    canSave: Boolean,
    isEditing: Boolean,
    canDelete: Boolean,
    onSave: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
            Text(tr(L10n.Key.ScreenerSaveScreen))
        }
        if (isEditing && canDelete) {
            OutlinedButton(
                onClick = onDeleteRequest,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr(L10n.Key.ScreenerDeleteScreen))
            }
        }
    }
}
