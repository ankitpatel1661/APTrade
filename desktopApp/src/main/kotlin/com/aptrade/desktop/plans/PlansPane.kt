package com.aptrade.desktop.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aptrade.desktop.AppGraph
import com.aptrade.desktop.LocalAppGraph
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.DonutChart
import com.aptrade.desktop.designkit.DonutSlice
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.StatTile
import com.aptrade.desktop.designkit.SuperscriptPrice
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.desktop.portfolio.SubmitAction
import com.aptrade.desktop.portfolio.attemptSubmit
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.RebalanceOrder
import com.aptrade.shared.domain.RebalanceSide
import com.aptrade.shared.l10n.L10n
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Colors a Pie's slices in a stable, repeatable order — the gold-family ramp plus silver,
 *  dimming a shade further each time the palette wraps so an eight-slice pie still reads as
 *  distinct rings rather than repeating identical colors. Never touches [DK.up]/[DK.down]:
 *  those stay reserved for price-direction data (drift bars), never allocation decoration.
 *  Transcribed from `Sources/APTradeApp/PlansSection.swift`'s `pieSliceColor`. Internal (not
 *  private): Kotlin top-level `private` is FILE-private, not package-private, and
 *  `PieWizardDialog.kt` (same package) reuses this for its slice-editor rows. */
internal fun pieSliceColor(index: Int): Color {
    val palette = listOf(DK.gold, DK.silver, DK.goldDeep, DK.goldLight, DK.textSecondary)
    val cycle = index / palette.size
    val opacity = (1.0 - cycle * 0.25).coerceAtLeast(0.35)
    return palette[index % palette.size].copy(alpha = opacity.toFloat())
}

/** Internal (not private) for the same cross-file reason as [pieSliceColor] —
 *  `PieWizardDialog.kt`'s schedule-step cadence picker reuses this. */
internal fun cadenceLabel(cadence: PieCadence): String = when (cadence) {
    PieCadence.Weekly -> tr(L10n.Key.CadenceWeekly)
    PieCadence.Biweekly -> tr(L10n.Key.CadenceBiweekly)
    PieCadence.Monthly -> tr(L10n.Key.CadenceMonthly)
}

internal fun BigDecimal.toDisplayDouble(): Double = doubleValue(false)

private fun formatWeight(value: BigDecimal): String =
    String.format(Locale.US, "%.2f%%", value.toDisplayDouble())

private fun formatDriftBadge(value: BigDecimal): String =
    String.format(Locale.US, "%.1f%%", value.toDisplayDouble())

private val DUE_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

private fun nextDueText(schedule: ContributionSchedule): String {
    val epochDay = PieSchedule.parseDay(schedule.nextDueDay) ?: return "—"
    return DUE_DAY_FORMATTER.format(LocalDate.ofEpochDay(epochDay))
}

/** The Portfolio tab's Plans section: a card list of the user's investment Pies plus the
 *  wizard dialog for creating/editing one and the detail screen for one Pie. Compose port of
 *  `Sources/APTradeApp/PlansSection.swift`. Owns its own [PlansViewModel] instance and
 *  single-thread-confined scope (mirrors `DetailScreen`'s per-composable VM pattern) — reads
 *  [AppGraph] via [LocalAppGraph] rather than threading VM state through `Main.kt`/`AppRoot`,
 *  since Plans is a SECTION nested inside the Portfolio tab, not a top-level tab of its own.
 *
 *  Every modal here (wizard, contribute, rebalance preview, delete confirm) renders via
 *  [Dialog] rather than an in-composition scrim `Box` — "dialogs not sheets" per the desktop
 *  UI convention. [Dialog] establishes its own measurement root anchored to the real window,
 *  sidestepping the unbounded-height constraint this pane would otherwise inherit from
 *  `PortfolioPane`'s enclosing `verticalScroll` column (a plain `Modifier.fillMaxSize()` scrim
 *  nested that deep would try to fill an effectively infinite height). */
@Composable
fun PlansPane() {
    val graph: AppGraph = LocalAppGraph.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val viewModel = remember { graph.makePlansViewModel(scope) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    LaunchedEffect(Unit) { viewModel.onAppear() }

    val state by viewModel.state.collectAsState()
    var selectedPieId by remember { mutableStateOf<String?>(null) }
    var showWizard by remember { mutableStateOf(false) }
    var editingPie by remember { mutableStateOf<Pie?>(null) }
    var showContribute by remember { mutableStateOf(false) }
    var showRebalanceSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var confirmTradesSnapshot by remember { mutableStateOf(true) }

    LaunchedEffect(selectedPieId) {
        val id = selectedPieId
        if (id != null) viewModel.openDetail(id)
    }

    val currentPieId = selectedPieId
    if (currentPieId != null) {
        PieDetailContent(
            state = state,
            pieId = currentPieId,
            onBack = { selectedPieId = null },
            onContribute = { showContribute = true },
            onRebalance = {
                viewModel.requestRebalance(currentPieId)
                scope.launch { confirmTradesSnapshot = graph.settingsStore.load().confirmTrades }
                showRebalanceSheet = true
            },
            onEdit = {
                scope.launch {
                    val pie = graph.pieStore.load().firstOrNull { it.id == currentPieId }
                    if (pie != null) {
                        editingPie = pie
                        showWizard = true
                    }
                }
            },
            onDelete = { showDeleteConfirm = true },
        )
    } else {
        PlansListContent(
            rows = state.rows,
            onSelect = { id -> selectedPieId = id },
            onCreate = { editingPie = null; showWizard = true },
        )
    }

    val detail = state.detail?.takeIf { it.pieId == currentPieId }
    if (showContribute && detail != null) {
        ContributeDialog(
            pieName = detail.name,
            onDismiss = { showContribute = false },
            onSubmit = { amount ->
                viewModel.contributeNow(detail.pieId, amount)
                showContribute = false
            },
        )
    }

    if (showRebalanceSheet) {
        RebalanceDialog(
            orders = state.rebalancePreview ?: emptyList(),
            confirmTrades = confirmTradesSnapshot,
            onConfirm = {
                currentPieId?.let { viewModel.confirmRebalance(it) }
                showRebalanceSheet = false
            },
            onCancel = { showRebalanceSheet = false },
        )
    }

    if (showDeleteConfirm) {
        DeletePlanConfirmDialog(
            onConfirm = {
                currentPieId?.let { viewModel.deletePie(it) }
                showDeleteConfirm = false
                selectedPieId = null
            },
            onCancel = { showDeleteConfirm = false },
        )
    }

    if (showWizard) {
        PieWizardDialog(
            existingPie = editingPie,
            onDismiss = { showWizard = false; editingPie = null },
            onSaved = {
                showWizard = false
                editingPie = null
                viewModel.onAppear()
                currentPieId?.let { viewModel.openDetail(it) }
            },
        )
    }
}

// MARK: - List

@Composable
private fun PlansListContent(rows: List<PieRowUi>, onSelect: (String) -> Unit, onCreate: () -> Unit) {
    if (rows.isEmpty()) {
        EmptyPlansState(onCreate)
        return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.End) {
        CreateButton(onCreate)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in rows) {
                PieRowCard(row = row, onClick = { onSelect(row.id) })
            }
        }
    }
}

@Composable
private fun CreateButton(onCreate: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.goldGradient)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCreate() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            "+",
            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.bgBottom),
        )
        Text(
            tr(L10n.Key.CreatePlan),
            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.bgBottom),
        )
    }
}

@Composable
private fun EmptyPlansState(onCreate: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DonutChart(slices = listOf(DonutSlice(fraction = 1.0, color = DK.textTertiary)), diameter = 48.dp)
            Text(
                tr(L10n.Key.PlansEmptyTitle),
                style = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
            )
            Text(
                tr(L10n.Key.PlansEmptyHint),
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DK.goldGradient)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCreate() }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    tr(L10n.Key.CreatePlan),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DK.bgBottom),
                )
            }
        }
    }
}

@Composable
private fun PieRowCard(row: PieRowUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DonutChart(
            slices = row.sliceWeights.mapIndexed { index, (_, weight) ->
                DonutSlice(fraction = weight.toDisplayDouble(), color = pieSliceColor(index))
            },
            diameter = 52.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.name,
                    maxLines = 1,
                    style = TextStyle(fontFamily = InterFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
                )
                if (row.maxDriftPP.toDisplayDouble() > 5.0) DriftBadge(row.maxDriftPP)
            }
            if (row.nextContributionLabel != null) {
                Text(
                    row.nextContributionLabel,
                    style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
                )
            }
        }
        SuperscriptPrice(amountText = row.currentValue.amountText, size = 18.sp)
    }
}

@Composable
private fun DriftBadge(driftPP: BigDecimal) {
    Text(
        "${tr(L10n.Key.DriftLabel)} ${formatDriftBadge(driftPP)}",
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            color = DK.bgBottom, letterSpacing = 0.4.sp,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.gold)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

// MARK: - Detail

@Composable
private fun PieDetailContent(
    state: PlansUiState,
    pieId: String,
    onBack: () -> Unit,
    onContribute: () -> Unit,
    onRebalance: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val detail = state.detail?.takeIf { it.pieId == pieId }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BackRow(onBack)
        if (detail == null) {
            Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DK.gold)
            }
            return
        }
        DetailHeader(detail)
        if (detail.activity.any { it.kind == PieActivityKind.ManualAdjustment }) {
            Text(
                tr(L10n.Key.ManualAdjustmentNote),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = DK.textTertiary),
            )
        }
        if (detail.activity.any { it.kind == PieActivityKind.MissedInsufficientCash }) {
            Text(
                tr(L10n.Key.MissedContribution),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = DK.textTertiary),
            )
        }
        detail.schedule?.let { ScheduleCard(it) }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (slice in detail.slices) SliceRow(slice)
        }
        if (state.errorMessage != null) {
            Text(
                state.errorMessage,
                style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = DK.down),
            )
        }
        ActionButtons(onContribute = onContribute, onRebalance = onRebalance, onEdit = onEdit, onDelete = onDelete)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹  " + tr(L10n.Key.Back),
            style = TextStyle(fontFamily = InterFamily, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() },
        )
    }
}

@Composable
private fun DetailHeader(detail: PieDetailUi) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        DonutChart(
            slices = detail.slices.mapIndexed { index, slice ->
                DonutSlice(fraction = slice.targetWeight.toDisplayDouble(), color = pieSliceColor(index))
            },
            diameter = 130.dp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                detail.name,
                style = TextStyle(fontFamily = InterFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DK.textPrimary),
            )
            SuperscriptPrice(amountText = detail.totalValue.amountText, size = 24.sp)
        }
    }
}

@Composable
private fun ScheduleCard(schedule: ContributionSchedule) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            tr(L10n.Key.ScheduleSection).uppercase(),
            style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.4.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatTile(label = tr(L10n.Key.ContributionAmountLabel), value = formatMoney(schedule.amount.amountText))
            StatTile(label = tr(L10n.Key.CadenceLabel), value = cadenceLabel(schedule.cadence))
            StatTile(label = tr(L10n.Key.NextContribution), value = nextDueText(schedule))
        }
    }
}

@Composable
private fun SliceRow(slice: PieSliceDetailUi) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                slice.symbol,
                style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${tr(L10n.Key.TargetWeightLabel)} ${formatWeight(slice.targetWeight)}",
                style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = DK.textSecondary, fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${tr(L10n.Key.ActualWeightLabel)} ${formatWeight(slice.actualWeight)}",
                style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = DK.textSecondary, fontFeatureSettings = "tnum"),
            )
        }
        DriftBar(slice.drift)
    }
}

/** A signed drift bar: green when the slice is over target, red when under — data color,
 *  never the brand accent — scaled by magnitude against a 20pp full-width cap, same as
 *  `PlansSection.swift`'s `driftBar`. Reuses the fillMaxWidth(fraction) idiom
 *  `PortfolioPane.kt`'s `AllocationBar` already establishes for a filled-track bar. */
@Composable
private fun DriftBar(drift: BigDecimal) {
    val value = drift.toDisplayDouble()
    val color = if (value >= 0) DK.up else DK.down
    val fraction = (kotlin.math.abs(value) / 20.0).coerceAtMost(1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(DK.surfaceHi)) {
        Box(
            Modifier
                .fillMaxWidth(if (fraction <= 0f) 0.02f else fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

private enum class ButtonKind { Primary, Secondary, Destructive }

@Composable
private fun ActionButtons(onContribute: () -> Unit, onRebalance: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton(tr(L10n.Key.ContributeNow), ButtonKind.Primary, Modifier.weight(1f), onContribute)
            ActionButton(tr(L10n.Key.RebalanceNow), ButtonKind.Secondary, Modifier.weight(1f), onRebalance)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton(tr(L10n.Key.EditPlan), ButtonKind.Secondary, Modifier.weight(1f), onEdit)
            ActionButton(tr(L10n.Key.DeletePlan), ButtonKind.Destructive, Modifier.weight(1f), onDelete)
        }
    }
}

@Composable
private fun ActionButton(label: String, kind: ButtonKind, modifier: Modifier, onClick: () -> Unit) {
    val background = when (kind) {
        ButtonKind.Primary -> DK.goldGradient
        ButtonKind.Secondary -> SolidColor(DK.surface)
        ButtonKind.Destructive -> SolidColor(DK.down.copy(alpha = 0.12f))
    }
    val foreground = when (kind) {
        ButtonKind.Primary -> DK.bgBottom
        ButtonKind.Secondary -> DK.textPrimary
        ButtonKind.Destructive -> DK.down
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .then(if (kind == ButtonKind.Secondary) Modifier.border(1.dp, DK.hairline, RoundedCornerShape(50)) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(label, style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = foreground))
    }
}

// MARK: - Contribute dialog

@Composable
private fun ContributeDialog(pieName: String, onDismiss: () -> Unit, onSubmit: (Money) -> Unit) {
    var amountText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val amount = parseAmount(amountText)

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
                    .width(360.dp)
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
                    .focusRequester(focusRequester)
                    .focusable()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        trf(L10n.Key.ContributeSheetTitleFormat, pieName),
                        maxLines = 1,
                        style = TextStyle(fontFamily = InterFamily, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DK.textPrimary),
                        modifier = Modifier.weight(1f),
                    )
                    CloseGlyphButton(onDismiss)
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DK.surfaceHi).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        tr(L10n.Key.ContributionAmountLabel).uppercase(),
                        style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
                    )
                    WizardTextField(value = amountText, onValueChange = { amountText = it }, placeholder = "0", fontSize = 28.sp)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(if (amount != null) DK.goldGradient else SolidColor(DK.surface))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = amount != null,
                        ) { amount?.let { onSubmit(Money(it)) } }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        tr(L10n.Key.ContributeNow),
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (amount != null) DK.bgBottom else DK.textTertiary,
                        ),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private fun parseAmount(text: String): BigDecimal? {
    val value = try {
        BigDecimal.parseString(text)
    } catch (e: Exception) {
        null
    } ?: return null
    return if (value > BigDecimal.ZERO) value else null
}

@Composable
private fun CloseGlyphButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(24.dp).height(24.dp)
            .clip(CircleShape)
            .background(DK.surfaceHi)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DK.textSecondary))
    }
}

// MARK: - Rebalance dialog

@Composable
private fun RebalanceDialog(
    orders: List<RebalanceOrder>,
    confirmTrades: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var showConfirmLayer by remember { mutableStateOf(false) }
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
                    .width(420.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DK.surface)
                    .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            if (showConfirmLayer) showConfirmLayer = false else onCancel()
                            true
                        } else {
                            false
                        }
                    }
                    .focusable()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (showConfirmLayer) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            tr(L10n.Key.ConfirmRebalanceTitle),
                            style = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
                        )
                        Text(
                            trf(L10n.Key.ConfirmRebalanceMessageFormat, orders.size),
                            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = DK.textSecondary),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            DialogButton(tr(L10n.Key.Cancel), primary = false, modifier = Modifier.weight(1f)) { showConfirmLayer = false }
                            DialogButton(tr(L10n.Key.RebalanceNow), primary = true, modifier = Modifier.weight(1f)) {
                                showConfirmLayer = false
                                onConfirm()
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr(L10n.Key.RebalancePreviewTitle),
                            style = TextStyle(fontFamily = InterFamily, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DK.textPrimary),
                            modifier = Modifier.weight(1f),
                        )
                        CloseGlyphButton(onCancel)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
                    if (orders.isEmpty()) {
                        Text(
                            tr(L10n.Key.RebalanceOrdersEmpty),
                            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = DK.textSecondary),
                            modifier = Modifier.padding(20.dp),
                        )
                    } else {
                        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                            for (order in orders) OrderRow(order)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                        DialogButton(tr(L10n.Key.Cancel), primary = false, modifier = Modifier.weight(1f)) { onCancel() }
                        DialogButton(
                            tr(L10n.Key.RebalanceNow),
                            primary = true,
                            enabled = orders.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            // Honors the app's Confirm Trades setting via the same pure gate
                            // TradeDialog uses (TradeConfirm.kt's attemptSubmit) — side-agnostic,
                            // so it applies to a rebalance's mixed buy/sell batch just as well.
                            when (attemptSubmit(confirmTrades)) {
                                SubmitAction.ShowConfirm -> showConfirmLayer = true
                                SubmitAction.SubmitDirectly -> onConfirm()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: RebalanceOrder) {
    val color = if (order.side == RebalanceSide.Buy) DK.up else DK.down
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.width(44.dp).height(22.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)),
        ) {
            Text(
                if (order.side == RebalanceSide.Buy) tr(L10n.Key.BuyChip) else tr(L10n.Key.SellChip),
                style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.8.sp),
            )
        }
        Text(
            order.symbol,
            style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
        )
        Spacer(Modifier.weight(1f))
        Text(
            formatMoney(order.amount.amountText),
            style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary, fontFeatureSettings = "tnum"),
        )
    }
}

// MARK: - Delete confirm dialog

@Composable
private fun DeletePlanConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
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
                    .focusable()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    tr(L10n.Key.DeletePlanConfirm),
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
                            tr(L10n.Key.DeletePlan),
                            style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DK.down),
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Shared dialog primitives (used by PieWizardDialog.kt too)

@Composable
internal fun DialogButton(label: String, primary: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    primary && enabled -> DK.goldGradient
                    else -> SolidColor(DK.surface)
                },
            )
            .then(if (!primary) Modifier.border(1.dp, DK.hairline, RoundedCornerShape(50)) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 14.sp,
                fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold,
                color = when {
                    primary && enabled -> DK.bgBottom
                    primary -> DK.textTertiary
                    else -> DK.textSecondary
                },
            ),
        )
    }
}

/** Plain text field styled like `TradeDialog.kt`'s `QuantityField`/`PieWizardView.swift`'s
 *  various `TextField`s — used by [ContributeDialog] and [PieWizardDialog]. */
@Composable
internal fun WizardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    fontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
) {
    Box {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                style = TextStyle(fontFamily = InterFamily, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = DK.textTertiary, fontFeatureSettings = "tnum"),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(DK.gold),
            textStyle = LocalTextStyle.current.merge(
                TextStyle(fontFamily = InterFamily, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = DK.textPrimary, fontFeatureSettings = "tnum"),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
