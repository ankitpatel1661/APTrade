package com.aptrade.desktop.portfolio

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPerformanceReport
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.FetchPortfolioPerformance
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.Transaction
import com.aptrade.shared.domain.TradeSide
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class InMemoryPortfolioStore(initial: Portfolio? = null) : PortfolioStore {
    var stored: Portfolio? = initial
    override suspend fun load(): Portfolio? = stored
    override suspend fun save(portfolio: Portfolio) { stored = portfolio }
}

private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)

private fun quote(symbol: String, price: String, change: Double = 1.0) =
    Quote(symbol, Money.usd(price), Money.usd(price), change)

private fun vm(
    repo: FakeMarketDataRepository,
    store: PortfolioStore,
    scope: kotlinx.coroutines.CoroutineScope,
    nowEpochSeconds: () -> Long = { 0L },
) = PortfolioViewModel(
    fetchPortfolio = FetchPortfolio(store),
    fetchMarketQuotes = FetchMarketQuotes(repo),
    buyAsset = BuyAsset(repo, store),
    sellAsset = SellAsset(repo, store),
    resetPortfolio = ResetPortfolio(store),
    fetchPortfolioPerformance = FetchPortfolioPerformance(repo, store),
    fetchPerformanceReport = FetchPerformanceReport(repo, FetchPortfolioPerformance(repo, store)),
    scope = scope,
    nowEpochSeconds = nowEpochSeconds,
)

class PortfolioViewModelTest {

    @Test
    fun startLoadsStartingPortfolioState() = runTest {
        val repo = FakeMarketDataRepository()
        val vm = vm(repo, InMemoryPortfolioStore(), backgroundScope)
        vm.start(); runCurrent()

        val s = vm.state.value
        assertFalse(s.isLoading)
        assertTrue(s.holdings.isEmpty())
        assertEquals("$100,000.00", s.cashText)
        assertEquals("100000", s.totalValueText)   // RAW — SuperscriptPrice/splitPrice consumer
    }

    @Test
    fun buyHappyPathUpdatesHoldingsPersistsAndClearsTradeError() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00") } }
        val store = InMemoryPortfolioStore()
        val vm = vm(repo, store, backgroundScope, nowEpochSeconds = { 1_000L })
        vm.start(); runCurrent()

        vm.buy(aapl, "10"); runCurrent()

        val s = vm.state.value
        assertNull(s.tradeError)
        assertEquals(listOf("AAPL"), s.holdings.map { it.symbol })
        assertEquals("10", s.holdings.single().quantityText)
        assertNotNull(store.stored)
        assertEquals(1, store.stored!!.positions.size)
    }

    @Test
    fun buyWithInsufficientFundsSetsTradeErrorAndLeavesStateUnchanged() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "1000000.00") } }
        val store = InMemoryPortfolioStore()
        val vm = vm(repo, store, backgroundScope)
        vm.start(); runCurrent()

        vm.buy(aapl, "10"); runCurrent()

        val s = vm.state.value
        assertEquals("Insufficient funds.", s.tradeError)
        assertTrue(s.holdings.isEmpty())
        assertEquals("$100,000.00", s.cashText)
        assertNull(store.stored)   // never persisted on failure
    }

    @Test
    fun buyWithQuoteErrorSetsTradeError() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { throw QuoteError.NotFound }
        val store = InMemoryPortfolioStore()
        val vm = vm(repo, store, backgroundScope)
        vm.start(); runCurrent()

        vm.buy(aapl, "10"); runCurrent()

        val s = vm.state.value
        assertNotNull(s.tradeError)
        assertTrue(s.holdings.isEmpty())
        assertNull(store.stored)
    }

    @Test
    fun sellHappyPathUpdatesHoldingsAndPersists() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00") } }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), Money.usd("0"))),
            transactions = listOf(
                Transaction("txn-1", "AAPL", TradeSide.Buy, BigDecimal.parseString("10"), Money.usd("100.00"), 500L),
            ),
        )
        val store = InMemoryPortfolioStore(seeded)
        val vm = vm(repo, store, backgroundScope, nowEpochSeconds = { 2_000L })
        vm.start(); runCurrent()

        vm.sell("AAPL", "4"); runCurrent()

        val s = vm.state.value
        assertNull(s.tradeError)
        assertEquals("6", s.holdings.single().quantityText)
        assertEquals("6", store.stored!!.positions.single().quantity.toStringExpanded())
    }

    @Test
    fun sellInsufficientSharesSetsTradeErrorAndLeavesStateUnchanged() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00") } }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), Money.usd("0"))),
            transactions = listOf(
                Transaction("txn-1", "AAPL", TradeSide.Buy, BigDecimal.parseString("10"), Money.usd("100.00"), 500L),
            ),
        )
        val store = InMemoryPortfolioStore(seeded)
        val vm = vm(repo, store, backgroundScope)
        vm.start(); runCurrent()

        vm.sell("AAPL", "20"); runCurrent()

        val s = vm.state.value
        assertEquals("Insufficient shares.", s.tradeError)
        assertEquals("10", s.holdings.single().quantityText)   // unchanged
        assertEquals("10", store.stored!!.positions.single().quantity.toStringExpanded())  // unchanged
    }

    @Test
    fun moneyTextsAreFormattedAndSignedOnlyForPnlAndDayChangeWhileSuperscriptFieldsStayRaw() = runTest {
        val repo = FakeMarketDataRepository()
        // previousClose ("1200.15") < price ("1234.55") so dayChange is strictly positive.
        repo.quotesImpl = { symbols -> symbols.map { Quote(it, Money.usd("1234.55"), Money.usd("1200.15"), 1.0) } }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("3"), Money.usd("1000.00"), Money.usd("0"))),
            transactions = listOf(
                Transaction("txn-1", "AAPL", TradeSide.Buy, BigDecimal.parseString("3"), Money.usd("1000.00"), 500L),
            ),
        )
        val vm = vm(repo, InMemoryPortfolioStore(seeded), backgroundScope)
        vm.start(); runCurrent()

        val s = vm.state.value
        // Pre-formatted plain money texts: grouped, 2dp, no sign forced despite positive values.
        assertEquals("$99,000.00", s.cashText)
        assertEquals("$1,000.00", s.holdings.single().averageCostText)
        // RAW fields — consumed by SuperscriptPrice/splitPrice (and TradeDialog's Money.usd
        // re-parse for priceText); they must NOT carry "$" or grouping.
        assertEquals("102703.65", s.totalValueText)                      // 99000 + 3703.65, raw: no "$", no commas
        assertEquals("3703.65", s.holdings.single().marketValueText)     // 1234.55 × 3, raw
        assertEquals("1234.55", s.holdings.single().priceText)           // raw quote amountText
        // Signed money texts: P&L / day-change carry a leading "+" when strictly positive.
        assertEquals("+$703.65", s.holdings.single().unrealizedText)     // (1234.55 − 1000) × 3
        assertTrue(s.unrealizedText!!.startsWith("+$"))
        assertTrue(s.dayChangeText!!.startsWith("+$"))
    }

    @Test
    fun pollTickRefreshesQuotesForHeldSymbolsOnly() = runTest {
        val repo = FakeMarketDataRepository()
        var price = "100.00"
        var requestedSymbols: List<String> = emptyList()
        repo.quotesImpl = { symbols -> requestedSymbols = symbols; symbols.map { quote(it, price) } }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), Money.usd("0"))),
        )
        val vm = vm(repo, InMemoryPortfolioStore(seeded), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(listOf("AAPL"), requestedSymbols)

        price = "110.25"
        advanceTimeBy(15_001); runCurrent()

        assertEquals(listOf("AAPL"), requestedSymbols)
        assertEquals("110.25", vm.state.value.holdings.single().priceText)   // RAW — TradeDialog/SuperscriptPrice consumer
    }

    @Test
    fun refreshQuotesMergesPerSymbolKeepingLastGoodQuoteForMissingSymbols() = runTest {
        val repo = FakeMarketDataRepository()
        var tick = 0
        repo.quotesImpl = { symbols ->
            tick += 1
            if (tick == 1) symbols.map { quote(it, "100.25") }
            else symbols.filter { it == "AAPL" }.map { quote(it, "150.75") }
        }
        val msft = Asset("MSFT", "Microsoft Corp.", AssetKind.Stock)
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(
                Position(aapl, BigDecimal.parseString("10"), Money.usd("100.25"), Money.usd("0")),
                Position(msft, BigDecimal.parseString("5"), Money.usd("100.25"), Money.usd("0")),
            ),
        )
        val vm = vm(repo, InMemoryPortfolioStore(seeded), backgroundScope)
        vm.start(); runCurrent()
        assertEquals("100.25", vm.state.value.holdings.first { it.symbol == "MSFT" }.priceText)

        // Simulate a subsequent poll that only returns AAPL (MSFT's quote drops out upstream).
        vm.refresh(); runCurrent()

        val s = vm.state.value
        assertEquals("150.75", s.holdings.first { it.symbol == "AAPL" }.priceText)
        // MSFT keeps its last-good quote (tick 1's price), NOT falling back to averageCost-implied text.
        assertEquals("100.25", s.holdings.first { it.symbol == "MSFT" }.priceText)
    }

    @Test
    fun setSpanMaxFetchesPerformanceWithSinceInceptionTrue() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00") } }
        var requestedTimeframe: Timeframe? = null
        repo.historyImpl = { _, timeframe ->
            requestedTimeframe = timeframe
            listOf(PricePoint(100L, Money.usd("100.00")), PricePoint(200_000L, Money.usd("110.00")))
        }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), Money.usd("0"))),
            transactions = listOf(
                Transaction("txn-1", "AAPL", TradeSide.Buy, BigDecimal.parseString("10"), Money.usd("100.00"), 200_000L),
            ),
        )
        val vm = vm(repo, InMemoryPortfolioStore(seeded), backgroundScope)
        vm.start(); runCurrent()

        vm.setSpan(PortfolioSpan.Max); runCurrent()

        assertEquals(Timeframe.OneYear, requestedTimeframe)
        assertEquals(PortfolioSpan.Max, vm.state.value.span)
        // trimmed to the transaction's inception day (200_000) — the point at epoch 100 is dropped
        assertEquals(1, vm.state.value.performance.size)
    }

    @Test
    fun performanceReportPopulatesRebasedSeriesAndMetricsOnStart() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00") } }
        repo.historyImpl = { symbol, _ ->
            if (symbol == "AAPL") {
                listOf(PricePoint(86_400L * 1, Money.usd("100.00")), PricePoint(86_400L * 2, Money.usd("110.00")))
            } else {
                listOf(PricePoint(86_400L * 1, Money.usd("400.00")), PricePoint(86_400L * 2, Money.usd("420.00")))
            }
        }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), Money.usd("0"))),
            transactions = listOf(
                Transaction("txn-1", "AAPL", TradeSide.Buy, BigDecimal.parseString("10"), Money.usd("100.00"), 0L),
            ),
        )
        val vm = vm(repo, InMemoryPortfolioStore(seeded), backgroundScope)
        vm.start(); runCurrent()

        val s = vm.state.value
        assertEquals("SPY", s.benchmark)
        assertEquals(listOf("SPY", "QQQ", "VTI"), s.benchmarks)
        assertTrue(s.performanceRebased.isNotEmpty())
        assertNotNull(s.benchmarkRebased)
        assertNotNull(s.metrics)
        // Only 2 points -> 1 daily return -> volatility needs >=2 returns -> sharpe is null -> "—"
        assertEquals("—", s.metrics!!.sharpe)
        assertNotNull(s.metrics!!.totalReturn)
        assertTrue(s.metrics!!.totalReturn.endsWith("%"))
    }

    @Test
    fun performanceReportBenchmarkOnlyFailureYieldsNullBenchmarkAndDashMetricsButKeepsPortfolioSeries() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00") } }
        repo.historyImpl = { symbol, _ ->
            if (symbol == "AAPL") {
                listOf(PricePoint(86_400L * 1, Money.usd("100.00")), PricePoint(86_400L * 2, Money.usd("110.00")))
            } else {
                throw QuoteError.RateLimited
            }
        }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), Money.usd("0"))),
            transactions = listOf(
                Transaction("txn-1", "AAPL", TradeSide.Buy, BigDecimal.parseString("10"), Money.usd("100.00"), 0L),
            ),
        )
        val vm = vm(repo, InMemoryPortfolioStore(seeded), backgroundScope)
        vm.start(); runCurrent()

        val s = vm.state.value
        assertTrue(s.performanceRebased.isNotEmpty())
        assertNull(s.benchmarkRebased)
        assertNotNull(s.metrics)
        assertEquals("—", s.metrics!!.beta)
        assertEquals("—", s.metrics!!.alpha)
    }

    @Test
    fun setBenchmarkTriggersARefetchOfThePerformanceReport() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00") } }
        var callCount = 0
        val requestedBenchmarks = mutableListOf<String>()
        repo.historyImpl = { symbol, _ ->
            if (symbol == "AAPL") {
                listOf(PricePoint(86_400L * 1, Money.usd("100.00")), PricePoint(86_400L * 2, Money.usd("110.00")))
            } else {
                callCount += 1
                requestedBenchmarks.add(symbol)
                listOf(PricePoint(86_400L * 1, Money.usd("400.00")), PricePoint(86_400L * 2, Money.usd("420.00")))
            }
        }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), Money.usd("0"))),
            transactions = listOf(
                Transaction("txn-1", "AAPL", TradeSide.Buy, BigDecimal.parseString("10"), Money.usd("100.00"), 0L),
            ),
        )
        val vm = vm(repo, InMemoryPortfolioStore(seeded), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(1, callCount)

        vm.setBenchmark("QQQ"); runCurrent()

        assertEquals(2, callCount)
        assertEquals("QQQ", vm.state.value.benchmark)
        assertEquals(listOf("SPY", "QQQ"), requestedBenchmarks)

        // A poll tick refreshes quotes only — it must NOT refetch the performance report.
        advanceTimeBy(15_001); runCurrent()
        assertEquals(2, callCount)
    }

    @Test
    fun resetReturnsToStartingAndClearsPerformance() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00") } }
        repo.historyImpl = { _, _ -> listOf(PricePoint(100L, Money.usd("100.00")), PricePoint(200L, Money.usd("110.00"))) }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), Money.usd("0"))),
        )
        val store = InMemoryPortfolioStore(seeded)
        val vm = vm(repo, store, backgroundScope)
        vm.start(); runCurrent()
        assertTrue(vm.state.value.performance.isNotEmpty())

        vm.reset(); runCurrent()

        val s = vm.state.value
        assertTrue(s.holdings.isEmpty())
        assertEquals("$100,000.00", s.cashText)
        assertTrue(s.performance.isEmpty())
        assertEquals(Portfolio.starting(), store.stored)
    }

    @Test
    fun exportCsvContainsHeldSymbolLine() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00") } }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(Position(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), Money.usd("0"))),
        )
        val vm = vm(repo, InMemoryPortfolioStore(seeded), backgroundScope)
        vm.start(); runCurrent()

        val csv = vm.exportCsv()

        assertTrue(csv.lines().any { it.startsWith("AAPL,") })
    }
}
