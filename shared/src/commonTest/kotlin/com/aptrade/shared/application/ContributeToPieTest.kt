package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieLedgerEntry
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Transcribed from `Tests/APTradeApplicationTests/ContributeToPieTests.swift`. */
class ContributeToPieTest {

    private val sliceA = PieSlice(symbol = "A", assetKind = AssetKind.Stock, targetWeightPP = BigDecimal.parseString("50"))
    private val sliceB = PieSlice(symbol = "B", assetKind = AssetKind.Stock, targetWeightPP = BigDecimal.parseString("50"))

    private fun usd(s: String): Money = Money(BigDecimal.parseString(s), "USD")

    private fun makeRepo(priceA: String, priceB: String): ContribFakeRepo = ContribFakeRepo(
        mapOf(
            "A" to Quote("A", usd(priceA), usd(priceA), 0.0),
            "B" to Quote("B", usd(priceB), usd(priceB), 0.0),
        ),
    )

    // MARK: (a) Fresh 50/50 pie, $100 contribution at A=$10, B=$25

    @Test
    fun contributeFreshPieBuysProportionalSharesAndTagsPieId() = runTest {
        val pie = Pie.create(id = "pie-1", name = "Test Pie", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = ContribFakePieStore(listOf(pie))
        val portfolioStore = ContribFakePortfolioStore(Portfolio.starting())
        val repo = makeRepo("10", "25")

        val sut = ContributeToPie(pieStore, portfolioStore, repo, Mutex())
        val outcome = sut.execute("pie-1", usd("100"), "2025-06-01", 0L)

        val executed = outcome as? ContributionOutcome.Executed ?: fail("expected Executed, got $outcome")

        assertEquals(BigDecimal.parseString("5"), executed.portfolio.positionFor("A")?.quantity)
        assertEquals(BigDecimal.parseString("2"), executed.portfolio.positionFor("B")?.quantity)
        assertEquals(usd("99900"), executed.portfolio.cash)

        val pieTransactions = executed.portfolio.transactions.filter { it.pieId == "pie-1" }
        assertEquals(2, pieTransactions.size)
        assertTrue(pieTransactions.all { it.pieId == "pie-1" })

        assertEquals(BigDecimal.parseString("5"), executed.pie.quantityOf("A"))
        assertEquals(BigDecimal.parseString("2"), executed.pie.quantityOf("B"))

        assertEquals(1, executed.pie.activity.size)
        assertEquals(PieActivityKind.Contribution, executed.pie.activity.first().kind)
        assertEquals("2025-06-01", executed.pie.activity.first().day)
        assertEquals(usd("100"), executed.pie.activity.first().amount)

        // Both stores persisted -- exactly one save each (pins single-save semantics, not
        // one save per buy).
        assertEquals(usd("99900"), portfolioStore.portfolio.cash)
        assertEquals(1, portfolioStore.saveCallCount)
        assertEquals("pie-1", pieStore.pies.first().id)
        assertEquals(BigDecimal.parseString("5"), pieStore.pies.first().quantityOf("A"))
    }

    // MARK: (b) Insufficient cash -- whole contribution skipped, portfolio untouched

    @Test
    fun contributeInsufficientCashSkipsWholeContribution() = runTest {
        val pie = Pie.create(id = "pie-2", name = "Test Pie", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = ContribFakePieStore(listOf(pie))
        val startingPortfolio = Portfolio(usd("50"))
        val portfolioStore = ContribFakePortfolioStore(startingPortfolio)
        val repo = makeRepo("10", "25")

        val sut = ContributeToPie(pieStore, portfolioStore, repo, Mutex())
        val outcome = sut.execute("pie-2", usd("100"), "2025-06-01", 0L)

        val skipped = outcome as? ContributionOutcome.SkippedInsufficientCash ?: fail("expected SkippedInsufficientCash, got $outcome")

        // Portfolio completely untouched -- and never even saved (whole-skip, not
        // save-with-no-changes).
        assertEquals(startingPortfolio, portfolioStore.portfolio)
        assertEquals(0, portfolioStore.portfolio.transactions.size)
        assertEquals(0, portfolioStore.saveCallCount)

        // Pie gains a missedInsufficientCash entry, ledger unchanged.
        assertEquals(1, skipped.pie.activity.size)
        assertEquals(PieActivityKind.MissedInsufficientCash, skipped.pie.activity.first().kind)
        assertEquals("2025-06-01", skipped.pie.activity.first().day)
        assertEquals(usd("100"), skipped.pie.activity.first().amount)
        assertEquals(BigDecimal.ZERO, skipped.pie.quantityOf("A"))
        assertEquals(BigDecimal.ZERO, skipped.pie.quantityOf("B"))

        // Pie saved with the missed entry.
        assertEquals(1, pieStore.pies.first().activity.size)
    }

    // MARK: (c) Drifted ledger routes entire contribution to the underweight slice
    // (A=$70, B=$30 at 50/50 targets, $20 contribution -> all to B)

    @Test
    fun contributeDriftedLedgerRoutesToUnderweightSlice() = runTest {
        // A: 7 shares @ $10 = $70. B: 3 shares @ $10 = $30.
        val ledger = listOf(
            PieLedgerEntry(symbol = "A", quantity = BigDecimal.parseString("7")),
            PieLedgerEntry(symbol = "B", quantity = BigDecimal.parseString("3")),
        )
        val pie = Pie.create(
            id = "pie-3", name = "Drifted Pie", slices = listOf(sliceA, sliceB), schedule = null,
            createdDay = "2025-01-01", ledger = ledger,
        )
        val pieStore = ContribFakePieStore(listOf(pie))
        val portfolioStore = ContribFakePortfolioStore(Portfolio.starting())
        val repo = makeRepo("10", "10")

        val sut = ContributeToPie(pieStore, portfolioStore, repo, Mutex())
        val outcome = sut.execute("pie-3", usd("20"), "2025-06-01", 0L)

        val executed = outcome as? ContributionOutcome.Executed ?: fail("expected Executed, got $outcome")

        // All $20 routed to underweight B (2 shares @ $10); nothing bought for A.
        assertNull(executed.portfolio.positionFor("A"))
        assertEquals(BigDecimal.parseString("2"), executed.portfolio.positionFor("B")?.quantity)
        assertEquals(usd("99980"), executed.portfolio.cash)

        assertEquals(BigDecimal.parseString("7"), executed.pie.quantityOf("A"))
        assertEquals(BigDecimal.parseString("5"), executed.pie.quantityOf("B"))
    }

    // MARK: Missing quote propagates as a thrown error

    @Test
    fun contributeMissingQuoteForSliceSymbolThrows() = runTest {
        val pie = Pie.create(id = "pie-4", name = "Test Pie", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = ContribFakePieStore(listOf(pie))
        val portfolioStore = ContribFakePortfolioStore(Portfolio.starting())
        // "B" quote deliberately missing.
        val repo = ContribFakeRepo(mapOf("A" to Quote("A", usd("10"), usd("10"), 0.0)))

        val sut = ContributeToPie(pieStore, portfolioStore, repo, Mutex())

        assertFailsWith<QuoteError.NotFound> {
            sut.execute("pie-4", usd("100"), "2025-06-01", 0L)
        }

        // Neither store mutated on failure.
        assertEquals(Portfolio.starting(), portfolioStore.portfolio)
        assertEquals(0, pieStore.pies.first().activity.size)
    }

    // MARK: (F1 regression, Kotlin variant) A contribution racing a MANUAL BuyAsset through
    // the SAME shared portfolioMutex must never lose either mutation -- proves the mutex
    // BuyAsset.kt documents is genuinely shared with ContributeToPie, mirroring
    // PortfolioUseCasesTest's `racingBuyAndSellSharingOneMutexBothLandNoLostUpdate` in the
    // house controllable-suspension style (a store whose FIRST save() call parks on a gate).

    @Test
    fun contributionRacingManualBuyThroughSharedMutexBothLandNoLostUpdate() = runTest {
        val pie = Pie.create(id = "pie-race", name = "Race Pie", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = ContribFakePieStore(listOf(pie))
        val store = ContribGatedPortfolioStore()
        store.stored = Portfolio.starting()

        val contributeRepo = makeRepo("10", "10")
        val cAsset = Asset("C", "C", AssetKind.Stock)
        val buyRepo = ContribFakeRepo(mapOf("C" to Quote("C", usd("50"), usd("50"), 0.0)))

        val sharedMutex = Mutex()
        val contributeToPie = ContributeToPie(pieStore, store, contributeRepo, sharedMutex)
        val buyAsset = BuyAsset(buyRepo, store, sharedMutex)

        // The contribution loads the pie/portfolio, computes both buys (A and B), then hangs
        // inside the shared PortfolioStore's save() on the gate -- still holding
        // sharedMutex the whole time (save() is called from inside withLock).
        val contributeJob = launch { contributeToPie.execute("pie-race", usd("100"), "2025-06-01", 1000L) }
        runCurrent() // contribution is now blocked inside store.save(), mutex held

        // The manual buy attempts to enter the SAME mutex the contribution still holds. If
        // the mutex were not genuinely shared, this would race straight through and clobber
        // the contribution's write (or vice versa) once both saves land.
        val buyJob = launch { buyAsset.execute(cAsset, BigDecimal.parseString("2"), 2000L) }
        runCurrent() // buy is parked waiting on the mutex; it cannot have loaded yet

        assertEquals(0, store.saveCount) // neither save has completed yet

        store.firstSaveGate.complete(Unit) // release the contribution's save
        runCurrent()

        contributeJob.join()
        buyJob.join()

        assertEquals(2, store.saveCount)
        val finalPortfolio = store.stored ?: fail("expected a stored portfolio")

        // Both the contribution (5 A + 5 B @ $10) and the manual buy (2 C @ $50) landed:
        // cash = 100000 - 100 (contribution) - 100 (2 * $50) = 99800, not a lost update.
        assertEquals(BigDecimal.parseString("5"), finalPortfolio.positionFor("A")?.quantity)
        assertEquals(BigDecimal.parseString("5"), finalPortfolio.positionFor("B")?.quantity)
        assertEquals(BigDecimal.parseString("2"), finalPortfolio.positionFor("C")?.quantity)
        assertEquals(usd("99800"), finalPortfolio.cash)
        assertEquals(3, finalPortfolio.transactions.size)
    }
}

// -- Shared fakes for this file --

private class ContribFakePieStore(initial: List<Pie> = emptyList()) : PieStore {
    var pies: List<Pie> = initial
    override suspend fun load(): List<Pie> = pies
    override suspend fun save(pies: List<Pie>) {
        this.pies = pies
    }
}

private class ContribFakePortfolioStore(initial: Portfolio) : PortfolioStore {
    var portfolio: Portfolio = initial
    var saveCallCount = 0
        private set

    override suspend fun load(): Portfolio = portfolio
    override suspend fun save(portfolio: Portfolio) {
        this.portfolio = portfolio
        saveCallCount += 1
    }
}

private class ContribFakeRepo(private val quotesBySymbol: Map<String, Quote> = emptyMap()) : MarketDataRepository {
    override suspend fun quotes(symbols: List<String>): List<Quote> = symbols.mapNotNull { quotesBySymbol[it] }
    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
    override suspend fun search(query: String): List<Asset> = emptyList()
}

/** A [PortfolioStore] whose FIRST [save] call suspends on [firstSaveGate] until the test
 *  releases it -- mirrors `PortfolioUseCasesTest`'s identical helper (see its doc comment for
 *  the full rationale). Gating is keyed by CALL ORDER, not "count of saves completed". */
private class ContribGatedPortfolioStore : PortfolioStore {
    var stored: Portfolio? = null
    var saveCount = 0
        private set
    val firstSaveGate = CompletableDeferred<Unit>()
    private var callIndex = 0

    override suspend fun load(): Portfolio? = stored
    override suspend fun save(portfolio: Portfolio) {
        val myIndex = callIndex++
        if (myIndex == 0) firstSaveGate.await()
        stored = portfolio
        saveCount++
    }
}
