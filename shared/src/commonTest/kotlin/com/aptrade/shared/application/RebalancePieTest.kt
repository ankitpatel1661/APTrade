package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieError
import com.aptrade.shared.domain.PieLedgerEntry
import com.aptrade.shared.domain.PieMath
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.RebalanceOrder
import com.aptrade.shared.domain.RebalanceSide
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeSide
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Transcribed from `Tests/APTradeApplicationTests/RebalancePieTests.swift`. */
class RebalancePieTest {

    private val sliceA = PieSlice(symbol = "A", assetKind = AssetKind.Stock, targetWeightPP = BigDecimal.parseString("50"))
    private val sliceB = PieSlice(symbol = "B", assetKind = AssetKind.Stock, targetWeightPP = BigDecimal.parseString("50"))

    private fun usd(s: String): Money = Money(BigDecimal.parseString(s), "USD")

    private fun makeRepo(priceA: String, priceB: String): RebalFakeRepo = RebalFakeRepo(
        mapOf(
            "A" to Quote("A", usd(priceA), usd(priceA), 0.0),
            "B" to Quote("B", usd(priceB), usd(priceB), 0.0),
        ),
    )

    // MARK: (a) Preview matches PieMath.rebalancePlan and leaves stores untouched.

    @Test
    fun previewDriftedPieMatchesRebalancePlanAndTouchesNothing() = runTest {
        // A: 7 @ $10 = $70. B: 3 @ $10 = $30. 50/50 targets -> sell A $20 / buy B $20.
        val ledger = listOf(
            PieLedgerEntry(symbol = "A", quantity = BigDecimal.parseString("7")),
            PieLedgerEntry(symbol = "B", quantity = BigDecimal.parseString("3")),
        )
        val pie = Pie.create(
            id = "pie-1", name = "Test Pie", slices = listOf(sliceA, sliceB), schedule = null,
            createdDay = "2025-01-01", ledger = ledger,
        )
        val pieStore = RebalFakePieStore(listOf(pie))
        val portfolio = Portfolio.starting()
            .buying(Asset("A", "A", AssetKind.Stock), BigDecimal.parseString("7"), usd("10"), 0L)
            .buying(Asset("B", "B", AssetKind.Stock), BigDecimal.parseString("3"), usd("10"), 0L)
        val portfolioStore = RebalFakePortfolioStore(portfolio)
        val repo = makeRepo("10", "10")

        val sut = RebalancePie(pieStore, portfolioStore, repo, Mutex())
        val orders = sut.preview("pie-1")

        val expected = PieMath.rebalancePlan(
            currentValues = mapOf("A" to usd("70"), "B" to usd("30")),
            targets = listOf(sliceA, sliceB),
        )
        assertEquals(expected, orders)
        assertEquals(2, orders.size)
        assertTrue(orders.contains(RebalanceOrder("A", RebalanceSide.Sell, usd("20"))))
        assertTrue(orders.contains(RebalanceOrder("B", RebalanceSide.Buy, usd("20"))))

        // Nothing persisted.
        assertEquals(0, portfolioStore.saveCallCount)
        assertEquals(listOf(pie), pieStore.pies)
    }

    // MARK: (b) Execute sells A $20 / buys B $20 -- cash unchanged, ledger updated, transactions tagged.

    @Test
    fun executeDriftedPieSellsAndBuysNetZeroCash() = runTest {
        val ledger = listOf(
            PieLedgerEntry(symbol = "A", quantity = BigDecimal.parseString("7")),
            PieLedgerEntry(symbol = "B", quantity = BigDecimal.parseString("3")),
        )
        val pie = Pie.create(
            id = "pie-2", name = "Test Pie", slices = listOf(sliceA, sliceB), schedule = null,
            createdDay = "2025-01-01", ledger = ledger,
        )
        val pieStore = RebalFakePieStore(listOf(pie))
        val startingPortfolio = Portfolio.starting()
            .buying(Asset("A", "A", AssetKind.Stock), BigDecimal.parseString("7"), usd("10"), 0L)
            .buying(Asset("B", "B", AssetKind.Stock), BigDecimal.parseString("3"), usd("10"), 0L)
        val startingCash = startingPortfolio.cash
        val portfolioStore = RebalFakePortfolioStore(startingPortfolio)
        val repo = makeRepo("10", "10")

        val sut = RebalancePie(pieStore, portfolioStore, repo, Mutex())
        val (resultPortfolio, resultPie) = sut.execute("pie-2", "2025-06-01", 0L)

        // Cash unchanged (sold $20 of A, bought $20 of B -- exact at these round numbers).
        assertEquals(startingCash, resultPortfolio.cash)

        assertEquals(BigDecimal.parseString("5"), resultPortfolio.positionFor("A")?.quantity)
        assertEquals(BigDecimal.parseString("5"), resultPortfolio.positionFor("B")?.quantity)

        assertEquals(BigDecimal.parseString("5"), resultPie.quantityOf("A"))
        assertEquals(BigDecimal.parseString("5"), resultPie.quantityOf("B"))

        val pieTransactions = resultPortfolio.transactions.filter { it.pieId == "pie-2" }
        assertEquals(2, pieTransactions.size)
        assertTrue(pieTransactions.any { it.symbol == "A" && it.side == TradeSide.Sell })
        assertTrue(pieTransactions.any { it.symbol == "B" && it.side == TradeSide.Buy })

        assertTrue(resultPie.activity.any { it.kind == PieActivityKind.Rebalance && it.day == "2025-06-01" })

        // Persisted.
        assertEquals(1, portfolioStore.saveCallCount)
        assertEquals("pie-2", pieStore.pies.first().id)
    }

    // MARK: (c) Sell-before-buy: buys exceed starting cash but fit after sells free proceeds.

    @Test
    fun executeSellsBeforeBuysSoBuyFitsEvenWithoutStartingCash() = runTest {
        val ledger = listOf(
            PieLedgerEntry(symbol = "A", quantity = BigDecimal.parseString("7")),
            PieLedgerEntry(symbol = "B", quantity = BigDecimal.parseString("3")),
        )
        val pie = Pie.create(
            id = "pie-3", name = "Test Pie", slices = listOf(sliceA, sliceB), schedule = null,
            createdDay = "2025-01-01", ledger = ledger,
        )
        val pieStore = RebalFakePieStore(listOf(pie))

        // Zero starting cash -- the $20 buy of B can only succeed because the $20 sell of A
        // is applied first, freeing exactly enough cash.
        val noCashPortfolio = Portfolio(
            cash = usd("0"),
            positions = listOf(
                Position(Asset("A", "A", AssetKind.Stock), BigDecimal.parseString("7"), usd("10"), usd("0")),
                Position(Asset("B", "B", AssetKind.Stock), BigDecimal.parseString("3"), usd("10"), usd("0")),
            ),
            transactions = emptyList(),
        )
        val portfolioStore = RebalFakePortfolioStore(noCashPortfolio)
        val repo = makeRepo("10", "10")

        val sut = RebalancePie(pieStore, portfolioStore, repo, Mutex())
        val (resultPortfolio, _) = sut.execute("pie-3", "2025-06-01", 0L)

        assertEquals(BigDecimal.ZERO, resultPortfolio.cash.amount)
        assertEquals(BigDecimal.parseString("5"), resultPortfolio.positionFor("B")?.quantity)
    }

    // MARK: Missing pie throws.

    @Test
    fun previewUnknownPieThrowsNotFound() = runTest {
        val pieStore = RebalFakePieStore()
        val portfolioStore = RebalFakePortfolioStore(Portfolio.starting())
        val repo = RebalFakeRepo()

        val sut = RebalancePie(pieStore, portfolioStore, repo, Mutex())
        assertFailsWith<PieError.NotFound> {
            sut.preview("missing")
        }
    }

    @Test
    fun executeUnknownPieThrowsNotFound() = runTest {
        val pieStore = RebalFakePieStore()
        val portfolioStore = RebalFakePortfolioStore(Portfolio.starting())
        val repo = RebalFakeRepo()

        val sut = RebalancePie(pieStore, portfolioStore, repo, Mutex())
        assertFailsWith<PieError.NotFound> {
            sut.execute("missing", "2025-06-01", 0L)
        }
    }
}

// MARK: - ReconcilePieLedgers

/** Transcribed from `Tests/APTradeApplicationTests/PieUseCasesTests.swift`'s
 *  `ReconcilePieLedgersTests` section. */
class ReconcilePieLedgersTest {

    private fun usd(s: String): Money = Money(BigDecimal.parseString(s), "USD")
    private val sliceA = PieSlice(symbol = "A", assetKind = AssetKind.Stock, targetWeightPP = BigDecimal.parseString("100"))

    // MARK: (d) portfolio holds 3 A; pie1 ledger 4, pie2 ledger 1 -> pie1 clamps to 2
    // (largest first), pie2 keeps 1; only pie1 gains manualAdjustment.

    @Test
    fun reconcileLargestLedgerClampsFirst() = runTest {
        val pie1 = Pie.create(
            id = "pie1", name = "Pie 1", slices = listOf(sliceA), schedule = null, createdDay = "2025-01-01",
            ledger = listOf(PieLedgerEntry("A", BigDecimal.parseString("4"))),
        )
        val pie2 = Pie.create(
            id = "pie2", name = "Pie 2", slices = listOf(sliceA), schedule = null, createdDay = "2025-01-01",
            ledger = listOf(PieLedgerEntry("A", BigDecimal.parseString("1"))),
        )
        val pieStore = RebalFakePieStore(listOf(pie1, pie2))

        val portfolio = Portfolio(
            cash = usd("100"),
            positions = listOf(Position(Asset("A", "A", AssetKind.Stock), BigDecimal.parseString("3"), usd("10"), usd("0"))),
            transactions = emptyList(),
        )
        val portfolioStore = RebalFakePortfolioStore(portfolio)

        // Fixed clock, injected -- proves the activity day is stamped from `nowEpochSeconds`/
        // `calendar`, not the wall clock, so this assertion is deterministic regardless of
        // when the test suite actually runs.
        val fixedDay = "2025-03-15"
        val fixedNowEpochSeconds = requireNotNull(PieSchedule.parseDay(fixedDay)) * 86_400L + 12L * 3_600L

        val sut = ReconcilePieLedgers(pieStore, portfolioStore, Mutex())
        val result = sut.execute(fixedNowEpochSeconds)

        val resultPie1 = result.first { it.id == "pie1" }
        val resultPie2 = result.first { it.id == "pie2" }

        assertEquals(BigDecimal.parseString("2"), resultPie1.quantityOf("A"))
        assertEquals(BigDecimal.parseString("1"), resultPie2.quantityOf("A"))

        assertTrue(resultPie1.activity.any { it.kind == PieActivityKind.ManualAdjustment })
        assertTrue(resultPie2.activity.none { it.kind == PieActivityKind.ManualAdjustment })
        assertEquals(fixedDay, resultPie1.activity.first { it.kind == PieActivityKind.ManualAdjustment }.day)

        // Persisted.
        assertEquals(BigDecimal.parseString("2"), pieStore.pies.first { it.id == "pie1" }.quantityOf("A"))
    }

    // MARK: (e) No over-claim -> no clamp, no activity, no save at all.

    @Test
    fun reconcileNoOverClaimNoActivity() = runTest {
        val pie1 = Pie.create(
            id = "pie1", name = "Pie 1", slices = listOf(sliceA), schedule = null, createdDay = "2025-01-01",
            ledger = listOf(PieLedgerEntry("A", BigDecimal.parseString("2"))),
        )
        val pieStore = RebalFakePieStore(listOf(pie1))

        val portfolio = Portfolio(
            cash = usd("100"),
            positions = listOf(Position(Asset("A", "A", AssetKind.Stock), BigDecimal.parseString("3"), usd("10"), usd("0"))),
            transactions = emptyList(),
        )
        val portfolioStore = RebalFakePortfolioStore(portfolio)

        val sut = ReconcilePieLedgers(pieStore, portfolioStore, Mutex())
        val result = sut.execute(0L)

        assertEquals(BigDecimal.parseString("2"), result.first().quantityOf("A"))
        assertTrue(result.first().activity.isEmpty())
        assertEquals(0, pieStore.saveCount, "no clamp applied -- the pie list must never be re-saved")
    }

    // MARK: Tie-break: equal ledgers -> lexicographically first pie id clamps first.

    @Test
    fun reconcileTieBreakLexicographicallyFirstClampsFirst() = runTest {
        val pieA = Pie.create(
            id = "pieA", name = "Pie A", slices = listOf(sliceA), schedule = null, createdDay = "2025-01-01",
            ledger = listOf(PieLedgerEntry("A", BigDecimal.parseString("3"))),
        )
        val pieB = Pie.create(
            id = "pieB", name = "Pie B", slices = listOf(sliceA), schedule = null, createdDay = "2025-01-01",
            ledger = listOf(PieLedgerEntry("A", BigDecimal.parseString("3"))),
        )
        val pieStore = RebalFakePieStore(listOf(pieA, pieB))

        val portfolio = Portfolio(
            cash = usd("100"),
            positions = listOf(Position(Asset("A", "A", AssetKind.Stock), BigDecimal.parseString("3"), usd("10"), usd("0"))),
            transactions = emptyList(),
        )
        val portfolioStore = RebalFakePortfolioStore(portfolio)

        val sut = ReconcilePieLedgers(pieStore, portfolioStore, Mutex())
        val result = sut.execute(0L)

        val resultA = result.first { it.id == "pieA" }
        val resultB = result.first { it.id == "pieB" }

        // pieA (lexicographically first) clamps first: reduced to 0. pieB keeps its 3.
        assertEquals(BigDecimal.ZERO, resultA.quantityOf("A"))
        assertEquals(BigDecimal.parseString("3"), resultB.quantityOf("A"))
        assertTrue(resultA.activity.any { it.kind == PieActivityKind.ManualAdjustment })
        assertTrue(resultB.activity.none { it.kind == PieActivityKind.ManualAdjustment })
    }
}

// -- Shared fakes for this file --

private class RebalFakePieStore(initial: List<Pie> = emptyList()) : PieStore {
    var pies: List<Pie> = initial
    var saveCount = 0
        private set

    override suspend fun load(): List<Pie> = pies
    override suspend fun save(pies: List<Pie>) {
        this.pies = pies
        saveCount += 1
    }
}

private class RebalFakePortfolioStore(initial: Portfolio) : PortfolioStore {
    var portfolio: Portfolio = initial
    var saveCallCount = 0
        private set

    override suspend fun load(): Portfolio = portfolio
    override suspend fun save(portfolio: Portfolio) {
        this.portfolio = portfolio
        saveCallCount += 1
    }
}

private class RebalFakeRepo(private val quotesBySymbol: Map<String, Quote> = emptyMap()) : MarketDataRepository {
    override suspend fun quotes(symbols: List<String>): List<Quote> = symbols.mapNotNull { quotesBySymbol[it] }
    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
    override suspend fun search(query: String): List<Asset> = emptyList()
}
