package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fixtures are computed at a fixed UTC-5 offset (this port's documented simplification
 * — see MarketCalendar's class doc for the DST caveat vs. the Swift America/New_York
 * source). All epoch values below are `local wall-clock time - (-5h)`, i.e. UTC.
 */
class MarketCalendarTest {
    private val calendar = MarketCalendar()

    // Monday 2024-01-08, 10:00 local (UTC-5) = 1704726000 UTC.
    private val mondayTenAmOpen = 1_704_726_000L

    // Monday 2024-01-08, 09:00 local = 1704722400 UTC (before the 09:30 open).
    private val mondayNineAmClosed = 1_704_722_400L

    // Monday 2024-01-08, 16:00 local = 1704747600 UTC (close boundary, exclusive).
    private val mondayFourPmClosed = 1_704_747_600L

    // Monday 2024-01-08, 15:59 local = 1704747540 UTC (one minute before close).
    private val mondayThreeFiftyNinePmOpen = 1_704_747_540L

    // Saturday 2024-01-06, 10:00 local = 1704553200 UTC.
    private val saturdayTenAmClosed = 1_704_553_200L

    // Sunday 2024-01-07, 10:00 local = 1704639600 UTC.
    private val sundayTenAmClosed = 1_704_639_600L

    // Tuesday 2024-01-09, 10:00 local = 1704812400 UTC.
    private val tuesdayTenAmOpen = 1_704_812_400L

    @Test
    fun statusIsOpenDuringRegularSessionOnAWeekday() {
        assertEquals(MarketStatus.OPEN, calendar.status(mondayTenAmOpen))
    }

    @Test
    fun statusIsClosedBeforeTheOpenBell() {
        assertEquals(MarketStatus.CLOSED, calendar.status(mondayNineAmClosed))
    }

    @Test
    fun statusIsClosedAtTheCloseBoundaryInclusiveOfFourPm() {
        // 16:00 is NOT in [09:30, 16:00) — matches the Swift `minutes < closeMinute`.
        assertEquals(MarketStatus.CLOSED, calendar.status(mondayFourPmClosed))
    }

    @Test
    fun statusIsOpenOneMinuteBeforeClose() {
        assertEquals(MarketStatus.OPEN, calendar.status(mondayThreeFiftyNinePmOpen))
    }

    @Test
    fun statusIsClosedOnSaturdayEvenDuringSessionHours() {
        assertEquals(MarketStatus.CLOSED, calendar.status(saturdayTenAmClosed))
    }

    @Test
    fun statusIsClosedOnSundayEvenDuringSessionHours() {
        assertEquals(MarketStatus.CLOSED, calendar.status(sundayTenAmClosed))
    }

    @Test
    fun tradingDayFormatsAsIsoLocalDate() {
        assertEquals("2024-01-08", calendar.tradingDay(mondayTenAmOpen))
        assertEquals("2024-01-09", calendar.tradingDay(tuesdayTenAmOpen))
    }

    @Test
    fun tradingDayIsStableAcrossTheWholeLocalDayRegardlessOfMarketStatus() {
        assertEquals("2024-01-08", calendar.tradingDay(mondayNineAmClosed))
        assertEquals("2024-01-08", calendar.tradingDay(mondayFourPmClosed))
    }
}
