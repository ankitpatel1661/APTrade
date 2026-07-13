package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.USMarketHoliday
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarDaysTest {
    private val calendar = MarketCalendar()
    // 2026-11-23 (Mon) local epoch day: compute via calendar.localEpochDay of 2026-11-23 12:00 ET
    // = 17:00 UTC = 1795453200
    private val monday = calendar.localEpochDay(1_795_453_200L)

    private fun ev(symbol: String, day: String) = EarningsEvent(symbol, "", day, EarningsSession.AfterClose, null, null)

    @Test
    fun holidayAndHalfDayRowsAppearEventlessDaysCollapse() {
        // Window Mon Nov 23 .. Sun Nov 29 2026: Thanksgiving Thu 26 (holiday), Fri 27 half-day.
        val days = buildCalendarDays(monday, 7, calendar, events = listOf(ev("AAPL", "2026-11-24")))
        assertEquals(listOf("2026-11-24", "2026-11-26", "2026-11-27"), days.map { it.day })
        assertEquals(USMarketHoliday.Thanksgiving, days[1].holiday)
        assertTrue(days[2].isHalfDay)
        assertEquals(listOf("AAPL"), days[0].events.map { it.symbol })
    }

    @Test
    fun eventsOutsideWindowAreDropped() {
        val days = buildCalendarDays(monday, 2, calendar, events = listOf(ev("AAPL", "2026-12-25")))
        assertTrue(days.none { it.events.isNotEmpty() })
    }
}
