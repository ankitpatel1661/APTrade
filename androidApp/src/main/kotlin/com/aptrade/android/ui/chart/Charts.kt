package com.aptrade.android.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
