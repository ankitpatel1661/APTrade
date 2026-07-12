package com.aptrade.android.ui.chart

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrosshairTest {

    // --- nearestIndex --------------------------------------------------------------------

    @Test
    fun `maps a pointer x to the nearest sampled index across the chart width`() {
        // 5 points span indices 0..4 over a 100px-wide chart: each point occupies a 25px slot.
        assertEquals(0, nearestIndex(0f, 100f, 5))
        assertEquals(1, nearestIndex(25f, 100f, 5))
        assertEquals(2, nearestIndex(50f, 100f, 5))
        assertEquals(4, nearestIndex(100f, 100f, 5))
    }

    @Test
    fun `rounds to the nearest index rather than truncating`() {
        // 4 points over 90px -> 30px/point; x=40 is closer to index 1 (30) than index 2 (60).
        assertEquals(1, nearestIndex(40f, 90f, 4))
        // x=50 is closer to index 2 (60) than index 1 (30).
        assertEquals(2, nearestIndex(50f, 90f, 4))
    }

    @Test
    fun `clamps out-of-bounds pointer positions to the valid index range`() {
        assertEquals(0, nearestIndex(-50f, 100f, 5))
        assertEquals(4, nearestIndex(500f, 100f, 5))
    }

    @Test
    fun `fewer than two points has nothing to scrub so it returns index zero`() {
        assertEquals(0, nearestIndex(50f, 100f, 1))
        assertEquals(0, nearestIndex(50f, 100f, 0))
    }

    @Test
    fun `zero-width chart falls back to the last index instead of dividing by zero`() {
        assertEquals(4, nearestIndex(10f, 0f, 5))
    }

    // --- crosshairReadout ------------------------------------------------------------------

    private val utc = ZoneId.of("UTC")

    @Test
    fun `maps an index to its pre-formatted price and a formatted date`() {
        val priceTexts = listOf("$100.50", "$101.25", "$99.80")
        // 2024-01-15T09:30:00Z
        val epochSeconds = listOf(1705311000L, 1705314600L, 1705318200L)

        val readout = crosshairReadout(1, priceTexts, epochSeconds, utc)

        assertEquals("$101.25", readout?.priceText)
        assertEquals("Jan 15, 10:30 AM", readout?.dateText)
    }

    @Test
    fun `index zero maps to the first point`() {
        val priceTexts = listOf("$100.50", "$101.25")
        val epochSeconds = listOf(1705311000L, 1705314600L)

        val readout = crosshairReadout(0, priceTexts, epochSeconds, utc)

        assertEquals("$100.50", readout?.priceText)
        assertEquals("Jan 15, 9:30 AM", readout?.dateText)
    }

    @Test
    fun `out-of-range index yields no readout rather than crashing`() {
        val priceTexts = listOf("$100.50")
        val epochSeconds = listOf(1705311000L)

        assertNull(crosshairReadout(5, priceTexts, epochSeconds, utc))
        assertNull(crosshairReadout(-1, priceTexts, epochSeconds, utc))
    }

    @Test
    fun `mismatched parallel list lengths yield no readout for the missing side`() {
        val priceTexts = listOf("$100.50", "$101.25")
        val epochSeconds = listOf(1705311000L) // shorter — date missing for index 1

        assertNull(crosshairReadout(1, priceTexts, epochSeconds, utc))
    }
}
