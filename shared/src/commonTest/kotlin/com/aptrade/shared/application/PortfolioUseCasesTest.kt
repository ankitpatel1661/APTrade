package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeError
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
 *  BuyAsset/SellAsset's `mutex.withLock` block, while a second caller attempts to enter the
 *  same lock. Without the mutex, the second caller's [load] would race in and observe the
 *  same stale snapshot, producing a lost update once both saves land. With the mutex, the
 *  second caller blocks on lock acquisition until the first's [save] (and its `withLock`
 *  block) completes, so it re-loads the FIRST write before applying its own mutation.
 *  Every [save] after the first proceeds immediately (subsequent calls are what the test
 *  wants to observe running unimpeded once the first releases the lock).
 *
 *  Gating is keyed by CALL ORDER (`callIndex`), not "count of saves that have completed" —
 *  that distinction matters when two use case instances with SEPARATE mutexes (e.g. one
 *  BuyAsset + one SellAsset sharing this store) both reach `save()` concurrently: the second
 *  arrival must proceed immediately even though no save has completed yet. */
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
        val result = BuyAsset(repository, store).execute(aapl, BigDecimal.parseString("10"), 5000L)

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
            BuyAsset(repository, store).execute(aapl, BigDecimal.parseString("10"), 5000L)
        }
        assertEquals(0, store.saveCount)
    }

    @Test
    fun buyPropagatesQuoteErrorWithoutSaving() = runTest {
        val store = InMemoryPortfolioStore()
        val repository = FakeMarketDataRepository(quotesError = QuoteError.RateLimited)
        assertFailsWith<QuoteError.RateLimited> {
            BuyAsset(repository, store).execute(aapl, BigDecimal.parseString("10"), 5000L)
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
        val result = SellAsset(repository, store).execute("AAPL", BigDecimal.parseString("4"), 6000L)

        assertEquals(BigDecimal.parseString("6"), result.positionFor("AAPL")?.quantity)
        assertEquals(2, result.transactions.size)
        assertEquals(6000L, result.transactions.last().epochSeconds)
        assertEquals(result, store.stored)
        assertEquals(1, store.saveCount)

        assertFailsWith<TradeError.InsufficientShares> {
            SellAsset(repository, store).execute("AAPL", BigDecimal.parseString("100"), 7000L)
        }
        assertEquals(1, store.saveCount) // the failed oversell did not persist
    }

    @Test
    fun resetSavesAndReturnsStarting() = runTest {
        val bought = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = bought }
        val result = ResetPortfolio(store).execute()
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

    // --- Concurrency (6d.2 Task 4): BuyAsset/SellAsset serialize their store RMW under a
    // private Mutex so two racing trades against ONE shared instance never lose an update. ---

    @Test
    fun twoRacingBuysThroughOneInstanceBothLandNoLostUpdate() = runTest {
        val store = GatedPortfolioStore()
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.0)),
        )
        val buyAsset = BuyAsset(repository, store) // ONE shared instance, as AppGraph wires it

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
        val sellAsset = SellAsset(repository, store) // ONE shared instance, as AppGraph wires it

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
    fun racingBuyAndSellOnSeparateInstancesSharingOneStoreIsAKnownResidualGapNotThisTasksScope() = runTest {
        // IMPORTANT SCOPE NOTE (flagged for final-review triage): the task brief specifies
        // "BuyAsset and SellAsset EACH gain a private Mutex" — mirroring ToggleBookmark's
        // shape of one mutex per use case. That closes the lost-update race for two
        // concurrent calls through the SAME use-case instance (see the two tests above), which
        // is the documented, in-scope fix. It does NOT close the race between a buy and a sell
        // in flight at the same time, because BuyAsset and SellAsset hold TWO SEPARATE mutex
        // instances even though they share one PortfolioStore — so a buy's load->mutate->save
        // is not serialized against a concurrent sell's load->mutate->save.
        //
        // This test PINS that residual gap (rather than silently asserting it away): it shows
        // a buy that loaded its snapshot before a concurrent sell committed can still overwrite
        // the sell's write, losing the sell's mutation. If a future task closes this gap (e.g.
        // one shared mutex per PortfolioStore instance, injected into both use cases), this
        // test's expected quantity should change from 15 (lost update) to 12 (both survive).
        val store = GatedPortfolioStore()
        store.stored = Portfolio.starting().buying(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), 500L, "txn-seed")
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.0)),
        )
        val buyAsset = BuyAsset(repository, store)
        val sellAsset = SellAsset(repository, store)

        // The buy loads the 10-share snapshot and computes updated=15 BEFORE hanging inside
        // save() on the gate; the sell then runs uncontended (separate mutex) and persists 7.
        val buyJob = launch { buyAsset.execute(aapl, BigDecimal.parseString("5"), 1000L) }
        runCurrent() // buy has loaded 10 shares, computed 15, now hangs inside save()

        val sellJob = launch { sellAsset.execute("AAPL", BigDecimal.parseString("3"), 2000L) }
        runCurrent() // sell runs its own (separate) mutex uncontended and completes: 10-3=7

        assertEquals(1, store.saveCount) // only the sell has saved so far
        assertEquals(BigDecimal.parseString("7"), store.stored?.positionFor("AAPL")?.quantity)

        store.firstSaveGate.complete(Unit) // release the buy's save
        runCurrent()
        buyJob.join()
        sellJob.join()

        // The buy's save() writes the value it computed BEFORE the sell ran (10+5=15), clobbering
        // the sell's already-persisted 7-share write — the sell's -3 mutation is LOST. This is
        // the accepted, documented residual gap (cross-use-case races), not a regression.
        assertEquals(2, store.saveCount)
        assertEquals(BigDecimal.parseString("15"), store.stored?.positionFor("AAPL")?.quantity)
    }
}
