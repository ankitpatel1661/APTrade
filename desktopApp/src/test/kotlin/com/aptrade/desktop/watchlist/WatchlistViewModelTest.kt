package com.aptrade.desktop.watchlist

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.AlertNotifier
import com.aptrade.shared.application.AlertStore
import com.aptrade.shared.application.CreatePriceAlert
import com.aptrade.shared.application.EvaluateAlerts
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.LoadAlerts
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.RemovePriceAlert
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PriceAlert
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

private class FakeAlertStore(initial: List<PriceAlert> = emptyList()) : AlertStore {
    var stored: List<PriceAlert> = initial
    var saveCallCount = 0
        private set
    override suspend fun load(): List<PriceAlert> = stored
    override suspend fun save(alerts: List<PriceAlert>) {
        stored = alerts
        saveCallCount += 1
    }
}

private class FakeAlertNotifier : AlertNotifier {
    val notified = mutableListOf<Pair<PriceAlert, Quote>>()
    override suspend fun notify(alert: PriceAlert, quote: Quote) {
        notified += alert to quote
    }
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
    alertStore: AlertStore = FakeAlertStore(),
    alertNotifier: AlertNotifier = FakeAlertNotifier(),
    isNotifyEnabled: suspend () -> Boolean = { true },
) = WatchlistViewModel(
    fetchMarketQuotes = FetchMarketQuotes(repo),
    fetchHistory = FetchHistory(repo),
    fetchWatchlist = FetchWatchlist(store, defaults),
    addToWatchlist = AddToWatchlist(store),
    removeFromWatchlist = RemoveFromWatchlist(store),
    evaluateAlerts = EvaluateAlerts(alertStore, alertNotifier, isNotifyEnabled),
    loadAlerts = LoadAlerts(alertStore),
    createPriceAlert = CreatePriceAlert(alertStore),
    removePriceAlert = RemovePriceAlert(alertStore),
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

    // --- Alert evaluation (increment 6d.1 Task 4) -----------------------------------

    @Test
    fun pollTickEvaluatesAlertsAndExposesUntriggeredCounts() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "210.00", 1.0) } }
        // Condition NOT met by 210.00 (price above 500) -> stays untriggered, counted.
        val alert = PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceAbove(Money.usd("500.00")), createdAtEpochSeconds = 0L)
        val alertStore = FakeAlertStore(initial = listOf(alert))
        val notifier = FakeAlertNotifier()
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore, alertNotifier = notifier)
        vm.start(); runCurrent()

        // Proves the tick actually ran EvaluateAlerts (not just loaded the raw list):
        // the untriggered count is populated from the evaluated result.
        assertEquals(1, vm.state.value.alertCounts["AAPL"])
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun triggeredAlertNotifiesOnceNotAgainNextTick() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "210.00", 1.0) } }
        // Condition IS met by 210.00 (price below 500) -> triggers and notifies on tick 1.
        val alert = PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceBelow(Money.usd("500.00")), createdAtEpochSeconds = 0L)
        val alertStore = FakeAlertStore(initial = listOf(alert))
        val notifier = FakeAlertNotifier()
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore, alertNotifier = notifier)
        vm.start(); runCurrent()
        assertEquals(1, notifier.notified.size)
        assertEquals(0, vm.state.value.alertCounts["AAPL"] ?: 0)  // now triggered -> not counted

        advanceTimeBy(15_001); runCurrent()
        assertEquals(1, notifier.notified.size)  // not re-notified next tick
    }

    @Test
    fun manualRefreshAlsoEvaluatesAlerts() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "210.00", 1.0) } }
        val alertStore = FakeAlertStore()
        val notifier = FakeAlertNotifier()
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore, alertNotifier = notifier)
        vm.start(); runCurrent()
        assertTrue(notifier.notified.isEmpty())   // no alerts registered yet

        // An alert created after start (e.g. via the sheet) is picked up by the next
        // manual refresh — not just the next poll tick.
        alertStore.stored = listOf(
            PriceAlert(symbol = "SPY", condition = AlertCondition.PriceBelow(Money.usd("500.00")), createdAtEpochSeconds = 0L),
        )
        vm.refresh(); runCurrent()
        assertEquals(1, notifier.notified.size)
    }

    @Test
    fun alertNotificationSuppressedWhenNotifyDisabledButStillTriggers() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "210.00", 1.0) } }
        val alert = PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceBelow(Money.usd("500.00")), createdAtEpochSeconds = 0L)
        val alertStore = FakeAlertStore(initial = listOf(alert))
        val notifier = FakeAlertNotifier()
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore, alertNotifier = notifier, isNotifyEnabled = { false })
        vm.start(); runCurrent()
        assertTrue(notifier.notified.isEmpty())              // no outward push
        assertEquals(0, vm.state.value.alertCounts["AAPL"] ?: 0)  // still marked triggered in-app
        assertTrue(alertStore.stored.single().isTriggered)
    }

    @Test
    fun reloadAlertsRefreshesCountsWithoutRefetchingQuotes() = runTest {
        val repo = FakeMarketDataRepository()
        var quoteCalls = 0
        repo.quotesImpl = { symbols -> quoteCalls++; symbols.map { quote(it, "210.00", 1.0) } }
        val alertStore = FakeAlertStore()
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore)
        vm.start(); runCurrent()
        val callsAfterStart = quoteCalls

        alertStore.stored = listOf(
            // NOT met by 210.00 -> stays untriggered, counted (proves reloadAlerts ran).
            PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceAbove(Money.usd("500.00")), createdAtEpochSeconds = 0L),
        )
        vm.reloadAlerts(); runCurrent()

        assertEquals(callsAfterStart, quoteCalls)   // no additional quote fetch
        assertEquals(1, vm.state.value.alertCounts["AAPL"])
    }

    @Test
    fun alertEvaluationFailureNeverBreaksThePoll() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "210.00", 1.0) } }
        val alertStore = FakeAlertStore()
        val throwingNotifier = object : AlertNotifier {
            override suspend fun notify(alert: PriceAlert, quote: Quote) {
                throw RuntimeException("tray unavailable")
            }
        }
        alertStore.stored = listOf(
            PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceBelow(Money.usd("500.00")), createdAtEpochSeconds = 0L),
        )
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore, alertNotifier = throwingNotifier)
        vm.start(); runCurrent()

        // The poll must still publish fresh quotes/rows despite the notifier throwing.
        // (Money.amountText drops trailing zeros — known shared-core debt, see `quote()` above.)
        assertEquals("210", vm.state.value.rows.single().amountText)
        assertNull(vm.state.value.error)
    }

    // --- Sheet-facing alert CRUD (increment 6d.1 Task 5) ----------------------------

    @Test
    fun alertsForReturnsOnlyThatSymbolMostRecentFirst() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "210.00", 1.0) } }
        val alertStore = FakeAlertStore(
            initial = listOf(
                PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceAbove(Money.usd("100")), createdAtEpochSeconds = 1L),
                PriceAlert(symbol = "SPY", condition = AlertCondition.PriceAbove(Money.usd("400")), createdAtEpochSeconds = 2L),
                PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceBelow(Money.usd("50")), createdAtEpochSeconds = 3L),
            ),
        )
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore)
        vm.start(); runCurrent()

        val aaplAlerts = vm.alertsFor("AAPL")
        assertEquals(2, aaplAlerts.size)
        assertEquals(3L, aaplAlerts[0].createdAtEpochSeconds)   // most recent first
        assertEquals(1L, aaplAlerts[1].createdAtEpochSeconds)
    }

    @Test
    fun alertsForIsEmptyWhenNoQuotesHaveLandedYet() = runTest {
        // Regression guard: refreshAlerts' quotes.isEmpty() branch must still populate
        // `alerts` via loadAlerts so the sheet has data before the first tick's quotes land.
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { throw QuoteError.Network("down") }
        val alertStore = FakeAlertStore(
            initial = listOf(
                PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceAbove(Money.usd("100")), createdAtEpochSeconds = 1L),
            ),
        )
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore)
        vm.start(); runCurrent()

        assertEquals(1, vm.alertsFor("AAPL").size)
    }

    @Test
    fun createAlertPersistsAndRefreshesCounts() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "210.00", 1.0) } }
        val alertStore = FakeAlertStore()
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore)
        vm.start(); runCurrent()

        vm.createAlert("AAPL", AlertCondition.PriceAbove(Money.usd("500.00"))); runCurrent()

        assertEquals(1, alertStore.stored.size)
        assertEquals(1, vm.alertsFor("AAPL").size)
        assertEquals(1, vm.state.value.alertCounts["AAPL"])   // reloadAlerts kept the bell fresh
    }

    @Test
    fun deleteAlertRemovesAndRefreshesCounts() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "210.00", 1.0) } }
        val alert = PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceAbove(Money.usd("500.00")), createdAtEpochSeconds = 0L)
        val alertStore = FakeAlertStore(initial = listOf(alert))
        val vm = vm(repo, InMemoryStore(), backgroundScope, alertStore = alertStore)
        vm.start(); runCurrent()
        assertEquals(1, vm.alertsFor("AAPL").size)

        vm.deleteAlert(alert.id); runCurrent()

        assertTrue(alertStore.stored.isEmpty())
        assertTrue(vm.alertsFor("AAPL").isEmpty())
        assertEquals(0, vm.state.value.alertCounts["AAPL"] ?: 0)
    }
}
