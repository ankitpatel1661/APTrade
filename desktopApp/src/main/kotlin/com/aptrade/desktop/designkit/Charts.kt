package com.aptrade.desktop.designkit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

data class ChartCandle(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    /** 0.0 when the source doesn't report volume. Consumed by the VWAP overlay; the
     *  CandleChart itself ignores it. */
    val volume: Double = 0.0,
)

/** Minimal intraday trace with soft gradient fill — Sparkline.swift, dark-mode tuning. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min(); val max = values.max()
        val range = max - min
        val stepX = size.width / (values.size - 1)
        val inset = 2f
        fun pt(i: Int): Offset {
            val n = if (range == 0.0) 0.5 else (values[i] - min) / range
            return Offset(i * stepX, inset + (1 - n.toFloat()) * (size.height - inset * 2))
        }
        val line = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until values.size) lineTo(pt(i).x, pt(i).y) }
        val fill = Path().apply {
            addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)),
            startY = 0f, endY = size.height))
        drawPath(line, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Gold line chart for the detail pane (values are pixel-math Doubles). */
@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier, color: Color = DK.gold) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min(); val max = values.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - min) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun CandleChart(candles: List<ChartCandle>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (candles.isEmpty()) return@Canvas
        val min = candles.minOf { it.low }; val max = candles.maxOf { it.high }
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val slot = size.width / candles.size
        val bodyWidth = (slot * 0.6f).coerceAtLeast(1f)
        fun y(v: Double) = size.height - ((v - min) / span * size.height).toFloat()
        candles.forEachIndexed { i, c ->
            val cx = i * slot + slot / 2f
            val color = if (c.close >= c.open) DK.up else DK.down
            drawLine(color, Offset(cx, y(c.high)), Offset(cx, y(c.low)), 1.dp.toPx())
            val top = y(maxOf(c.open, c.close)); val bottom = y(minOf(c.open, c.close))
            drawRect(color, Offset(cx - bodyWidth / 2f, top), Size(bodyWidth, (bottom - top).coerceAtLeast(1f)))
        }
    }
}
