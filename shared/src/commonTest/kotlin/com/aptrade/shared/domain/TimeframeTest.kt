package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeframeTest {
    @Test
    fun yahooRangeAndIntervalPerTimeframe() {
        assertEquals("5d" to "5m", Timeframe.OneDay.yahooRange to Timeframe.OneDay.yahooInterval)
        assertEquals("1mo" to "60m", Timeframe.OneWeek.yahooRange to Timeframe.OneWeek.yahooInterval)
        assertEquals("3mo" to "1d", Timeframe.OneMonth.yahooRange to Timeframe.OneMonth.yahooInterval)
        assertEquals("1y" to "1d", Timeframe.OneYear.yahooRange to Timeframe.OneYear.yahooInterval)
    }

    @Test
    fun windowDurationSecondsPerTimeframe() {
        assertEquals(24L * 3600, Timeframe.OneDay.windowDurationSeconds)
        assertEquals(7L * 24 * 3600, Timeframe.OneWeek.windowDurationSeconds)
        assertEquals(30L * 24 * 3600, Timeframe.OneMonth.windowDurationSeconds)
        assertEquals(365L * 24 * 3600, Timeframe.OneYear.windowDurationSeconds)
    }
}
