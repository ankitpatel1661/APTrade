package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.SP500Symbols
import kotlinx.coroutines.CancellationException

/** Ticker forms differ between sources ("BRK.B" vs "BRK-B"); compare on a dot/dash-blind key. */
private fun normalized(symbol: String): String = symbol.uppercase().replace('-', '.')

/**
 * Serves both earnings surfaces. [ownSymbols] is a provider (watchlist ∪ portfolio read
 * fresh per call — both change at runtime). Filtering: keep events whose symbol is in the
 * S&P 500 snapshot OR owned; owned events sort before index events within a day, then
 * alphabetically. Failures degrade to an empty list (CancellationException excepted) —
 * the calendar's holiday banners must render even when the network is down.
 */
class FetchEarningsCalendar(
    private val repository: EarningsCalendarRepository,
    private val ownSymbols: suspend () -> Set<String>,
) {
    suspend fun execute(fromDay: String, toDay: String): List<EarningsEvent> {
        val events = fetchOrEmpty(fromDay, toDay)
        val own = ownSymbols().mapTo(HashSet(), ::normalized)
        val index = SP500Symbols.set.mapTo(HashSet(), ::normalized)
        return events
            .filter { normalized(it.symbol) in own || normalized(it.symbol) in index }
            .sortedWith(
                compareBy<EarningsEvent> { it.day }
                    .thenBy { normalized(it.symbol) !in own } // owned first
                    .thenBy { it.symbol },
            )
    }

    /** Earliest event for [symbol] in the window, or null. Uses the same fetch (and any
     *  caching the repository provides) as the calendar screen. */
    suspend fun nextEarnings(symbol: String, fromDay: String, toDay: String): EarningsEvent? {
        val key = normalized(symbol)
        val events = fetchOrEmpty(fromDay, toDay)
        return events.filter { normalized(it.symbol) == key }.minByOrNull { it.day }
    }

    /** Events on [day] restricted to symbols the user actually owns/watches — index-only
     *  events (S&P 500 constituents the user has no position or watch on) are excluded. */
    suspend fun ownedToday(day: String): List<EarningsEvent> {
        val events = fetchOrEmpty(day, day)
        val own = ownSymbols().mapTo(HashSet(), ::normalized)
        return events.filter { normalized(it.symbol) in own }
    }

    private suspend fun fetchOrEmpty(fromDay: String, toDay: String): List<EarningsEvent> =
        try {
            repository.earnings(fromDay, toDay)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
}
