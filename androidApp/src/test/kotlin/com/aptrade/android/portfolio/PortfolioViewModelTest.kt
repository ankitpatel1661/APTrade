package com.aptrade.android.portfolio

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.android.FakePortfolioStore
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPerformanceReport
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.FetchPortfolioPerformance
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NOTE ON SCHEDULER DISCIPLINE: the VM's poll loop is an infinite `while(isActive)` with a
 *  15s delay, so `advanceUntilIdle()` would advance virtual time forever (the scheduler never
 *  goes idle). Tests therefore use `runCurrent()` for zero-time work and `advanceTimeBy` for
 *  ticks, and call `vm.stop()` before returning so no poll coroutine outlives the test. */
class PortfolioViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val utc = ZoneId.of("UTC")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun quote(symbol: String, price: String, change: Double = 0.0) =
        Quote(symbol, Money.usd(price), Money.usd(price), change)

    private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)

    /** A portfolio holding 100 AAPL @ $300 avg, $70,000 cash. */
    private fun heldPortfolio(): Portfolio = Portfolio(
        cash = Money.usd("70000"),
        positions = listOf(Position(aapl, BigDecimal.parseString("100"), Money.usd("300"), Money.usd("0"))),
    )

    private fun vm(
        store: FakePortfolioStore,
        repo: FakeMarketDataRepository,
        now: () -> Long = { 1_700_000_000L },
    ): PortfolioViewModel {
        val perf = FetchPortfolioPerformance(repo, store)
        return PortfolioViewModel(
            fetchPortfolio = FetchPortfolio(store),
            fetchMarketQuotes = FetchMarketQuotes(repo),
            buyAsset = BuyAsset(repo, store),
            sellAsset = SellAsset(repo, store),
            resetPortfolio = ResetPortfolio(store),
            fetchPerformanceReport = FetchPerformanceReport(repo, perf),
            nowEpochSeconds = now,
            tickMillis = 15_000,
            zoneId = utc,
        )
    }

    @Test
    fun summaryMathFromStoreAndQuotes() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore().apply { saveImpl = {} ; loadImpl = { heldPortfolio() } }
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "350.4046")) }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()

        val s = vm.state.value
        assertEquals(false, s.isLoading)
        // 100 shares * $350.4046 = $35,040.46 holdings; + $70,000 cash = $105,040.46
        assertEquals("$105,040.46", s.totalValueText)
        assertEquals("$35,040.46", s.holdingsValueText)
        assertEquals("$70,000.00", s.cashText)
        // unrealized = (350.4046 - 300) * 100 = +$5,040.46
        assertEquals("+$5,040.46", s.unrealizedText)
        assertEquals(true, s.unrealizedPositive)
        assertEquals(1, s.holdings.size)
        assertEquals("$350.40", s.holdings[0].priceText)
        assertEquals("$35,040.46", s.holdings[0].marketValueText)
        vm.stop()
    }

    @Test
    fun spanChangeRefetchesReportOnce() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore().apply { saveImpl = {} ; loadImpl = { heldPortfolio() } }
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "350")) }
        var historyCalls = 0
        repo.historyImpl = { _, _ ->
            historyCalls++
            listOf(PricePoint(1_699_000_000L, Money.usd("300")), PricePoint(1_699_100_000L, Money.usd("350")))
        }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()
        val callsAfterFirstReport = historyCalls
        assertTrue(callsAfterFirstReport > 0) // one report on tick 0 (AAPL + benchmark history)

        vm.setSpan(PortfolioSpan.Year)
        runCurrent()
        // exactly one more report: the same number of history fetches again, no more
        assertEquals(callsAfterFirstReport * 2, historyCalls)

        assertEquals(PortfolioSpan.Year, vm.state.value.span)
        vm.stop()
    }

    @Test
    fun benchmarkChangeRefetchesReport() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore().apply { saveImpl = {} ; loadImpl = { heldPortfolio() } }
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "350")) }
        var historyCalls = 0
        repo.historyImpl = { _, _ ->
            historyCalls++
            listOf(PricePoint(1_699_000_000L, Money.usd("300")), PricePoint(1_699_100_000L, Money.usd("350")))
        }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()
        val callsAfterFirstReport = historyCalls

        vm.setBenchmark("QQQ")
        runCurrent()
        assertEquals(callsAfterFirstReport * 2, historyCalls)
        assertEquals("QQQ", vm.state.value.benchmark)
        vm.stop()
    }

    @Test
    fun pollTickDoesNotRefetchReport() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore().apply { saveImpl = {} ; loadImpl = { heldPortfolio() } }
        val repo = FakeMarketDataRepository()
        var quoteCalls = 0
        repo.quotesImpl = { quoteCalls++; listOf(quote("AAPL", "350")) }
        var historyCalls = 0
        repo.historyImpl = { _, _ ->
            historyCalls++
            listOf(PricePoint(1_699_000_000L, Money.usd("300")), PricePoint(1_699_100_000L, Money.usd("350")))
        }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()
        val historyAfterFirstReport = historyCalls
        val quotesAfterFirst = quoteCalls

        advanceTimeBy(15_001)
        runCurrent()

        assertTrue(quoteCalls > quotesAfterFirst)          // poll merged fresh quotes
        assertEquals(historyAfterFirstReport, historyCalls) // report NOT refetched on tick
        vm.stop()
    }

    @Test
    fun pollTickMergesQuotesRightBiased() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore().apply { saveImpl = {} ; loadImpl = { heldPortfolio() } }
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "300")) }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()
        assertEquals("$300.00", vm.state.value.holdings[0].priceText)

        repo.quotesImpl = { listOf(quote("AAPL", "355")) }
        advanceTimeBy(15_001)
        runCurrent()

        assertEquals("$355.00", vm.state.value.holdings[0].priceText)
        vm.stop()
    }

    @Test
    fun benchmarkTwinNullPath() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore().apply { saveImpl = {} ; loadImpl = { heldPortfolio() } }
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "350")) }
        // Portfolio has history (so points/metrics compute) but the benchmark symbol has none:
        // FetchPerformanceReport swallows the empty benchmark and yields a null twin.
        repo.historyImpl = { symbol, _ ->
            if (symbol == "AAPL") {
                listOf(PricePoint(1_699_000_000L, Money.usd("300")), PricePoint(1_699_100_000L, Money.usd("350")))
            } else {
                emptyList()
            }
        }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()

        val s = vm.state.value
        assertTrue(s.performanceValues.isNotEmpty())
        assertNull(s.benchmarkTwinValues)
        assertNotNull(s.metrics)
        vm.stop()
    }

    @Test
    fun buySuccessUpdatesPortfolioAndClearsTradeError() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore() // starts empty -> Portfolio.starting() ($100k cash)
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "300")) }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()

        vm.buy(aapl, "10")
        runCurrent()

        val s = vm.state.value
        assertNull(s.tradeError)
        assertEquals(1, s.holdings.size)
        assertEquals("AAPL", s.holdings[0].symbol)
        assertEquals(1, s.transactions.size)
        assertTrue(store.saveCallCount >= 1)
        vm.stop()
    }

    @Test
    fun buyInsufficientFundsSetsTradeError() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore() // $100k cash
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "300")) }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()

        vm.buy(aapl, "1000") // 1000 * 300 = $300k > $100k
        runCurrent()

        assertEquals("Insufficient funds.", vm.state.value.tradeError)
        assertEquals(0, vm.state.value.holdings.size)
        vm.stop()
    }

    @Test
    fun buyInvalidQuantitySetsTradeErrorWithoutHittingUseCase() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore()
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "300")) }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()
        val savesBefore = store.saveCallCount

        vm.buy(aapl, "-5")
        runCurrent()

        assertEquals("Enter a valid quantity.", vm.state.value.tradeError)
        assertEquals(savesBefore, store.saveCallCount) // never reached the use case
        vm.stop()
    }

    @Test
    fun resetClearsHoldingsAndReport() = runTest(dispatcher.scheduler) {
        val store = FakePortfolioStore().apply { loadImpl = { heldPortfolio() } }
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "350")) }
        repo.historyImpl = { _, _ ->
            listOf(PricePoint(1_699_000_000L, Money.usd("300")), PricePoint(1_699_100_000L, Money.usd("350")))
        }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()
        assertTrue(vm.state.value.holdings.isNotEmpty())

        // Reset persists the fresh portfolio; make load() return what was last saved so the
        // store and the VM stay coherent from here on.
        store.loadImpl = { store.saved }
        vm.reset()
        runCurrent()

        val s = vm.state.value
        assertEquals(0, s.holdings.size)
        assertTrue(s.performanceValues.isEmpty())
        assertNull(s.benchmarkTwinValues)
        assertNull(s.metrics)
        assertEquals("$100,000.00", s.cashText) // Portfolio.starting()
        vm.stop()
    }

    @Test
    fun restartReloadsPortfolioSoTradeFromElsewhereAppears() = runTest(dispatcher.scheduler) {
        // A trade made from the DetailScreen persists to the shared store while the Portfolio
        // screen is STOPped (nav-to-detail). start() must RELOAD from the store when re-armed
        // (return to Portfolio) so that position appears — this is the first-buy coherence pin.
        val store = FakePortfolioStore().apply { loadImpl = { saved } } // empty -> starting()
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "300") } }
        val vm = vm(store, repo)

        vm.start()
        runCurrent()
        assertEquals(0, vm.state.value.holdings.size) // fresh portfolio, nothing held

        vm.stop() // nav to detail

        // Simulate the detail-made buy: 5 AAPL @ $300, cash reduced accordingly.
        store.saveImpl(
            Portfolio(
                cash = Money.usd("98500"),
                positions = listOf(
                    Position(aapl, BigDecimal.parseString("5"), Money.usd("300"), Money.usd("0")),
                ),
            ),
        )

        vm.start() // return to Portfolio: re-arm must reload from disk
        runCurrent()

        assertEquals(1, vm.state.value.holdings.size)
        assertEquals("AAPL", vm.state.value.holdings[0].symbol)
        vm.stop()
    }

    @Test
    fun transactionDateTextExactEnUsAbsolute() = runTest(dispatcher.scheduler) {
        // A buy at a known epoch; dateText must be "MMM d, uuuu, h:mm a" in en_US at UTC.
        val store = FakePortfolioStore()
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "300")) }
        // 2023-11-14T22:13:20Z
        val vm = vm(store, repo, now = { 1_700_000_000L })

        vm.start()
        runCurrent()
        vm.buy(aapl, "1")
        runCurrent()

        val txn = vm.state.value.transactions.first()
        assertEquals("Nov 14, 2023, 10:13 PM", txn.dateText)
        vm.stop()
    }
}
