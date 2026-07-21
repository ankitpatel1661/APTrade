package com.aptrade.desktop.screener

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.desktop.plans.DialogButton
import com.aptrade.desktop.plans.WizardTextField
import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.ScreenComparison
import com.aptrade.shared.domain.ScreenCondition
import com.aptrade.shared.domain.ScreenerMetric
import com.aptrade.shared.l10n.L10n
import java.text.DecimalFormatSymbols
import java.util.UUID

// MARK: - ScreenBuilderModel

/**
 * Local editing state for the custom screen builder dialog: a name, an ordered list of draft
 * conditions, and the validation that gates Save. Deliberately store-free — it only shapes a
 * [CustomScreen] in memory; [ScreenerViewModel.saveScreen]/[ScreenerViewModel.deleteScreen] own
 * the actual persistence, and [ScreenBuilderDialog] wires this model to those calls.
 *
 * Compose port of `Sources/APTradeApp/ScreenBuilderSheet.swift`'s `ScreenBuilderModel` AS-BUILT.
 *
 * DELIBERATE DIVERGENCE from the Swift twin's per-row `Binding` plumbing: [ConditionDraft]'s
 * fields are Compose `mutableStateOf`-backed `var`s on a reference-type class (not a Swift
 * `struct` copied in/out of an index/id-keyed `Binding`), so a picker's `onSelect` or a text
 * field's `onValueChange` can mutate the draft object directly (e.g. `draft.metric = it`) —
 * Compose's snapshot-state system tracks that write exactly like the Swift `@Observable` macro
 * tracks a struct-array element write, with none of `ScreenBuilderSheet`'s
 * `metricBinding(for:)`/`comparisonBinding(for:)`/`thresholdBinding(for:)` id-lookup machinery
 * needed. [conditions] itself is a `val` [androidx.compose.runtime.snapshots.SnapshotStateList]
 * (mutated in place via `add`/`removeAll`/`clear`), not a reassignable Swift `var
 * [ConditionDraft]` — tests that reset the Swift twin's array (`model.conditions = []`) instead
 * call `model.conditions.clear()` here.
 */
class ScreenBuilderModel(
    existingScreen: CustomScreen? = null,
    /** The locale's decimal separator. Injected for testability; defaults to the JVM's active
     *  locale (desktop is jvmMain, so `java.text.DecimalFormatSymbols` is safe to use
     *  directly). Gates comma-normalization in [parsedThreshold]: only replace "," with "."
     *  when the locale's separator is ",". See that function's doc for the full rationale. */
    val decimalSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator,
) {

    /** One condition row's editable state. `thresholdText` is free-form user input —
     *  intentionally a `String`, not a `Double` — so a row can sit in an unparseable,
     *  mid-typing state (e.g. "-", "") without discarding what the user has typed so far;
     *  validation and parsing happen at [isValid]/[buildScreen], not on every keystroke. */
    class ConditionDraft(
        val id: String = UUID.randomUUID().toString(),
        metric: ScreenerMetric = ScreenerMetric.price,
        comparison: ScreenComparison = ScreenComparison.Above,
        thresholdText: String = "",
    ) {
        var metric: ScreenerMetric by mutableStateOf(metric)
        var comparison: ScreenComparison by mutableStateOf(comparison)
        var thresholdText: String by mutableStateOf(thresholdText)
    }

    var name: String by mutableStateOf("")

    /** Mutated in place (`add`/`removeAll`/`clear`) rather than reassigned — see this class's
     *  doc for why a `SnapshotStateList` replaces the Swift twin's reassignable array. */
    val conditions = mutableStateListOf<ConditionDraft>()

    /** The id a saved [CustomScreen] will carry: the original screen's id when editing, or a
     *  freshly generated one for a brand-new screen — decided once at construction so it stays
     *  stable across edits within one dialog session. */
    val screenId: String
    val isEditing: Boolean

    init {
        if (existingScreen != null) {
            screenId = existingScreen.id
            name = existingScreen.name
            conditions.addAll(
                existingScreen.conditions.map {
                    ConditionDraft(metric = it.metric, comparison = it.comparison, thresholdText = textFor(it.threshold))
                },
            )
            isEditing = true
        } else {
            screenId = UUID.randomUUID().toString()
            conditions.add(ConditionDraft())
            isEditing = false
        }
    }

    /** Non-empty trimmed name, at least one condition row, and every row's threshold a
     *  parseable number — the exact three rules Task 8's brief calls out. */
    val isValid: Boolean
        get() = trimmedName().isNotEmpty() && conditions.isNotEmpty() &&
            conditions.all { parsedThreshold(it.thresholdText) != null }

    fun addCondition() {
        conditions.add(ConditionDraft())
    }

    fun removeCondition(id: String) {
        conditions.removeAll { it.id == id }
    }

    /** The [CustomScreen] this draft represents, or `null` while [isValid] is false — callers
     *  (the dialog's Save button) should treat a `null` result as "can't save yet" rather than
     *  force-unwrap. */
    fun buildScreen(): CustomScreen? {
        if (!isValid) return null
        val built = conditions.mapNotNull { draft ->
            val threshold = parsedThreshold(draft.thresholdText) ?: return@mapNotNull null
            ScreenCondition(metric = draft.metric, comparison = draft.comparison, threshold = threshold)
        }
        return CustomScreen(id = screenId, name = trimmedName(), conditions = built)
    }

    /** Best-effort conditions for the dialog's LIVE match-count preview: rows with a
     *  still-being-typed (unparseable) threshold are skipped rather than blocking the whole
     *  preview — unlike [buildScreen], which requires every row valid before it will produce
     *  anything at all. A user midway through typing a second condition's threshold still sees
     *  a live count reflecting the rows that already parse. */
    val matchableConditions: List<ScreenCondition>
        get() = conditions.mapNotNull { draft ->
            val threshold = parsedThreshold(draft.thresholdText) ?: return@mapNotNull null
            ScreenCondition(metric = draft.metric, comparison = draft.comparison, threshold = threshold)
        }

    private fun trimmedName(): String = name.trim()

    /** Parses a threshold field's raw text as a `Double`. Normalizes a comma decimal separator
     *  to a dot BEFORE parsing ONLY when [decimalSeparator] is ',' (e.g. DE/IT/ES locales).
     *  Gating on the locale prevents silent corruption of US-style grouped numerals (e.g.
     *  "1,500" on an EN/dot locale now correctly fails to parse instead of becoming 1.5). This
     *  is a simple, predictable normalization (not a full locale-aware parse) — good enough for
     *  a plain decimal threshold, and it correctly still rejects genuine garbage like "1,5.2"
     *  (becomes "1.5.2", which fails to parse either way). */
    private fun parsedThreshold(text: String): Double? {
        val trimmed = text.trim()
        val normalized = if (decimalSeparator == ',') trimmed.replace(',', '.') else trimmed
        return normalized.toDoubleOrNull()
    }

    companion object {
        /** Pre-fill text for an existing condition's threshold — whole numbers render without
         *  a trailing ".0" (e.g. `30` not `30.0`), matching how a user would actually type it. */
        private fun textFor(threshold: Double): String =
            if (threshold == Math.floor(threshold) && !threshold.isInfinite()) threshold.toLong().toString() else threshold.toString()
    }
}

// MARK: - Metric labels

/** Every [ScreenerMetric] case's builder-picker label. Distinct from `ScreenerPane.kt`'s
 *  `activeMetricColumn(metric)` mapping, which deliberately returns `null` for `.price`/
 *  `.dayChangePercent` (those already have dedicated result-table columns) — the builder's
 *  metric picker has no such exclusion, since every metric is a valid thing to condition a
 *  screen on. Transcribed from `ScreenBuilderSheet.swift`'s `screenerMetricLabelKey`. */
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

// MARK: - ScreenBuilderDialog

/**
 * Create/edit dialog for a custom screen: name, an editable list of AND-combined conditions, a
 * live match count against the current snapshot, and Save / (when editing) Delete. Compose port
 * of `Sources/APTradeApp/ScreenBuilderSheet.swift`. All validation lives in [ScreenBuilderModel];
 * this composable only renders its state and forwards user input. Follows [PieWizardDialog]'s
 * dialog idioms — see `desktopApp/.../plans/PieWizardDialog.kt` — a real [Dialog] with a scrim,
 * Escape-to-dismiss, and a fixed-size panel (460x600dp, matching the Swift twin's
 * `.frame(width: 460, height: 600)`) rather than `fillMaxSize()`.
 *
 * [existingScreen] is `null` for a brand-new screen (the model seeds one blank condition row);
 * non-`null` pre-fills every field from the screen being edited. [onDelete] is `null` unless the
 * caller is editing an existing saved screen — the Delete button (behind a destructive confirm,
 * see [DeleteScreenConfirmDialog]) only appears when both `model.isEditing` and [onDelete] are
 * non-null, exactly mirroring the Swift twin's own double gate. [onDismiss] is called after a
 * successful Save or a confirmed Delete (mirroring the Swift twin's `save()`/confirm-button both
 * calling `dismiss()`), so callers only need [onSave]/[onDelete] to do their persistence work —
 * closing the dialog is this composable's job, not theirs.
 */
@Composable
fun ScreenBuilderDialog(
    existingScreen: CustomScreen?,
    matchCount: (List<ScreenCondition>) -> Int,
    onDismiss: () -> Unit,
    onSave: (CustomScreen) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val model = remember(existingScreen) { ScreenBuilderModel(existingScreen = existingScreen) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun handleSave() {
        val screen = model.buildScreen() ?: return
        onSave(screen)
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(460.dp)
                    .height(600.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DK.surface)
                    .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss(); true
                        } else {
                            false
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            ) {
                BuilderHeader(isEditing = model.isEditing, onCancel = onDismiss)
                Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        NameField(model.name) { model.name = it }
                        ConditionsSection(model)
                        MatchCountFooter(matchCount(model.matchableConditions))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
                BuilderActions(
                    canSave = model.isValid,
                    isEditing = model.isEditing,
                    canDelete = onDelete != null,
                    onSave = ::handleSave,
                    onDeleteRequest = { showDeleteConfirm = true },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteScreenConfirmDialog(
            onConfirm = {
                showDeleteConfirm = false
                onDelete?.invoke()
                onDismiss()
            },
            onCancel = { showDeleteConfirm = false },
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
            style = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DK.textPrimary),
            modifier = Modifier.weight(1f),
        )
        Text(
            tr(L10n.Key.Cancel),
            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DK.textSecondary),
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
        )
    }
}

// MARK: - Name

@Composable
private fun NameField(name: String, onNameChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            tr(L10n.Key.ScreenerScreenName).uppercase(),
            style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = DK.textTertiary),
        )
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DK.surfaceHi).padding(14.dp)) {
            WizardTextField(value = name, onValueChange = onNameChange, placeholder = tr(L10n.Key.ScreenerScreenName), fontSize = 18.sp)
        }
    }
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
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricPicker(selected = draft.metric, onSelect = { draft.metric = it })
            Spacer(Modifier.weight(1f))
            // Removing the last remaining row would leave the builder with zero conditions —
            // always invalid — so the remove action only appears once a second row exists to
            // fall back to.
            if (canRemove) {
                Text(
                    "✕",
                    style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = DK.textTertiary),
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onRemove() },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ComparisonPicker(selected = draft.comparison, onSelect = { draft.comparison = it })
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(DK.surfaceHi).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                WizardTextField(value = draft.thresholdText, onValueChange = { draft.thresholdText = it }, placeholder = "0", fontSize = 14.sp)
            }
        }
    }
}

/** No existing multi-option (>2) picker precedent in this codebase covers 10 metrics without a
 *  dropdown (`PieWizardDialog`'s `CadencePicker`/`YearsPicker` segmented-row style only scales
 *  to a handful of options) — `androidx.compose.material3.DropdownMenu` is used here instead,
 *  themed via the app's `MaterialTheme` colorScheme (`DK.surface`/`DK.textPrimary`, see
 *  `designkit/DK.kt`'s `dkColorScheme()`), so it reads consistently with the rest of the app. */
@Composable
private fun MetricPicker(selected: ScreenerMetric, onSelect: (ScreenerMetric) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(DK.surfaceHi)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                tr(screenerMetricLabelKey(selected)),
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
            )
            Spacer(Modifier.width(6.dp))
            Text("▾", style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, color = DK.textTertiary))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (metric in ScreenerMetric.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            tr(screenerMetricLabelKey(metric)),
                            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = DK.textPrimary),
                        )
                    },
                    onClick = { onSelect(metric); expanded = false },
                )
            }
        }
    }
}

/** Two-option segmented picker, styled like `PieWizardDialog.kt`'s `CadencePicker`/
 *  `YearsPicker`. */
@Composable
private fun ComparisonPicker(selected: ScreenComparison, onSelect: (ScreenComparison) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        for (comparison in ScreenComparison.entries) {
            val isSelected = comparison == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) DK.surfaceHi else Color.Transparent)
                    .then(if (isSelected) Modifier.border(1.dp, DK.gold.copy(alpha = 0.40f), RoundedCornerShape(50)) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(comparison) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    tr(screenerComparisonLabelKey(comparison)),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) DK.textPrimary else DK.textSecondary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun AddConditionButton(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
    ) {
        Text("+", style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.gold))
        Text(
            tr(L10n.Key.ScreenerAddCondition),
            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.gold),
        )
    }
}

// MARK: - Match count

@Composable
private fun MatchCountFooter(count: Int) {
    Text(
        trf(L10n.Key.ScreenerMatchCountFmt, count.toString()),
        style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DK.textSecondary),
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
        DialogButton(tr(L10n.Key.ScreenerSaveScreen), primary = true, enabled = canSave, modifier = Modifier.fillMaxWidth()) { onSave() }
        if (isEditing && canDelete) {
            DestructiveTextButton(tr(L10n.Key.ScreenerDeleteScreen), onClick = onDeleteRequest)
        }
    }
}

@Composable
private fun DestructiveTextButton(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(DK.down.copy(alpha = 0.12f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(label, style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DK.down))
    }
}

// MARK: - Delete confirm dialog

/** Destructive confirm before deleting a saved screen — same shape as `PlansPane.kt`'s
 *  `DeletePlanConfirmDialog` (the house confirm-before-delete idiom): a real [Dialog] scrim,
 *  Escape-to-cancel, Cancel + destructive-styled confirm buttons. The Swift twin's
 *  `confirmationDialog` uses the SAME `tr(.screenerDeleteScreen)` string for both the title and
 *  the destructive button label — reproduced verbatim here rather than inventing a separate
 *  title copy. */
@Composable
private fun DeleteScreenConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(360.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DK.surface)
                    .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onCancel(); true
                        } else {
                            false
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    tr(L10n.Key.ScreenerDeleteScreen),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    DialogButton(tr(L10n.Key.Cancel), primary = false, modifier = Modifier.weight(1f)) { onCancel() }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(DK.down.copy(alpha = 0.16f))
                            .border(1.dp, DK.down.copy(alpha = 0.4f), RoundedCornerShape(50))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onConfirm() }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(
                            tr(L10n.Key.ScreenerDeleteScreen),
                            style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DK.down),
                        )
                    }
                }
            }
        }
    }
}
