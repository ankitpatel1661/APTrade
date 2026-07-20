package com.aptrade.android.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.android.portfolio.SubmitAction
import com.aptrade.android.portfolio.attemptSubmit
import com.aptrade.android.ui.money
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityEntry
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.RebalanceOrder
import com.aptrade.shared.domain.RebalanceSide
import com.aptrade.shared.l10n.L10n
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `BigDecimal` -> `Double` for pixel/format math only — never for exact-decimal display.
 *  Internal (not `private`, which in Kotlin is FILE-private, not package-private): reused by
 *  [PieWizardSheet]. Mirrors desktop `PlansPane.kt`'s identical extension. */
internal fun BigDecimal.toDisplayDouble(): Double = doubleValue(false)

/** [PieCadence]'s display label. A plain function (not a `when` inlined at each call site) so
 *  every reader gets [tr] fresh on recomposition — mirrors desktop `PlansPane.kt`'s
 *  `cadenceLabel`. Internal: [PieWizardSheet]'s schedule-step cadence picker reuses this. */
internal fun cadenceLabel(cadence: PieCadence): String = when (cadence) {
    PieCadence.Weekly -> tr(L10n.Key.CadenceWeekly)
    PieCadence.Biweekly -> tr(L10n.Key.CadenceBiweekly)
    PieCadence.Monthly -> tr(L10n.Key.CadenceMonthly)
}

/** A small label-over-value stat pair, Android Material3 echo of desktop designkit's
 *  `StatTile`. Internal: reused by [PieWizardSheet]'s backtest step. */
@Composable
internal fun StatTile(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

private fun formatWeight(value: BigDecimal): String = String.format(Locale.US, "%.2f%%", value.toDisplayDouble())

private fun formatDriftBadge(value: BigDecimal): String = String.format(Locale.US, "%.1f%%", value.toDisplayDouble())

private val DUE_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

private fun nextDueText(schedule: ContributionSchedule): String {
    val epochDay = PieSchedule.parseDay(schedule.nextDueDay) ?: return "—"
    return DUE_DAY_FORMATTER.format(LocalDate.ofEpochDay(epochDay))
}

private fun activityDayText(day: String): String =
    try {
        DUE_DAY_FORMATTER.format(LocalDate.parse(day))
    } catch (e: Exception) {
        day
    }

/** Every existing Pie L10n key is either an action ("Contribute Now") or a full sentence
 *  ("Contribution skipped…") — there is no dedicated per-kind noun ("Contribution",
 *  "Rebalance") in the shared catalog. Reusing the closest existing key per [PieActivityKind]
 *  keeps every activity-row label localized without adding new shared L10n keys (Global
 *  Constraint: shared/ stays untouched for this task). */
private fun activityKindLabel(kind: PieActivityKind): String = when (kind) {
    PieActivityKind.Contribution -> tr(L10n.Key.ContributeNow)
    PieActivityKind.Rebalance -> tr(L10n.Key.RebalanceNow)
    PieActivityKind.MissedInsufficientCash -> tr(L10n.Key.MissedContribution)
    PieActivityKind.ManualAdjustment -> tr(L10n.Key.ManualAdjustmentNote)
}

/**
 * The Portfolio screen's Plans section: a card list of the user's investment Pies plus the
 * detail screen for one Pie and its contribute/rebalance/edit/delete actions. Android Compose
 * counterpart of desktop's `PlansPane.kt`'s `PlansPane` — same list/detail structure and VM
 * usage, adapted to this app's own idioms: Material3 components instead of the desktop
 * designkit, and [ModalBottomSheet]s instead of [androidx.compose.ui.window.Dialog]s for every
 * modal (contribute amount, rebalance preview, the wizard) — the app's existing bottom-sheet
 * convention ([com.aptrade.android.portfolio.TradeSheet],
 * [com.aptrade.android.watchlist.PriceAlertSheet]), recorded per the plan's allowed Android-UI
 * divergence from desktop's dialogs.
 *
 * Owns its own [PlansViewModel] instance (via the app's standard `viewModel { ... }` factory,
 * mirroring [com.aptrade.android.portfolio.PortfolioScreen]) rather than a hand-rolled scope —
 * reused across re-entries into the Plans section exactly like `PortfolioViewModel` is reused
 * across Portfolio-tab revisits, since [PlansViewModel.onAppear] always reconciles + reloads
 * fresh from disk on every (re-)appearance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansSection(confirmTrades: Boolean) {
    val portfolio = AppGraph.portfolio
    val viewModel: PlansViewModel = viewModel {
        PlansViewModel(
            loadPies = portfolio.loadPies,
            deletePieUseCase = portfolio.deletePie,
            contributeToPie = portfolio.contributeToPie,
            rebalancePie = portfolio.rebalancePie,
            reconcileLedgers = portfolio.reconcilePieLedgers,
            fetchMarketQuotes = AppGraph.fetchMarketQuotes,
            calendar = portfolio.marketCalendar,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        )
    }
    LaunchedEffect(Unit) { viewModel.onAppear() }
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedPieId by rememberSaveable { mutableStateOf<String?>(null) }
    var showWizard by remember { mutableStateOf(false) }
    var editingPie by remember { mutableStateOf<Pie?>(null) }
    var showContribute by remember { mutableStateOf(false) }
    var showRebalanceSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(selectedPieId) {
        selectedPieId?.let { viewModel.openDetail(it) }
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
                showRebalanceSheet = true
            },
            onEdit = {
                scope.launch {
                    val pie = portfolio.loadPies.execute().firstOrNull { it.id == currentPieId }
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
        ContributeSheet(
            pieName = detail.name,
            onDismiss = { showContribute = false },
            onSubmit = { amount ->
                viewModel.contributeNow(detail.pieId, amount)
                showContribute = false
            },
        )
    }

    if (showRebalanceSheet) {
        RebalanceSheet(
            orders = state.rebalancePreview ?: emptyList(),
            confirmTrades = confirmTrades,
            onConfirm = {
                currentPieId?.let { viewModel.confirmRebalance(it) }
                showRebalanceSheet = false
            },
            onCancel = { showRebalanceSheet = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(tr(L10n.Key.DeletePlanConfirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    currentPieId?.let { viewModel.deletePie(it) }
                    selectedPieId = null
                }) { Text(tr(L10n.Key.DeletePlan)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(tr(L10n.Key.Cancel)) }
            },
        )
    }

    if (showWizard) {
        PieWizardSheet(
            existingPie = editingPie,
            onDismiss = { showWizard = false; editingPie = null },
            onSaved = {
                showWizard = false
                editingPie = null
                scope.launch {
                    viewModel.onAppear()
                    currentPieId?.let { viewModel.openDetail(it) }
                }
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
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCreate) { Text(tr(L10n.Key.CreatePlan)) }
        }
        for (row in rows) {
            PieRowCard(row = row, onClick = { onSelect(row.id) })
        }
    }
}

@Composable
private fun EmptyPlansState(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            tr(L10n.Key.PlansEmptyTitle),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            tr(L10n.Key.PlansEmptyHint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onCreate) { Text(tr(L10n.Key.CreatePlan)) }
    }
}

@Composable
private fun PieRowCard(row: PieRowUi, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (row.maxDriftPP.toDisplayDouble() > 5.0) {
                    DriftBadge(row.maxDriftPP)
                    Spacer(Modifier.width(8.dp))
                }
                Text(money(row.currentValue.amountText), style = MaterialTheme.typography.titleMedium)
            }
            StackedAllocationBar(row.sliceWeights)
            row.nextContributionLabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DriftBadge(driftPP: BigDecimal) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
        Text(
            "${tr(L10n.Key.DriftLabel)} ${formatDriftBadge(driftPP)}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Compact allocation visual for a Pie row card: a single row of proportionally-weighted
 *  color segments (one per slice) — the app's "bars" allocation idiom
 *  ([com.aptrade.android.portfolio.PortfolioScreen]'s `AllocationBar`) condensed to a stacked
 *  single-row bar rather than desktop's `DonutChart`.
 *
 *  RECORDED DIVERGENCE: this app has no donut/pie-chart component (see
 *  `PortfolioScreen.kt`'s `AllocationBar`, which is itself desktop-donut-vs-Android-bars); this
 *  stacked bar is the Plans-list counterpart of that same recorded divergence. */
@Composable
private fun StackedAllocationBar(sliceWeights: List<Pair<String, BigDecimal>>) {
    if (sliceWeights.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        sliceWeights.forEachIndexed { index, (_, weight) ->
            val fraction = (weight.toDisplayDouble() / 100.0).toFloat().coerceIn(0f, 1f)
            if (fraction > 0f) {
                Box(
                    Modifier
                        .weight(fraction)
                        .fillMaxHeight()
                        .background(pieSliceColor(index)),
                )
            }
        }
    }
}

/** Colors a Pie's slices in a stable, repeatable order — dimming a shade further each time the
 *  small palette wraps so an eight-slice pie still reads as distinct segments. Mirrors desktop
 *  `PlansPane.kt`'s `pieSliceColor` intent, built from this app's own [MaterialTheme] palette
 *  instead of the desktop designkit's gold/silver ramp (this app has no such ramp). */
@Composable
private fun pieSliceColor(index: Int): Color {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.outline,
    )
    val cycle = index / palette.size
    val opacity = (1.0 - cycle * 0.25).coerceAtLeast(0.35)
    return palette[index % palette.size].copy(alpha = opacity.toFloat())
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
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(tr(L10n.Key.Back), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (detail == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(detail.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(money(detail.totalValue.amountText), style = MaterialTheme.typography.titleLarge)
        }
        detail.schedule?.let { ScheduleCard(it) }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (slice in detail.slices) SliceRow(slice)
        }
        if (detail.activity.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionHeader(tr(L10n.Key.ActivitySection))
                for (entry in detail.activity) PieActivityRow(entry)
            }
        }
        state.errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        ActionButtons(onContribute = onContribute, onRebalance = onRebalance, onEdit = onEdit, onDelete = onDelete)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(Locale.US),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ScheduleCard(schedule: ContributionSchedule) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(tr(L10n.Key.ScheduleSection))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatTile(tr(L10n.Key.ContributionAmountLabel), money(schedule.amount.amountText))
                StatTile(tr(L10n.Key.CadenceLabel), cadenceLabel(schedule.cadence))
                StatTile(tr(L10n.Key.NextContribution), nextDueText(schedule))
            }
        }
    }
}

@Composable
private fun SliceRow(slice: PieSliceDetailUi) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(slice.symbol, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "${tr(L10n.Key.TargetWeightLabel)} ${formatWeight(slice.targetWeight)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${tr(L10n.Key.ActualWeightLabel)} ${formatWeight(slice.actualWeight)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DriftBar(slice.drift)
    }
}

/** A signed drift bar: green when the slice is over target, red when under — data color,
 *  same house rule as [com.aptrade.android.portfolio.PortfolioScreen]'s P&L coloring. Scaled
 *  by magnitude against a 20pp full-width cap, mirroring desktop `PlansPane.kt`'s `DriftBar`. */
@Composable
private fun DriftBar(drift: BigDecimal) {
    val value = drift.toDisplayDouble()
    val color = if (value >= 0) GainGreen else LossRed
    val fraction = (kotlin.math.abs(value) / 20.0).coerceAtMost(1.0).toFloat()
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(if (fraction <= 0f) 0.02f else fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

@Composable
private fun PieActivityRow(entry: PieActivityEntry) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(activityKindLabel(entry.kind), style = MaterialTheme.typography.bodyMedium)
            Text(activityDayText(entry.day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entry.amount?.let {
            Text(money(it.amountText), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ActionButtons(onContribute: () -> Unit, onRebalance: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onContribute, modifier = Modifier.weight(1f)) { Text(tr(L10n.Key.ContributeNow)) }
            OutlinedButton(onClick = onRebalance, modifier = Modifier.weight(1f)) { Text(tr(L10n.Key.RebalanceNow)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text(tr(L10n.Key.EditPlan)) }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(tr(L10n.Key.DeletePlan)) }
        }
    }
}

// MARK: - Contribute sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContributeSheet(pieName: String, onDismiss: () -> Unit, onSubmit: (Money) -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var amountText by remember { mutableStateOf("") }
    val amount = parseAmount(amountText)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(trf(L10n.Key.ContributeSheetTitleFormat, pieName), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(tr(L10n.Key.ContributionAmountLabel)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { amount?.let { onSubmit(Money(it)) } },
                enabled = amount != null,
                modifier = Modifier.align(Alignment.End),
            ) { Text(tr(L10n.Key.ContributeNow)) }
        }
    }
}

private fun parseAmount(text: String): BigDecimal? {
    val value = try {
        BigDecimal.parseString(text)
    } catch (e: Exception) {
        null
    } ?: return null
    return if (value > BigDecimal.ZERO) value else null
}

// MARK: - Rebalance sheet

/**
 * Manual-rebalance preview + confirm, in a [ModalBottomSheet] (app convention) rather than
 * desktop's [androidx.compose.ui.window.Dialog]. Honors the app's Confirm Trades setting via
 * the SAME pure gate [com.aptrade.android.portfolio.TradeSheet] uses
 * ([com.aptrade.android.portfolio.attemptSubmit]) — side-agnostic, so it applies to a
 * rebalance's mixed buy/sell batch just as well, mirroring desktop `PlansPane.kt`'s
 * `RebalanceDialog`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RebalanceSheet(
    orders: List<RebalanceOrder>,
    confirmTrades: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var showConfirmLayer by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (showConfirmLayer) showConfirmLayer = false else onCancel() },
        sheetState = sheetState,
    ) {
        if (showConfirmLayer) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(tr(L10n.Key.ConfirmRebalanceTitle), style = MaterialTheme.typography.headlineSmall)
                Text(
                    trf(L10n.Key.ConfirmRebalanceMessageFormat, orders.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showConfirmLayer = false }, modifier = Modifier.weight(1f)) { Text(tr(L10n.Key.Cancel)) }
                    TextButton(
                        onClick = { showConfirmLayer = false; onConfirm() },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr(L10n.Key.RebalanceNow)) }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(tr(L10n.Key.RebalancePreviewTitle), style = MaterialTheme.typography.headlineSmall)
                if (orders.isEmpty()) {
                    Text(
                        tr(L10n.Key.RebalanceOrdersEmpty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (order in orders) OrderRow(order)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(tr(L10n.Key.Cancel)) }
                    TextButton(
                        onClick = {
                            when (attemptSubmit(confirmTrades)) {
                                SubmitAction.ShowConfirm -> showConfirmLayer = true
                                SubmitAction.SubmitDirectly -> onConfirm()
                            }
                        },
                        enabled = orders.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text(tr(L10n.Key.RebalanceNow)) }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: RebalanceOrder) {
    val color = if (order.side == RebalanceSide.Buy) GainGreen else LossRed
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
            Text(
                if (order.side == RebalanceSide.Buy) tr(L10n.Key.BuyChip) else tr(L10n.Key.SellChip),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(order.symbol, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(money(order.amount.amountText), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
