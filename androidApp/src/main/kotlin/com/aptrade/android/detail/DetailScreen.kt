package com.aptrade.android.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.portfolio.TradeSheet
import com.aptrade.android.portfolio.TradeSheetInfo
import com.aptrade.android.ui.ErrorPane
import com.aptrade.android.ui.chart.CandleChart
import com.aptrade.android.ui.chart.LineChart
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeSide

private val timeframeLabels = listOf(
    Timeframe.OneDay to "1D",
    Timeframe.OneWeek to "1W",
    Timeframe.OneMonth to "1M",
    Timeframe.OneYear to "1Y",
)

@Composable
fun DetailScreen(symbol: String) {
    val portfolio = AppGraph.portfolio
    val viewModel: DetailViewModel = viewModel(key = symbol) {
        DetailViewModel(
            symbol = symbol,
            fetchProfile = AppGraph.fetchProfile,
            fetchHistory = AppGraph.fetchHistory,
            fetchCandles = AppGraph.fetchCandles,
            buyAsset = portfolio.buyAsset,
            sellAsset = portfolio.sellAsset,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        )
    }
    val state by viewModel.state.collectAsState()
    var tradeSide by remember { mutableStateOf<TradeSide?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.symbol, style = MaterialTheme.typography.headlineMedium)
                state.name?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.kindLabel?.let { AssistChip(onClick = {}, label = { Text(it) }) }
        }
        state.profileError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { tradeSide = TradeSide.Buy },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("BUY / SELL") }
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
        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth().height(240.dp)) {
            when {
                state.isLoadingChart -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.chartError != null ->
                    ErrorPane(state.chartError!!, onRetry = viewModel::retryChart, Modifier.align(Alignment.Center))
                state.mode == ChartMode.Line ->
                    LineChart(state.lineValues, Modifier.fillMaxSize())
                else ->
                    CandleChart(state.candles, Modifier.fillMaxSize())
            }
        }
    }

    tradeSide?.let { side ->
        TradeSheet(
            info = TradeSheetInfo(
                symbol = state.symbol,
                // Fall back to the symbol as the display name until the profile header resolves.
                name = state.name ?: state.symbol,
                priceText = null,
                initialSide = side,
            ),
            tradeError = state.tradeError,
            transactionCount = state.transactionCount,
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
