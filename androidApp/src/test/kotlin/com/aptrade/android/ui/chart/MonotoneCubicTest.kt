package com.aptrade.android.ui.chart

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonotoneCubicTest {

    /** Standard cubic Bézier evaluation at parameter [t] in [0, 1] — used to sample the curve
     *  a segment actually draws, independent of the production Canvas path-drawing code. */
    private fun cubicAt(segment: CubicSegment, t: Float): Offset {
        val u = 1f - t
        val x = u * u * u * segment.start.x +
            3f * u * u * t * segment.control1.x +
            3f * u * t * t * segment.control2.x +
            t * t * t * segment.end.x
        val y = u * u * u * segment.start.y +
            3f * u * u * t * segment.control1.y +
            3f * u * t * t * segment.control2.y +
            t * t * t * segment.end.y
        return Offset(x, y)
    }

    @Test
    fun `empty input yields no segments`() {
        assertTrue(monotoneCubicControlPoints(emptyList()).isEmpty())
    }

    @Test
    fun `single point yields no segments`() {
        assertTrue(monotoneCubicControlPoints(listOf(Offset(0f, 0f))).isEmpty())
    }

    @Test
    fun `two points degrade to a single straight-line segment`() {
        val points = listOf(Offset(0f, 0f), Offset(10f, 10f))
        val segments = monotoneCubicControlPoints(points)

        assertEquals(1, segments.size)
        val segment = segments.first()
        assertEquals(points[0], segment.start)
        assertEquals(points[1], segment.end)
        // Control points must sit exactly on the connecting line (y == x here) — a straight
        // line is the correct degenerate case for exactly 2 points, not an arbitrary curve.
        assertEquals(segment.control1.x, segment.control1.y, 0.001f)
        assertEquals(segment.control2.x, segment.control2.y, 0.001f)
        // Sampling the curve at several t must also fall exactly on the line.
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val p = cubicAt(segment, t)
            assertEquals(p.x, p.y, 0.01f)
        }
    }

    @Test
    fun `monotone ascending points never overshoot their bracketing values`() {
        // A jagged-but-monotone-per-run series (steep then shallow rises) is exactly the
        // shape a naive Catmull-Rom/bezier-midpoint curve would overshoot above the local
        // high — Fritsch-Carlson must not.
        val points = listOf(
            Offset(0f, 0f),
            Offset(10f, 50f),
            Offset(20f, 60f),
            Offset(30f, 61f),
            Offset(40f, 100f),
        )
        val segments = monotoneCubicControlPoints(points)
        assertEquals(points.size - 1, segments.size)

        for (segment in segments) {
            val lo = minOf(segment.start.y, segment.end.y)
            val hi = maxOf(segment.start.y, segment.end.y)
            for (i in 0..20) {
                val t = i / 20f
                val y = cubicAt(segment, t).y
                assertTrue(
                    y >= lo - 0.01f && y <= hi + 0.01f,
                    "segment ${segment.start} -> ${segment.end} overshot at t=$t: y=$y not in [$lo, $hi]",
                )
            }
        }
    }

    @Test
    fun `monotone descending points never overshoot their bracketing values`() {
        val points = listOf(
            Offset(0f, 100f),
            Offset(10f, 90f),
            Offset(20f, 40f),
            Offset(30f, 39f),
            Offset(40f, 0f),
        )
        val segments = monotoneCubicControlPoints(points)

        for (segment in segments) {
            val lo = minOf(segment.start.y, segment.end.y)
            val hi = maxOf(segment.start.y, segment.end.y)
            for (i in 0..20) {
                val t = i / 20f
                val y = cubicAt(segment, t).y
                assertTrue(y >= lo - 0.01f && y <= hi + 0.01f)
            }
        }
    }

    @Test
    fun `curve passes exactly through every data point`() {
        val points = listOf(Offset(0f, 5f), Offset(5f, 1f), Offset(15f, 20f), Offset(25f, 3f))
        val segments = monotoneCubicControlPoints(points)

        assertEquals(points[0], segments.first().start)
        for (i in 1 until segments.size) {
            assertEquals(segments[i - 1].end, segments[i].start)
        }
        assertEquals(points.last(), segments.last().end)
    }

    @Test
    fun `local extremum gets a zero tangent so the peak is not overshot`() {
        // A single interior peak: the curve must not bulge above the peak's own y value on
        // either side of it.
        val points = listOf(Offset(0f, 0f), Offset(10f, 100f), Offset(20f, 0f))
        val segments = monotoneCubicControlPoints(points)
        assertEquals(2, segments.size)

        for (segment in segments) {
            for (i in 0..20) {
                val y = cubicAt(segment, i / 20f).y
                assertTrue(y <= 100f + 0.01f, "curve overshot the peak: y=$y")
                assertTrue(y >= -0.01f, "curve undershot below zero: y=$y")
            }
        }
    }

    @Test
    fun `flat run produces a flat curve`() {
        val points = listOf(Offset(0f, 5f), Offset(10f, 5f), Offset(20f, 5f))
        val segments = monotoneCubicControlPoints(points)
        for (segment in segments) {
            for (i in 0..10) {
                assertEquals(5f, cubicAt(segment, i / 10f).y, 0.01f)
            }
        }
    }

}
