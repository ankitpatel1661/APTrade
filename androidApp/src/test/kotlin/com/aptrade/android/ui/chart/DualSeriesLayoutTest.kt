package com.aptrade.android.ui.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DualSeriesLayoutTest {

    private val height = 100f

    @Test
    fun `shared domain spans the concatenation, not each series independently`() {
        // Primary ranges 0..10, secondary ranges 90..100 — if scaled per-series, both would fill
        // the whole canvas identically and the (very different) comparison would be lost. Under
        // a SHARED min/max (0..100) primary must hug the bottom of the canvas and secondary the
        // top, pinning the load-bearing property from the 6c.5 hazard.
        val primary = listOf(0.0, 10.0)
        val secondary = listOf(90.0, 100.0)

        val layout = dualSeriesLayout(primary, secondary, height)

        val secondaryY = assertNotNull(layout.secondaryY)
        // Combined min=0, max=100, span=100. y(v) = height - (v-min)/span*height.
        assertEquals(100f, layout.primaryY[0], 0.001f) // y(0) = 100
        assertEquals(90f, layout.primaryY[1], 0.001f) // y(10) = 90
        assertEquals(10f, secondaryY[0], 0.001f) // y(90) = 10
        assertEquals(0f, secondaryY[1], 0.001f) // y(100) = 0

        // Under (wrong) per-series scaling, primary's own 0..10 domain would map its two points
        // to 100f and 0f (spanning the FULL canvas) instead of hugging the bottom as asserted
        // above — confirming the shared-domain behavior is actually exercised here.
        assertTrue(layout.primaryY[1] > height / 2f, "primary should stay in the lower half under the shared domain")
    }

    @Test
    fun `degenerate flat combined series maps every point to the same y without dividing by zero`() {
        // min == max means a naive (max-min) span would divide by zero; the fallback span of 1.0
        // must produce a single stable, finite y for every point on BOTH series (not NaN/Inf).
        val primary = listOf(5.0, 5.0, 5.0)
        val secondary = listOf(5.0, 5.0)

        val layout = dualSeriesLayout(primary, secondary, height)

        val secondaryY = assertNotNull(layout.secondaryY)
        val expected = layout.primaryY.first()
        assertTrue(expected.isFinite())
        for (y in layout.primaryY) assertEquals(expected, y, 0.001f)
        for (y in secondaryY) assertEquals(expected, y, 0.001f)
    }

    @Test
    fun `null secondary yields primary-only layout`() {
        val primary = listOf(1.0, 2.0, 3.0)

        val layout = dualSeriesLayout(primary, null, height)

        assertNull(layout.secondaryY)
        assertEquals(3, layout.primaryY.size)
        // Primary-only domain is 1..3: y(1)=height, y(3)=0.
        assertEquals(height, layout.primaryY[0], 0.001f)
        assertEquals(0f, layout.primaryY[2], 0.001f)
    }

    @Test
    fun `secondary with fewer than two points falls back to primary-only`() {
        val primary = listOf(1.0, 2.0)
        val secondary = listOf(999.0)

        val layout = dualSeriesLayout(primary, secondary, height)

        assertNull(layout.secondaryY)
        assertEquals(2, layout.primaryY.size)
    }

    @Test
    fun `primary with fewer than two points yields nothing to draw`() {
        val layout = dualSeriesLayout(listOf(1.0), listOf(1.0, 2.0), height)

        assertTrue(layout.primaryY.isEmpty())
        assertNull(layout.secondaryY)
    }
}
