package com.aptrade.desktop.designkit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One wedge of the allocation donut: `fraction` is 0.0–1.0 of the whole. */
data class DonutSlice(val fraction: Double, val color: Color)

private const val ANGULAR_INSET_DEGREES = 1.5f
private const val INNER_RADIUS_RATIO = 0.64f
private const val START_ANGLE_DEGREES = -90f

/** True donut (ring), transcribed from PortfolioView.allocationDonut (Swift Charts
 *  SectorMark): inner radius 64% of outer, 1.5° angular inset either side of each slice,
 *  rounded stroke caps, starts at 12 o'clock. Drawn as a stroked arc at the ring's mid
 *  radius rather than a filled sector, so `Stroke(cap = Round)` gives the rounded slice
 *  ends Swift Charts' `.cornerRadius(3)` produces. Designed for a 150.dp default frame,
 *  with a content slot for the "HOLDINGS" + value overlay centered in the hole. */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    // Named `diameter` (not `size`) to avoid shadowing `DrawScope.size` inside the Canvas
    // lambda below. Lets callers reuse this component at other sizes — the Plans list card's
    // 52dp mini-donut and the pie detail header's 130dp target-weight donut (M7.2 Task 12,
    // mirroring PlansSection.swift's `DonutChart(slices:, size:)` calls) — without touching
    // the 150dp default every Portfolio allocation call site relies on.
    diameter: Dp = 150.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.size(diameter)) {
        Canvas(Modifier.size(diameter)) {
            val outerRadius = size.minDimension / 2f
            val ringWidth = (1f - INNER_RADIUS_RATIO) * outerRadius
            val midRadius = outerRadius * (1f + INNER_RADIUS_RATIO) / 2f
            val arcSize = androidx.compose.ui.geometry.Size(midRadius * 2f, midRadius * 2f)
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - arcSize.width) / 2f,
                (size.height - arcSize.height) / 2f,
            )

            val total = slices.sumOf { it.fraction }.takeIf { it > 0.0 } ?: 1.0
            var startAngle = START_ANGLE_DEGREES
            val visibleSlices = slices.filter { it.fraction > 0.0 }
            val isSingleFullSlice = visibleSlices.size == 1 &&
                (visibleSlices[0].fraction / total) >= 0.999

            for (slice in visibleSlices) {
                val sweep = (slice.fraction / total * 360.0).toFloat()
                if (isSingleFullSlice) {
                    // A single 100% slice draws as a full ring with no angular inset —
                    // an inset here would carve a visible gap out of an otherwise
                    // unbroken circle, which the macOS SectorMark render never shows.
                    drawArc(
                        color = slice.color,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = ringWidth, cap = StrokeCap.Butt),
                    )
                } else {
                    val insetSweep = (sweep - 2 * ANGULAR_INSET_DEGREES).coerceAtLeast(0f)
                    drawArc(
                        color = slice.color,
                        startAngle = startAngle + ANGULAR_INSET_DEGREES,
                        sweepAngle = insetSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = ringWidth, cap = StrokeCap.Round),
                    )
                }
                startAngle += sweep
            }
        }
        content()
    }
}
