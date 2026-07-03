package com.aptrade.desktop.designkit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Pointer x → nearest point index; clamped, degenerate inputs fall back sensibly. */
fun crosshairIndex(pointerX: Float, chartWidth: Float, pointCount: Int): Int {
    if (pointCount < 2) return 0
    if (chartWidth <= 0f) return pointCount - 1
    val raw = (pointerX / chartWidth * (pointCount - 1)).roundToInt()
    return raw.coerceIn(0, pointCount - 1)
}

/** Percentage-point change from the series start to `index` (0 when out of range/empty). */
fun percentPointDelta(values: List<Double>, index: Int): Double {
    val v = values.getOrNull(index) ?: return 0.0
    val start = values.firstOrNull() ?: return 0.0
    return v - start
}

/** The macOS ExpandedValueCard: an inline (in-flow, full-width) chart card grown from the
 *  header sparkline. Hover drives a crosshair; the headline shows the value under the
 *  cursor and its percentage-point delta from the period start. Percent data, not money. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ExpandedValueCard(
    title: String,
    values: List<Double>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hoverIndex by remember { mutableStateOf<Int?>(null) }
    var chartWidthPx by remember { mutableStateOf(0f) }
    val activeIndex = hoverIndex ?: (values.size - 1).coerceAtLeast(0)
    val activeValue = values.getOrNull(activeIndex) ?: 0.0
    val delta = percentPointDelta(values, activeIndex)
    val color = DK.changeColor(activeValue.takeIf { values.isNotEmpty() })

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title.uppercase(), style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textTertiary, letterSpacing = 1.sp))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatPercent(activeValue), style = TextStyle(fontFamily = InterFamily,
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = color,
                        fontFeatureSettings = "tnum"))
                    Text("${formatPercent(delta)} from start", style = TextStyle(fontFamily = InterFamily,
                        fontSize = 12.sp, color = DK.textSecondary, fontFeatureSettings = "tnum"))
                }
            }
            Spacer(Modifier.weight(1f))
            Text("✕", style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, color = DK.textSecondary),
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = onClose)
                    .padding(4.dp))
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .onPointerEvent(PointerEventType.Move) { event ->
                    val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
                    hoverIndex = crosshairIndex(x, chartWidthPx, values.size)
                }
                .onPointerEvent(PointerEventType.Exit) { hoverIndex = null },
        ) {
            chartWidthPx = size.width
            if (values.size < 2) return@Canvas
            val min = values.min(); val max = values.max()
            val span = (max - min).takeIf { it > 0.0 } ?: 1.0
            val stepX = size.width / (values.size - 1)
            fun y(v: Double) = size.height - ((v - min) / span * size.height).toFloat()
            val path = Path()
            values.forEachIndexed { i, v ->
                if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * stepX, y(v))
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            // crosshair: vertical hairline + dot at the active point
            val cx = activeIndex * stepX
            drawLine(DK.hairline, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx())
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(cx, y(activeValue)))
        }
    }
}
