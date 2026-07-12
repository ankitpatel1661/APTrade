package com.aptrade.android.detail

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.portfolio.TradeSheet
import com.aptrade.android.portfolio.TradeSheetInfo
import com.aptrade.android.ui.ErrorPane
import com.aptrade.android.ui.chart.CandleChart
import com.aptrade.android.ui.chart.CrosshairOverlay
import com.aptrade.android.ui.chart.CrosshairTooltip
import com.aptrade.android.ui.chart.LineChart
import com.aptrade.android.ui.chart.crosshairReadout
import com.aptrade.android.ui.chart.nearestIndex
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeSide

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
            fetchCandles = AppGraph.fetchCandles,
            fetchMarketQuotes = AppGraph.fetchMarketQuotes,
            buyAsset = portfolio.buyAsset,
            sellAsset = portfolio.sellAsset,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
            notifyOrderFill = AppGraph.notifyOrderFill,
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
                    state.kindLabel?.let {
                        AssistChip(
                            onClick = {},
                            label = { Text(it) },
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

            // Span/mode selectors sit BELOW the chart (UAT request) — crosshair drag lives on
            // the chart's own Box so scrubbing near the top of the screen never fights a
            // FilterChip tap target above it.
            var dragX by remember { mutableStateOf<Float?>(null) }
            var chartWidthPx by remember { mutableStateOf(0f) }
            val pointCount = if (state.mode == ChartMode.Line) state.lineValues.size else state.candles.size

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .onSizeChanged { chartWidthPx = it.width.toFloat() }
                    .pointerInput(state.mode, pointCount) {
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
                    state.mode == ChartMode.Line ->
                        LineChart(state.lineValues, Modifier.fillMaxSize())
                    else ->
                        CandleChart(state.candles, Modifier.fillMaxSize())
                }

                // Crosshair: only meaningful once the chart has actually rendered (not loading,
                // no error) and there are at least 2 points to scrub between.
                if (!state.isLoadingChart && state.chartError == null && pointCount >= 2) {
                    dragX?.let { x ->
                        val index = nearestIndex(x, chartWidthPx, pointCount)
                        val curveValues = if (state.mode == ChartMode.Line) {
                            state.lineValues
                        } else {
                            state.candles.map { it.close }
                        }
                        CrosshairOverlay(curveValues, index, modifier = Modifier.fillMaxSize())
                        val readout = if (state.mode == ChartMode.Line) {
                            crosshairReadout(index, state.lineValueTexts, state.lineDates)
                        } else {
                            crosshairReadout(index, state.candleCloseTexts, state.candleDates)
                        }
                        readout?.let {
                            CrosshairTooltip(
                                priceText = it.priceText,
                                dateText = it.dateText,
                                fraction = index.toFloat() / (pointCount - 1),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
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
                }
            },
            onDismiss = { tradeSide = null },
        )
    }
}
