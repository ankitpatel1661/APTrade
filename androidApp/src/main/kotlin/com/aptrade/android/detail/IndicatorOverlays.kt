package com.aptrade.android.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.android.ui.chart.monotoneCubicLineTo
import com.aptrade.shared.domain.BollingerBand
import com.aptrade.shared.domain.MacdPoint
import com.aptrade.shared.domain.TechnicalIndicators
import com.aptrade.shared.l10n.L10n

/** The six chart indicators — Android port of desktop's `IndicatorOverlays.kt` `Indicator`
 *  enum. Overlays draw on the price chart; RSI/MACD get their own sub-pane. Chip labels
 *  resolve via [tr] against the SAME shared L10n keys desktop uses
 *  (`indicatorSMA`/`indicatorEMA`/`indicatorVWAP`/`indicatorBollinger`/`indicatorRSI`/MACD),
 *  identical across all four languages (technical abbreviations, not prose). `label` is a
 *  reactive `get()` (re-resolves [tr] on every read) so chip UI recomposes on a language
 *  change, mirroring desktop.
 *
 *  Colors are fixed literals transcribed from desktop's `DK`-scoped values (`DK.gold` /
 *  teal / `DK.silver` / blue / purple / orange) rather than reading the live accent theme —
 *  unlike desktop's `DK` singleton, Android's color scheme is only reachable inside
 *  composition (`MaterialTheme.colorScheme`), and `Indicator` is a plain (non-composable)
 *  enum. This is a deliberate, recorded divergence: indicator colors stay the champagne-gold
 *  default rather than tracking a live accent-theme change. */
enum class Indicator(val key: L10n.Key, val color: Color, val isOverlay: Boolean) {
    Sma(L10n.Key.IndicatorSMA, Color(0xFFD4A94E), true),
    Ema(L10n.Key.IndicatorEMA, Color(0xFF4DBDDB), true),
    Vwap(L10n.Key.IndicatorVWAP, Color(0xFFD8D5CE), true),
    Bollinger(L10n.Key.IndicatorBollinger, Color(0xFF618FF2), true),
    Rsi(L10n.Key.IndicatorRSI, Color(0xFFA67DEB), false),
    Macd(L10n.Key.MacdParamsLabel, Color(0xFFE69442), false);

    /** Resolves [key] against the active language on every read — recomposes correctly when
     *  the active language changes, unlike a cached `val` would. */
    val label: String get() = tr(key)
}

/** MACD signal line — pink, distinct from the amber MACD line (desktop parity). */
val MacdSignalColor = Color(0xFFD673AB)

private const val SmaPeriod = 20
private const val EmaPeriod = 12
private const val RsiPeriod = 14
private const val BollingerPeriod = 20

/** Precomputed, index-aligned indicator series for a candle set. Nulls are preserved so the
 *  seed prefix is skipped (never drawn as zero). Built via `remember(candles, selection)`. */
data class IndicatorSeries(
    val closes: List<Double>,
    val sma: List<Double?>,
    val ema: List<Double?>,
    val vwap: List<Double?>,
    val bollinger: List<BollingerBand?>,
    val rsi: List<Double?>,
    val macd: List<MacdPoint?>,
)

/** Computes every enabled indicator once, delegating to the shared, framework-free
 *  [TechnicalIndicators] math — mirrors desktop's `computeIndicators` exactly. VWAP requires
 *  equal-length H/L/C/volume lists, guaranteed here since all four come from the same
 *  [CandleBar] list. */
fun computeIndicators(candles: List<CandleBar>, selection: Set<Indicator>): IndicatorSeries {
    val closes = candles.map { it.close }
    val active = { ind: Indicator -> selection.contains(ind) }
    return IndicatorSeries(
        closes = closes,
        sma = if (active(Indicator.Sma)) TechnicalIndicators.sma(closes, SmaPeriod) else emptyList(),
        ema = if (active(Indicator.Ema)) TechnicalIndicators.ema(closes, EmaPeriod) else emptyList(),
        vwap = if (active(Indicator.Vwap)) TechnicalIndicators.vwap(
            highs = candles.map { it.high },
            lows = candles.map { it.low },
            closes = closes,
            volumes = candles.map { it.volume },
        ) else emptyList(),
        bollinger = if (active(Indicator.Bollinger)) TechnicalIndicators.bollingerBands(closes, BollingerPeriod) else emptyList(),
        rsi = if (active(Indicator.Rsi)) TechnicalIndicators.rsi(closes, RsiPeriod) else emptyList(),
        macd = if (active(Indicator.Macd)) TechnicalIndicators.macd(closes) else emptyList(),
    )
}

/** Price chart (a smooth curve from candle closes — Enhancement 1's monotone-cubic helper)
 *  with the enabled overlays. [candles] and [series] span the FULL lookback+visible series
 *  (indicators are computed over the whole thing so their warm-up prefix is fully formed by
 *  [visibleStartIndex]); only the visible slice is actually drawn — x maps every index i to
 *  `(i - visibleStartIndex) * stepX`, so lookback-only points fall off-canvas to the left.
 *  The y-domain pads the VISIBLE close extremes 12% and widens to include VISIBLE Bollinger
 *  extremes when BB is on — desktop parity. */
@Composable
fun PriceChartWithOverlays(
    candles: List<CandleBar>,
    series: IndicatorSeries,
    selection: Set<Indicator>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    visibleStartIndex: Int = 0,
) {
    Canvas(modifier) {
        val visibleCount = candles.size - visibleStartIndex
        if (visibleCount < 2) return@Canvas
        val visibleCloses = candles.subList(visibleStartIndex, candles.size).map { it.close }
        var lo = visibleCloses.min()
        var hi = visibleCloses.max()
        if (selection.contains(Indicator.Bollinger)) {
            series.bollinger.drop(visibleStartIndex).forEach { band ->
                if (band != null) { lo = minOf(lo, band.lower); hi = maxOf(hi, band.upper) }
            }
        }
        if (hi <= lo) return@Canvas
        val padding = (hi - lo) * 0.12
        val domainLo = lo - padding
        val domainHi = hi + padding
        val span = (domainHi - domainLo).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (visibleCount - 1)
        fun x(i: Int) = (i - visibleStartIndex) * stepX
        fun y(v: Double) = size.height - ((v - domainLo) / span * size.height).toFloat()

        // Bollinger fill sits BEHIND the price line and band edges.
        drawBollingerFillIfOn(series, selection, ::x, ::y)

        // Price line (from candle closes, smoothed) — only the visible slice.
        val pricePoints = (visibleStartIndex until candles.size).map { i -> Offset(x(i), y(candles[i].close)) }
        val pricePath = Path().apply { monotoneCubicLineTo(pricePoints) }
        drawPath(pricePath, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Overlay polylines (full series; lookback prefix draws off-canvas and is clipped).
        drawOverlaySeries(series, selection, ::x, ::y)
    }
}

/** REAL candlesticks (identical geometry to [com.aptrade.android.ui.chart.CandleChart]) with
 *  the enabled overlays drawn on top, sharing the candle index → x space so overlay point i
 *  sits at candle i's center. The y-domain spans candle low/high (widened to include Bollinger
 *  extremes when BB is on). Candle bodies/wicks are UNCHANGED (no smoothing — Enhancement 1
 *  applies to line/area charts, not candlesticks); the overlay lines drawn on top are smooth. */
@Composable
fun CandleChartWithOverlays(
    candles: List<CandleBar>,
    series: IndicatorSeries,
    selection: Set<Indicator>,
    modifier: Modifier = Modifier,
    visibleStartIndex: Int = 0,
    upColor: Color = com.aptrade.android.ui.theme.GainGreen,
    downColor: Color = com.aptrade.android.ui.theme.LossRed,
) {
    Canvas(modifier) {
        val visibleCount = candles.size - visibleStartIndex
        if (visibleCount < 2) return@Canvas
        val visibleCandles = candles.subList(visibleStartIndex, candles.size)
        var lo = visibleCandles.minOf { it.low }
        var hi = visibleCandles.maxOf { it.high }
        if (selection.contains(Indicator.Bollinger)) {
            series.bollinger.drop(visibleStartIndex).forEach { band ->
                if (band != null) { lo = minOf(lo, band.lower); hi = maxOf(hi, band.upper) }
            }
        }
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val slot = size.width / visibleCount
        val bodyWidth = (slot * 0.6f).coerceAtLeast(1f)
        fun x(i: Int) = (i - visibleStartIndex) * slot + slot / 2f
        fun y(v: Double) = size.height - ((v - lo) / span * size.height).toFloat()

        drawBollingerFillIfOn(series, selection, ::x, ::y)

        for (i in visibleStartIndex until candles.size) {
            val c = candles[i]
            val cx = x(i)
            val color = if (c.close >= c.open) upColor else downColor
            drawLine(color, Offset(cx, y(c.high)), Offset(cx, y(c.low)), 1.dp.toPx())
            val top = y(maxOf(c.open, c.close)); val bottom = y(minOf(c.open, c.close))
            drawRect(color, Offset(cx - bodyWidth / 2f, top), Size(bodyWidth, (bottom - top).coerceAtLeast(1f)))
        }

        drawOverlaySeries(series, selection, ::x, ::y)
    }
}

/** Draws the enabled Bollinger translucent fill (nothing if BB is off). Shared by both chart
 *  variants so the fill geometry lives in one place. */
private fun DrawScope.drawBollingerFillIfOn(
    series: IndicatorSeries,
    selection: Set<Indicator>,
    x: (Int) -> Float,
    y: (Double) -> Float,
) {
    if (selection.contains(Indicator.Bollinger)) drawBollingerFill(series.bollinger, x, y)
}

/** Draws every enabled overlay polyline (SMA / EMA / VWAP / Bollinger bands), each rendered as
 *  a smooth monotone-cubic curve (Enhancement 1's helper) rather than straight segments —
 *  shared by the Line-mode and Candles-mode variants so overlay geometry is identical, only the
 *  price backdrop and x/y mapping differ. */
private fun DrawScope.drawOverlaySeries(
    series: IndicatorSeries,
    selection: Set<Indicator>,
    x: (Int) -> Float,
    y: (Double) -> Float,
) {
    if (selection.contains(Indicator.Sma)) {
        drawSmoothedSeries(series.sma, Indicator.Sma.color, 1.5.dp.toPx(), x, y)
    }
    if (selection.contains(Indicator.Ema)) {
        drawSmoothedSeries(series.ema, Indicator.Ema.color, 1.5.dp.toPx(), x, y)
    }
    if (selection.contains(Indicator.Vwap)) {
        drawSmoothedSeries(series.vwap, Indicator.Vwap.color, 1.5.dp.toPx(), x, y, dash = floatArrayOf(5f, 3f))
    }
    if (selection.contains(Indicator.Bollinger)) {
        val bbColor = Indicator.Bollinger.color
        drawSmoothedSeries(series.bollinger.map { it?.upper }, bbColor, 1.dp.toPx(), x, y)
        drawSmoothedSeries(series.bollinger.map { it?.lower }, bbColor, 1.dp.toPx(), x, y)
        drawSmoothedSeries(series.bollinger.map { it?.middle }, bbColor.copy(alpha = 0.6f), 1.dp.toPx(), x, y,
            dash = floatArrayOf(3f, 3f))
    }
}

/** Draws a nullable series as a SMOOTH (monotone-cubic, Enhancement 1) polyline, breaking the
 *  curve across null gaps so a leading (or interior) null prefix is never rendered as a
 *  zero-valued point — each contiguous non-null run is its own curve, matching desktop's
 *  `drawSeries` null-gap-breaking behavior exactly, just with smoothed segments instead of
 *  straight `lineTo`s. A run of exactly one point draws nothing (nothing to connect), same as
 *  a lone `moveTo` with no following `lineTo` would. */
private fun DrawScope.drawSmoothedSeries(
    values: List<Double?>,
    color: Color,
    strokeWidth: Float,
    x: (Int) -> Float,
    y: (Double) -> Float,
    dash: FloatArray? = null,
) {
    if (values.isEmpty()) return
    val effect = dash?.let { PathEffect.dashPathEffect(it, 0f) }
    var run = mutableListOf<Offset>()
    fun flush() {
        if (run.size >= 2) {
            val path = Path().apply { monotoneCubicLineTo(run) }
            drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
        }
        run = mutableListOf()
    }
    values.forEachIndexed { i, v ->
        if (v == null) flush() else run.add(Offset(x(i), y(v)))
    }
    flush()
}

/** Translucent fill between the Bollinger upper and lower band (band color at 0.07 alpha),
 *  built as one closed polygon over the run of defined points. Deliberately NOT smoothed
 *  (straight edges) — this is a fill polygon, not a line chart; Enhancement 1's smoothing
 *  scope is line/area price charts, and the band LINES drawn on top of this fill (via
 *  [drawOverlaySeries]) are already smooth, which is what reads as the visible curve. */
private fun DrawScope.drawBollingerFill(
    bands: List<BollingerBand?>,
    x: (Int) -> Float,
    y: (Double) -> Float,
) {
    val fillColor = Indicator.Bollinger.color.copy(alpha = 0.07f)
    var upper: Path? = null
    val lowerPoints = ArrayList<Offset>()
    fun flush() {
        val u = upper ?: return
        if (lowerPoints.isNotEmpty()) {
            for (k in lowerPoints.indices.reversed()) u.lineTo(lowerPoints[k].x, lowerPoints[k].y)
            u.close()
            drawPath(u, fillColor)
        }
        upper = null
        lowerPoints.clear()
    }
    bands.forEachIndexed { i, band ->
        if (band == null) { flush() } else {
            val xi = x(i)
            if (upper == null) { upper = Path().apply { moveTo(xi, y(band.upper)) } }
            else upper!!.lineTo(xi, y(band.upper))
            lowerPoints.add(Offset(xi, y(band.lower)))
        }
    }
    flush()
}

/** RSI sub-pane: 90dp, y-domain 0..100, dashed 30/70 guides with Oversold/Overbought labels,
 *  a smooth RSI polyline in its color. No x-axis. [series.rsi] spans the full lookback+visible
 *  series; only the visible slice (from [visibleStartIndex]) is drawn, sharing the same
 *  index → x mapping as the price/candle charts above it. */
@Composable
fun RsiPane(series: IndicatorSeries, modifier: Modifier = Modifier, visibleStartIndex: Int = 0) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            trf(L10n.Key.RsiPeriodFormat, RsiPeriod),
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp),
        )
        Box(Modifier.fillMaxWidth().height(90.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f)
                val hairline = Color.Gray.copy(alpha = 0.3f)
                fun y(v: Double) = size.height - (v / 100.0 * size.height).toFloat()
                drawLine(hairline, Offset(0f, y(70.0)), Offset(size.width, y(70.0)), 1.dp.toPx(), pathEffect = dash)
                drawLine(hairline, Offset(0f, y(30.0)), Offset(size.width, y(30.0)), 1.dp.toPx(), pathEffect = dash)
                val visibleCount = series.rsi.size - visibleStartIndex
                if (visibleCount >= 2) {
                    val stepX = size.width / (visibleCount - 1)
                    drawSmoothedSeries(series.rsi, Indicator.Rsi.color, 1.5.dp.toPx(), { (it - visibleStartIndex) * stepX }, ::y)
                }
            }
            Text(
                tr(L10n.Key.Overbought),
                style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                tr(L10n.Key.Oversold),
                style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/** MACD sub-pane: 100dp, zero-centered histogram bars (up/down at 0.5 alpha, UNCHANGED — bars
 *  are not a line chart), smooth MACD + signal lines, a small dot legend. No x-axis.
 *  [series.macd] spans the full lookback+visible series; only the visible slice (from
 *  [visibleStartIndex]) is drawn/scaled, sharing the same index → x mapping as the price/candle
 *  charts above it. */
@Composable
fun MacdPane(series: IndicatorSeries, modifier: Modifier = Modifier, visibleStartIndex: Int = 0) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr(L10n.Key.MacdParamsLabel),
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp),
            )
            LegendDot(Indicator.Macd.color, tr(L10n.Key.IndicatorMACD))
            LegendDot(MacdSignalColor, tr(L10n.Key.SignalLegend))
        }
        Box(Modifier.fillMaxWidth().height(100.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val points = series.macd
                val visibleCount = points.size - visibleStartIndex
                if (visibleCount < 2) return@Canvas
                val visiblePoints = points.subList(visibleStartIndex, points.size)
                val macdVals = points.map { it?.macd }
                val signalVals = points.map { it?.signal }
                val histVals = points.map { it?.histogram }
                val visibleMacd = visiblePoints.map { it?.macd }
                val visibleSignal = visiblePoints.map { it?.signal }
                val visibleHist = visiblePoints.map { it?.histogram }
                // Shared y-domain across macd line, signal line, and histogram (zero-centered),
                // computed over the VISIBLE slice only so the scale matches what's shown.
                var lo = 0.0
                var hi = 0.0
                (visibleMacd + visibleSignal + visibleHist).forEach { v -> if (v != null) { lo = minOf(lo, v); hi = maxOf(hi, v) } }
                if (hi <= lo) { hi = 1.0; lo = -1.0 }
                val pad = (hi - lo) * 0.1
                val domainLo = lo - pad
                val domainHi = hi + pad
                val vspan = (domainHi - domainLo).takeIf { it > 0.0 } ?: 1.0
                val stepX = size.width / (visibleCount - 1)
                fun x(i: Int) = (i - visibleStartIndex) * stepX
                fun y(v: Double) = size.height - ((v - domainLo) / vspan * size.height).toFloat()
                val zeroY = y(0.0)
                val barW = (stepX * 0.6f).coerceAtLeast(1f)
                for (i in visibleStartIndex until points.size) {
                    val v = histVals[i]
                    if (v != null) {
                        val cx = x(i)
                        val top = if (v >= 0) y(v) else zeroY
                        val h = kotlin.math.abs(y(v) - zeroY).coerceAtLeast(1f)
                        val color = (if (v >= 0) com.aptrade.android.ui.theme.GainGreen else com.aptrade.android.ui.theme.LossRed).copy(alpha = 0.5f)
                        drawRect(color, Offset(cx - barW / 2f, top), Size(barW, h))
                    }
                }
                drawSmoothedSeries(macdVals, Indicator.Macd.color, 1.5.dp.toPx(), ::x, ::y)
                drawSmoothedSeries(signalVals, MacdSignalColor, 1.5.dp.toPx(), ::x, ::y)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(
            label,
            style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/** Horizontally-scrolling indicator toggle chips — Android port of desktop's `IndicatorChips`
 *  (same chip anatomy: dot swatch + label, filled/bordered when on, dim when off), same six
 *  [Indicator] entries in enum order. */
@Composable
fun IndicatorChips(selection: Set<Indicator>, onToggle: (Indicator) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
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
                    .background(if (on) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        if (on) ind.color.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(50),
                    )
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
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
