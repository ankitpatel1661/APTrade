package com.aptrade.desktop.portfolio

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.DonutChart
import com.aptrade.desktop.designkit.DonutSlice
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.LineChart
import com.aptrade.desktop.designkit.LiveBadge
import com.aptrade.desktop.designkit.StatTile
import com.aptrade.desktop.designkit.SuperscriptPrice
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.shared.domain.AllocationSlice

/** The three content sections beneath the summary and chart. */
private enum class PortfolioSection(val label: String) {
    Holdings("Holdings"), Allocation("Allocation"), Activity("Activity")
}

/** Portfolio tab: the Compose port of `Sources/APTradeApp/PortfolioView.swift`. A full-width
 *  column — summary header, an expandable P&L chart block (span bar + gold LineChart of the
 *  account-value series), a Holdings / Allocation / Activity section switcher, and the section
 *  content. All state is read from [PortfolioUiState]; trades are raised through [onTrade],
 *  which the host opens as a [TradeDialog] overlaying the whole window. Row clicks open the
 *  existing full-window detail via [onOpenDetail]. */
@Composable
fun PortfolioPane(
    state: PortfolioUiState,
    onSetSpan: (PortfolioSpan) -> Unit,
    onSetBenchmark: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onTrade: (symbol: String, side: com.aptrade.shared.domain.TradeSide) -> Unit,
    onReset: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onExportPdf: () -> Unit,
) {
    var section by remember { mutableStateOf(PortfolioSection.Holdings) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // The whole pane scrolls as one column: summary → P&L chart → PERFORMANCE → section
    // switcher → section content. The bounded paper-trading portfolio makes plain Columns
    // (not nested LazyColumns) the right fit here — everything flows under a single scroll.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SummaryHeader(
            state = state,
            onReset = { showResetConfirm = true },
            onExportCsv = onExportCsv,
            onExportJson = onExportJson,
            onExportPdf = onExportPdf,
        )
        ChartBlock(state = state, onSetSpan = onSetSpan)
        if (state.holdings.isNotEmpty()) {
            PerformanceSection(
                state = state,
                onSetBenchmark = onSetBenchmark,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp),
            )
            SectionSwitcher(
                selected = section,
                onSelect = { section = it },
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
        if (state.holdings.isEmpty()) {
            EmptyState()
        } else {
            when (section) {
                PortfolioSection.Holdings -> HoldingsList(state, onOpenDetail, onTrade)
                PortfolioSection.Allocation -> AllocationView(state)
                PortfolioSection.Activity -> ActivityView(state)
            }
        }
    }

    if (showResetConfirm) {
        ResetConfirmDialog(
            onConfirm = { showResetConfirm = false; onReset() },
            onCancel = { showResetConfirm = false },
        )
    }
}

// MARK: - Summary

@Composable
private fun SummaryHeader(
    state: PortfolioUiState,
    onReset: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onExportPdf: () -> Unit,
) {
    var exportOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(
                    "TOTAL VALUE",
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = DK.textSecondary, letterSpacing = 1.8.sp,
                    ),
                )
                if (state.totalValueText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SuperscriptPrice(amountText = state.totalValueText, size = 34.sp)
                        if (state.dayChangeText != null) {
                            SignedMoneyPill(text = state.dayChangeText, positive = state.dayChangePositive)
                        }
                    }
                } else {
                    Text(
                        "—",
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 34.sp,
                            fontWeight = FontWeight.SemiBold, color = DK.textSecondary,
                        ),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.holdings.isNotEmpty()) LiveBadge()
                Box {
                    TextButton("Export…", DK.textSecondary) { exportOpen = true }
                    if (exportOpen) {
                        ExportChooser(
                            onDismiss = { exportOpen = false },
                            onExportCsv = { exportOpen = false; onExportCsv() },
                            onExportJson = { exportOpen = false; onExportJson() },
                            onExportPdf = { exportOpen = false; onExportPdf() },
                        )
                    }
                }
                TextButton("Reset portfolio…", DK.textTertiary, onReset)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            StatTile(label = "Cash", value = state.cashText ?: "—")
            StatTile(label = "Holdings", value = state.holdingsValueText ?: "—")
            StatTile(
                label = "Unrealized P&L",
                value = state.unrealizedText ?: "—",
                valueColor = signColor(state.unrealizedPositive),
            )
            StatTile(
                label = "Realized P&L",
                value = state.realizedText ?: "—",
                valueColor = signColor(state.realizedPositive),
            )
        }
    }
}

/** ChangePill-shaped capsule for signed day-change money, colored by direction. */
@Composable
private fun SignedMoneyPill(text: String, positive: Boolean?) {
    val color = signColor(positive)
    Text(
        text,
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = color, fontFeatureSettings = "tnum",
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** The unified export menu: a small DK-styled popup panel anchored under the "Export…" button
 *  with CSV / JSON / PDF entries. Dismisses on any selection, on a click outside, and on Esc —
 *  the Esc handler runs on the panel's own `onPreviewKeyEvent` (TradeDialog pattern) so it
 *  never reaches the window's Esc-priority chain. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExportChooser(
    onDismiss: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onExportPdf: () -> Unit,
) {
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, 26),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(160.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(10.dp))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss(); true
                    } else {
                        false
                    }
                }
                .padding(vertical = 6.dp),
        ) {
            ExportChooserItem("CSV", onExportCsv)
            ExportChooserItem("JSON", onExportJson)
            ExportChooserItem("PDF", onExportPdf)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExportChooserItem(label: String, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    Text(
        label,
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .background(if (hovered) DK.surfaceHi else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun TextButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
        ),
        modifier = Modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
    )
}

// MARK: - P&L chart

@Composable
private fun ChartBlock(state: PortfolioUiState, onSetSpan: (PortfolioSpan) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
        SpanBar(selection = state.span, onSelect = onSetSpan)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
            // On MAX, a portfolio that has traded but has fewer than two performance points is
            // day-one: the tracking curve fills in from the first market close, not instantly.
            val maxDayOne = state.span == PortfolioSpan.Max &&
                state.transactions.isNotEmpty() && state.performance.size < 2
            when {
                state.isLoadingPerformance -> CircularProgressIndicator(color = DK.gold)
                state.performance.size > 1 -> LineChart(values = state.performance, modifier = Modifier.fillMaxSize(), color = DK.gold)
                maxDayOne -> Text(
                    "Tracking starts today — performance appears after your first market day.",
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, color = DK.textTertiary,
                    ),
                )
                else -> Text(
                    "No performance data yet.",
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, color = DK.textTertiary,
                    ),
                )
            }
        }
    }
}

/** The TimeframeBar idiom extended to the five portfolio spans (1D · 1W · 1M · 1Y · MAX). */
@Composable
private fun SpanBar(selection: PortfolioSpan, onSelect: (PortfolioSpan) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        for (span in PortfolioSpan.entries) {
            val selected = span == selection
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(span) },
            ) {
                Text(
                    span.label,
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (selected) DK.gold else DK.textSecondary, fontFeatureSettings = "tnum",
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.height(2.dp).fillMaxWidth(0.6f).clip(RoundedCornerShape(1.dp))
                        .background(if (selected) DK.gold else Color.Transparent),
                )
            }
        }
    }
}

// MARK: - Section switcher

@Composable
private fun SectionSwitcher(
    selected: PortfolioSection,
    onSelect: (PortfolioSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in PortfolioSection.entries) {
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) DK.surfaceHi else Color.Transparent)
                    .then(
                        if (isSelected) Modifier.border(1.dp, DK.gold.copy(alpha = 0.40f), RoundedCornerShape(50))
                        else Modifier,
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    option.label,
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) DK.textPrimary else DK.textSecondary,
                    ),
                )
            }
        }
    }
}

// MARK: - Holdings

@Composable
private fun HoldingsList(
    state: PortfolioUiState,
    onOpenDetail: (String) -> Unit,
    onTrade: (String, com.aptrade.shared.domain.TradeSide) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        for (row in state.holdings) {
            HoldingRow(row = row, onClick = { onOpenDetail(row.symbol) }, onTrade = onTrade)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HoldingRow(
    row: HoldingRowUi,
    onClick: () -> Unit,
    onTrade: (String, com.aptrade.shared.domain.TradeSide) -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) DK.surfaceHi else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
                ),
                maxLines = 1,
            )
            Text(
                "${row.symbol}   ${row.quantityText} @ ${row.averageCostText}",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = DK.textSecondary, fontFeatureSettings = "tnum",
                ),
            )
        }
        if (hovered) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TextButton("BUY", DK.gold) { onTrade(row.symbol, com.aptrade.shared.domain.TradeSide.Buy) }
                TextButton("SELL", DK.gold) { onTrade(row.symbol, com.aptrade.shared.domain.TradeSide.Sell) }
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            SuperscriptPrice(amountText = row.marketValueText, size = 18.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                signedText(row.unrealizedText, row.unrealizedPositive),
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = signColor(row.unrealizedPositive), fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

// MARK: - Allocation

@Composable
private fun AllocationView(state: PortfolioUiState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(20.dp))
        AllocationDonutRow(state)
        Spacer(Modifier.height(24.dp))
        AllocationGroupHeader("BY HOLDING")
        Spacer(Modifier.height(12.dp))
        for (slice in state.allocationByHolding) {
            AllocationBar(label = slice.label, fraction = slice.fraction, fillColor = null)
            Spacer(Modifier.height(14.dp))
        }
        Spacer(Modifier.height(10.dp))
        AllocationGroupHeader("BY CLASS")
        Spacer(Modifier.height(12.dp))
        for (slice in state.allocationByKind) {
            AllocationBar(label = slice.label, fraction = slice.fraction, fillColor = kindColor(slice.id))
            Spacer(Modifier.height(14.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The allocation donut (150dp) with a HOLDINGS + total-value center overlay, beside a manual
 *  legend (dot · class label · right-aligned percent). Slices are the by-class breakdown in
 *  Stock/ETF/Crypto order with zero slices omitted (the source list already drops them). */
@Composable
private fun AllocationDonutRow(state: PortfolioUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        DonutChart(
            slices = state.allocationByKind.map { DonutSlice(fraction = it.fraction, color = donutColor(it.id)) },
        ) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "HOLDINGS",
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                        color = DK.textTertiary, letterSpacing = 1.2.sp,
                    ),
                )
                Text(
                    state.holdingsValueText ?: "—",
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = DK.textPrimary, fontFeatureSettings = "tnum",
                    ),
                    maxLines = 1,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (slice in state.allocationByKind) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(9.dp).height(9.dp).clip(RoundedCornerShape(50)).background(donutColor(slice.id)))
                    Text(
                        slice.label,
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = DK.textPrimary,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        String.format(java.util.Locale.US, "%.1f%%", slice.fraction * 100),
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = DK.textSecondary, fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }
        }
    }
}

/** Donut + legend slice colors (brief: Stock=gold, ETF=goldDeep, Crypto=silver), keyed by
 *  `AllocationSlice.id` (the `AssetKind` name). Distinct from the by-class BAR colors below. */
private fun donutColor(id: String): Color = when (id) {
    "Stock" -> DK.gold
    "Etf" -> DK.goldDeep
    "Crypto" -> DK.silver
    else -> DK.textTertiary
}

@Composable
private fun AllocationGroupHeader(text: String) {
    Text(
        text,
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = DK.textTertiary, letterSpacing = 1.4.sp,
        ),
    )
}

/** A labeled fraction bar. `fillColor == null` uses the gold gradient (by-holding); the
 *  by-class bars pass a solid per-kind color. */
@Composable
private fun AllocationBar(label: String, fraction: Double, fillColor: Color?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
                ),
            )
            Text(
                formatPercent(fraction * 100),
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = DK.textSecondary, fontFeatureSettings = "tnum",
                ),
            )
        }
        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)).background(DK.surfaceHi)) {
            val f = fraction.coerceIn(0.0, 1.0).toFloat()
            Box(
                Modifier
                    .fillMaxWidth(if (f <= 0f) 0.01f else f)
                    .height(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(fillColor?.let { androidx.compose.ui.graphics.SolidColor(it) } ?: DK.goldGradient),
            )
        }
    }
}

/** By-class bar colors, keyed by `AllocationSlice.id` (the AssetKind name). */
private fun kindColor(id: String): Color = when (id) {
    "Stock" -> DK.gold
    "Etf" -> DK.silver
    "Crypto" -> DK.goldDeep
    else -> DK.textTertiary
}

// MARK: - Activity

@Composable
private fun ActivityView(state: PortfolioUiState) {
    if (state.transactions.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            Text(
                "No transactions yet.",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, color = DK.textSecondary,
                ),
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        for (txn in state.transactions) {
            TransactionRow(txn)
        }
    }
}

@Composable
private fun TransactionRow(txn: TransactionRowUi) {
    val sideColor = if (txn.isBuy) DK.up else DK.down
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(44.dp).height(22.dp)
                .clip(RoundedCornerShape(50)).background(sideColor.copy(alpha = 0.12f)),
        ) {
            Text(
                txn.sideLabel.uppercase(),
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = sideColor, letterSpacing = 0.8.sp,
                ),
            )
        }
        Text(
            txn.symbol,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            "${txn.quantityText} @ ${txn.priceText}",
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = DK.textSecondary, fontFeatureSettings = "tnum",
            ),
        )
    }
}

// MARK: - Empty state & reset

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().height(320.dp).padding(40.dp), contentAlignment = Alignment.Center) {
        Text(
            "No holdings yet — open an asset and hit Buy.",
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, color = DK.textTertiary,
            ),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResetConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                // Consume Esc on the panel's own preview handler (TradeDialog pattern) so it
                // dismisses the dialog before ever reaching the window's Esc-priority chain.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onCancel(); true
                    } else {
                        false
                    }
                }
                .focusRequester(focusRequester)
                .focusable()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Start over with $100,000?",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f).clip(RoundedCornerShape(50)).background(DK.surface)
                        .border(1.dp, DK.hairline, RoundedCornerShape(50))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        "Cancel",
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = DK.textSecondary,
                        ),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f).clip(RoundedCornerShape(50)).background(DK.down.copy(alpha = 0.16f))
                        .border(1.dp, DK.down.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onConfirm() }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        "Confirm",
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, color = DK.down,
                        ),
                    )
                }
            }
        }
    }
    // Focus the panel on open so its onPreviewKeyEvent receives Esc.
    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

// MARK: - Shared helpers

/** Positive → up, negative → down, null (empty portfolio) → neutral primary text. */
private fun signColor(positive: Boolean?): Color = when (positive) {
    true -> DK.up
    false -> DK.down
    null -> DK.textPrimary
}

/** Prefix a "+" when the value is a gain and the pre-formatted text lacks a sign; losses
 *  already carry their "-" from Money.amountText. */
private fun signedText(text: String, positive: Boolean?): String =
    if (positive == true && !text.startsWith("+") && !text.startsWith("-")) "+$text" else text
