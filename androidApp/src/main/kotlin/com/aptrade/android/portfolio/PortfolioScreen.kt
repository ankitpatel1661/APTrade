package com.aptrade.android.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.ui.chart.ChartLegend
import com.aptrade.android.ui.chart.DualLineChart
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import com.aptrade.shared.domain.AllocationSlice
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.TradeSide
import java.util.Locale

/** Reconstruct an [AssetKind] from the display [HoldingRowUi.kindLabel] so a held-row BUY can call
 *  the VM's `buy(asset, …)`. Mirrors the desktop `assetKindFromLabel`. */
private fun assetKindFromLabel(label: String?): AssetKind = when (label) {
    "ETF" -> AssetKind.Etf
    "Crypto" -> AssetKind.Crypto
    else -> AssetKind.Stock
}

/** The holding row a [TradeSheet] is opened against, plus the side the user tapped. */
private data class TradeTarget(val row: HoldingRowUi, val side: TradeSide)

@Composable
fun PortfolioScreen(onBack: () -> Unit, onOpenDetail: (String) -> Unit) {
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
    exportCsv: () -> String,
    exportJson: () -> String,
) {
    val context = LocalContext.current
    var tradeTarget by remember { mutableStateOf<TradeTarget?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showExportChooser by remember { mutableStateOf(false) }

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

                    item {
                        PerformanceSection(
                            state = state,
                            onSetSpan = onSetSpan,
                            onSetBenchmark = onSetBenchmark,
                        )
                    }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) }

                    if (state.holdings.isNotEmpty()) {
                        item { SectionHeader("HOLDINGS") }
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

                    if (state.allocationByHolding.isNotEmpty() || state.allocationByKind.isNotEmpty()) {
                        item { SectionHeader("ALLOCATION") }
                        if (state.allocationByHolding.isNotEmpty()) {
                            item { AllocationGroupHeader("BY HOLDING") }
                            items(state.allocationByHolding, key = { "h-${it.id}" }) { slice ->
                                AllocationBar(slice)
                            }
                        }
                        if (state.allocationByKind.isNotEmpty()) {
                            item { AllocationGroupHeader("BY CLASS") }
                            items(state.allocationByKind, key = { "c-${it.id}" }) { slice ->
                                AllocationBar(slice)
                            }
                        }
                    }

                    if (state.transactions.isNotEmpty()) {
                        item { SectionHeader("ACTIVITY") }
                        items(state.transactions, key = { it.id }) { txn ->
                            ActivityRow(txn)
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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
            onSubmit = { side, quantity ->
                when (side) {
                    TradeSide.Buy -> onBuy(
                        Asset(
                            symbol = target.row.symbol,
                            name = target.row.name,
                            kind = assetKindFromLabel(target.row.kindLabel),
                        ),
                        quantity,
                    )
                    TradeSide.Sell -> onSell(target.row.symbol, quantity)
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
                    shareExport(context, ExportFormat.Csv, exportCsv())
                }) { Text("CSV") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportChooser = false
                    shareExport(context, ExportFormat.Json, exportJson())
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

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeaderInline("PERFORMANCE")

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
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

        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            when {
                maxDayOne -> EmptyChartText(
                    "Tracking starts today — performance appears after your first market day.",
                )
                state.performanceValues.size < 2 -> EmptyChartText("No performance data yet.")
                state.benchmarkTwinValues == null -> EmptyChartText("Benchmark unavailable")
                else -> DualLineChart(
                    primary = state.performanceValues,
                    secondary = state.benchmarkTwinValues,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Legend only when the benchmark twin actually renders (mirrors desktop guard).
        if (state.benchmarkTwinValues != null && state.performanceValues.size >= 2 && !maxDayOne) {
            ChartLegend(primaryLabel = "Portfolio", secondaryLabel = state.benchmark)
        }
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
            Text(slice.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
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
