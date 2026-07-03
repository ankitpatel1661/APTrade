package com.aptrade.shared.application

import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class InMemoryWatchlistStore : WatchlistStore {
    var stored: List<WatchlistEntry> = emptyList()
    var saveCount = 0
        private set

    override suspend fun load(): List<WatchlistEntry> = stored
    override suspend fun save(entries: List<WatchlistEntry>) {
        stored = entries
        saveCount++
    }
}

private val aapl = WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)
private val spy = WatchlistEntry("SPY", "SPDR S&P 500 ETF Trust", AssetKind.Etf)
private val btc = WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto)

class WatchlistUseCasesTest {

    @Test
    fun fetchSeedsAndPersistsDefaultsWhenStoreIsEmpty() = runTest {
        val store = InMemoryWatchlistStore()
        val result = FetchWatchlist(store, defaults = listOf(aapl, spy)).execute()
        assertEquals(listOf(aapl, spy), result)
        assertEquals(listOf(aapl, spy), store.stored)   // seed was persisted
    }

    @Test
    fun fetchReturnsStoredEntriesWithoutTouchingDefaults() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(btc) }
        val result = FetchWatchlist(store, defaults = listOf(aapl)).execute()
        assertEquals(listOf(btc), result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun addAppendsNormalizedSymbolAndPersists() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl) }
        val result = AddToWatchlist(store)
            .execute(WatchlistEntry("  msft ", "Microsoft Corporation", AssetKind.Stock))
        assertEquals(listOf("AAPL", "MSFT"), result.map { it.symbol })
        assertEquals(result, store.stored)
    }

    @Test
    fun addIsIdempotentForDuplicateSymbols() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl) }
        val result = AddToWatchlist(store).execute(aapl.copy(name = "Renamed"))
        assertEquals(listOf(aapl), result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun addIgnoresBlankSymbols() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl) }
        val result = AddToWatchlist(store).execute(WatchlistEntry("   ", "", AssetKind.Stock))
        assertEquals(listOf(aapl), result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun removeDropsTheSymbolAndPersists() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl, spy) }
        val result = RemoveFromWatchlist(store).execute("AAPL")
        assertEquals(listOf(spy), result)
        assertEquals(result, store.stored)
    }

    @Test
    fun removeOfUnknownSymbolIsANoOp() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl) }
        val result = RemoveFromWatchlist(store).execute("ZZZZ")
        assertEquals(listOf(aapl), result)
        assertEquals(0, store.saveCount)
    }
}
