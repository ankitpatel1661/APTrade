package com.aptrade.android.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.aptrade.android.AppGraph
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.chart.ChartLegend
import com.aptrade.android.ui.chart.DualLineChart
import com.aptrade.android.ui.money
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.BacktestReport
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.l10n.L10n
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

private enum class WizardStep { Name, Slices, Schedule, Backtest }

private val ONE_HUNDRED_PP: BigDecimal = BigDecimal.parseString("100")

/** Per-step gate for the "Next" button — full 100%-weight validation is `canSave`'s job
 *  (enforced only at Save, with the live weight-sum footer as the in-step hint); this only
 *  blocks advancing past a step that's structurally incomplete. Mirrors desktop
 *  `PieWizardDialog.kt`'s `canAdvance`. */
private fun canAdvance(step: WizardStep, state: PieWizardUiState): Boolean = when (step) {
    WizardStep.Name -> state.name.trim().isNotEmpty()
    WizardStep.Slices -> state.slices.isNotEmpty()
    WizardStep.Schedule, WizardStep.Backtest -> true
}

private fun formatSignedPercent(value: BigDecimal): String {
    val d = value.toDisplayDouble()
    val sign = if (d > 0) "+" else if (d < 0) "-" else ""
    return String.format(Locale.US, "%s%.2f%%", sign, kotlin.math.abs(d))
}

/**
 * Four-step Pie creation/edit sheet: name -> slice allocation -> contribution schedule -> DCA
 * backtest preview. Android [ModalBottomSheet] counterpart of desktop `PieWizardDialog.kt`'s
 * `PieWizardDialog` (a fixed 480x600dp [androidx.compose.ui.window.Dialog] there — a
 * near-fullscreen bottom sheet here, the app's own modal convention). All validation and
 * persistence live in [PieWizardViewModel]; this composable only renders its published state
 * and forwards user input.
 *
 * Owns its own [PieWizardViewModel] instance, `remember`-keyed on [existingPie] exactly like
 * desktop keys its own VM/scope on the same parameter (`PieWizardDialog.kt`) — the caller
 * ([PlansSection]) only ever includes this composable in the tree while the sheet is open, so a
 * fresh key here means a fresh instance each time the sheet opens, never a stale one carried
 * over from a previous aborted create/edit. [PieWizardViewModel] is a plain [androidx.lifecycle.ViewModel]
 * subclass constructed directly (not via `ViewModelProvider`/the `viewModel {}` composable,
 * unlike this screen's other VMs) specifically so it is NOT cached in the host Activity's
 * `ViewModelStore` beyond this composable's own lifetime — that cache would otherwise return the
 * SAME stale instance (with a previous session's unsaved draft) the next time "Create Pie" is
 * tapped. [viewModelScope] still works on a directly-constructed `ViewModel` (the scope is a tag
 * stored on the instance itself, not on any `ViewModelStore`); [DisposableEffect] cancels it on
 * dismiss, mirroring desktop's own `DisposableEffect(existingPie) { onDispose { scope.cancel() } }`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PieWizardSheet(existingPie: Pie?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val portfolio = AppGraph.portfolio
    val viewModel = remember(existingPie) {
        PieWizardViewModel(
            existingPie = existingPie,
            savePie = portfolio.savePie,
            simulateDCA = portfolio.simulateDCA,
            searchAssets = AppGraph.fetchSearch,
            calendar = portfolio.marketCalendar,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        )
    }
    DisposableEffect(existingPie) { onDispose { viewModel.viewModelScope.cancel() } }

    val state by viewModel.state.collectAsState()
    var step by remember { mutableStateOf(WizardStep.Name) }
    var searchQuery by remember { mutableStateOf("") }
    var backtestYears by remember { mutableStateOf(1) }
    val isEditing = existingPie != null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Mirrors PieWizardDialog.kt's `LaunchedEffect(step, backtestYears)` on the backtest step:
    // reruns whenever `years` changes, AND whenever the step first becomes Backtest (so results
    // always reflect the wizard's CURRENT slices/amount, never a stale snapshot).
    LaunchedEffect(step, backtestYears) {
        if (step == WizardStep.Backtest) viewModel.runBacktest(backtestYears)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            WizardHeader(isEditing = isEditing, onCancel = onDismiss)
            StepIndicator(step)
            HorizontalDivider()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (step) {
                    WizardStep.Name -> NameStep(state.name) { viewModel.setName(it) }
                    WizardStep.Slices -> SlicesStep(
                        state = state,
                        searchQuery = searchQuery,
                        onQueryChange = { text -> searchQuery = text; viewModel.updateSearchQuery(text) },
                        onAddSlice = { asset -> viewModel.addSlice(asset); searchQuery = "" },
                        onRemoveSlice = viewModel::removeSlice,
                        onSetWeight = viewModel::setWeight,
                        onEqualSplit = viewModel::equalSplit,
                    )
                    WizardStep.Schedule -> ScheduleStep(
                        state = state,
                        onToggle = viewModel::setScheduleEnabled,
                        onAmountChange = viewModel::setScheduleAmountText,
                        onCadenceChange = viewModel::setCadence,
                        onStartDayChange = viewModel::setScheduleStartDay,
                    )
                    WizardStep.Backtest -> BacktestStep(
                        report = state.backtest,
                        years = backtestYears,
                        onYearsChange = { backtestYears = it },
                        onRunBacktest = { scope.launch { viewModel.runBacktest(backtestYears) } },
                    )
                }
            }
            HorizontalDivider()
            WizardBottomNav(
                step = step,
                canAdvance = canAdvance(step, state),
                canSave = state.canSave,
                onBack = { step = WizardStep.entries[step.ordinal - 1] },
                onNext = { step = WizardStep.entries[step.ordinal + 1] },
                onSave = { scope.launch { if (viewModel.save()) onSaved() } },
            )
        }
    }
}

// MARK: - Header / step indicator

@Composable
private fun WizardHeader(isEditing: Boolean, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (isEditing) tr(L10n.Key.EditPlan) else tr(L10n.Key.CreatePlan),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(tr(L10n.Key.Cancel)) }
    }
}

@Composable
private fun StepIndicator(step: WizardStep) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
        StepLabel(tr(L10n.Key.Name), step == WizardStep.Name, Modifier.weight(1f))
        StepLabel(tr(L10n.Key.StepSlicesTitle), step == WizardStep.Slices, Modifier.weight(1f))
        StepLabel(tr(L10n.Key.StepScheduleTitle), step == WizardStep.Schedule, Modifier.weight(1f))
        StepLabel(tr(L10n.Key.BacktestTitle), step == WizardStep.Backtest, Modifier.weight(1f))
    }
}

@Composable
private fun StepLabel(title: String, selected: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.height(2.dp).fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

// MARK: - Step 1: Name

@Composable
private fun NameStep(name: String, onNameChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(tr(L10n.Key.PieNameLabel)) },
            placeholder = { Text(tr(L10n.Key.PieNamePlaceholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// MARK: - Step 2: Slices

@Composable
private fun SlicesStep(
    state: PieWizardUiState,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onAddSlice: (Asset) -> Unit,
    onRemoveSlice: (String) -> Unit,
    onSetWeight: (String, BigDecimal) -> Unit,
    onEqualSplit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            label = { Text(tr(L10n.Key.SearchAssetsToAddPlaceholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.searchResults.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                for (asset in state.searchResults) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onAddSlice(asset) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                asset.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(asset.symbol, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(L10n.Key.SliceWeights),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEqualSplit, enabled = state.slices.isNotEmpty()) {
                Text(tr(L10n.Key.EqualSplit))
            }
        }
        if (state.slices.isEmpty()) {
            Text(
                tr(L10n.Key.NoSlicesYetHint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (slice in state.slices) {
                    SliceEditorRow(
                        slice = slice,
                        onRemove = { onRemoveSlice(slice.symbol) },
                        onSetWeight = { pp -> onSetWeight(slice.symbol, pp) },
                    )
                }
            }
        }
        WeightSumFooter(state.weightSumPP)
    }
}

@Composable
private fun SliceEditorRow(slice: PieSlice, onRemove: () -> Unit, onSetWeight: (BigDecimal) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(slice.symbol, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        WeightStepper(value = slice.targetWeightPP, onChange = onSetWeight)
        Text(
            "✕",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onRemove).padding(4.dp),
        )
    }
}

/** Hand-rolled -/+ stepper (Compose Material3 has no native `Stepper`), 1pp per tap, clamped
 *  0..100 — mirrors desktop `PieWizardDialog.kt`'s `WeightStepper`. */
@Composable
private fun WeightStepper(value: BigDecimal, onChange: (BigDecimal) -> Unit) {
    val one = BigDecimal.ONE
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepGlyphButton("−") { val next = value - one; onChange(if (next < BigDecimal.ZERO) BigDecimal.ZERO else next) }
        Text(
            "${value.toDisplayDouble().let { Math.round(it) }}%",
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(46.dp),
        )
        StepGlyphButton("+") { val next = value + one; onChange(if (next > ONE_HUNDRED_PP) ONE_HUNDRED_PP else next) }
    }
}

@Composable
private fun StepGlyphButton(glyph: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Text(glyph, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WeightSumFooter(sum: BigDecimal) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(tr(L10n.Key.WeightSumLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${Math.round(sum.toDisplayDouble())}%",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (sum.compareTo(ONE_HUNDRED_PP) == 0) GainGreen else LossRed,
        )
    }
}

// MARK: - Step 3: Schedule

@Composable
private fun ScheduleStep(
    state: PieWizardUiState,
    onToggle: (Boolean) -> Unit,
    onAmountChange: (String) -> Unit,
    onCadenceChange: (PieCadence) -> Unit,
    onStartDayChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(L10n.Key.RecurringContributionToggle),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.scheduleEnabled, onCheckedChange = onToggle)
        }
        if (state.scheduleEnabled) {
            OutlinedTextField(
                value = state.scheduleAmountText,
                onValueChange = onAmountChange,
                label = { Text(tr(L10n.Key.ContributionAmountLabel)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    tr(L10n.Key.CadenceLabel).uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CadencePicker(state.cadence, onCadenceChange)
            }
            // Desktop-acceptable simplification (no DatePicker requirement): a plain
            // yyyy-MM-dd text field — PieWizardViewModel.isValidScheduleStartDay validates;
            // this view surfaces no separate inline error, matching how `scheduleAmountText`
            // is likewise only gated through `canSave`/`Next`. Mirrors PieWizardDialog.kt.
            OutlinedTextField(
                value = state.scheduleStartDay,
                onValueChange = onStartDayChange,
                label = { Text(tr(L10n.Key.ScheduleStartDay)) },
                placeholder = { Text("yyyy-MM-dd") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CadencePicker(selected: PieCadence, onSelect: (PieCadence) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PieCadence.entries.forEachIndexed { index, cadence ->
            SegmentedButton(
                selected = cadence == selected,
                onClick = { onSelect(cadence) },
                shape = SegmentedButtonDefaults.itemShape(index, PieCadence.entries.size),
            ) { Text(cadenceLabel(cadence)) }
        }
    }
}

// MARK: - Step 4: Backtest

@Composable
private fun BacktestStep(report: BacktestReport?, years: Int, onYearsChange: (Int) -> Unit, onRunBacktest: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            YearsPicker(years, onYearsChange, Modifier.weight(1f))
            TextButton(onClick = onRunBacktest) { Text(tr(L10n.Key.RunBacktest)) }
        }
        if (report != null) {
            BacktestChart(report)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatTile(tr(L10n.Key.BacktestInvested), money(report.totalInvested.amountText))
                StatTile(
                    tr(L10n.Key.BacktestValue),
                    money(report.finalValue.amountText),
                    valueColor = if (report.finalValue.amount >= report.totalInvested.amount) GainGreen else LossRed,
                )
                StatTile(
                    tr(L10n.Key.TotalReturn),
                    formatSignedPercent(report.totalReturnPP),
                    valueColor = if (report.totalReturnPP.toDisplayDouble() < 0) LossRed else GainGreen,
                )
            }
            Text(
                "${tr(L10n.Key.BacktestLumpSum)}: ${money(report.lumpSumFinalValue.amountText)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                tr(L10n.Key.BacktestInsufficient),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YearsPicker(years: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        listOf(1, 3, 5).forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == years,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, 3),
            ) { Text("${option}Y") }
        }
    }
}

/** Invested + value lines over the backtest window, reusing this app's existing
 *  [DualLineChart]/[ChartLegend] (which already share ONE min/max scale across both series) —
 *  the "reuse the app's chart composables" instruction for the backtest pane, replacing
 *  desktop `PieWizardDialog.kt`'s hand-rolled two-series `Canvas`. */
@Composable
private fun BacktestChart(report: BacktestReport) {
    val investedValues = report.points.map { it.invested.amount.toDisplayDouble() }
    val valueValues = report.points.map { it.value.amount.toDisplayDouble() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DualLineChart(
            primary = valueValues,
            secondary = investedValues,
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        if (investedValues.size >= 2) {
            ChartLegend(primaryLabel = tr(L10n.Key.BacktestValue), secondaryLabel = tr(L10n.Key.BacktestInvested))
        }
    }
}

// MARK: - Footer navigation

@Composable
private fun WizardBottomNav(
    step: WizardStep,
    canAdvance: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (step != WizardStep.Name) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(tr(L10n.Key.Back)) }
        }
        if (step == WizardStep.Backtest) {
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.weight(1f)) { Text(tr(L10n.Key.SaveAction)) }
        } else {
            Button(onClick = onNext, enabled = canAdvance, modifier = Modifier.weight(1f)) { Text(tr(L10n.Key.Next)) }
        }
    }
}
