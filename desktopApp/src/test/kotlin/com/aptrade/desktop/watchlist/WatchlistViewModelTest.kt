package com.aptrade.desktop.watchlist

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class InMemoryStore : WatchlistStore {
    var stored: List<WatchlistEntry> = emptyList()
    override suspend fun load() = stored
    override suspend fun save(entries: List<WatchlistEntry>) { stored = entries }
}

private val defaults = listOf(
    WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
    WatchlistEntry("SPY", "SPDR S&P 500 ETF Trust", AssetKind.Etf),
    WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto),
)

private fun assertDoubleListEquals(expected: List<Double>, actual: List<Double>, tolerance: Double = 1e-9) {
    assertEquals(expected.size, actual.size)
    for (i in expected.indices) {
        assertEquals(expected[i], actual[i], tolerance)
    }
}

// .25 cents: Money.amountText drops trailing zeros (known shared-core debt); display padding is splitPrice's job
private fun quote(symbol: String, price: String, change: Double) =
    Quote(symbol, Money.usd(price), Money.usd(price), change)

private fun vm(
    repo: FakeMarketDataRepository,
    store: InMemoryStore,
    scope: kotlinx.coroutines.CoroutineScope,
) = WatchlistViewModel(
    fetchMarketQuotes = FetchMarketQuotes(repo),
    fetchHistory = FetchHistory(repo),
    fetchWatchlist = FetchWatchlist(store, defaults),
    addToWatchlist = AddToWatchlist(store),
    removeFromWatchlist = RemoveFromWatchlist(store),
    scope = scope,
)

class WatchlistViewModelTest {

    @Test
    fun startLoadsSeededWatchlistWithQuotesAndCounts() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.25", 1.0) } }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()

        val s = vm.state.value
        assertEquals(false, s.isLoading)
        assertEquals(AssetKind.Stock, s.kind)
        assertEquals(listOf("AAPL"), s.rows.map { it.symbol })       // filtered to Stocks
        assertEquals(mapOf(AssetKind.Stock to 1, AssetKind.Etf to 1, AssetKind.Crypto to 1), s.counts)
        assertEquals("100.25", s.rows.single().amountText)
    }

    @Test
    fun kindSelectionSwitchesVisibleRows() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        vm.onKindSelect(AssetKind.Crypto); runCurrent()
        assertEquals(listOf("BTC-USD"), vm.state.value.rows.map { it.symbol })
    }

    @Test
    fun pollTickRefreshesPrices() = runTest {
        val repo = FakeMarketDataRepository()
        var price = "100.25"
        repo.quotesImpl = { symbols -> symbols.map { quote(it, price, 1.0) } }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        price = "101.25"
        advanceTimeBy(15_001); runCurrent()
        assertEquals("101.25", vm.state.value.rows.single().amountText)
    }

    @Test
    fun pollFailureKeepsLastGoodRowsAndSetsError() = runTest {
        val repo = FakeMarketDataRepository()
        var fail = false
        repo.quotesImpl = { symbols ->
            if (fail) throw QuoteError.Network("boom") else symbols.map { quote(it, "100.25", 1.0) }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        fail = true
        advanceTimeBy(15_001); runCurrent()
        val s = vm.state.value
        assertEquals("100.25", s.rows.single().amountText)   // stale-but-present beats empty
        assertNotNull(s.error)
        fail = false
        advanceTimeBy(15_001); runCurrent()
        assertNull(vm.state.value.error)                      // recovers
    }

    @Test
    fun sparklinesFetchOnFirstTickOnly_thenEveryFourth() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        var historyCalls = 0
        repo.historyImpl = { _, _ -> historyCalls++
            listOf(PricePoint(1, Money.usd("99.00")), PricePoint(2, Money.usd("100.00"))) }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(3, historyCalls)                          // tick 0: all 3 symbols
        assertEquals(listOf(99.0, 100.0), vm.state.value.rows.single().spark)
        advanceTimeBy(15_001); runCurrent()                    // tick 1: quotes only
        assertEquals(3, historyCalls)
        advanceTimeBy(3 * 15_000 + 1); runCurrent()            // tick 4: sparks again
        assertEquals(6, historyCalls)
    }

    @Test
    fun sparklineFailureIsNotFatal() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.25", 1.0) } }
        repo.historyImpl = { _, _ -> throw QuoteError.Network("spark down") }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        val s = vm.state.value
        assertEquals("100.25", s.rows.single().amountText)     // quotes still rendered
        assertTrue(s.rows.single().spark.isEmpty())
        assertNull(s.error)                                    // sparklines are decoration
    }

    @Test
    fun addAppendsPersistsAndFetchesItsQuote() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        val store = InMemoryStore()
        val vm = vm(repo, store, backgroundScope)
        vm.start(); runCurrent()
        vm.onAdd(WatchlistEntry("MSFT", "Microsoft Corporation", AssetKind.Stock)); runCurrent()
        assertEquals(listOf("AAPL", "MSFT"), vm.state.value.rows.map { it.symbol })
        assertTrue(store.stored.any { it.symbol == "MSFT" })
    }

    @Test
    fun removeDropsRowAndPersists() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        val store = InMemoryStore()
        val vm = vm(repo, store, backgroundScope)
        vm.start(); runCurrent()
        vm.onRemove("AAPL"); runCurrent()
        assertEquals(emptyList(), vm.state.value.rows)
        assertTrue(store.stored.none { it.symbol == "AAPL" })
    }

    @Test
    fun advancersAndDeclinersCountAcrossAllKinds() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols ->
            symbols.map { quote(it, "100.00", if (it == "AAPL") -1.0 else 2.0) }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(2, vm.state.value.advancers)
        assertEquals(1, vm.state.value.decliners)
    }

    @Test
    fun selectionIsTracked() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        vm.onSelect("AAPL")
        assertEquals("AAPL", vm.state.value.selectedSymbol)
    }

    @Test
    fun averageChangeIsMeanAcrossAllKinds() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols ->
            symbols.map { quote(it, "100.25", when (it) { "AAPL" -> 2.0; "SPY" -> -1.0; else -> 5.0 }) }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(2.0, vm.state.value.averageChange)   // (2 - 1 + 5) / 3
    }

    @Test
    fun averageChangeIsNullWithoutQuotes() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { throw QuoteError.Network("down") }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertNull(vm.state.value.averageChange)
    }

    @Test
    fun averageSparkNormalizesEachSeriesToPercentThenAverages() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.25", 1.0) } }
        repo.historyImpl = { symbol, _ ->
            when (symbol) {
                "AAPL" -> listOf(PricePoint(1, Money.usd("100.00")), PricePoint(2, Money.usd("110.00")))  // +10%
                "SPY" -> listOf(PricePoint(1, Money.usd("200.00")), PricePoint(2, Money.usd("210.00")))   // +5%
                else -> emptyList()
            }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        val spark = vm.state.value.averageSpark
        assertEquals(2, spark.size)
        assertEquals(0.0, spark[0], 1e-9)
        assertEquals(7.5, spark[1], 1e-6)   // mean of +10% and +5%
    }

    @Test
    fun averageSparkToleratesLengthMismatch() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.25", 1.0) } }
        repo.historyImpl = { symbol, _ ->
            when (symbol) {
                "AAPL" -> listOf(PricePoint(1, Money.usd("100.00")), PricePoint(2, Money.usd("110.00")),
                                 PricePoint(3, Money.usd("121.00")))                                       // 0, +10, +21
                "SPY" -> listOf(PricePoint(1, Money.usd("200.00")), PricePoint(2, Money.usd("220.00")))   // 0, +10
                else -> emptyList()
            }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertDoubleListEquals(listOf(0.0, 10.0, 21.0), vm.state.value.averageSpark, 1e-9)  // idx2 has only AAPL
    }

    @Test
    fun averageSparkEmptyWhenFewerThanTwoPoints() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.25", 1.0) } }
        repo.historyImpl = { _, _ -> listOf(PricePoint(1, Money.usd("100.25"))) }  // single point each
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(emptyList(), vm.state.value.averageSpark)
    }
}
