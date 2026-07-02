package com.aptrade.android.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
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
