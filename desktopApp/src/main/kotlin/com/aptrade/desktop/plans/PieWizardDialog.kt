package com.aptrade.desktop.plans

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aptrade.desktop.AppGraph
import com.aptrade.desktop.LocalAppGraph
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.DKSwitch
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.MagnifierIcon
import com.aptrade.desktop.designkit.StatTile
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.l10n.tr
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.BacktestReport
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.l10n.L10n
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

private enum class WizardStep { Name, Slices, Schedule, Backtest }

private val ONE_HUNDRED_PP: BigDecimal = BigDecimal.parseString("100")

/** Per-step gate for the "Next" button — full 100%-weight validation is `canSave`'s job
 *  (enforced only at Save, with the live weight-sum footer as the in-step hint); this only
 *  blocks advancing past a step that's structurally incomplete. Transcribed from
 *  `PieWizardView.swift`'s `canAdvance`. */
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
 * Four-step Pie creation/edit dialog: name -> slice allocation -> contribution schedule ->
 * DCA backtest preview. Compose port of `Sources/APTradeApp/PieWizardView.swift`. All
 * validation and persistence live in [PieWizardViewModel]; this composable only renders its
 * published state and forwards user input. Owns its own VM instance and scope, keyed on
 * [existingPie] so switching between "create" and "edit an existing pie" (a different dialog
 * instance each time it's opened — see `PlansPane.kt`) never reuses stale form state.
 *
 * Renders via [Dialog] — see [PlansPane]'s doc comment for why every Plans modal uses the real
 * `Dialog` composable rather than an in-composition scrim, and a fixed 480x600dp panel
 * (matching macOS's `.frame(width: 480, height: 600)`) rather than `fillMaxSize()`.
 */
@Composable
fun PieWizardDialog(existingPie: Pie?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val graph: AppGraph = LocalAppGraph.current
    val scope = remember(existingPie) { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val viewModel = remember(existingPie) { graph.makePieWizardViewModel(existingPie = existingPie, scope = scope) }
    DisposableEffect(existingPie) { onDispose { scope.cancel() } }

    val state by viewModel.state.collectAsState()
    var step by remember { mutableStateOf(WizardStep.Name) }
    var searchQuery by remember { mutableStateOf("") }
    var backtestYears by remember { mutableStateOf(1) }
    val isEditing = existingPie != null

    // Mirrors PieWizardView.swift's `.task(id: backtestYears)` on the backtest step: reruns
    // whenever `years` changes, AND whenever the step first becomes Backtest (so results always
    // reflect the wizard's CURRENT slices/amount, never a stale snapshot from wizard-open time).
    LaunchedEffect(step, backtestYears) {
        if (step == WizardStep.Backtest) viewModel.runBacktest(backtestYears)
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
                    .width(480.dp)
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
                WizardHeader(isEditing = isEditing, onCancel = onDismiss)
                StepIndicator(step)
                Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
                Box(Modifier.weight(1f)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
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
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
                NavigationBar(
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

@Composable
private fun StepIndicator(step: WizardStep) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
        StepLabel(tr(L10n.Key.Name), step == WizardStep.Name)
        StepLabel(tr(L10n.Key.StepSlicesTitle), step == WizardStep.Slices)
        StepLabel(tr(L10n.Key.StepScheduleTitle), step == WizardStep.Schedule)
        StepLabel(tr(L10n.Key.BacktestTitle), step == WizardStep.Backtest)
    }
}

@Composable
private fun RowScope.StepLabel(title: String, selected: Boolean) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = if (selected) DK.gold else DK.textSecondary,
            ),
        )
        Box(
            Modifier.height(2.dp).fillMaxWidth().clip(RoundedCornerShape(1.dp))
                .background(if (selected) DK.gold else DK.hairline),
        )
    }
}

// MARK: - Step 1: Name

@Composable
private fun NameStep(name: String, onNameChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            tr(L10n.Key.PieNameLabel).uppercase(),
            style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
        )
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DK.surfaceHi).padding(16.dp)) {
            WizardTextField(value = name, onValueChange = onNameChange, placeholder = tr(L10n.Key.PieNamePlaceholder), fontSize = 22.sp)
        }
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
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SearchField(searchQuery, onQueryChange)
        if (state.searchResults.isNotEmpty()) {
            SearchResultsList(state.searchResults, onAddSlice)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(L10n.Key.SliceWeights).uppercase(),
                style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
                modifier = Modifier.weight(1f),
            )
            Text(
                tr(L10n.Key.EqualSplit),
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (state.slices.isEmpty()) DK.textTertiary else DK.gold,
                ),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = state.slices.isNotEmpty(),
                ) { onEqualSplit() },
            )
        }
        if (state.slices.isEmpty()) {
            Text(
                tr(L10n.Key.NoSlicesYetHint),
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = DK.textSecondary),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (slice in state.slices) {
                    SliceEditorRow(slice = slice, onRemove = { onRemoveSlice(slice.symbol) }, onSetWeight = { pp -> onSetWeight(slice.symbol, pp) })
                }
            }
        }
        WeightSumFooter(state.weightSumPP)
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MagnifierIcon(tint = DK.textTertiary)
        WizardTextField(value = query, onValueChange = onQueryChange, placeholder = tr(L10n.Key.SearchAssetsToAddPlaceholder), fontSize = 14.sp)
    }
}

@Composable
private fun SearchResultsList(results: List<Asset>, onSelect: (Asset) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DK.surface).border(1.dp, DK.hairline, RoundedCornerShape(10.dp)),
    ) {
        for (asset in results) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(asset) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        asset.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
                    )
                    Text(
                        asset.symbol,
                        style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary, fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SliceEditorRow(slice: PieSlice, onRemove: () -> Unit, onSetWeight: (BigDecimal) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DK.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            slice.symbol,
            style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
            modifier = Modifier.weight(1f),
        )
        WeightStepper(value = slice.targetWeightPP, onChange = onSetWeight)
        Text(
            "✕",
            style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = DK.textTertiary),
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onRemove() },
        )
    }
}

/** Hand-rolled -/+ stepper (Compose has no native `Stepper`), 1pp per tap, clamped 0..100 —
 *  transcribed from `PieWizardView.swift`'s `Stepper(value:in: 0...100, step: 1)`. */
@Composable
private fun WeightStepper(value: BigDecimal, onChange: (BigDecimal) -> Unit) {
    val one = BigDecimal.ONE
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepGlyphButton("−") { val next = value - one; onChange(if (next < BigDecimal.ZERO) BigDecimal.ZERO else next) }
        Text(
            "${value.toDisplayDouble().let { Math.round(it) }}%",
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary, fontFeatureSettings = "tnum"),
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
            .width(22.dp).height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(DK.surfaceHi)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
    ) {
        Text(glyph, style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.textSecondary))
    }
}

@Composable
private fun WeightSumFooter(sum: BigDecimal) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr(L10n.Key.WeightSumLabel),
            style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = DK.textSecondary),
        )
        Text(
            "${Math.round(sum.toDisplayDouble())}%",
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (sum.compareTo(ONE_HUNDRED_PP) == 0) DK.up else DK.down,
                fontFeatureSettings = "tnum",
            ),
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
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(L10n.Key.RecurringContributionToggle),
                style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, color = DK.textPrimary),
                modifier = Modifier.weight(1f),
            )
            DKSwitch(checked = state.scheduleEnabled, onCheckedChange = onToggle)
        }
        if (state.scheduleEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    tr(L10n.Key.ContributionAmountLabel).uppercase(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
                )
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DK.surfaceHi).padding(16.dp)) {
                    WizardTextField(value = state.scheduleAmountText, onValueChange = onAmountChange, placeholder = "0", fontSize = 22.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    tr(L10n.Key.CadenceLabel).uppercase(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
                )
                CadencePicker(state.cadence, onCadenceChange)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    tr(L10n.Key.ScheduleStartDay).uppercase(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
                )
                // Desktop-acceptable simplification (no DatePicker requirement): a plain
                // yyyy-MM-dd text field. PieWizardViewModel.isValidScheduleStartDay validates —
                // this view surfaces no separate inline error, matching how `scheduleAmountText`
                // is likewise only gated through `canSave`/`Next`.
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DK.surface)
                        .border(1.dp, DK.hairline, RoundedCornerShape(12.dp)).padding(16.dp),
                ) {
                    WizardTextField(value = state.scheduleStartDay, onValueChange = onStartDayChange, placeholder = "yyyy-MM-dd", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun CadencePicker(selected: PieCadence, onSelect: (PieCadence) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        for (cadence in PieCadence.entries) {
            val isSelected = cadence == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) DK.surfaceHi else Color.Transparent)
                    .then(if (isSelected) Modifier.border(1.dp, DK.gold.copy(alpha = 0.40f), RoundedCornerShape(50)) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(cadence) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    cadenceLabel(cadence),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) DK.textPrimary else DK.textSecondary,
                    ),
                )
            }
        }
    }
}

// MARK: - Step 4: Backtest

@Composable
private fun BacktestStep(report: BacktestReport?, years: Int, onYearsChange: (Int) -> Unit, onRunBacktest: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            YearsPicker(years, onYearsChange, Modifier.weight(1f))
            Text(
                tr(L10n.Key.RunBacktest),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DK.gold),
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onRunBacktest() },
            )
        }
        if (report != null) {
            BacktestChart(report)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatTile(label = tr(L10n.Key.BacktestInvested), value = formatMoney(report.totalInvested.amountText))
                StatTile(
                    label = tr(L10n.Key.BacktestValue),
                    value = formatMoney(report.finalValue.amountText),
                    valueColor = if (report.finalValue.amount >= report.totalInvested.amount) DK.up else DK.down,
                )
                StatTile(
                    label = tr(L10n.Key.TotalReturn),
                    value = formatSignedPercent(report.totalReturnPP),
                    valueColor = if (report.totalReturnPP.toDisplayDouble() < 0) DK.down else DK.up,
                )
            }
            Text(
                "${tr(L10n.Key.BacktestLumpSum)}: ${formatMoney(report.lumpSumFinalValue.amountText)}",
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = DK.textTertiary),
            )
        } else {
            Text(
                tr(L10n.Key.BacktestInsufficient),
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = DK.textSecondary),
            )
        }
    }
}

@Composable
private fun YearsPicker(years: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        for (option in listOf(1, 3, 5)) {
            val isSelected = option == years
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) DK.surfaceHi else Color.Transparent)
                    .then(if (isSelected) Modifier.border(1.dp, DK.gold.copy(alpha = 0.40f), RoundedCornerShape(50)) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(option) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    "${option}Y",
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) DK.textPrimary else DK.textSecondary,
                    ),
                )
            }
        }
    }
}

/** Invested + value lines over the backtest window, sharing ONE min/max scale (unlike two
 *  independent calls to `Charts.kt`'s single-series `LineChart`, which would each normalize by
 *  their own min/max and lose the relative-magnitude comparison Swift's `Chart` shows via
 *  `chartForegroundStyleScale` on one shared Y-axis). Deliberate divergence, documented per
 *  house convention: same simple Canvas-polyline shape as `LineChart`, extended to two series. */
@Composable
private fun BacktestChart(report: BacktestReport) {
    val investedValues = report.points.map { it.invested.amount.toDisplayDouble() }
    val valueValues = report.points.map { it.value.amount.toDisplayDouble() }
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        if (investedValues.size < 2) return@Canvas
        val allValues = investedValues + valueValues
        val min = allValues.min()
        val max = allValues.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (investedValues.size - 1)
        fun path(values: List<Double>): Path {
            val p = Path()
            values.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - ((v - min) / span * size.height).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        drawPath(path(investedValues), DK.textSecondary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path(valueValues), DK.gold, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

// MARK: - Footer navigation

@Composable
private fun NavigationBar(
    step: WizardStep,
    canAdvance: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (step != WizardStep.Name) {
            DialogButton(tr(L10n.Key.Back), primary = false, modifier = Modifier.weight(1f)) { onBack() }
        }
        if (step == WizardStep.Backtest) {
            DialogButton(tr(L10n.Key.SaveAction), primary = true, enabled = canSave, modifier = Modifier.weight(1f)) { onSave() }
        } else {
            DialogButton(tr(L10n.Key.Next), primary = true, enabled = canAdvance, modifier = Modifier.weight(1f)) { onNext() }
        }
    }
}
