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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.StatTile

/** The Portfolio tab's analytics surface — the Compose port of
 *  `Sources/APTradeApp/PerformanceSection.swift`. Sits below the P&L chart block: a
 *  "PERFORMANCE" header, a segmented benchmark picker (SPY/QQQ/VTI, gold-selected KindToggle
 *  idiom), a 200dp overlay chart of two rebased-to-100 curves (portfolio gold, benchmark
 *  silver), and a 7-tile risk-metric grid. All state is read from [PortfolioUiState]; the
 *  benchmark switch is raised through [onSetBenchmark] (a one-shot report refetch on the VM). */
@Composable
fun PerformanceSection(
    state: PortfolioUiState,
    onSetBenchmark: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "PERFORMANCE",
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = DK.textSecondary, letterSpacing = 1.8.sp,
            ),
        )
        BenchmarkPicker(selection = state.benchmark, options = state.benchmarks, onSelect = onSetBenchmark)
        OverlayChart(portfolio = state.performanceRebased, benchmark = state.benchmarkRebased)
        MetricGrid(metrics = state.metrics)
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

/** 200dp overlay of the two rebased-to-100 curves on a shared y-axis: portfolio in gold,
 *  benchmark in silver. When the benchmark curve is null (unavailable for this symbol/span),
 *  the whole plot is replaced by a "Benchmark unavailable" line. Both series are pre-rebased
 *  to a common 100-basis start by the VM, so a single min/max across both is a like-for-like
 *  vertical scale. */
@Composable
private fun OverlayChart(portfolio: List<Double>, benchmark: List<Double>?) {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        when {
            benchmark == null -> Text(
                "Benchmark unavailable",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, color = DK.textTertiary,
                ),
            )
            else -> {
                val all = portfolio + benchmark
                val min = all.minOrNull() ?: 0.0
                val max = all.maxOrNull() ?: 1.0
                val span = (max - min).takeIf { it > 0.0 } ?: 1.0
                Canvas(Modifier.fillMaxSize()) {
                    fun drawSeries(values: List<Double>, color: Color) {
                        if (values.size < 2) return
                        val stepX = size.width / (values.size - 1)
                        val path = Path()
                        values.forEachIndexed { i, v ->
                            val x = i * stepX
                            val y = size.height - ((v - min) / span * size.height).toFloat()
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    // Benchmark first, so the gold portfolio line reads on top where they cross.
                    drawSeries(benchmark, DK.silver)
                    drawSeries(portfolio, DK.gold)
                }
            }
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
