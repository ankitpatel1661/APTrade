package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeError
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class InMemoryPortfolioStore : PortfolioStore {
    var stored: Portfolio? = null
    var saveCount = 0
        private set

    override suspend fun load(): Portfolio? = stored
    override suspend fun save(portfolio: Portfolio) {
        stored = portfolio
        saveCount++
    }
}

/** A [PortfolioStore] whose FIRST [save] call suspends on [firstSaveGate] until the test
 *  releases it, so a test can force two coroutines to interleave: the first caller's [save]
 *  suspends *after* it has already re-loaded the pre-mutation [stored] snapshot inside
 *  BuyAsset/SellAsset's `portfolioMutex.withLock` block, while a second caller attempts to
 *  enter the same lock. Without a lock SHARED by both use cases, the second caller's [load]
 *  would race in and observe the same stale snapshot, producing a lost update once both saves
 *  land. With one shared mutex, the second caller blocks on lock acquisition until the first's
 *  [save] (and its `withLock` block) completes, so it re-loads the FIRST write before applying
 *  its own mutation. Every [save] after the first proceeds immediately (subsequent calls are
 *  what the test wants to observe running unimpeded once the first releases the lock).
 *
 *  Gating is keyed by CALL ORDER (`callIndex`), not "count of saves that have completed" — this
 *  matters for any test where a second `save()` could plausibly be reached without the first
 *  one having completed yet, so the second arrival's gate check must not depend on how many
 *  saves have *finished*. */
private class GatedPortfolioStore : PortfolioStore {
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

/** A trivial in-memory [PieStore] — this file's racing test only needs a single seeded [Pie]
 *  round-tripped, not the persistence edge cases [ContributeToPieTest] already covers. Named
 *  distinctly from other files' `FakePieStore`/`*FakePieStore` fakes — Kotlin private
 *  top-level classes are file-scoped for visibility only, not name-mangled, so two files in
 *  this same package both declaring `FakePieStore` collide at the class-name level. */
private class ResetRaceFakePieStore(initial: List<Pie>) : PieStore {
    var pies: List<Pie> = initial
    override suspend fun load(): List<Pie> = pies
    override suspend fun save(pies: List<Pie>) { this.pies = pies }
}

private class FakeMarketDataRepository(
    private val quotesBySymbol: Map<String, Quote> = emptyMap(),
    private val historiesBySymbol: Map<String, List<PricePoint>> = emptyMap(),
    private val failingSymbols: Set<String> = emptySet(),
    private val quotesError: QuoteError? = null,
) : MarketDataRepository {
    override suspend fun quotes(symbols: List<String>): List<Quote> {
        quotesError?.let { throw it }
        return symbols.mapNotNull { quotesBySymbol[it] }
    }

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
        if (symbol in failingSymbols) throw QuoteError.RateLimited
        return historiesBySymbol[symbol] ?: emptyList()
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
    override suspend fun search(query: String): List<Asset> = emptyList()
}

private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)

class PortfolioUseCasesTest {

    @Test
    fun fetchReturnsStartingPortfolioWithoutPersistingWhenNeverSaved() = runTest {
        val store = InMemoryPortfolioStore()
        val result = FetchPortfolio(store).execute()
        assertEquals(Portfolio.starting(), result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun fetchReturnsTheStoredPortfolioVerbatim() = runTest {
        val saved = Portfolio.starting().buying(aapl, BigDecimal.parseString("2"), Money.usd("100.00"), 1000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = saved }
        val result = FetchPortfolio(store).execute()
        assertEquals(saved, result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun buyExecutesAtLiveQuotePriceAndPersists() = runTest {
        val store = InMemoryPortfolioStore()
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("150.00"), Money.usd("148.00"), 1.5)),
        )
        val result = BuyAsset(repository, store, Mutex()).execute(aapl, BigDecimal.parseString("10"), 5000L)

        assertEquals(Money.usd("100000").amount - BigDecimal.parseString("1500.00"), result.cash.amount)
        val position = result.positionFor("AAPL")
        assertEquals(BigDecimal.parseString("10"), position?.quantity)
        assertEquals(Money.usd("150.00"), position?.averageCost)
        assertEquals(1, result.transactions.size)
        assertEquals(5000L, result.transactions.first().epochSeconds)
        assertEquals(result, store.stored)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun buyPropagatesInsufficientFundsWithoutSaving() = runTest {
        val store = InMemoryPortfolioStore()
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("1000000.00"), Money.usd("999000.00"), 0.1)),
        )
        assertFailsWith<TradeError.InsufficientFunds> {
            BuyAsset(repository, store, Mutex()).execute(aapl, BigDecimal.parseString("10"), 5000L)
        }
        assertEquals(0, store.saveCount)
    }

    @Test
    fun buyPropagatesQuoteErrorWithoutSaving() = runTest {
        val store = InMemoryPortfolioStore()
        val repository = FakeMarketDataRepository(quotesError = QuoteError.RateLimited)
        assertFailsWith<QuoteError.RateLimited> {
            BuyAsset(repository, store, Mutex()).execute(aapl, BigDecimal.parseString("10"), 5000L)
        }
        assertEquals(0, store.saveCount)
    }

    @Test
    fun sellRealizesAndPersistsAndPropagatesInsufficientSharesWithoutSaving() = runTest {
        val bought = Portfolio.starting().buying(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), 1000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = bought }
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("150.00"), Money.usd("148.00"), 1.5)),
        )
        val result = SellAsset(repository, store, Mutex()).execute("AAPL", BigDecimal.parseString("4"), 6000L)

        assertEquals(BigDecimal.parseString("6"), result.positionFor("AAPL")?.quantity)
        assertEquals(2, result.transactions.size)
        assertEquals(6000L, result.transactions.last().epochSeconds)
        assertEquals(result, store.stored)
        assertEquals(1, store.saveCount)

        assertFailsWith<TradeError.InsufficientShares> {
            SellAsset(repository, store, Mutex()).execute("AAPL", BigDecimal.parseString("100"), 7000L)
        }
        assertEquals(1, store.saveCount) // the failed oversell did not persist
    }

    @Test
    fun resetSavesAndReturnsStarting() = runTest {
        val bought = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = bought }
        val result = ResetPortfolio(store, Mutex()).execute()
        assertEquals(Portfolio.starting(), result)
        assertEquals(Portfolio.starting(), store.stored)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun performanceFetchesHistoriesForEachHeldSymbolConcurrentlyAndBuildsSeries() = runTest {
        val msft = Asset("MSFT", "Microsoft Corp.", AssetKind.Stock)
        var portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        portfolio = portfolio.buying(msft, BigDecimal.parseString("1"), Money.usd("200.00"), 1000L, "txn-2")
        val store = InMemoryPortfolioStore().apply { stored = portfolio }
        val repository = FakeMarketDataRepository(
            historiesBySymbol = mapOf(
                "AAPL" to listOf(PricePoint(86400L, Money.usd("110.00"))),
                "MSFT" to listOf(PricePoint(86400L, Money.usd("210.00"))),
            ),
        )

        val result = FetchPortfolioPerformance(repository, store).execute(Timeframe.OneMonth)

        assertEquals(1, result.size)
        assertEquals(86400L, result.first().epochSeconds)
        // cash after buying 1 AAPL@100 + 1 MSFT@200 = 100000 - 100 - 200 = 99700; holdings = 110 + 210 = 320.
        assertEquals(Money.usd("100000").amount - BigDecimal.parseString("300.00") + BigDecimal.parseString("320.00"), result.first().value.amount)
    }

    @Test
    fun performanceTreatsAPerSymbolFailureAsEmptyHistory() = runTest {
        val msft = Asset("MSFT", "Microsoft Corp.", AssetKind.Stock)
        var portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        portfolio = portfolio.buying(msft, BigDecimal.parseString("1"), Money.usd("200.00"), 1000L, "txn-2")
        val store = InMemoryPortfolioStore().apply { stored = portfolio }
        val repository = FakeMarketDataRepository(
            historiesBySymbol = mapOf(
                "MSFT" to listOf(PricePoint(86400L, Money.usd("210.00"))),
            ),
            failingSymbols = setOf("AAPL"),
        )

        val result = FetchPortfolioPerformance(repository, store).execute(Timeframe.OneMonth)

        assertEquals(1, result.size)
        assertEquals(86400L, result.first().epochSeconds)
        // cash after buying 1 AAPL@100 + 1 MSFT@200 = 99700. Only MSFT priced in — AAPL
        // contributed an empty history (its per-symbol failure), so only MSFT's 210 counts.
        assertEquals(Money.usd("100000").amount - BigDecimal.parseString("300.00") + BigDecimal.parseString("210.00"), result.first().value.amount)
    }

    @Test
    fun performanceResamplesToDailyForNonOneDayTimeframes() = runTest {
        // OneWeek timeframe: several intraday points land in the same UTC day (100000 and
        // 105000 both fall in day 100000/86400 == 1) plus one point the next day. Only the
        // day's closing (max-epoch) value should survive per day once resampled.
        var portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = portfolio }
        val repository = FakeMarketDataRepository(
            historiesBySymbol = mapOf(
                "AAPL" to listOf(
                    PricePoint(100_000L, Money.usd("110.00")),
                    PricePoint(105_000L, Money.usd("115.00")),
                    PricePoint(190_000L, Money.usd("120.00")),
                ),
            ),
        )

        val result = FetchPortfolioPerformance(repository, store).execute(Timeframe.OneWeek)

        assertEquals(2, result.size)
        // Day 1 (100000/86400 == 1): last intraday point at 105000 (close 115.00) wins.
        assertEquals(105_000L, result[0].epochSeconds)
        assertEquals(Money.usd("100000").amount - BigDecimal.parseString("100.00") + BigDecimal.parseString("115.00"), result[0].value.amount)
        // Day 2 (190000/86400 == 2): single point at 190000 (close 120.00).
        assertEquals(190_000L, result[1].epochSeconds)
        assertEquals(Money.usd("100000").amount - BigDecimal.parseString("100.00") + BigDecimal.parseString("120.00"), result[1].value.amount)
    }

    @Test
    fun performanceLeavesIntradayGridUntouchedForOneDayTimeframe() = runTest {
        // OneDay timeframe must NOT be resampled — the whole point of 1D is the intraday grid.
        var portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = portfolio }
        val repository = FakeMarketDataRepository(
            historiesBySymbol = mapOf(
                "AAPL" to listOf(
                    PricePoint(100_000L, Money.usd("110.00")),
                    PricePoint(105_000L, Money.usd("115.00")),
                    PricePoint(108_000L, Money.usd("118.00")),
                ),
            ),
        )

        val result = FetchPortfolioPerformance(repository, store).execute(Timeframe.OneDay)

        assertEquals(3, result.size)
        assertEquals(100_000L, result[0].epochSeconds)
        assertEquals(105_000L, result[1].epochSeconds)
        assertEquals(108_000L, result[2].epochSeconds)
    }

    @Test
    fun performanceSinceInceptionTrimsToFirstTransactionDay() = runTest {
        var portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 200_000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = portfolio }
        val repository = FakeMarketDataRepository(
            historiesBySymbol = mapOf(
                "AAPL" to listOf(
                    PricePoint(86_400L * 1, Money.usd("110.00")),
                    PricePoint(86_400L * 3, Money.usd("120.00")),
                ),
            ),
        )

        val result = FetchPortfolioPerformance(repository, store).execute(Timeframe.OneMonth, sinceInception = true)

        // first-txn day = (200000 / 86400) * 86400 = 172800; the 86400 point is dropped.
        assertEquals(1, result.size)
        assertEquals(86_400L * 3, result.first().epochSeconds)
    }

    // --- Concurrency (6d.2 Task 4, hardened by the shared-mutex fix): BuyAsset/SellAsset
    // serialize their store RMW under a Mutex SHARED between both use cases (constructor-
    // injected `portfolioMutex`), so racing trades — buy-vs-buy, sell-vs-sell, and buy-vs-sell —
    // against the same PortfolioStore never lose an update. ---

    @Test
    fun twoRacingBuysThroughOneInstanceBothLandNoLostUpdate() = runTest {
        val store = GatedPortfolioStore()
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.0)),
        )
        val buyAsset = BuyAsset(repository, store, Mutex()) // ONE shared instance, as AppGraph wires it

        // First buy: quote fetch completes synchronously, enters the mutex, loads the (empty)
        // starting portfolio, then hangs inside store.save() on firstSaveGate — still holding
        // the mutex the whole time (save() is called from inside withLock).
        val first = launch { buyAsset.execute(aapl, BigDecimal.parseString("10"), 1000L) }
        runCurrent() // first buy is now blocked inside store.save(), mutex held

        // Second buy attempts to enter the SAME mutex while the first still holds it. If the
        // mutex were missing, this would race straight through: load() would see the same
        // empty portfolio the first buy saw, and whichever save() wins would silently discard
        // the other buy (the pre-6d.2 lost-update bug).
        val second = launch { buyAsset.execute(aapl, BigDecimal.parseString("5"), 2000L) }
        runCurrent() // second buy is parked waiting on the mutex; it cannot have loaded yet

        assertEquals(0, store.saveCount) // neither save has completed yet

        store.firstSaveGate.complete(Unit) // release the first save; mutex releases after withLock returns
        runCurrent()

        first.join()
        second.join()

        // Both buys landed: 10 + 5 = 15 shares, not 10 or 5 (which a lost update would produce).
        assertEquals(2, store.saveCount)
        assertEquals(BigDecimal.parseString("15"), store.stored?.positionFor("AAPL")?.quantity)
        assertEquals(2, store.stored?.transactions?.size)
    }

    @Test
    fun racingSellsThroughOneInstanceBothLandNoLostUpdate() = runTest {
        val store = GatedPortfolioStore()
        store.stored = Portfolio.starting().buying(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), 500L, "txn-seed")
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.0)),
        )
        val sellAsset = SellAsset(repository, store, Mutex()) // ONE shared instance, as AppGraph wires it

        // First sell (3 shares): quote fetch completes, enters the mutex, loads the 10-share
        // snapshot, then hangs inside store.save() on firstSaveGate — still holding the mutex.
        val first = launch { sellAsset.execute("AAPL", BigDecimal.parseString("3"), 1000L) }
        runCurrent() // first sell is now blocked inside store.save(), mutex held

        // Second sell (2 shares) attempts to enter the SAME mutex while the first still holds
        // it. Without the mutex, it would load the same stale 10-share snapshot and validate/
        // apply against it, and whichever save() lands last would discard the other's sell.
        val second = launch { sellAsset.execute("AAPL", BigDecimal.parseString("2"), 2000L) }
        runCurrent() // second sell is parked waiting on the mutex; it cannot have loaded yet

        assertEquals(0, store.saveCount)

        store.firstSaveGate.complete(Unit)
        runCurrent()

        first.join()
        second.join()

        // Both sells landed: 10 - 3 - 2 = 5 shares remaining, not 7 or 8 (a lost update).
        assertEquals(2, store.saveCount)
        assertEquals(BigDecimal.parseString("5"), store.stored?.positionFor("AAPL")?.quantity)
        assertEquals(3, store.stored?.transactions?.size) // seed + two sells
    }

    @Test
    fun racingBuyAndSellSharingOneMutexBothLandNoLostUpdate() = runTest {
        // This test used to PIN a documented residual gap: BuyAsset and SellAsset each held
        // their OWN private Mutex, so a buy and a sell in flight at the same time were not
        // serialized against each other and could clobber one another's write (see git history
        // for the prior version of this test, which asserted quantity == 15 — the LOST update).
        //
        // The user decided to close that gap by having BuyAsset and SellAsset share ONE Mutex
        // instance (constructor-injected `portfolioMutex`), exactly as AppGraph/PortfolioGraph
        // now wire them. This test proves the fix: it forces the same interleaving as before
        // (buy loads its snapshot and hangs inside save() while a sell is launched concurrently)
        // and asserts BOTH mutations survive.
        //
        // How we know this test fails WITHOUT the fix: this is the exact interleaving the
        // pre-fix version of this test exercised and pinned as lossy (15, not 12). With two
        // separate `Mutex()` instances the sell would run uncontended while the buy still held
        // its own lock, save first, and then the buy's later save would overwrite it. Only
        // because both use cases now block on the SAME Mutex does the sell queue BEHIND the
        // buy (rather than racing past it) and re-load the buy's already-persisted write before
        // computing its own mutation — so this assertion (12, not 15) is a genuine regression
        // guard for the shared-mutex fix, not a tautology.
        val store = GatedPortfolioStore()
        store.stored = Portfolio.starting().buying(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), 500L, "txn-seed")
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.0)),
        )
        val sharedMutex = Mutex()
        val buyAsset = BuyAsset(repository, store, sharedMutex)
        val sellAsset = SellAsset(repository, store, sharedMutex)

        // The buy loads the 10-share snapshot, computes updated=15, then hangs inside save()
        // on the gate — still holding the shared mutex the whole time.
        val buyJob = launch { buyAsset.execute(aapl, BigDecimal.parseString("5"), 1000L) }
        runCurrent() // buy has loaded 10 shares, computed 15, now hangs inside save()

        // The sell attempts to enter the SAME mutex the buy still holds. It cannot proceed to
        // load() until the buy's save() (and its withLock block) completes.
        val sellJob = launch { sellAsset.execute("AAPL", BigDecimal.parseString("3"), 2000L) }
        runCurrent() // sell is parked waiting on the shared mutex; it cannot have loaded yet

        assertEquals(0, store.saveCount) // neither save has completed yet — the sell is blocked
        assertEquals(BigDecimal.parseString("10"), store.stored?.positionFor("AAPL")?.quantity) // unchanged

        store.firstSaveGate.complete(Unit) // release the buy's save; mutex releases after withLock returns
        runCurrent()
        buyJob.join()
        sellJob.join()

        // Both the buy and the sell landed: 10 + 5 - 3 = 12 shares, not 15 (the buy's lost
        // update) or 7 (the sell's lost update). The sell re-loaded the buy's persisted 15
        // AFTER the shared mutex released it, so its -3 mutation applies on top instead of
        // being clobbered.
        assertEquals(2, store.saveCount)
        assertEquals(BigDecimal.parseString("12"), store.stored?.positionFor("AAPL")?.quantity)
        assertEquals(3, store.stored?.transactions?.size) // seed + buy + sell
    }

    // --- M7.2 final-review fix: ResetPortfolio now joins the SAME shared portfolioMutex
    // every other portfolio/pie writer holds (see BuyAsset's co-holder doc, extended to list
    // ResetPortfolio). Before this fix, a reset raced against an in-flight pie contribution
    // (e.g. the coordinator's launch catch-up) could silently be overwritten, or leave a
    // fresh portfolio saddled with a contribution's stale pie ledger claim. ---

    @Test
    fun resetRacingContributionThroughOneMutexNeitherTornNorLost() = runTest {
        val slice = PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = BigDecimal.parseString("100"))
        val pie = Pie.create(id = "pie-race", name = "Race Pie", slices = listOf(slice), schedule = null, createdDay = "2025-01-01")
        val pieStore = ResetRaceFakePieStore(listOf(pie))
        val store = GatedPortfolioStore()
        store.stored = Portfolio.starting().buying(aapl, BigDecimal.parseString("5"), Money.usd("100.00"), 500L, "txn-seed")
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.0)),
        )
        val sharedMutex = Mutex()
        val resetPortfolio = ResetPortfolio(store, sharedMutex)
        val contributeToPie = ContributeToPie(pieStore, store, repository, sharedMutex)

        // The reset enters the mutex first, computes the fresh starting portfolio, then hangs
        // inside store.save() on the gate — still holding sharedMutex the whole time (save()
        // is called from inside withLock; ResetPortfolio has no load, so entering the lock and
        // reaching save() happen in the same synchronous step).
        val resetJob = launch { resetPortfolio.execute() }
        runCurrent() // reset is now blocked inside store.save(), mutex held

        // The contribution attempts to enter the SAME mutex the reset still holds. Without a
        // lock SHARED between both use cases, it would load() the stale 5-share snapshot
        // concurrently with the reset and whichever save() landed last would silently discard
        // the other — either the reset "loses" (contribution's stale-based write survives) or
        // the contribution "loses" (its buy vanishes under the fresh reset).
        val contributeJob = launch { contributeToPie.execute("pie-race", Money.usd("100"), "2025-06-01", 1000L) }
        runCurrent() // contribution is parked waiting on the mutex; it cannot have loaded yet

        assertEquals(0, store.saveCount) // neither save has completed yet
        assertEquals(BigDecimal.parseString("5"), store.stored?.positionFor("AAPL")?.quantity) // still the seed

        store.firstSaveGate.complete(Unit) // release the reset's save; mutex releases after withLock returns
        runCurrent()

        resetJob.join()
        contributeJob.join()

        // Both writes landed, serialized: the reset's save completes and releases the mutex
        // BEFORE the contribution's load() runs, so the contribution reloads the FRESH
        // $100,000-cash starting portfolio (not the stale 5-share snapshot) and buys 1 AAPL
        // ($100 / $100.00) on top of it — fresh-then-contributed, never a torn mix where the
        // seed position survives alongside the contribution, and never a lost update where
        // either write vanishes entirely.
        assertEquals(2, store.saveCount)
        val finalPortfolio = store.stored ?: error("expected a stored portfolio")
        assertEquals(BigDecimal.parseString("1"), finalPortfolio.positionFor("AAPL")?.quantity)
        assertEquals(Money.usd("100000").amount - BigDecimal.parseString("100.00"), finalPortfolio.cash.amount)
        assertEquals(1, finalPortfolio.transactions.size) // only the contribution's buy; reset wiped the seed txn
    }
}
