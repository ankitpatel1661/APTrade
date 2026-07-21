package com.aptrade.desktop.detail

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.EarningsCalendarRepository
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchDividendEvents
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun event(symbol: String, day: String) =
    EarningsEvent(symbol = symbol, companyName = "$symbol Inc.", day = day, session = EarningsSession.AfterClose, epsEstimate = 1.5, epsActual = null)

private class FakeEarningsCalendarRepository(
    private val events: List<EarningsEvent> = emptyList(),
    private val error: Throwable? = null,
) : EarningsCalendarRepository {
    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> {
        error?.let { throw it }
        return events
    }
}

private fun vm(
    symbol: String,
    repo: FakeMarketDataRepository,
    earningsRepo: EarningsCalendarRepository,
    scope: CoroutineScope,
    nowEpochSeconds: Long,
) = DetailViewModel(
    symbol = symbol,
    fetchProfile = FetchProfile(repo),
    fetchMarketQuotes = FetchMarketQuotes(repo),
    fetchHistory = FetchHistory(repo),
    fetchChartWindow = FetchChartWindow(repo),
    fetchEarningsCalendar = FetchEarningsCalendar(earningsRepo) { emptySet() },
    fetchDividendEvents = FetchDividendEvents(repo),
    scope = scope,
    nowEpochSeconds = { nowEpochSeconds },
)

class DetailViewModelEarningsTest {
    // Monday 2026-11-23 local epoch day: calendar.localEpochDay of 2026-11-23 12:00 ET =
    // 17:00 UTC = 1795453200 — same reference instant CalendarDaysTest/CalendarViewModelTest use.
    private val nowEpochSeconds = 1_795_453_200L

    @Test
    fun nextEarningsPopulatesFromFakeRepo() = runTest {
        val repo = FakeEarningsCalendarRepository(events = listOf(
            event("AAPL", "2026-11-24"),
            event("AAPL", "2026-12-20"),
        ))
        val vm = vm("AAPL", FakeMarketDataRepository(), repo, backgroundScope, nowEpochSeconds)
        runCurrent()

        // Earliest of the two AAPL events within the 30-day window wins.
        assertEquals("2026-11-24", vm.state.value.nextEarnings?.day)
    }

    @Test
    fun failingRepoDegradesToNullWithoutCrashing() = runTest {
        val repo = FakeEarningsCalendarRepository(error = QuoteError.Network("boom"))
        val vm = vm("AAPL", FakeMarketDataRepository(), repo, backgroundScope, nowEpochSeconds)
        runCurrent()

        assertNull(vm.state.value.nextEarnings)
    }

    @Test
    fun noMatchingEventLeavesNextEarningsNull() = runTest {
        val repo = FakeEarningsCalendarRepository(events = listOf(event("MSFT", "2026-11-24")))
        val vm = vm("AAPL", FakeMarketDataRepository(), repo, backgroundScope, nowEpochSeconds)
        runCurrent()

        assertNull(vm.state.value.nextEarnings)
    }
}
