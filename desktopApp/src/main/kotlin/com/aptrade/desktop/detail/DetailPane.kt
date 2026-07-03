package com.aptrade.desktop.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.designkit.signedMoney
import com.aptrade.desktop.portfolio.HoldingRowUi
import com.aptrade.desktop.ui.assetKindFromLabel
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.collectAsState

/** Full-window asset detail. A fresh `DetailViewModel` (with its own single-thread
 *  scope) is built per `symbol` so a stale load dies with its symbol. A 48dp back bar
 *  sits above the existing `DetailContent`. `onBuy` (asset + live price text) opens the
 *  paper-trade dialog with Buy preselected (the dialog's toggle covers Sell).
 *  `heldPosition` is the portfolio row for this symbol (or null) — the YOUR POSITION card
 *  reads it directly; no second portfolio store read path. */
@Composable
fun DetailScreen(
    symbol: String,
    onBack: () -> Unit,
    heldPosition: HoldingRowUi? = null,
    onBuy: ((com.aptrade.shared.domain.Asset, String?) -> Unit)? = null,
) {
    val graph: AppGraph = LocalAppGraph.current
    val scope = remember(symbol) { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val vm = remember(symbol) {
        DetailViewModel(
            symbol = symbol,
            fetchProfile = graph.fetchProfile,
            fetchMarketQuotes = graph.fetchMarketQuotes,
            fetchHistory = graph.fetchHistory,
            fetchCandles = graph.fetchCandles,
            scope = scope,
        )
    }
    DisposableEffect(symbol) { onDispose { scope.cancel() } }

    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹  Back",
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textSecondary,
                ),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBack() },
            )
        }
        DetailContent(
            state = state,
            heldPosition = heldPosition,
            onTimeframeChange = vm::onTimeframeChange,
            onModeChange = vm::onModeChange,
            onIndicatorsActiveChange = vm::onIndicatorsActiveChange,
            onRetry = vm::retryChart,
            onBuy = onBuy,
        )
    }
}

@Composable
private fun DetailContent(
    state: DetailUiState,
    heldPosition: HoldingRowUi?,
    onTimeframeChange: (com.aptrade.shared.domain.Timeframe) -> Unit,
    onModeChange: (ChartMode) -> Unit,
    onIndicatorsActiveChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onBuy: ((com.aptrade.shared.domain.Asset, String?) -> Unit)? = null,
) {
    // Indicator selection is view-local UI state (macOS parity: view @State), none on by default.
    var selection by remember { mutableStateOf(emptySet<Indicator>()) }
    // Report to the VM so it fetches candles in Line mode when any indicator is on.
    LaunchedEffect(selection.isNotEmpty()) { onIndicatorsActiveChange(selection.isNotEmpty()) }

    val series = remember(state.candles, selection) { computeIndicators(state.candles, selection) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
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
            if (onBuy != null) {
                Spacer(Modifier.weight(1f))
                TradeButton(
                    onClick = {
                        val asset = com.aptrade.shared.domain.Asset(
                            symbol = state.symbol,
                            name = state.name ?: state.symbol,
                            kind = assetKindFromLabel(state.kindLabel),
                        )
                        onBuy(asset, state.amountText)
                    },
                )
            }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeToggle(mode = state.mode, onModeChange = onModeChange)
            Spacer(Modifier.width(16.dp))
            IndicatorChips(
                selection = selection,
                onToggle = { ind ->
                    selection = if (selection.contains(ind)) selection - ind else selection + ind
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            when {
                state.isLoadingChart -> CircularProgressIndicator(color = DK.gold)
                state.chartError != null -> ChartError(message = state.chartError, onRetry = onRetry)
                // Any overlay on → draw the price line from candles so overlays align on one
                // index space. Otherwise the original Line/Candles rendering.
                selection.any { it.isOverlay } && state.candles.size >= 2 ->
                    PriceChartWithOverlays(
                        candles = state.candles,
                        series = series,
                        selection = selection,
                        lineColor = DK.changeColor(state.changePercent),
                        modifier = Modifier.fillMaxSize(),
                    )
                state.mode == ChartMode.Line ->
                    LineChart(values = state.lineValues, modifier = Modifier.fillMaxSize(), color = DK.gold)
                else ->
                    CandleChart(candles = state.candles, modifier = Modifier.fillMaxSize())
            }
        }
        if (selection.contains(Indicator.Rsi) && state.candles.size >= 2) {
            Spacer(Modifier.height(16.dp))
            RsiPane(series = series)
        }
        if (selection.contains(Indicator.Macd) && state.candles.size >= 2) {
            Spacer(Modifier.height(16.dp))
            MacdPane(series = series)
        }
        Spacer(Modifier.height(24.dp))
        KeyStatsCard(state)
        if (heldPosition != null) {
            Spacer(Modifier.height(16.dp))
            PositionCard(heldPosition)
        }
    }
}

/** Gold paper-trade entry point in the detail header. macOS ships separate Buy/Sell buttons;
 *  desktop diverges to a single "BUY / SELL" pill that opens the dialog with Buy preselected —
 *  the dialog's own toggle covers Sell. */
@Composable
private fun TradeButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.goldGradient)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            "BUY / SELL",
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DK.bgBottom,
                letterSpacing = 0.8.sp,
            ),
        )
    }
}

/** Horizontally scrolling row of multi-select indicator chips, each with a leading 6dp
 *  colored dot (dimmed when off). macOS parity: none on by default. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IndicatorChips(selection: Set<Indicator>, onToggle: (Indicator) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (ind in Indicator.entries) {
            val on = selection.contains(ind)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) DK.surfaceHi else DK.surface)
                    .border(1.dp, if (on) ind.color.copy(alpha = 0.5f) else DK.hairline, RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggle(ind) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Box(Modifier.size(6.dp).background(ind.color.copy(alpha = if (on) 1f else 0.4f), CircleShape))
                Text(
                    ind.label,
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (on) DK.textPrimary else DK.textTertiary,
                    ),
                )
            }
        }
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

/** Card header with wide tracking, matching macOS KEY STATS / YOUR POSITION section labels. */
@Composable
private fun CardHeader(title: String) {
    Text(
        title,
        style = TextStyle(
            fontFamily = InterFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = DK.textSecondary,
            letterSpacing = 1.8.sp,
        ),
    )
}

/** Two-column card container (surface at 0.5 alpha, 14dp radius, hairline border, 20dp pad). */
@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DK.surface.copy(alpha = 0.5f))
            .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
            .padding(20.dp),
    ) {
        CardHeader(title)
        content()
    }
}

/** One row of the 2-column stat grid. */
@Composable
private fun StatRow(
    leftLabel: String, leftValue: String, leftColor: Color = DK.textPrimary,
    rightLabel: String, rightValue: String, rightColor: Color = DK.textPrimary,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.weight(1f)) { StatTile(label = leftLabel, value = leftValue, valueColor = leftColor) }
        Box(Modifier.weight(1f)) { StatTile(label = rightLabel, value = rightValue, valueColor = rightColor) }
    }
}

/** KEY STATS: Last, Previous close, Day change, Day change %, Symbol, Type. Money figures are
 *  formatted HERE via formatMoney/signedMoney from raw amountText (contract: raw money never
 *  goes through SuperscriptPrice/Money.usd from these cards). */
@Composable
private fun KeyStatsCard(state: DetailUiState) {
    val changeColor = DK.changeColor(state.changePercent)
    val last = state.amountText
    val prevClose = state.previousCloseText
    val dayChange = if (last != null && prevClose != null) {
        signedMoney((BigDecimal(last) - BigDecimal(prevClose)).toPlainString())
    } else "—"
    StatCard(title = "KEY STATS") {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            StatRow(
                leftLabel = "Last", leftValue = last?.let { formatMoney(it) } ?: "—",
                rightLabel = "Previous close", rightValue = prevClose?.let { formatMoney(it) } ?: "—",
            )
            StatRow(
                leftLabel = "Day change", leftValue = dayChange, leftColor = changeColor,
                rightLabel = "Day change %", rightValue = formatPercent(state.changePercent), rightColor = changeColor,
            )
            StatRow(
                leftLabel = "Symbol", leftValue = state.symbol,
                rightLabel = "Type", rightValue = state.kindLabel ?: "—",
            )
        }
    }
}

/** YOUR POSITION (held only): Shares, Average cost, Market value, Unrealized P&L. The row's
 *  averageCost/unrealized are pre-formatted; marketValue/price are RAW → formatted here for
 *  plain display (never fed to SuperscriptPrice). */
@Composable
private fun PositionCard(row: HoldingRowUi) {
    val pnlColor = when (row.unrealizedPositive) {
        true -> DK.up
        false -> DK.down
        null -> DK.textPrimary
    }
    StatCard(title = "YOUR POSITION") {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            StatRow(
                leftLabel = "Shares", leftValue = row.quantityText,
                rightLabel = "Average cost", rightValue = row.averageCostText,
            )
            StatRow(
                leftLabel = "Market value", leftValue = formatMoney(row.marketValueText),
                rightLabel = "Unrealized P&L", rightValue = row.unrealizedText, rightColor = pnlColor,
            )
        }
    }
}
