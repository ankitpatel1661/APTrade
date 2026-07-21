package com.aptrade.android.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.income.IncomeSection
import com.aptrade.android.l10n.tr
import com.aptrade.android.plans.PlansSection
import com.aptrade.android.ui.chart.ChartLegend
import com.aptrade.android.ui.chart.CrosshairTooltip
import com.aptrade.android.ui.chart.DualLineChart
import com.aptrade.android.ui.chart.DualSeriesCrosshairOverlay
import com.aptrade.android.ui.chart.crosshairReadout
import com.aptrade.android.ui.chart.nearestIndex
import com.aptrade.android.ui.localizedLabel
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import com.aptrade.shared.application.FetchDividendEvents
import com.aptrade.shared.domain.AllocationSlice
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.launch
import java.util.Locale

/** The holding row a [TradeSheet] is opened against, plus the side the user tapped. */
private data class TradeTarget(val row: HoldingRowUi, val side: TradeSide)

/** The six content sections switched below the summary header — desktop parity
 *  ([com.aptrade.desktop.portfolio.PortfolioPane]'s `PortfolioSection`, which added `Plans` the
 *  same way for M7.3, and `Income` for M8.3 Task 2), plus Performance as a switchable tab
 *  (desktop keeps its chart block always visible above the switcher; Android instead folds it
 *  into the switcher itself so only one section — chart or list — is on screen at a time,
 *  matching this screen's existing single-LazyColumn, one-thing-at-a-time layout). Order and
 *  labels match desktop's set exactly (same six L10n keys). */
private enum class PortfolioSection { Holdings, Allocation, Activity, Plans, Income, Performance }

/** [PortfolioSection]'s display label. A plain function (not an enum property) so it calls
 *  [tr] fresh on every read — recomposes correctly when the active language changes, mirroring
 *  desktop's `PortfolioSection.label()` (`PortfolioPane.kt`). */
private fun PortfolioSection.label(): String = when (this) {
    PortfolioSection.Holdings -> tr(L10n.Key.HoldingsSection)
    PortfolioSection.Allocation -> tr(L10n.Key.AllocationSection)
    PortfolioSection.Activity -> tr(L10n.Key.ActivitySection)
    PortfolioSection.Plans -> tr(L10n.Key.PlansSection)
    PortfolioSection.Income -> tr(L10n.Key.IncomeSection)
    PortfolioSection.Performance -> tr(L10n.Key.PerformanceSection)
}

@Composable
fun PortfolioScreen(onBack: () -> Unit, onOpenDetail: (String) -> Unit, confirmTrades: Boolean) {
    val portfolio = AppGraph.portfolio
    val viewModel: PortfolioViewModel = viewModel {
        PortfolioViewModel(
            fetchPortfolio = portfolio.fetchPortfolio,
            fetchMarketQuotes = AppGraph.fetchMarketQuotes,
            buyAsset = portfolio.buyAsset,
            sellAsset = portfolio.sellAsset,
            resetPortfolio = portfolio.resetPortfolio,
            fetchPerformanceReport = portfolio.fetchPerformanceReport,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
            notifyOrderFill = AppGraph.notifyOrderFill,
            fetchDividendEvents = FetchDividendEvents(portfolio.repository),
        )
    }

    // Honor the VM's lifecycle contract: start() the load + 15s poll when the screen is at least
    // STARTED, stop() it on stop/dispose. In-flight trades survive stop() by design (VM KDoc).
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()
    PortfolioContent(
        state = state,
        onBack = onBack,
        onOpenDetail = onOpenDetail,
        onSetSpan = viewModel::setSpan,
        onSetBenchmark = viewModel::setBenchmark,
        onBuy = viewModel::buy,
        onSell = viewModel::sell,
        onReset = viewModel::reset,
        exportCsv = viewModel::exportCsv,
        exportJson = viewModel::exportJson,
        confirmTrades = confirmTrades,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortfolioContent(
    state: PortfolioUiState,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onSetSpan: (PortfolioSpan) -> Unit,
    onSetBenchmark: (String) -> Unit,
    onBuy: (Asset, String) -> Unit,
    onSell: (String, String) -> Unit,
    onReset: () -> Unit,
    exportCsv: suspend () -> String,
    exportJson: suspend () -> String,
    confirmTrades: Boolean,
) {
    val context = LocalContext.current
    var tradeTarget by remember { mutableStateOf<TradeTarget?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showExportChooser by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(PortfolioSection.Holdings) }
    // Composition-scoped coroutine launcher for the export buttons below — `exportCsv`/
    // `exportJson` became `suspend` (M8.3 final-review fix: `exportSnapshot`'s
    // `projectedAnnualIncome` fetches per-symbol dividend events), so their click handlers
    // need somewhere to launch into. Mirrors desktop Main.kt's `exportScope`.
    val exportScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Portfolio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SummaryHeader(state) }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) }

                    // The switcher is ALWAYS shown — matching desktop PortfolioPane and
                    // macOS PortfolioView, both deliberately un-gated in M7 so Plans (and
                    // now Income) are reachable before the first holding exists. Only the
                    // Holdings section shows the empty state. (An earlier gate here hid
                    // every section on a fresh portfolio and wrongly claimed desktop parity.)
                    item {
                        SectionSwitcher(
                            selected = section,
                            onSelect = { section = it },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) }

                    when (section) {
                        PortfolioSection.Holdings -> {
                            if (state.holdings.isEmpty()) {
                                item { EmptyHoldingsState() }
                            } else {
                                items(state.holdings, key = { it.symbol }) { row ->
                                    HoldingRow(
                                        row = row,
                                        onOpenDetail = { onOpenDetail(row.symbol) },
                                        onBuy = { tradeTarget = TradeTarget(row, TradeSide.Buy) },
                                        onSell = { tradeTarget = TradeTarget(row, TradeSide.Sell) },
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                }
                            }
                        }
                        PortfolioSection.Allocation -> {
                            if (state.allocationByHolding.isNotEmpty()) {
                                item { AllocationGroupHeader(tr(L10n.Key.ByHolding)) }
                                items(state.allocationByHolding, key = { "h-${it.id}" }) { slice ->
                                    AllocationBar(slice)
                                }
                            }
                            if (state.allocationByKind.isNotEmpty()) {
                                item { AllocationGroupHeader(tr(L10n.Key.ByClass)) }
                                items(state.allocationByKind, key = { "c-${it.id}" }) { slice ->
                                    AllocationBar(slice)
                                }
                            }
                        }
                        PortfolioSection.Activity -> {
                            if (state.transactions.isEmpty()) {
                                item { EmptyChartText(tr(L10n.Key.NoTransactionsYet)) }
                            } else {
                                items(state.transactions, key = { it.id }) { txn ->
                                    ActivityRow(txn)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                }
                            }
                        }
                        PortfolioSection.Plans -> {
                            item { PlansSection(confirmTrades = confirmTrades) }
                        }
                        PortfolioSection.Income -> {
                            item { IncomeSection() }
                        }
                        PortfolioSection.Performance -> {
                            item {
                                PerformanceSection(
                                    state = state,
                                    onSetSpan = onSetSpan,
                                    onSetBenchmark = onSetBenchmark,
                                )
                            }
                        }
                    }

                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { showExportChooser = true }) { Text("Export…") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { showResetConfirm = true }) {
                                Text("Reset portfolio…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    tradeTarget?.let { target ->
        // A buy/sell is fire-and-forget on the VM; success appends a transaction. Snapshot the
        // count when the sheet opens and dismiss once it grows — so the sheet stays put on a
        // failure to show the inline tradeError. Mirrors the desktop TradeDialog close semantics.
        TradeSheet(
            info = TradeSheetInfo(
                symbol = target.row.symbol,
                name = target.row.name,
                priceText = target.row.priceText,
                initialSide = target.side,
            ),
            tradeError = state.tradeError,
            transactionCount = state.transactions.size,
            confirmTrades = confirmTrades,
            onSubmit = { side, quantity ->
                when (side) {
                    TradeSide.Buy -> onBuy(
                        Asset(
                            symbol = target.row.symbol,
                            name = target.row.name,
                            kind = target.row.kind,
                        ),
                        quantity,
                    )
                    TradeSide.Sell -> onSell(target.row.symbol, quantity)
                    // The trade sheet never offers Dividend as a selectable side today —
                    // minimal neutral staging only. Real handling lands with the coordinator task.
                    TradeSide.Dividend -> Unit
                }
            },
            onDismiss = { tradeTarget = null },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset portfolio") },
            text = { Text("Start over with $100,000?") },
            confirmButton = {
                TextButton(onClick = { showResetConfirm = false; onReset() }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showExportChooser) {
        AlertDialog(
            onDismissRequest = { showExportChooser = false },
            title = { Text("Export portfolio") },
            text = { Text("Choose a format to share.") },
            confirmButton = {
                TextButton(onClick = {
                    showExportChooser = false
                    exportScope.launch { shareExport(context, ExportFormat.Csv, exportCsv()) }
                }) { Text("CSV") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportChooser = false
                    exportScope.launch { shareExport(context, ExportFormat.Json, exportJson()) }
                }) { Text("JSON") }
            },
        )
    }
}

@Composable
private fun SummaryHeader(state: PortfolioUiState) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            state.totalValueText ?: "—",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        state.dayChangeText?.let { text ->
            DayChangePill(text, state.dayChangePositive)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SummaryMetric("Cash", state.cashText, null, Modifier.weight(1f))
            SummaryMetric("Holdings", state.holdingsValueText, null, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SummaryMetric("Unrealized", state.unrealizedText, state.unrealizedPositive, Modifier.weight(1f))
            SummaryMetric("Realized", state.realizedText, state.realizedPositive, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DayChangePill(text: String, positive: Boolean?) {
    val color = pnlColor(positive)
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SummaryMetric(label: String, value: String?, positive: Boolean?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            color = if (positive == null) MaterialTheme.colorScheme.onSurface else pnlColor(positive),
        )
    }
}

@Composable
private fun PerformanceSection(
    state: PortfolioUiState,
    onSetSpan: (PortfolioSpan) -> Unit,
    onSetBenchmark: (String) -> Unit,
) {
    // On MAX, a portfolio that has traded but has fewer than two performance points is day-one:
    // the tracking curve fills in from the first market close, not instantly. Mirrors desktop.
    val maxDayOne = state.span == PortfolioSpan.Max &&
        state.transactions.isNotEmpty() && state.performanceValues.size < 2
    val chartRenders = !maxDayOne && state.performanceValues.size >= 2 && state.benchmarkTwinValues != null

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeaderInline("PERFORMANCE")

        SingleChoiceSegmentedButtonRow(Modifier.segmentedRowWidth()) {
            PortfolioSpan.entries.forEachIndexed { index, span ->
                SegmentedButton(
                    selected = state.span == span,
                    onClick = { onSetSpan(span) },
                    shape = SegmentedButtonDefaults.itemShape(index, PortfolioSpan.entries.size),
                ) { Text(span.label) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.benchmarks.forEach { symbol ->
                FilterChip(
                    selected = state.benchmark == symbol,
                    onClick = { onSetBenchmark(symbol) },
                    label = { Text(symbol) },
                )
            }
        }

        // Follow-the-finger crosshair (UAT round 2 — same gesture + tooltip mechanic as the
        // detail chart, reusing the pure nearestIndex/crosshairReadout/clampedTooltipX helpers).
        // The comparison (benchmark) curve is drawn by the same DualLineChart, so one crosshair
        // scrubbing the shared x-axis covers both the portfolio and comparison series; the
        // readout itself always shows the PRIMARY (portfolio) point, in exact-decimal
        // performanceValueTexts — never the pixels-only performanceValues Double.
        var dragX by remember { mutableStateOf<Float?>(null) }
        var chartWidthPx by remember { mutableStateOf(0f) }

        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .onSizeChanged { chartWidthPx = it.width.toFloat() }
                .let { base ->
                    if (chartRenders) {
                        base.pointerInput(state.span, state.benchmark, state.performanceValues.size) {
                            detectDragGestures(
                                onDragStart = { offset -> dragX = offset.x },
                                onDrag = { change, _ -> dragX = change.position.x },
                                onDragEnd = { dragX = null },
                                onDragCancel = { dragX = null },
                            )
                        }
                    } else {
                        base
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                maxDayOne -> EmptyChartText(
                    "Tracking starts today — performance appears after your first market day.",
                )
                state.performanceValues.size < 2 -> EmptyChartText("No performance data yet.")
                state.benchmarkTwinValues == null -> EmptyChartText("Benchmark unavailable")
                else -> {
                    DualLineChart(
                        primary = state.performanceValues,
                        secondary = state.benchmarkTwinValues,
                        modifier = Modifier.fillMaxSize(),
                    )
                    dragX?.let { x ->
                        val index = nearestIndex(x, chartWidthPx, state.performanceValues.size)
                        DualSeriesCrosshairOverlay(
                            primary = state.performanceValues,
                            secondary = state.benchmarkTwinValues,
                            index = index,
                            modifier = Modifier.fillMaxSize(),
                        )
                        val readout = crosshairReadout(index, state.performanceValueTexts, state.performanceDates)
                        readout?.let {
                            CrosshairTooltip(
                                priceText = it.priceText,
                                dateText = it.dateText,
                                rawX = x,
                                chartWidthPx = chartWidthPx,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        // Legend only when the benchmark twin actually renders (mirrors desktop guard).
        if (state.benchmarkTwinValues != null && state.performanceValues.size >= 2 && !maxDayOne) {
            ChartLegend(primaryLabel = "Portfolio", secondaryLabel = state.benchmark)
        }
    }
}

/** Segmented rows fill the width on phones but cap at 480dp and center on wider (tablet)
 *  windows — full-bleed segments there stretch into comically wide tap targets. The trailing
 *  fillMaxWidth re-fills the now-capped constraint, so phones are pixel-identical to before. */
private fun Modifier.segmentedRowWidth(): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = 480.dp)
    .fillMaxWidth()

/** Holdings / Allocation / Activity / Performance segmented row — the Android counterpart of
 *  desktop's pill-style [com.aptrade.desktop.portfolio.PortfolioPane]'s `SectionSwitcher`,
 *  built from the same [SingleChoiceSegmentedButtonRow]/[SegmentedButton] pair the PERFORMANCE
 *  span bar directly above it already uses (this screen's existing pill idiom). Only the
 *  selected section renders below it; default is [PortfolioSection.Holdings]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionSwitcher(
    selected: PortfolioSection,
    onSelect: (PortfolioSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.segmentedRowWidth()) {
        PortfolioSection.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, PortfolioSection.entries.size),
            ) {
                Text(
                    option.label(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Desktop-parity empty state (PortfolioPane.kt's `EmptyState`): the two source sentences
 *  joined with an em dash, shown in place of the switcher + section content while the
 *  portfolio holds nothing. */
@Composable
private fun EmptyHoldingsState() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(
            "${tr(L10n.Key.NoHoldingsYet)} — ${tr(L10n.Key.NoHoldingsHint)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyChartText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HoldingRow(
    row: HoldingRowUi,
    onOpenDetail: () -> Unit,
    onBuy: () -> Unit,
    onSell: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpenDetail).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.symbol, style = MaterialTheme.typography.titleMedium)
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${row.quantityText} @ ${row.averageCostText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(row.marketValueText, style = MaterialTheme.typography.bodyLarge)
                Text(
                    row.unrealizedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = pnlColor(row.unrealizedPositive),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBuy) { Text("BUY") }
            TextButton(onClick = onSell) { Text("SELL") }
        }
    }
}

@Composable
private fun AllocationBar(slice: AllocationSlice) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(slice.localizedLabel(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                String.format(Locale.US, "%.1f%%", slice.fraction * 100),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { slice.fraction.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun ActivityRow(txn: TransactionRowUi) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SideChip(txn.sideLabel, txn.isBuy)
        Column(Modifier.weight(1f)) {
            Text(txn.symbol, style = MaterialTheme.typography.titleSmall)
            Text(
                txn.dateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${txn.quantityText} @ ${txn.priceText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SideChip(label: String, isBuy: Boolean) {
    val color = if (isBuy) GainGreen else LossRed
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            label.uppercase(Locale.US),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SectionHeaderInline(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AllocationGroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** P&L text color: green up, red down, on-surface when direction is unknown (empty portfolio). */
@Composable
private fun pnlColor(positive: Boolean?) = when (positive) {
    true -> GainGreen
    false -> LossRed
    null -> MaterialTheme.colorScheme.onSurface
}
