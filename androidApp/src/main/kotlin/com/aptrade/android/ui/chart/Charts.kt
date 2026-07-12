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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, value ->
            val x = i * stepX
            val y = size.height - ((value - min) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
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
            val path = Path()
            ys.forEachIndexed { i, y ->
                val x = i * stepX
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
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

/** The floating readout pill next to the crosshair: price over date, matching the desktop
 *  `CrosshairTooltip`'s anatomy (value line + smaller secondary-color date line). Horizontally
 *  biased to the crosshair's [fraction] of the chart width, flipping to the left of the touch
 *  point past the midpoint so it never clips the far edge. */
@Composable
fun CrosshairTooltip(priceText: String, dateText: String, fraction: Float, modifier: Modifier = Modifier) {
    val alignment = if (fraction > 0.5f) Alignment.TopEnd else Alignment.TopStart
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(alignment)
                .padding(4.dp)
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
