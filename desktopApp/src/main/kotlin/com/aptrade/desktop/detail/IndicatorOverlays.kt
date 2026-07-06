package com.aptrade.desktop.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.material3.Text
import com.aptrade.desktop.designkit.ChartCandle
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.l10n.L10n
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.shared.domain.TechnicalIndicators

/** The six chart indicators. Overlays draw on the price chart; RSI/MACD get their own pane.
 *  Chip labels and colors are macOS-parity (AssetDetailView.swift). Labels resolve via
 *  [tr] against macOS's `indicatorSMA`/`indicatorEMA`/`indicatorVWAP`/`indicatorBollinger`/
 *  `indicatorRSI` Keys — L10n.swift keeps these IDENTICAL across all four languages (they're
 *  technical abbreviations, not prose), so [tr] always resolves to the same English text
 *  shown here, but routes through the catalog for parity/consistency with the rest of the
 *  detail screen. `Indicator.label` is a reactive `get()` (see below) that re-resolves
 *  [tr] on every read, so chip UI recomposes correctly when the active language changes —
 *  it is never cached or frozen at enum-init time. */
enum class Indicator(val key: L10n.Key, val color: Color, val isOverlay: Boolean) {
    Sma(L10n.Key.IndicatorSMA, DK.gold, true),
    Ema(L10n.Key.IndicatorEMA, Color(0.30f, 0.74f, 0.86f), true),
    Vwap(L10n.Key.IndicatorVWAP, DK.silver, true),
    Bollinger(L10n.Key.IndicatorBollinger, Color(0.38f, 0.56f, 0.95f), true),
    Rsi(L10n.Key.IndicatorRSI, Color(0.65f, 0.49f, 0.92f), false),
    Macd(L10n.Key.MacdParamsLabel, Color(0.90f, 0.58f, 0.26f), false);

    /** Resolves [key] against the active language (recomposes with it, unlike a cached
     *  `val label` would). */
    val label: String get() = tr(key)
}

/** MACD signal line — pink, distinct from the amber MACD line (macOS parity). */
val MacdSignalColor = Color(0.84f, 0.45f, 0.67f)

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
    val bollinger: List<com.aptrade.shared.domain.BollingerBand?>,
    val rsi: List<Double?>,
    val macd: List<com.aptrade.shared.domain.MacdPoint?>,
)

/** Computes every enabled indicator once. VWAP requires equal-length H/L/C/volume lists —
 *  guaranteed here since all four come from the same candle list. */
fun computeIndicators(candles: List<ChartCandle>, selection: Set<Indicator>): IndicatorSeries {
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

/** Price chart (line drawn from candle closes) with the enabled overlays. [candles] and
 *  [series] span the FULL lookback+visible series (indicators are computed over the whole
 *  thing so their warm-up prefix is fully formed by [visibleStartIndex]); only the visible
 *  slice (`candles.drop(visibleStartIndex)`) is actually drawn — x maps every index i to
 *  `(i - visibleStartIndex) * stepX`, so lookback-only points (i < visibleStartIndex) fall
 *  off-canvas to the left and are naturally clipped. The y-domain pads the VISIBLE close
 *  extremes 12% and widens to include VISIBLE Bollinger extremes when BB is on, matching the
 *  macOS chart's domain logic. All series share one index → x space so overlays align. */
@Composable
fun PriceChartWithOverlays(
    candles: List<ChartCandle>,
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

        // Price line (from candle closes) — only the visible slice.
        val pricePath = Path()
        for (i in visibleStartIndex until candles.size) {
            val c = candles[i]
            if (i == visibleStartIndex) pricePath.moveTo(x(i), y(c.close)) else pricePath.lineTo(x(i), y(c.close))
        }
        drawPath(pricePath, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Overlay polylines (full series; lookback prefix draws off-canvas and is clipped) —
        // each also skips the null warm-up prefix (drawSeries).
        drawOverlaySeries(series, selection, ::x, ::y)
    }
}

/** REAL candlesticks (identical geometry to [com.aptrade.desktop.designkit.CandleChart]) with
 *  the enabled overlays drawn on top, sharing the candle index → x space so overlay point i sits
 *  at candle i's center. The y-domain spans candle low/high (widened to include Bollinger extremes
 *  when BB is on) so candles and overlay lines read on one scale. This is the Candles-mode
 *  counterpart to [PriceChartWithOverlays] (which draws a line from closes for Line mode);
 *  macOS `AssetDetailView` likewise draws indicators over both chart styles. */
@Composable
fun CandleChartWithOverlays(
    candles: List<ChartCandle>,
    series: IndicatorSeries,
    selection: Set<Indicator>,
    modifier: Modifier = Modifier,
    visibleStartIndex: Int = 0,
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
        // Candle i center — the shared x for both wicks/bodies and every overlay point.
        // Lookback-only indices (i < visibleStartIndex) map to negative x and are clipped.
        fun x(i: Int) = (i - visibleStartIndex) * slot + slot / 2f
        fun y(v: Double) = size.height - ((v - lo) / span * size.height).toFloat()

        // Bollinger fill behind the candles and overlay edges.
        drawBollingerFillIfOn(series, selection, ::x, ::y)

        // Candlesticks (same wick/body drawing as CandleChart, on the extended domain) — only
        // the visible slice.
        for (i in visibleStartIndex until candles.size) {
            val c = candles[i]
            val cx = x(i)
            val color = if (c.close >= c.open) DK.up else DK.down
            drawLine(color, Offset(cx, y(c.high)), Offset(cx, y(c.low)), 1.dp.toPx())
            val top = y(maxOf(c.open, c.close)); val bottom = y(minOf(c.open, c.close))
            drawRect(color, Offset(cx - bodyWidth / 2f, top), Size(bodyWidth, (bottom - top).coerceAtLeast(1f)))
        }

        // Overlay polylines on top, aligned to candle centers (full series; lookback prefix
        // draws off-canvas and is clipped).
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

/** Draws every enabled overlay polyline (SMA / EMA / VWAP / Bollinger bands) with the given
 *  index → pixel mapping. Extracted so the Line-mode and Candles-mode variants share identical
 *  overlay geometry — only the price backdrop and x/y mapping differ. */
private fun DrawScope.drawOverlaySeries(
    series: IndicatorSeries,
    selection: Set<Indicator>,
    x: (Int) -> Float,
    y: (Double) -> Float,
) {
    if (selection.contains(Indicator.Sma)) {
        drawSeries(series.sma, Indicator.Sma.color, 1.5.dp.toPx(), x, y)
    }
    if (selection.contains(Indicator.Ema)) {
        drawSeries(series.ema, Indicator.Ema.color, 1.5.dp.toPx(), x, y)
    }
    if (selection.contains(Indicator.Vwap)) {
        drawSeries(series.vwap, Indicator.Vwap.color, 1.5.dp.toPx(), x, y,
            dash = floatArrayOf(5f, 3f))
    }
    if (selection.contains(Indicator.Bollinger)) {
        val bbColor = Indicator.Bollinger.color
        drawSeries(series.bollinger.map { it?.upper }, bbColor, 1.dp.toPx(), x, y)
        drawSeries(series.bollinger.map { it?.lower }, bbColor, 1.dp.toPx(), x, y)
        drawSeries(series.bollinger.map { it?.middle }, bbColor.copy(alpha = 0.6f), 1.dp.toPx(), x, y,
            dash = floatArrayOf(3f, 3f))
    }
}

/** Draws a nullable series as a polyline, breaking the path across null gaps so a leading
 *  (or interior) null prefix is never rendered as a zero-valued point. */
private fun DrawScope.drawSeries(
    values: List<Double?>,
    color: Color,
    strokeWidth: Float,
    x: (Int) -> Float,
    y: (Double) -> Float,
    dash: FloatArray? = null,
) {
    if (values.isEmpty()) return
    val effect = dash?.let { PathEffect.dashPathEffect(it, 0f) }
    var path: Path? = null
    values.forEachIndexed { i, v ->
        if (v == null) {
            path?.let { drawPath(it, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)) }
            path = null
        } else {
            if (path == null) { path = Path().apply { moveTo(x(i), y(v)) } }
            else path!!.lineTo(x(i), y(v))
        }
    }
    path?.let { drawPath(it, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)) }
}

/** Translucent fill between the Bollinger upper and lower band (band color at 0.07 alpha),
 *  built as one closed polygon over the run of defined points. */
private fun DrawScope.drawBollingerFill(
    bands: List<com.aptrade.shared.domain.BollingerBand?>,
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
 *  the RSI polyline in its color. No x-axis. [series.rsi] spans the full lookback+visible
 *  series; only the visible slice (from [visibleStartIndex]) is drawn, sharing the same
 *  index → x mapping as the price/candle charts above it. */
@Composable
fun RsiPane(series: IndicatorSeries, modifier: Modifier = Modifier, visibleStartIndex: Int = 0) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            trf(L10n.Key.RsiPeriodFormat, RsiPeriod),
            style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = DK.textTertiary, letterSpacing = 1.2.sp),
        )
        Box(Modifier.fillMaxWidth().height(90.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f)
                fun y(v: Double) = size.height - (v / 100.0 * size.height).toFloat()
                drawLine(DK.hairline, Offset(0f, y(70.0)), Offset(size.width, y(70.0)), 1.dp.toPx(), pathEffect = dash)
                drawLine(DK.hairline, Offset(0f, y(30.0)), Offset(size.width, y(30.0)), 1.dp.toPx(), pathEffect = dash)
                val visibleCount = series.rsi.size - visibleStartIndex
                if (visibleCount >= 2) {
                    val stepX = size.width / (visibleCount - 1)
                    drawSeries(series.rsi, Indicator.Rsi.color, 1.5.dp.toPx(), { (it - visibleStartIndex) * stepX }, ::y)
                }
            }
            Text(
                tr(L10n.Key.Overbought),
                style = TextStyle(fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.Medium,
                    color = DK.textTertiary),
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                tr(L10n.Key.Oversold),
                style = TextStyle(fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.Medium,
                    color = DK.textTertiary),
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/** MACD sub-pane: 100dp, zero-centered histogram bars (up/down at 0.5 alpha), MACD + signal
 *  lines, a small dot legend. No x-axis. [series.macd] spans the full lookback+visible
 *  series; only the visible slice (from [visibleStartIndex]) is drawn/scaled, sharing the
 *  same index → x mapping as the price/candle charts above it. */
@Composable
fun MacdPane(series: IndicatorSeries, modifier: Modifier = Modifier, visibleStartIndex: Int = 0) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr(L10n.Key.MacdParamsLabel),
                style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = DK.textTertiary, letterSpacing = 1.2.sp),
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
                        val color = (if (v >= 0) DK.up else DK.down).copy(alpha = 0.5f)
                        drawRect(color, Offset(cx - barW / 2f, top), Size(barW, h))
                    }
                }
                drawSeries(macdVals, Indicator.Macd.color, 1.5.dp.toPx(), ::x, ::y)
                drawSeries(signalVals, MacdSignalColor, 1.5.dp.toPx(), ::x, ::y)
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
            style = TextStyle(fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = DK.textTertiary),
        )
    }
}
