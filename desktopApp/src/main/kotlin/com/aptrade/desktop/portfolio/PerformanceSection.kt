package com.aptrade.desktop.portfolio

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.StatTile
import com.aptrade.desktop.designkit.crosshairIndex

/** The Portfolio tab's chart-and-analytics surface — the Compose port of
 *  `Sources/APTradeApp/PerformanceSection.swift`, promoted (Task 12) to be THE performance
 *  chart block directly under the summary header. A header row carries the "PERFORMANCE"
 *  label, a live hovered-delta readout (macOS ExpandedValueCard parity), the five-span
 *  [SpanBar], and the SPY/QQQ/VTI benchmark picker; below it a crosshair-scrubbed 200dp
 *  overlay chart (portfolio gold, benchmark silver) and a 7-tile risk-metric grid. All state
 *  is read from [PortfolioUiState]; span/benchmark switches are raised through [onSetSpan] /
 *  [onSetBenchmark] (a one-shot report refetch on the VM). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PerformanceSection(
    state: PortfolioUiState,
    onSetSpan: (PortfolioSpan) -> Unit,
    onSetBenchmark: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val points = state.performancePoints
    // Hover index is shared between the header readout and the chart crosshair; null → not
    // hovering, which the readout and marker resolve to the LAST index (Exit → latest, exactly
    // like the watchlist header card).
    var hoverIndex by remember { mutableStateOf<Int?>(null) }
    val lastIndex = (points.size - 1).coerceAtLeast(0)
    val activeIndex = (hoverIndex ?: lastIndex).coerceIn(0, lastIndex)
    val activePoint = points.getOrNull(activeIndex)

    // On MAX, a portfolio that has traded but has fewer than two performance points is day-one:
    // the tracking curve fills in from the first market close, not instantly.
    val maxDayOne = state.span == PortfolioSpan.Max &&
        state.transactions.isNotEmpty() && points.size < 2

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PERFORMANCE",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = DK.textSecondary, letterSpacing = 1.8.sp,
                ),
            )
            // Live readout right of the label: the hovered point's pre-formatted deltaText,
            // colored by direction. Rendered verbatim (PerfPointUi text is display-only).
            if (activePoint != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    activePoint.deltaText,
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (activePoint.isUp) DK.up else DK.down, fontFeatureSettings = "tnum",
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            BenchmarkPicker(selection = state.benchmark, options = state.benchmarks, onSelect = onSetBenchmark)
        }
        SpanBar(selection = state.span, onSelect = onSetSpan)
        OverlayChart(
            portfolio = state.performanceValues,
            benchmark = state.benchmarkTwinValues,
            points = points,
            activeIndex = activeIndex,
            isHovering = hoverIndex != null,
            maxDayOne = maxDayOne,
            onHoverIndex = { hoverIndex = it },
        )
        MetricGrid(metrics = state.metrics)
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

/** Segmented SPY/QQQ/VTI picker in the app's gold-selected capsule idiom (KindToggle). */
@Composable
private fun BenchmarkPicker(selection: String, options: List<String>, onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        for (option in options) {
            val selected = option == selection
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) DK.surfaceHi else Color.Transparent)
                    .then(
                        if (selected) Modifier.border(1.dp, DK.gold.copy(alpha = 0.40f), RoundedCornerShape(50))
                        else Modifier,
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(option) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(
                    option,
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (selected) DK.gold else DK.textSecondary, fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

/** 200dp crosshair-scrubbed overlay of the two DOLLAR-valued curves on a shared y-axis:
 *  portfolio in gold, the cash-flow-replay benchmark twin in silver. Hover → nearest index via
 *  the shared designkit [crosshairIndex] helper; a dashed (3,3) hairline vertical + a small
 *  filled gold circle mark the PORTFOLIO polyline at the index (the benchmark line gets no
 *  marker — macOS single-series parity), and a tooltip pill near the crosshair shows the hovered
 *  point's valueText + tooltipDateText. When the benchmark twin is null the whole plot is
 *  replaced by a "Benchmark unavailable" line; on MAX day-one the plot shows the tracking-starts
 *  message. Both series are actual dollars aligned 1:1 (Task 1 construction), so a single min/max
 *  across both is a like-for-like vertical scale; [points] (same length/order) supplies the
 *  scrubber's display text. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun OverlayChart(
    portfolio: List<Double>,
    benchmark: List<Double>?,
    points: List<PerfPointUi>,
    activeIndex: Int,
    isHovering: Boolean,
    maxDayOne: Boolean,
    onHoverIndex: (Int?) -> Unit,
) {
    var chartWidthPx by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        when {
            maxDayOne -> Text(
                "Tracking starts today — performance appears after your first market day.",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, color = DK.textTertiary,
                ),
            )
            benchmark == null -> Text(
                "Benchmark unavailable",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, color = DK.textTertiary,
                ),
            )
            portfolio.size < 2 -> Text(
                "No performance data yet.",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, color = DK.textTertiary,
                ),
            )
            else -> {
                val all = portfolio + benchmark
                val min = all.minOrNull() ?: 0.0
                val max = all.maxOrNull() ?: 1.0
                val span = (max - min).takeIf { it > 0.0 } ?: 1.0
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
                            onHoverIndex(crosshairIndex(x, chartWidthPx, portfolio.size))
                        }
                        .onPointerEvent(PointerEventType.Exit) { onHoverIndex(null) },
                ) {
                    chartWidthPx = size.width
                    val stepX = size.width / (portfolio.size - 1)
                    fun y(v: Double) = size.height - ((v - min) / span * size.height).toFloat()
                    fun drawSeries(values: List<Double>, color: Color) {
                        if (values.size < 2) return
                        val path = Path()
                        values.forEachIndexed { i, v ->
                            val x = i * stepX
                            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                        }
                        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    // Benchmark first, so the gold portfolio line reads on top where they cross.
                    drawSeries(benchmark, DK.silver)
                    drawSeries(portfolio, DK.gold)

                    // Crosshair on the PORTFOLIO polyline only: dashed (3,3) hairline vertical +
                    // small filled gold circle at the active index.
                    val idx = activeIndex.coerceIn(0, portfolio.size - 1)
                    val cx = idx * stepX
                    val cy = y(portfolio[idx])
                    drawLine(
                        DK.hairline, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f),
                    )
                    drawCircle(DK.gold, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
                }

                // Tooltip pill near the crosshair: valueText + tooltipDateText (display-only).
                val activePoint = points.getOrNull(activeIndex)
                if (activePoint != null && portfolio.size > 1) {
                    val fraction = activeIndex.toFloat() / (portfolio.size - 1)
                    CrosshairTooltip(
                        valueText = activePoint.valueText,
                        dateText = activePoint.tooltipDateText,
                        fraction = fraction,
                        emphasized = isHovering,
                    )
                }
            }
        }
    }
}

/** The floating pill next to the crosshair — value over date, DK surface with a hairline
 *  border. Horizontally biased to the crosshair position ([fraction] of chart width) and
 *  flipped to the left of the cursor past the midpoint so it never clips the right edge. */
@Composable
private fun CrosshairTooltip(
    valueText: String,
    dateText: String,
    fraction: Float,
    emphasized: Boolean,
) {
    val alignment = if (fraction > 0.5f) Alignment.TopEnd else Alignment.TopStart
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(alignment)
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DK.surface)
                .border(1.dp, if (emphasized) DK.gold.copy(alpha = 0.30f) else DK.hairline, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                valueText,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = DK.textPrimary, fontFeatureSettings = "tnum",
                ),
            )
            Text(
                dateText,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = DK.textTertiary,
                ),
            )
        }
    }
}

/** The 7-tile risk grid. Metric text (including "—" for nulls) comes straight from
 *  [MetricTexts]; when the report hasn't loaded yet every tile shows "—". */
@Composable
private fun MetricGrid(metrics: MetricTexts?) {
    val tiles = listOf(
        "Total Return" to (metrics?.totalReturn ?: "—"),
        "Annualized" to (metrics?.annualizedReturn ?: "—"),
        "Volatility" to (metrics?.volatility ?: "—"),
        "Max Drawdown" to (metrics?.maxDrawdown ?: "—"),
        "Sharpe" to (metrics?.sharpe ?: "—"),
        "Beta" to (metrics?.beta ?: "—"),
        "Alpha" to (metrics?.alpha ?: "—"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        tiles.chunked(4).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth()) {
                for ((label, value) in rowTiles) {
                    Box(Modifier.weight(1f)) { StatTile(label = label, value = value) }
                }
                // Pad the trailing row so tiles keep their column width instead of stretching.
                repeat(4 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
