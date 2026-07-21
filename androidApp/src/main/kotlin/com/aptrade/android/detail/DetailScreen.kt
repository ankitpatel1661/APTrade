package com.aptrade.android.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.calendar.sessionLabel
import com.aptrade.android.l10n.tr
import com.aptrade.android.portfolio.TradeSheet
import com.aptrade.android.portfolio.TradeSheetInfo
import com.aptrade.android.ui.ErrorPane
import com.aptrade.android.ui.formatPercent
import com.aptrade.android.ui.localizedLabel
import com.aptrade.android.ui.money
import com.aptrade.android.ui.chart.CandleChart
import com.aptrade.android.ui.chart.CrosshairOverlay
import com.aptrade.android.ui.chart.CrosshairTooltip
import com.aptrade.android.ui.chart.LineChart
import com.aptrade.android.ui.chart.crosshairReadout
import com.aptrade.android.ui.chart.nearestIndex
import com.aptrade.shared.application.FetchDividendEvents
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.l10n.L10n
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dividendExDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneOffset.UTC)

private val timeframeLabels = listOf(
    Timeframe.OneDay to "1D",
    Timeframe.OneWeek to "1W",
    Timeframe.OneMonth to "1M",
    Timeframe.OneYear to "1Y",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(symbol: String, confirmTrades: Boolean, onBack: () -> Unit) {
    val portfolio = AppGraph.portfolio
    val viewModel: DetailViewModel = viewModel(key = symbol) {
        DetailViewModel(
            symbol = symbol,
            fetchProfile = AppGraph.fetchProfile,
            fetchHistory = AppGraph.fetchHistory,
            fetchChartWindow = AppGraph.fetchChartWindow,
            fetchMarketQuotes = AppGraph.fetchMarketQuotes,
            buyAsset = portfolio.buyAsset,
            sellAsset = portfolio.sellAsset,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
            notifyOrderFill = AppGraph.notifyOrderFill,
            fetchEarnings = AppGraph.fetchEarningsCalendar,
            fetchDividendEvents = FetchDividendEvents(portfolio.repository),
        )
    }
    val state by viewModel.state.collectAsState()
    var tradeSide by remember { mutableStateOf<TradeSide?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.symbol) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    state.kind?.let {
                        AssistChip(
                            onClick = {},
                            label = { Text(it.localizedLabel()) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            state.name?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.profileError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { tradeSide = TradeSide.Buy },
                // Gated on profileResolved (not kindLabel != null): a profile ERROR still resolves
                // and allows the Stock-fallback trade path; only the in-flight window is blocked,
                // so a crypto/ETF asset can never fire tradeAsset() before its real kind is known.
                enabled = state.profileResolved,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("BUY / SELL") }
            Spacer(Modifier.height(16.dp))

            // Indicator selection is view-local UI state (desktop parity: view-local `remember`,
            // none on by default). Reported to the VM so it fetches candles in Line mode when
            // any indicator is on — the VM otherwise only needs OHLCV bars in Candles mode.
            var selection by remember { mutableStateOf(emptySet<Indicator>()) }
            LaunchedEffect(selection.isNotEmpty()) { viewModel.onIndicatorsActiveChange(selection.isNotEmpty()) }
            val series = remember(state.candles, selection) { computeIndicators(state.candles, selection) }
            val overlaysOn = selection.any { it.isOverlay } && state.candles.size >= 2
            val visibleCandles = remember(state.candles, state.visibleStartIndex) {
                state.candles.drop(state.visibleStartIndex)
            }
            // What's actually rendered/scrubbed: candle-sourced (visible slice) whenever
            // overlays are on OR the mode is Candles; otherwise the plain line-history values —
            // mirrors desktop's DetailContent chart-selection `when`.
            val readoutFromCandles = overlaysOn || state.mode == ChartMode.Candles

            // Span/mode selectors sit BELOW the chart (UAT request) — crosshair drag lives on
            // the chart's own Box so scrubbing near the top of the screen never fights a
            // FilterChip tap target above it.
            var dragX by remember { mutableStateOf<Float?>(null) }
            var chartWidthPx by remember { mutableStateOf(0f) }
            val pointCount = if (readoutFromCandles) visibleCandles.size else state.lineValues.size

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .onSizeChanged { chartWidthPx = it.width.toFloat() }
                    .pointerInput(state.mode, pointCount, overlaysOn) {
                        detectDragGestures(
                            onDragStart = { offset -> dragX = offset.x },
                            onDrag = { change, _ -> dragX = change.position.x },
                            onDragEnd = { dragX = null },
                            onDragCancel = { dragX = null },
                        )
                    },
            ) {
                when {
                    state.isLoadingChart -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.chartError != null ->
                        ErrorPane(state.chartError!!, onRetry = viewModel::retryChart, Modifier.align(Alignment.Center))
                    // Overlays on: honor the mode. Candles mode -> real candlesticks with overlay
                    // polylines on top (shared candle index space); Line mode -> the smoothed
                    // price line drawn from candle closes. Both share one index -> x space so
                    // overlays align. Otherwise the original Line/Candles rendering.
                    overlaysOn && state.mode == ChartMode.Candles ->
                        CandleChartWithOverlays(
                            candles = state.candles,
                            series = series,
                            selection = selection,
                            visibleStartIndex = state.visibleStartIndex,
                            modifier = Modifier.fillMaxSize(),
                        )
                    overlaysOn ->
                        PriceChartWithOverlays(
                            candles = state.candles,
                            series = series,
                            selection = selection,
                            lineColor = MaterialTheme.colorScheme.primary,
                            visibleStartIndex = state.visibleStartIndex,
                            modifier = Modifier.fillMaxSize(),
                        )
                    state.mode == ChartMode.Line ->
                        LineChart(state.lineValues, Modifier.fillMaxSize())
                    else ->
                        CandleChart(visibleCandles, Modifier.fillMaxSize())
                }

                // Crosshair: only meaningful once the chart has actually rendered (not loading,
                // no error) and there are at least 2 points to scrub between.
                if (!state.isLoadingChart && state.chartError == null && pointCount >= 2) {
                    dragX?.let { x ->
                        val index = nearestIndex(x, chartWidthPx, pointCount)
                        val curveValues = if (readoutFromCandles) {
                            visibleCandles.map { it.close }
                        } else {
                            state.lineValues
                        }
                        CrosshairOverlay(curveValues, index, modifier = Modifier.fillMaxSize())
                        val readout = if (readoutFromCandles) {
                            crosshairReadout(index, state.candleCloseTexts, state.candleDates)
                        } else {
                            crosshairReadout(index, state.lineValueTexts, state.lineDates)
                        }
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
            if (selection.contains(Indicator.Rsi) && state.candles.size >= 2) {
                Spacer(Modifier.height(12.dp))
                RsiPane(series = series, visibleStartIndex = state.visibleStartIndex)
            }
            if (selection.contains(Indicator.Macd) && state.candles.size >= 2) {
                Spacer(Modifier.height(12.dp))
                MacdPane(series = series, visibleStartIndex = state.visibleStartIndex)
            }
            Spacer(Modifier.height(16.dp))
            IndicatorChips(selection = selection, onToggle = { ind ->
                selection = if (selection.contains(ind)) selection - ind else selection + ind
            })
            Spacer(Modifier.height(16.dp))

            Row {
                timeframeLabels.forEach { (timeframe, label) ->
                    FilterChip(
                        selected = state.timeframe == timeframe,
                        onClick = { viewModel.onTimeframeChange(timeframe) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                FilterChip(
                    selected = state.mode == ChartMode.Line,
                    onClick = { viewModel.onModeChange(ChartMode.Line) },
                    label = { Text("Line") },
                    modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = state.mode == ChartMode.Candles,
                    onClick = { viewModel.onModeChange(ChartMode.Candles) },
                    label = { Text("Candles") },
                )
            }

            state.nextEarnings?.let { next ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        tr(L10n.Key.NextEarnings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${next.day} · ${sessionLabel(next.session)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            state.dividendInfo?.let { info ->
                Spacer(Modifier.height(16.dp))
                DividendCard(info)
            }
        }
    }

    tradeSide?.let { side ->
        TradeSheet(
            info = TradeSheetInfo(
                symbol = state.symbol,
                // Fall back to the symbol as the display name until the profile header resolves.
                name = state.name ?: state.symbol,
                priceText = state.priceText,
                initialSide = side,
            ),
            tradeError = state.tradeError,
            transactionCount = state.transactionCount,
            confirmTrades = confirmTrades,
            onSubmit = { submittedSide, quantity ->
                when (submittedSide) {
                    TradeSide.Buy -> viewModel.buy(quantity)
                    TradeSide.Sell -> viewModel.sell(quantity)
                    // The trade sheet never offers Dividend as a selectable side today —
                    // minimal neutral staging only. Real handling lands with the coordinator task.
                    TradeSide.Dividend -> Unit
                }
            },
            onDismiss = { tradeSide = null },
        )
    }
}

// MARK: - Dividends

/** DIVIDENDS card: yield/annual-rate stat pair, a next-estimated-ex-date row (with an "Est."
 *  badge, hidden when the projection is stale/absent), and a last-8 per-share mini bar chart.
 *  Only ever rendered when [DetailUiState.dividendInfo] is non-null — crypto, non-payers, and
 *  degraded fetches all present identically: no error state, no empty placeholder. Material3
 *  port of desktop `DetailPane.kt`'s `DividendCard` (`AssetDetailView.dividendSection` on
 *  macOS), styled after this app's own `IncomeScreen.kt` card idiom (`Surface` +
 *  `surfaceVariant` fill) rather than the desktop designkit's `StatCard`. */
@Composable
private fun DividendCard(info: DividendInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                tr(L10n.Key.AssetDividendSection).uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        tr(L10n.Key.AssetDividendYield).uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatPercent(info.yieldFraction * 100),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        tr(L10n.Key.AssetDividendRate).uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        money(info.trailingAnnualRate.amountText),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (info.nextEstimatedExDateEpochSeconds != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(L10n.Key.AssetNextExDate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        dividendExDateFormatter.format(Instant.ofEpochSecond(info.nextEstimatedExDateEpochSeconds)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
                        Text(
                            tr(L10n.Key.IncomeEstimatedBadge).uppercase(Locale.US),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            if (info.recentAmounts.isNotEmpty()) {
                DividendMiniChart(info.recentAmounts)
            }
        }
    }
}

/** A small bar mini-chart of the last (up to) 8 per-share amounts, oldest first — no axes or
 *  labels, just the shape of the trend (bars read better than a continuous line for discrete
 *  per-payout amounts). Material3 port of desktop `DetailPane.kt`'s `DividendMiniChart`. */
@Composable
private fun DividendMiniChart(amounts: List<Money>) {
    val values = amounts.map { it.amount.doubleValue(false) }
    val maxValue = values.maxOrNull() ?: 0.0
    Row(
        Modifier.fillMaxWidth().height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (value in values) {
            val barHeight = if (maxValue > 0.0) (36.0 * value / maxValue).coerceAtLeast(3.0) else 3.0
            Box(
                Modifier
                    .weight(1f)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
            )
        }
    }
}
