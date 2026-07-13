package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun event(symbol: String, day: String, session: EarningsSession = EarningsSession.AfterClose) =
    EarningsEvent(symbol = symbol, companyName = "$symbol Inc.", day = day, session = session, epsEstimate = 1.0, epsActual = null)

private class FakeEarningsRepository(private val events: List<EarningsEvent>) : EarningsCalendarRepository {
    var calls = 0
    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> {
        calls++
        return events
    }
}

class EarningsUseCasesTest {

    @Test
    fun filtersToIndexPlusOwnSymbols() = runTest {
        val repo = FakeEarningsRepository(listOf(
            event("AAPL", "2026-07-20"),           // in index
            event("TINYCO", "2026-07-20"),         // not index, not owned -> dropped
            event("MYPENNY", "2026-07-21"),        // not index, but OWNED -> kept
        ))
        val fetch = FetchEarningsCalendar(repo) { setOf("MYPENNY") }
        val out = fetch.execute("2026-07-20", "2026-07-27")
        assertEquals(listOf("AAPL", "MYPENNY"), out.map { it.symbol })
    }

    @Test
    fun ownSymbolsSortFirstWithinADay() = runTest {
        val repo = FakeEarningsRepository(listOf(
            event("AAPL", "2026-07-20"),
            event("ZTS", "2026-07-20"),
            event("MSFT", "2026-07-20"),
        ))
        val fetch = FetchEarningsCalendar(repo) { setOf("ZTS") }
        val out = fetch.execute("2026-07-20", "2026-07-27")
        assertEquals(listOf("ZTS", "AAPL", "MSFT"), out.map { it.symbol }) // owned pinned, rest alphabetical
    }

    @Test
    fun nextEarningsPicksEarliestForSymbol() = runTest {
        val repo = FakeEarningsRepository(listOf(
            event("AAPL", "2026-07-24"),
            event("AAPL", "2026-10-22"),
            event("MSFT", "2026-07-21"),
        ))
        val fetch = FetchEarningsCalendar(repo) { emptySet() }
        assertEquals("2026-07-24", fetch.nextEarnings("AAPL", "2026-07-13", "2026-08-12")?.day)
    }

    @Test
    fun nextEarningsNullWhenAbsent() = runTest {
        val fetch = FetchEarningsCalendar(FakeEarningsRepository(emptyList())) { emptySet() }
        assertNull(fetch.nextEarnings("AAPL", "2026-07-13", "2026-08-12"))
    }

    @Test
    fun repositoryFailureDegradesToEmpty() = runTest {
        val repo = object : EarningsCalendarRepository {
            override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> =
                throw QuoteError.Network("boom")
        }
        val fetch = FetchEarningsCalendar(repo) { emptySet() }
        assertEquals(emptyList(), fetch.execute("2026-07-20", "2026-07-27"))
    }

    @Test
    fun dotClassTickersMatchDashForm() = runTest {
        // Finnhub reports BRK.B as "BRK.B"; a user may hold "BRK-B". Normalization makes them meet.
        val repo = FakeEarningsRepository(listOf(event("BRK.B", "2026-07-20")))
        val fetch = FetchEarningsCalendar(repo) { setOf("BRK-B") }
        val out = fetch.execute("2026-07-20", "2026-07-27")
        assertEquals(listOf("BRK.B"), out.map { it.symbol })
        // and it counts as OWN (pinned) — verify via a mixed day
    }

    @Test
    fun ownedTodayReturnsOnlyOwnedSymbolsForTheDay() = runTest {
        val repo = FakeEarningsRepository(listOf(
            event("MYPENNY", "2026-07-20"),  // owned
            event("AAPL", "2026-07-20"),     // index-only, not owned
        ))
        val fetch = FetchEarningsCalendar(repo) { setOf("MYPENNY") }
        val out = fetch.ownedToday("2026-07-20")
        assertEquals(listOf("MYPENNY"), out.map { it.symbol })
    }
}
