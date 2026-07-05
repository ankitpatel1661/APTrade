package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The original fixtures below (January 2024) are all in the EST portion of the year, so
 * they are computed at UTC-5 -- `local wall-clock time - (-5h)`, i.e. UTC -- and are
 * unaffected by the statutory-DST fix (see MarketCalendar's class doc). The fixtures
 * further down cover the EDT (UTC-4) window and the March/November transition dates.
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

    // --- Statutory US DST fixtures (post-fix: EDT = UTC-4 in the DST window) ---
    //
    // Derivation for wednesdayJulyNineThirtyAmEdtOpen:
    // Wednesday 2024-07-10, 09:30 America/New_York wall-clock. In July, New York observes
    // EDT (UTC-4), so local 09:30 EDT is UTC 13:30, i.e.
    //   epoch = (2024-07-10T13:30:00Z).epochSeconds = 1_720_618_200
    // Sanity check on why the fixed-UTC-5 bug mattered: the OLD code computed
    // localSeconds = epoch + (-5h), which maps this same instant to local 08:30 -- one
    // hour before the open bell, i.e. CLOSED. The correct EDT-aware answer is OPEN.
    private val wednesdayJulyNineThirtyAmEdtOpen = 1_720_618_200L

    // One minute earlier: 2024-07-10 09:29 EDT = UTC 13:29:00Z.
    private val wednesdayJulyNineTwentyNineAmEdtClosed = 1_720_618_140L

    // 2024 DST window: began 2nd Sunday of March (2024-03-10), ended 1st Sunday of
    // November (2024-11-03). Monday the 11th is the first full weekday inside DST;
    // Monday the 4th of November is the first full weekday back on standard time.
    //
    // Monday 2024-03-11, 10:00 EDT (UTC-4) = UTC 14:00:00Z.
    private val mondayAfterMarchDstStartTenAmEdtOpen = 1_710_165_600L

    // Monday 2024-11-04, 10:00 EST (UTC-5) = UTC 15:00:00Z.
    private val mondayAfterNovemberDstEndTenAmEstOpen = 1_730_732_400L

    @Test
    fun statusIsOpenAtNineThirtyAmEdtInJuly() {
        assertEquals(MarketStatus.OPEN, calendar.status(wednesdayJulyNineThirtyAmEdtOpen))
    }

    @Test
    fun statusIsClosedOneMinuteBeforeTheEdtOpenBell() {
        assertEquals(MarketStatus.CLOSED, calendar.status(wednesdayJulyNineTwentyNineAmEdtClosed))
    }

    @Test
    fun statusIsOpenOnTheMondayAfterTheMarchDstStartAtTenAmLocal() {
        // 2024-03-11 is the Monday immediately after the 2nd Sunday of March (DST start);
        // this instant must be interpreted as EDT (UTC-4), not the old fixed UTC-5.
        assertEquals(MarketStatus.OPEN, calendar.status(mondayAfterMarchDstStartTenAmEdtOpen))
    }

    @Test
    fun statusIsOpenOnTheMondayAfterTheNovemberDstEndAtTenAmLocal() {
        // 2024-11-04 is the Monday immediately after the 1st Sunday of November (DST end);
        // this instant must be interpreted as EST (UTC-5).
        assertEquals(MarketStatus.OPEN, calendar.status(mondayAfterNovemberDstEndTenAmEstOpen))
    }
}
