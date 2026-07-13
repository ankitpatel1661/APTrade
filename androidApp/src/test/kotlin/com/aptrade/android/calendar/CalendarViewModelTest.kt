package com.aptrade.android.calendar

import com.aptrade.shared.application.EarningsCalendarRepository
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.USMarketHoliday
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun event(symbol: String, day: String) =
    EarningsEvent(symbol = symbol, companyName = "$symbol Inc.", day = day, session = EarningsSession.AfterClose, epsEstimate = 1.5, epsActual = null)

/** Records nothing beyond the fixed [events]/[error] it was built with — mirrors
 *  desktopApp/src/test/.../calendar/CalendarViewModelTest.kt's fake, package-renamed. */
private class FakeEarningsCalendarRepository(
    private val events: List<EarningsEvent> = emptyList(),
    private val error: Throwable? = null,
) : EarningsCalendarRepository {
    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> {
        error?.let { throw it }
        return events
    }
}

/** [CalendarViewModel] is an androidx ViewModel using `viewModelScope`
 *  (Dispatchers.Main.immediate), mirroring NewsViewModelTest's scheduler discipline: a
 *  [StandardTestDispatcher] installed as Main, with `runCurrent()` after each `load()`. */
class CalendarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val calendar = MarketCalendar()

    // Monday 2026-11-23 local epoch day: calendar.localEpochDay of 2026-11-23 12:00 ET =
    // 17:00 UTC = 1795453200 — the SAME reference instant CalendarDaysTest (shared) and
    // desktop's CalendarViewModelTest use, so a 14-day window starting here is known to
    // contain Thanksgiving (Thu Nov 26) and its half-day (Fri Nov 27).
    private val nowEpochSeconds = 1_795_453_200L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        events: List<EarningsEvent> = emptyList(),
        error: Throwable? = null,
        ownSymbols: Set<String> = emptySet(),
        needsKey: Boolean = false,
    ) = CalendarViewModel(
        fetchProvider = { FetchEarningsCalendar(FakeEarningsCalendarRepository(events, error)) { ownSymbols } },
        needsKeyProvider = { needsKey },
        ownSymbols = { ownSymbols },
        calendar = calendar,
        nowEpochSeconds = { nowEpochSeconds },
    )

    @Test
    fun loadPopulatesEventDayHolidayDayAndOwnSymbols() = runTest {
        // AAPL "tomorrow" (Nov 24) plus a window that contains Thanksgiving (Nov 26).
        val viewModel = vm(events = listOf(event("AAPL", "2026-11-24")), ownSymbols = setOf("AAPL"))
        viewModel.load(); runCurrent()

        val s = viewModel.state.value
        assertFalse(s.isLoading)
        assertFalse(s.needsKey)
        assertEquals(setOf("AAPL"), s.ownSymbols)
        assertTrue(s.days.any { it.day == "2026-11-24" && it.events.map { e -> e.symbol } == listOf("AAPL") })
        assertEquals(USMarketHoliday.Thanksgiving, s.days.first { it.day == "2026-11-26" }.holiday)
    }

    @Test
    fun needsKeyStillRendersHolidayRowsFromAnEmptyFetch() = runTest {
        val viewModel = vm(needsKey = true)
        viewModel.load(); runCurrent()

        val s = viewModel.state.value
        assertTrue(s.needsKey)
        assertFalse(s.isLoading)
        assertTrue(s.days.all { it.events.isEmpty() })
        assertTrue(s.days.any { it.day == "2026-11-26" && it.holiday == USMarketHoliday.Thanksgiving })
    }

    @Test
    fun fetchFailureDegradesToHolidayOnlyDaysWithoutCrashing() = runTest {
        val viewModel = vm(error = QuoteError.Network("boom"))
        viewModel.load(); runCurrent()

        val s = viewModel.state.value
        assertFalse(s.isLoading)
        assertTrue(s.days.all { it.events.isEmpty() })
        assertTrue(s.days.any { it.day == "2026-11-26" && it.holiday == USMarketHoliday.Thanksgiving })
        assertTrue(s.days.any { it.day == "2026-11-27" && it.isHalfDay })
    }

    /** Android-specific (no desktop analog): [CalendarViewModel.load] re-resolves
     *  [CalendarUiState.needsKey] from `needsKeyProvider` on EVERY call — the Settings
     *  key-entry flow's contract, mirroring NewsViewModelTest's
     *  `startPicksUpAKeyConfiguredAfterConstruction`. The VM outlives Calendar-tab switches, so
     *  a key saved mid-session must be seen by the NEXT `load()` (LifecycleStartEffect calls it
     *  on every tab entry), not just at construction time. */
    @Test
    fun loadReResolvesNeedsKeyProviderOnEveryCall() = runTest {
        var needsKey = true
        val viewModel = CalendarViewModel(
            fetchProvider = { FetchEarningsCalendar(FakeEarningsCalendarRepository()) { emptySet() } },
            needsKeyProvider = { needsKey },
            ownSymbols = { emptySet() },
            calendar = calendar,
            nowEpochSeconds = { nowEpochSeconds },
        )
        viewModel.load(); runCurrent()
        assertTrue(viewModel.state.value.needsKey)

        // Settings key-entry happens mid-session; the provider now resolves false.
        needsKey = false
        viewModel.load(); runCurrent()
        assertFalse(viewModel.state.value.needsKey)
    }

    /** Regression guard (review finding): [ownSymbols] is a raw file-backed watchlist/portfolio
     *  read that sits OUTSIDE [FetchEarningsCalendar]'s own failure guard and can throw
     *  IOException. Uncaught, androidx's `viewModelScope` reaches the default uncaught-exception
     *  handler and CRASHES the process. A thrown ownSymbols must degrade to empty
     *  events/ownSymbols but still finish the load with the holiday-only days built and
     *  rendered — no crash, `isLoading` cleared. */
    @Test
    fun throwingOwnSymbolsDegradesToHolidayOnlyDaysWithoutCrashing() = runTest {
        val viewModel = CalendarViewModel(
            fetchProvider = { FetchEarningsCalendar(FakeEarningsCalendarRepository(listOf(event("AAPL", "2026-11-24")))) { emptySet() } },
            needsKeyProvider = { false },
            ownSymbols = { throw java.io.IOException("watchlist file unreadable") },
            calendar = calendar,
            nowEpochSeconds = { nowEpochSeconds },
        )
        viewModel.load(); runCurrent()

        val s = viewModel.state.value
        assertFalse(s.isLoading)
        assertEquals(emptySet(), s.ownSymbols)
        assertTrue(s.days.all { it.events.isEmpty() })
        assertTrue(s.days.any { it.day == "2026-11-26" && it.holiday == USMarketHoliday.Thanksgiving })
        assertTrue(s.days.any { it.day == "2026-11-27" && it.isHalfDay })
    }

    /** Regression guard (review finding): ticker forms differ between sources ("BRK.B" Finnhub
     *  vs "BRK-B" Yahoo/watchlist) — a watched dash-form symbol must still mark the dot-form
     *  event as owned via [CalendarUiState.ownSymbols]'s normalized storage. */
    @Test
    fun dashFormOwnSymbolMarksDotFormEventAsOwnedViaNormalizedState() = runTest {
        val viewModel = vm(
            events = listOf(event("BRK.B", "2026-11-24")),
            ownSymbols = setOf("BRK-B"),
        )
        viewModel.load(); runCurrent()

        val s = viewModel.state.value
        assertTrue(s.ownSymbols.contains("BRK.B")) // normalized storage: dash -> dot
        assertTrue(s.days.any { it.day == "2026-11-24" && it.events.any { e -> e.symbol == "BRK.B" } })
    }
}
