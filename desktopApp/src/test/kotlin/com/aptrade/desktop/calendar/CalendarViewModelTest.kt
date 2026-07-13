package com.aptrade.desktop.calendar

import com.aptrade.shared.application.EarningsCalendarRepository
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.USMarketHoliday
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun event(symbol: String, day: String) =
    EarningsEvent(symbol = symbol, companyName = "$symbol Inc.", day = day, session = EarningsSession.AfterClose, epsEstimate = 1.5, epsActual = null)

/** Records nothing beyond the fixed [events]/[error] it was built with — the ViewModel's
 *  contract under test is grouping/needsKey/failure-degradation, not repository call shape
 *  (that's EarningsUseCasesTest's job). */
private class FakeEarningsCalendarRepository(
    private val events: List<EarningsEvent> = emptyList(),
    private val error: Throwable? = null,
) : EarningsCalendarRepository {
    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> {
        error?.let { throw it }
        return events
    }
}

class CalendarViewModelTest {
    private val calendar = MarketCalendar()

    // Monday 2026-11-23 local epoch day: calendar.localEpochDay of 2026-11-23 12:00 ET =
    // 17:00 UTC = 1795453200 — the SAME reference instant CalendarDaysTest (shared) uses, so a
    // 14-day window starting here is known to contain Thanksgiving (Thu Nov 26) and its
    // half-day (Fri Nov 27).
    private val nowEpochSeconds = 1_795_453_200L

    private fun vm(
        scope: CoroutineScope,
        events: List<EarningsEvent> = emptyList(),
        error: Throwable? = null,
        ownSymbols: Set<String> = emptySet(),
        needsKey: Boolean = false,
    ) = CalendarViewModel(
        fetch = FetchEarningsCalendar(FakeEarningsCalendarRepository(events, error)) { ownSymbols },
        calendar = calendar,
        ownSymbols = { ownSymbols },
        needsKey = needsKey,
        nowEpochSeconds = { nowEpochSeconds },
        scope = scope,
    )

    @Test
    fun loadPopulatesEventDayHolidayDayAndOwnSymbols() = runTest {
        // AAPL "tomorrow" (Nov 24) plus a window that contains Thanksgiving (Nov 26).
        val vm = vm(backgroundScope, events = listOf(event("AAPL", "2026-11-24")), ownSymbols = setOf("AAPL"))
        vm.load(); runCurrent()

        val s = vm.state.value
        assertFalse(s.isLoading)
        assertFalse(s.needsKey)
        assertEquals(setOf("AAPL"), s.ownSymbols)
        assertTrue(s.days.any { it.day == "2026-11-24" && it.events.map { e -> e.symbol } == listOf("AAPL") })
        assertEquals(USMarketHoliday.Thanksgiving, s.days.first { it.day == "2026-11-26" }.holiday)
    }

    @Test
    fun needsKeyStillRendersHolidayRowsFromAnEmptyFetch() = runTest {
        val vm = vm(backgroundScope, needsKey = true)
        vm.load(); runCurrent()

        val s = vm.state.value
        assertTrue(s.needsKey)
        assertFalse(s.isLoading)
        assertTrue(s.days.all { it.events.isEmpty() })
        assertTrue(s.days.any { it.day == "2026-11-26" && it.holiday == USMarketHoliday.Thanksgiving })
    }

    @Test
    fun fetchFailureDegradesToHolidayOnlyDaysWithoutCrashing() = runTest {
        val vm = vm(backgroundScope, error = QuoteError.Network("boom"))
        vm.load(); runCurrent()

        val s = vm.state.value
        assertFalse(s.isLoading)
        assertTrue(s.days.all { it.events.isEmpty() })
        assertTrue(s.days.any { it.day == "2026-11-26" && it.holiday == USMarketHoliday.Thanksgiving })
        assertTrue(s.days.any { it.day == "2026-11-27" && it.isHalfDay })
    }
}
