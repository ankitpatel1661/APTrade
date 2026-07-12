package com.aptrade.android.watchlist

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeWatchlistStore(
    var entries: List<WatchlistEntry> = emptyList(),
) : WatchlistStore {
    override suspend fun load(): List<WatchlistEntry> = entries
    override suspend fun save(entries: List<WatchlistEntry>) { this.entries = entries }
}

class WatchlistViewModelTest {

    // FetchWatchlist's `defaults` param is mandatory (no default value on the shared use case) —
    // empty here since these tests always seed the store directly and never exercise first-launch
    // seeding (that's FetchWatchlistTest's job in shared/).
    private fun vm(store: FakeWatchlistStore, repo: FakeMarketDataRepository) = WatchlistViewModel(
        fetchWatchlist = FetchWatchlist(store, emptyList()),
        addToWatchlist = AddToWatchlist(store),
        removeFromWatchlist = RemoveFromWatchlist(store),
        fetchMarketQuotes = FetchMarketQuotes(repo),
        pollIntervalMs = Long.MAX_VALUE, // no re-poll during tests
    )

    @Test
    fun `load populates rows in watchlist order with quotes`() = runTest {
        val store = FakeWatchlistStore(listOf(
            WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
            WatchlistEntry("BTC-USD", "Bitcoin", AssetKind.Crypto),
        ))
        val repo = FakeMarketDataRepository()
        // The fake's default quotesImpl returns emptyList() regardless of input, which would
        // leave amountText null for every row — configure it to answer for whatever symbols the
        // VM asks for, mirroring how every other suite in this app drives the same fake.
        repo.quotesImpl = { symbols -> symbols.map { Quote(it, Money.usd("100.00"), Money.usd("100.00"), 0.0) } }
        val viewModel = vm(store, repo)
        viewModel.load()
        val state = viewModel.state.value
        assertEquals(listOf("AAPL", "BTC-USD"), state.rows.map { it.symbol })
        assertTrue(state.rows.first().amountText != null)
    }

    @Test
    fun `remove drops the row and persists`() = runTest {
        val store = FakeWatchlistStore(listOf(WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)))
        val viewModel = vm(store, FakeMarketDataRepository())
        viewModel.load()
        viewModel.remove("AAPL")
        assertEquals(emptyList(), viewModel.state.value.rows)
        assertEquals(emptyList(), store.entries)
    }
}
