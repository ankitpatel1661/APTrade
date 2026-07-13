package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.USMarketHoliday

/** One rendered day on the Calendar tab. Only days that have something to say survive
 *  grouping (holiday, half-day, or at least one earnings event) — see [buildCalendarDays]. */
data class CalendarDay(
    val day: String,
    val localEpochDay: Long,
    val holiday: USMarketHoliday?,
    val isHalfDay: Boolean,
    val events: List<EarningsEvent>,
)

/** Groups a pre-filtered, pre-sorted event list (FetchEarningsCalendar.execute output —
 *  its ordering is preserved within a day) into the next [count] days starting at
 *  [startLocalEpochDay]. Pure; both Kotlin apps' ViewModels call this, and its tests are
 *  the single source of truth for grouping behavior. */
fun buildCalendarDays(
    startLocalEpochDay: Long,
    count: Int,
    calendar: MarketCalendar,
    events: List<EarningsEvent>,
): List<CalendarDay> {
    val byDay = events.groupBy { it.day }
    return (0 until count).mapNotNull { offset ->
        val epochDay = startLocalEpochDay + offset
        val day = calendar.dayString(epochDay)
        val holiday = calendar.holiday(epochDay)
        val halfDay = calendar.isHalfDay(epochDay)
        val dayEvents = byDay[day].orEmpty()
        if (holiday == null && !halfDay && dayEvents.isEmpty()) return@mapNotNull null
        CalendarDay(day = day, localEpochDay = epochDay, holiday = holiday, isHalfDay = halfDay, events = dayEvents)
    }
}
