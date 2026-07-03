package com.aptrade.desktop.detail

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.AppGraph
import com.aptrade.desktop.LocalAppGraph
import com.aptrade.desktop.designkit.CandleChart
import com.aptrade.desktop.designkit.ChangePill
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.LineChart
import com.aptrade.desktop.designkit.StatTile
import com.aptrade.desktop.designkit.SuperscriptPrice
import com.aptrade.desktop.designkit.TimeframeBar
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.designkit.splitPrice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.collectAsState

/** Right-hand asset detail. A fresh `DetailViewModel` (with its own single-thread
 *  scope) is built per selection so a stale load dies with its symbol. Empty when
 *  nothing is selected. */
@Composable
fun DetailPane(selectedSymbol: String?) {
    if (selectedSymbol == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Select a symbol",
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textTertiary,
                ),
            )
        }
        return
    }

    val graph: AppGraph = LocalAppGraph.current
    val scope = remember(selectedSymbol) { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val vm = remember(selectedSymbol) {
        DetailViewModel(
            symbol = selectedSymbol,
            fetchProfile = graph.fetchProfile,
            fetchMarketQuotes = graph.fetchMarketQuotes,
            fetchHistory = graph.fetchHistory,
            fetchCandles = graph.fetchCandles,
            scope = scope,
        )
    }
    DisposableEffect(selectedSymbol) { onDispose { scope.cancel() } }

    val state by vm.state.collectAsState()
    DetailContent(
        state = state,
        onTimeframeChange = vm::onTimeframeChange,
        onModeChange = vm::onModeChange,
        onRetry = vm::retryChart,
    )
}

@Composable
private fun DetailContent(
    state: DetailUiState,
    onTimeframeChange: (com.aptrade.shared.domain.Timeframe) -> Unit,
    onModeChange: (ChartMode) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                state.name ?: state.symbol,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DK.textPrimary,
                ),
            )
            state.kindLabel?.let { KindChip(it) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.amountText != null) {
                SuperscriptPrice(amountText = state.amountText, size = 34.sp)
            } else {
                Text(
                    "—",
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DK.textSecondary,
                    ),
                )
            }
            ChangePill(changePercent = state.changePercent)
        }
        Spacer(Modifier.height(18.dp))
        TimeframeBar(selection = state.timeframe, onSelect = onTimeframeChange)
        Spacer(Modifier.height(12.dp))
        ModeToggle(mode = state.mode, onModeChange = onModeChange)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            when {
                state.isLoadingChart -> CircularProgressIndicator(color = DK.gold)
                state.chartError != null -> ChartError(message = state.chartError, onRetry = onRetry)
                state.mode == ChartMode.Line ->
                    LineChart(values = state.lineValues, modifier = Modifier.fillMaxSize(), color = DK.gold)
                else ->
                    CandleChart(candles = state.candles, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(20.dp))
        StatGrid(state)
    }
}

@Composable
private fun KindChip(label: String) {
    Text(
        label.uppercase(),
        style = TextStyle(
            fontFamily = InterFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = DK.textSecondary,
            letterSpacing = 0.8.sp,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Line / Candles underline toggle — the TimeframeBar idiom for two labels. */
@Composable
private fun ModeToggle(mode: ChartMode, onModeChange: (ChartMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        for (m in ChartMode.entries) {
            val selected = m == mode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onModeChange(m) },
            ) {
                Text(
                    if (m == ChartMode.Line) "Line" else "Candles",
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) DK.gold else DK.textSecondary,
                    ),
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .height(2.dp)
                        .width(28.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (selected) DK.gold else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun ChartError(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            message,
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = DK.textSecondary,
            ),
        )
        Text(
            "Retry",
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = DK.gold,
            ),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onRetry() },
        )
    }
}

@Composable
private fun StatGrid(state: DetailUiState) {
    val changeColor = DK.changeColor(state.changePercent)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                StatTile(
                    label = "Price",
                    value = state.amountText?.let { "$" + splitPrice(it).let { p -> p.whole + "." + p.fraction } } ?: "—",
                )
            }
            Box(Modifier.weight(1f)) {
                StatTile(
                    label = "Change",
                    value = formatPercent(state.changePercent),
                    valueColor = changeColor,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                StatTile(
                    label = "Prev Close",
                    value = state.previousCloseText?.let { "$" + splitPrice(it).let { p -> p.whole + "." + p.fraction } } ?: "—",
                )
            }
            Box(Modifier.weight(1f)) {
                StatTile(label = "Kind", value = state.kindLabel ?: "—")
            }
        }
    }
}
