package com.aptrade.android.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.android.detail.CandleBar
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One cubic Bézier segment between two consecutive data points, expressed as Compose [Offset]s
 *  ready for [Path.cubicTo]. [start]/[end] are the original data points (the curve passes
 *  through them exactly — Hermite interpolation, not an approximation); [control1]/[control2]
 *  are the Bézier control points derived from the segment's Fritsch–Carlson tangents. */
internal data class CubicSegment(val start: Offset, val control1: Offset, val control2: Offset, val end: Offset)

/** Monotone cubic (Fritsch–Carlson, 1980) interpolation control points for [points] (assumed
 *  sorted by ascending x, which every chart in this file already produces). Deliberately NOT a
 *  naive Catmull-Rom/bezier-midpoint curve: those overshoot above a local high or below a local
 *  low, which would misrepresent the actual traded price on a line/area price chart — this
 *  construction guarantees the curve never leaves the `[min(y_i, y_{i+1}), max(y_i, y_{i+1})]`
 *  band on any monotone run of the input. Degenerate inputs: fewer than 2 points returns
 *  [emptyList] (nothing to draw, matching every other chart primitive here); exactly 2 points
 *  degrades to a single segment whose control points sit exactly on the connecting line
 *  (indistinguishable from a plain `lineTo`).
 *
 *  Algorithm:
 *  1. Secant slopes `Δ_k` between consecutive points.
 *  2. Initial tangent `m_k` at each point: the lone adjacent secant at the ends, the average of
 *     the two adjacent secants everywhere else.
 *  3. Zero the tangent at any point flanked by secants of opposite sign (a local extremum) —
 *     this is what stops the curve from bulging past a peak or trough.
 *  4. Fritsch–Carlson's τ-clamp: rescale a segment's two tangents so neither exceeds 3× the
 *     segment's own secant slope — the other overshoot guard, for steep runs between shallow
 *     neighbors.
 *  Each interval's Hermite tangents are then converted to Bézier control points at the standard
 *  1/3 and 2/3 x-offsets. */
internal fun monotoneCubicControlPoints(points: List<Offset>): List<CubicSegment> {
    val n = points.size
    if (n < 2) return emptyList()

    val dx = FloatArray(n - 1)
    val delta = FloatArray(n - 1)
    for (i in 0 until n - 1) {
        val dxi = points[i + 1].x - points[i].x
        dx[i] = dxi
        delta[i] = if (dxi == 0f) 0f else (points[i + 1].y - points[i].y) / dxi
    }

    val m = FloatArray(n)
    m[0] = delta[0]
    m[n - 1] = delta[n - 2]
    for (i in 1 until n - 1) {
        val opposingSigns = (delta[i - 1] > 0f) != (delta[i] > 0f)
        m[i] = if (delta[i - 1] == 0f || delta[i] == 0f || opposingSigns) 0f else (delta[i - 1] + delta[i]) / 2f
    }

    for (i in 0 until n - 1) {
        if (delta[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
            continue
        }
        if (m[i] / delta[i] < 0f) m[i] = 0f
        if (m[i + 1] / delta[i] < 0f) m[i + 1] = 0f
        val alpha = m[i] / delta[i]
        val beta = m[i + 1] / delta[i]
        val h = alpha * alpha + beta * beta
        if (h > 9f) {
            val tau = 3f / sqrt(h.toDouble()).toFloat()
            m[i] = tau * alpha * delta[i]
            m[i + 1] = tau * beta * delta[i]
        }
    }

    return (0 until n - 1).map { i ->
        val p0 = points[i]
        val p1 = points[i + 1]
        val third = dx[i] / 3f
        CubicSegment(
            start = p0,
            control1 = Offset(p0.x + third, p0.y + m[i] * third),
            control2 = Offset(p1.x - third, p1.y - m[i + 1] * third),
            end = p1,
        )
    }
}

/** Appends [points] to this [Path] as a monotone-cubic curve (via [monotoneCubicControlPoints]):
 *  `moveTo` the first point, then `cubicTo` through each segment. Fewer than 2 points is a
 *  no-op — every caller in this file already guards `values.size < 2` before building a path. */
internal fun Path.monotoneCubicLineTo(points: List<Offset>) {
    val segments = monotoneCubicControlPoints(points)
    if (segments.isEmpty()) return
    moveTo(segments.first().start.x, segments.first().start.y)
    for (segment in segments) {
        cubicTo(
            segment.control1.x, segment.control1.y,
            segment.control2.x, segment.control2.y,
            segment.end.x, segment.end.y,
        )
    }
}

/** A tiny (no axes, no crosshair) gradient-filled line — the watchlist row's mini price trend,
 *  Android counterpart of desktop `designkit/Charts.kt`'s `Sparkline` (same anatomy: a 1.5dp
 *  stroke over a fading vertical-gradient fill beneath it, [inset]-padded so the line never
 *  touches the top/bottom edge). [values] is pixel-math only (never used for exact-decimal
 *  display — the row's own price text is a separate, Money-backed string); fewer than two
 *  points draws nothing, matching every other chart primitive in this file. Sized by the
 *  caller — [com.aptrade.android.watchlist.WatchlistScreen]'s row places it at 72x32dp, left of
 *  the alert bell, mirroring desktop's `WatchlistRow`. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = max - min
        val stepX = size.width / (values.size - 1)
        val inset = 2f
        fun point(i: Int): Offset {
            val n = if (range == 0.0) 0.5 else (values[i] - min) / range
            return Offset(i * stepX, inset + (1 - n.toFloat()) * (size.height - inset * 2))
        }
        val line = Path().apply { monotoneCubicLineTo(values.indices.map { point(it) }) }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)),
                startY = 0f,
                endY = size.height,
            ),
        )
        drawPath(line, color = color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val points = values.mapIndexed { i, value ->
            val x = i * stepX
            val y = size.height - ((value - min) / span * size.height).toFloat()
            Offset(x, y)
        }
        val path = Path().apply { monotoneCubicLineTo(points) }
        drawPath(path, color = color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun CandleChart(
    candles: List<CandleBar>,
    modifier: Modifier = Modifier,
    upColor: Color = GainGreen,
    downColor: Color = LossRed,
) {
    Canvas(modifier = modifier) {
        if (candles.isEmpty()) return@Canvas
        val min = candles.minOf { it.low }
        val max = candles.maxOf { it.high }
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val slot = size.width / candles.size
        val bodyWidth = (slot * 0.6f).coerceAtLeast(1f)

        fun y(value: Double): Float = size.height - ((value - min) / span * size.height).toFloat()

        candles.forEachIndexed { i, candle ->
            val centerX = i * slot + slot / 2f
            val color = if (candle.close >= candle.open) upColor else downColor
            drawLine(
                color = color,
                start = Offset(centerX, y(candle.high)),
                end = Offset(centerX, y(candle.low)),
                strokeWidth = 1.dp.toPx(),
            )
            val top = y(maxOf(candle.open, candle.close))
            val bottom = y(minOf(candle.open, candle.close))
            drawRect(
                color = color,
                topLeft = Offset(centerX - bodyWidth / 2f, top),
                size = Size(bodyWidth, (bottom - top).coerceAtLeast(1f)),
            )
        }
    }
}

/** Result of normalizing two series onto ONE shared min/max domain (over the concatenation of
 *  both), so the two curves are drawn to a like-for-like vertical scale — per-series scaling
 *  would silently distort the comparison (the 6c.5 hazard). [primaryY]/[secondaryY] are
 *  normalized-to-pixel y-coordinates for a canvas of [height]; secondary is null iff the input
 *  secondary series was null or had fewer than 2 points. */
internal class DualSeriesLayout(
    val primaryY: List<Float>,
    val secondaryY: List<Float>?,
)

/** Pure normalization: one min/max span across primary + (secondary orEmpty), mapped to pixel
 *  y-coordinates for a canvas of [height] (y grows downward, so higher values → smaller y).
 *  Degenerate guards: primary with fewer than 2 points yields empty output entirely (nothing to
 *  draw); a null or sub-2-point secondary yields a null secondaryY (primary-only rendering). A
 *  flat combined series (max == min) falls back to a span of 1.0 so every point maps to the same
 *  finite y instead of dividing by zero. */
internal fun dualSeriesLayout(
    primary: List<Double>,
    secondary: List<Double>?,
    height: Float,
): DualSeriesLayout {
    if (primary.size < 2) return DualSeriesLayout(emptyList(), null)
    val hasSecondary = secondary != null && secondary.size >= 2
    val combined = if (hasSecondary) primary + secondary!! else primary
    val min = combined.min()
    val max = combined.max()
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
    fun y(value: Double): Float = height - ((value - min) / span * height).toFloat()
    val primaryY = primary.map { y(it) }
    val secondaryY = if (hasSecondary) secondary!!.map { y(it) } else null
    return DualSeriesLayout(primaryY, secondaryY)
}

/** Dual-series overlay for comparing a portfolio (or asset) curve against a secondary reference
 *  (e.g. a benchmark) on a SHARED vertical scale — the Android counterpart of the desktop
 *  OverlayChart's dollar-aligned rendering. [secondary] is optional (a solo curve renders just
 *  [primary]); when present it is drawn dashed by default to distinguish it from the solid
 *  primary line, mirroring the desktop legend semantics. Both series are guarded independently:
 *  primary with fewer than 2 points draws nothing, and a null/sub-2-point secondary silently
 *  falls back to primary-only. */
@Composable
fun DualLineChart(
    primary: List<Double>,
    secondary: List<Double>?,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    secondaryDashed: Boolean = true,
) {
    Canvas(modifier = modifier) {
        val layout = dualSeriesLayout(primary, secondary, size.height)
        if (layout.primaryY.isEmpty()) return@Canvas

        fun drawSeries(ys: List<Float>, color: Color, dashed: Boolean) {
            val stepX = size.width / (ys.size - 1)
            val points = ys.mapIndexed { i, y -> Offset(i * stepX, y) }
            val path = Path().apply { monotoneCubicLineTo(points) }
            drawPath(
                path,
                color = color,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f) else null,
                ),
            )
        }

        // Secondary first so the primary line reads on top where the two curves cross.
        layout.secondaryY?.let { drawSeries(it, secondaryColor, dashed = secondaryDashed) }
        drawSeries(layout.primaryY, primaryColor, dashed = false)
    }
}

/** Two-entry legend for [DualLineChart]: a solid swatch + [primaryLabel], and a dashed swatch +
 *  [secondaryLabel]. Android Material3-scale echo of the desktop PerformanceSection's
 *  ChartLegend (11sp, tertiary-label color). */
@Composable
fun ChartLegend(
    primaryLabel: String,
    secondaryLabel: String,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendSwatch(color = primaryColor, dashed = false, label = primaryLabel)
        LegendSwatch(color = secondaryColor, dashed = true, label = secondaryLabel)
    }
}

@Composable
private fun LegendSwatch(color: Color, dashed: Boolean, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(16.dp).height(2.dp)) {
            val cy = size.height / 2f
            drawLine(
                color = color,
                start = Offset(0f, cy),
                end = Offset(size.width, cy),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f) else null,
            )
        }
        Text(
            label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/** Drag-pointer x → nearest sampled-point index, clamped to the valid range. Mirrors the
 *  desktop `crosshairIndex` helper (designkit `ValueCard.kt`) exactly, ported to a touch-drag
 *  gesture instead of mouse hover (Android has no pointer-hover signal): degenerate inputs
 *  (fewer than two points, a zero-width chart) fall back sensibly rather than dividing by zero
 *  or returning an out-of-bounds index. */
internal fun nearestIndex(dragX: Float, chartWidth: Float, pointCount: Int): Int {
    if (pointCount < 2) return 0
    if (chartWidth <= 0f) return pointCount - 1
    val raw = (dragX / chartWidth * (pointCount - 1)).roundToInt()
    return raw.coerceIn(0, pointCount - 1)
}

/** The crosshair tooltip's display payload for one sampled point: the pre-formatted price
 *  text and a "MMM d, h:mm a" date/time (mirrors desktop PerformanceSection's
 *  `tooltipDateText` format exactly, so the readout reads the same across platforms). */
internal data class CrosshairReadout(val priceText: String, val dateText: String)

private val CROSSHAIR_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

/** Maps [index] to its display readout via two PARALLEL arrays — [priceTexts] (already
 *  display-formatted, e.g. via [com.aptrade.android.ui.money]) and [epochSeconds] (the raw
 *  UTC instants backing each sampled point). Returns null when [index] is out of range for
 *  either list (including a parallel-array length mismatch) — the caller shows no tooltip in
 *  that case rather than crashing or showing a mismatched price/date pair. [zoneId] defaults
 *  to the device's local zone; pinned to UTC in tests for deterministic date assertions. */
internal fun crosshairReadout(
    index: Int,
    priceTexts: List<String>,
    epochSeconds: List<Long>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): CrosshairReadout? {
    val price = priceTexts.getOrNull(index) ?: return null
    val epoch = epochSeconds.getOrNull(index) ?: return null
    val dateText = Instant.ofEpochSecond(epoch).atZone(zoneId).format(CROSSHAIR_DATE_FORMATTER)
    return CrosshairReadout(price, dateText)
}

/** Vertical hairline + filled dot marking the crosshair's active index on [values]' rendered
 *  curve — the touch-drag analog of the desktop hover crosshair (`ValueCard`/
 *  `PerformanceSection`'s dashed hairline + dot). [values] MUST use the same min/max/step
 *  layout as the underlying [LineChart] (or a candle series' close prices in Candle mode) so
 *  the marker lands exactly on the visible line; fewer than two points draws nothing. */
@Composable
fun CrosshairOverlay(
    values: List<Double>,
    index: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val idx = index.coerceIn(0, values.size - 1)
        val cx = idx * stepX
        val cy = size.height - ((values[idx] - min) / span * size.height).toFloat()
        drawLine(
            color = color.copy(alpha = 0.5f),
            start = Offset(cx, 0f),
            end = Offset(cx, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(cx, cy))
    }
}

/** Clamped tooltip CENTER x-coordinate so a tooltip of [tooltipWidth] centered on [rawX] never
 *  clips off either edge of a [chartWidth]-wide chart. Pure port of iOS
 *  `AssetDetailView.swift`'s `hoverTooltip` clamping math (`chartOverlay`):
 *  `clampedX = min(max(frame.origin.x + x, tooltipWidth / 2), geometry.size.width - tooltipWidth / 2)`
 *  — same min/max sandwich, just Kotlin `coerceIn`. Degenerate guards: a non-positive
 *  [chartWidth] centers at the tooltip's own half-width (nothing to clamp against); a chart
 *  narrower than the tooltip itself centers on the chart (both edges would clip regardless of
 *  where we put it, so staying centered is the least-bad placement rather than pinning to one
 *  corner). */
internal fun clampedTooltipX(rawX: Float, chartWidth: Float, tooltipWidth: Float): Float {
    val half = tooltipWidth / 2f
    if (chartWidth <= 0f) return half
    if (chartWidth <= tooltipWidth) return chartWidth / 2f
    return rawX.coerceIn(half, chartWidth - half)
}

/** [CrosshairOverlay]'s counterpart for [DualLineChart]: the crosshair dot must land on the
 *  PRIMARY curve's actual rendered position, which — unlike a solo [LineChart] — is scaled
 *  against the shared primary+secondary domain ([dualSeriesLayout]), not primary's own
 *  min/max. Reusing [CrosshairOverlay] here would mis-scale the dot off the visible line
 *  whenever the secondary (benchmark) series widens the combined range. Draws only the primary
 *  point (the readout is always the portfolio value, per [PortfolioScreen]'s comparison-chart
 *  crosshair); nothing to draw when [dualSeriesLayout] yields an empty layout. */
@Composable
fun DualSeriesCrosshairOverlay(
    primary: List<Double>,
    secondary: List<Double>?,
    index: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier) {
        val layout = dualSeriesLayout(primary, secondary, size.height)
        val primaryY = layout.primaryY
        if (primaryY.isEmpty()) return@Canvas
        val stepX = size.width / (primaryY.size - 1)
        val idx = index.coerceIn(0, primaryY.size - 1)
        val cx = idx * stepX
        val cy = primaryY[idx]
        drawLine(
            color = color.copy(alpha = 0.5f),
            start = Offset(cx, 0f),
            end = Offset(cx, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(cx, cy))
    }
}

/** The floating readout pill next to the crosshair: price over date, matching the desktop
 *  `CrosshairTooltip`'s anatomy (value line + smaller secondary-color date line). Its x
 *  position FOLLOWS the finger continuously — [rawX] is the live drag/touch x in px, clamped
 *  via [clampedTooltipX] (mirroring iOS's hoverTooltip) so a fixed-width tooltip never clips
 *  off either edge of the [chartWidthPx]-wide chart. Replaces the old binary left/right corner
 *  flip (UAT round 2: "readout must follow the finger"). */
@Composable
fun CrosshairTooltip(
    priceText: String,
    dateText: String,
    rawX: Float,
    chartWidthPx: Float,
    modifier: Modifier = Modifier,
    tooltipWidth: Dp = 120.dp,
) {
    val density = LocalDensity.current
    val tooltipWidthPx = with(density) { tooltipWidth.toPx() }
    val centerX = clampedTooltipX(rawX, chartWidthPx, tooltipWidthPx)
    val offsetXPx = (centerX - tooltipWidthPx / 2f).roundToInt()
    val offsetYPx = with(density) { 4.dp.roundToPx() }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(tooltipWidth)
                .offset { IntOffset(offsetXPx, offsetYPx) }
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                priceText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                dateText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
