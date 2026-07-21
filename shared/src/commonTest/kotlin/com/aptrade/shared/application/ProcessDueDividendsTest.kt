package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.Transaction
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transcribed from `Tests/APTradeApplicationTests/ProcessDueDividendsTests.swift` AS-BUILT —
 * the 9 scenarios (a–i) plus the reset×stale-marker regression (j), with byte-equal fixtures.
 * Three additional probes ((k)–(m)) pin the Kotlin carry-note divergences 1–3 (see
 * [ProcessDueDividends]'s doc). Days are UTC-noon epoch-seconds instants (see [date]); the
 * fake market indexes daily closes by [MarketCalendar.tradingDay] exactly as the engine does.
 */
class ProcessDueDividendsTest {
    private val calendar = MarketCalendar()

    private fun usd(s: String): Money = Money.usd(s)

    /** A UTC-noon instant for [day] — resolves, via [MarketCalendar]'s ET offset, to a
     *  market-local hour safely inside the same calendar day (mirrors the production
     *  `dayToEpochSeconds` helper's rationale). */
    private fun date(day: String): Long {
        val epochDay = PieSchedule.parseDay(day) ?: 0L
        return epochDay * 86_400L + 12L * 3_600L
    }

    private fun makeSUT(
        portfolioStore: PortfolioStore,
        market: MarketDataRepository,
        stateStore: SchedulerStateStore,
        drip: Boolean,
        mutex: Mutex = Mutex(),
    ): ProcessDueDividends =
        ProcessDueDividends(portfolioStore, market, stateStore, calendar, mutex, isDripEnabled = { drip })

    /** A single position with one buy transaction on [buyDay]. */
    private fun portfolio(
        cash: String = "100000",
        symbol: String,
        kind: AssetKind = AssetKind.Stock,
        shares: String,
        buyDay: String,
    ): Portfolio {
        val asset = Asset(symbol, symbol, kind)
        val qty = BigDecimal.parseString(shares)
        val pos = Position(asset, qty, usd("10"), Money(BigDecimal.ZERO, "USD"))
        val txn = Transaction("buy-$symbol", symbol, TradeSide.Buy, qty, usd("10"), date(buyDay))
        return Portfolio(usd(cash), listOf(pos), listOf(txn))
    }

    // MARK: (a) Backfill: two past events credit as cash even with DRIP on; firstRunDay persisted.

    @Test
    fun backfillEvents_creditAsCashEvenWithDripOn_andPersistFirstRunDay() = runTest {
        val portfolioStore = FakeDivPortfolioStore(portfolio(symbol = "AAPL", shares = "100", buyDay = "2024-01-01"))
        val market = FakeDivMarket()
        market.dailyClosesBySymbol["AAPL"] = mapOf("2025-02-15" to usd("200"), "2025-05-15" to usd("200"))
        market.eventsBySymbol["AAPL"] = listOf(
            DividendEvent("AAPL", date("2025-02-15"), usd("0.50")),
            DividendEvent("AAPL", date("2025-05-15"), usd("0.60")),
        )
        val stateStore = FakeDivStateStore() // fresh -> first run
        val sut = makeSUT(portfolioStore, market, stateStore, drip = true)

        val outcomes = sut.execute(date("2025-06-01"))

        assertEquals(
            listOf(
                DividendOutcome.Credited("AAPL", usd("50"), isBackfill = true),
                DividendOutcome.Credited("AAPL", usd("60"), isBackfill = true),
            ),
            outcomes,
        )
        assertEquals("2025-06-01", stateStore.state.dividendsFirstRunDay)
        assertEquals(usd("100110"), portfolioStore.portfolio.cash)
        assertEquals(2, portfolioStore.portfolio.transactions.count { it.side == TradeSide.Dividend })
        assertTrue(portfolioStore.portfolio.transactions.none { it.isDrip })
    }

    // MARK: (b) Post-first-run event, DRIP on -> reinvested at that day's close, fractional exact.

    @Test
    fun postFirstRunEvent_dripOn_reinvestsAtCloseWithExactFractionalShares() = runTest {
        val portfolioStore = FakeDivPortfolioStore(portfolio(symbol = "MSFT", shares = "100", buyDay = "2023-06-01"))
        val market = FakeDivMarket()
        market.dailyClosesBySymbol["MSFT"] = mapOf("2025-03-14" to usd("400"))
        market.eventsBySymbol["MSFT"] = listOf(DividendEvent("MSFT", date("2025-03-14"), usd("1.00")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = true)

        val outcomes = sut.execute(date("2025-06-01"))

        val expectedShares = usd("100").amount.divide(usd("400").amount, MONEY_MATH) // 0.25 exactly
        assertEquals(
            listOf(DividendOutcome.Reinvested("MSFT", usd("100"), expectedShares, isBackfill = false)),
            outcomes,
        )
        assertEquals(1, portfolioStore.portfolio.transactions.count { it.side == TradeSide.Dividend })
        val dripBuys = portfolioStore.portfolio.transactions.filter { it.side == TradeSide.Buy && it.isDrip }
        assertEquals(1, dripBuys.size)
        assertEquals(usd("400"), dripBuys.first().price)
        assertEquals(expectedShares, dripBuys.first().quantity)
        assertEquals(
            BigDecimal.parseString("100") + expectedShares,
            portfolioStore.portfolio.positionFor("MSFT")?.quantity,
        )
    }

    // MARK: (c) Second run -> zero outcomes (ledger dedup).

    @Test
    fun secondRun_dedupsAlreadyCreditedEvent() = runTest {
        val portfolioStore = FakeDivPortfolioStore(portfolio(symbol = "T", shares = "10", buyDay = "2024-01-01"))
        val market = FakeDivMarket()
        market.eventsBySymbol["T"] = listOf(DividendEvent("T", date("2025-03-15"), usd("1")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = false)

        val first = sut.execute(date("2025-06-01"))
        assertEquals(listOf(DividendOutcome.Credited("T", usd("10"), isBackfill = false)), first)

        val second = sut.execute(date("2025-06-01"))
        assertEquals(emptyList(), second)
        assertEquals(1, portfolioStore.portfolio.transactions.count { it.side == TradeSide.Dividend })
        assertEquals(usd("100010"), portfolioStore.portfolio.cash)
    }

    // MARK: (d) A buy on the ex-date itself earns nothing (strictly-before).

    @Test
    fun buyOnExDate_earnsNothing() = runTest {
        val asset = Asset("X", "X", AssetKind.Stock)
        val txn = Transaction("buy-X", "X", TradeSide.Buy, BigDecimal.parseString("10"), usd("10"), date("2025-03-15"))
        val pf = Portfolio(
            usd("100000"),
            listOf(Position(asset, BigDecimal.parseString("10"), usd("10"), Money(BigDecimal.ZERO, "USD"))),
            listOf(txn),
        )
        val portfolioStore = FakeDivPortfolioStore(pf)
        val market = FakeDivMarket()
        market.eventsBySymbol["X"] = listOf(DividendEvent("X", date("2025-03-15"), usd("1")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = false)

        val outcomes = sut.execute(date("2025-06-01"))

        assertEquals(emptyList(), outcomes)
        assertEquals(0, portfolioStore.portfolio.transactions.count { it.side == TradeSide.Dividend })
        assertEquals(usd("100000"), portfolioStore.portfolio.cash)
    }

    // MARK: (e) A sell before the ex-date reduces the credited quantity.

    @Test
    fun sellBeforeExDate_reducesCreditedQuantity() = runTest {
        val asset = Asset("Y", "Y", AssetKind.Stock)
        val buy = Transaction("buy-Y", "Y", TradeSide.Buy, BigDecimal.parseString("100"), usd("10"), date("2024-01-01"))
        val sell = Transaction("sell-Y", "Y", TradeSide.Sell, BigDecimal.parseString("60"), usd("12"), date("2025-01-01"))
        val pf = Portfolio(
            usd("100000"),
            listOf(Position(asset, BigDecimal.parseString("40"), usd("10"), Money(BigDecimal.ZERO, "USD"))),
            listOf(buy, sell),
        )
        val portfolioStore = FakeDivPortfolioStore(pf)
        val market = FakeDivMarket()
        market.eventsBySymbol["Y"] = listOf(DividendEvent("Y", date("2025-03-15"), usd("1")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = false)

        val outcomes = sut.execute(date("2025-06-01"))

        // 40 shares held before ex-date x $1 = $40 (not $100).
        assertEquals(listOf(DividendOutcome.Credited("Y", usd("40"), isBackfill = false)), outcomes)
        assertEquals(usd("100040"), portfolioStore.portfolio.cash)
    }

    // MARK: (f) DRIP on but close missing that day -> cash fallback.

    @Test
    fun dripOn_missingClose_fallsBackToCash() = runTest {
        val portfolioStore = FakeDivPortfolioStore(portfolio(symbol = "Z", shares = "50", buyDay = "2023-01-01"))
        val market = FakeDivMarket()
        market.dailyClosesBySymbol["Z"] = mapOf("2025-01-10" to usd("100")) // no close on the ex-date
        market.eventsBySymbol["Z"] = listOf(DividendEvent("Z", date("2025-03-15"), usd("2")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = true)

        val outcomes = sut.execute(date("2025-06-01"))

        assertEquals(listOf(DividendOutcome.Credited("Z", usd("100"), isBackfill = false)), outcomes)
        assertEquals(0, portfolioStore.portfolio.transactions.count { it.side == TradeSide.Buy && it.isDrip })
        assertEquals(usd("100100"), portfolioStore.portfolio.cash)
    }

    // MARK: (g) Crypto position ignored.

    @Test
    fun cryptoPosition_ignored() = runTest {
        val portfolioStore = FakeDivPortfolioStore(
            portfolio(symbol = "BTC-USD", kind = AssetKind.Crypto, shares = "5", buyDay = "2024-01-01"),
        )
        val market = FakeDivMarket()
        market.eventsBySymbol["BTC-USD"] = listOf(DividendEvent("BTC-USD", date("2025-03-15"), usd("1")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = false)

        val outcomes = sut.execute(date("2025-06-01"))

        assertEquals(emptyList(), outcomes)
        assertEquals(0, portfolioStore.portfolio.transactions.count { it.side == TradeSide.Dividend })
    }

    // MARK: (h) Repository throws for symbol A -> symbol B's event still credits.

    @Test
    fun repositoryThrowsForOneSymbol_otherSymbolStillCredits() = runTest {
        val assetA = Asset("A", "A", AssetKind.Stock)
        val assetB = Asset("B", "B", AssetKind.Stock)
        val buyA = Transaction("buy-A", "A", TradeSide.Buy, BigDecimal.parseString("5"), usd("10"), date("2024-01-01"))
        val buyB = Transaction("buy-B", "B", TradeSide.Buy, BigDecimal.parseString("10"), usd("10"), date("2024-01-01"))
        val pf = Portfolio(
            usd("100000"),
            listOf(
                Position(assetA, BigDecimal.parseString("5"), usd("10"), Money(BigDecimal.ZERO, "USD")),
                Position(assetB, BigDecimal.parseString("10"), usd("10"), Money(BigDecimal.ZERO, "USD")),
            ),
            listOf(buyA, buyB),
        )
        val portfolioStore = FakeDivPortfolioStore(pf)
        val market = FakeDivMarket()
        market.errorsBySymbol["A"] = QuoteError.NotFound
        market.eventsBySymbol["B"] = listOf(DividendEvent("B", date("2025-03-15"), usd("1")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = false)

        val outcomes = sut.execute(date("2025-06-01"))

        assertEquals(listOf(DividendOutcome.Credited("B", usd("10"), isBackfill = false)), outcomes)
        assertEquals(usd("100010"), portfolioStore.portfolio.cash)
    }

    // MARK: (i) DRIP off -> plain cash credit for a post-first-run event.

    @Test
    fun dripOff_postFirstRunEvent_creditsCash() = runTest {
        val portfolioStore = FakeDivPortfolioStore(portfolio(symbol = "M", shares = "20", buyDay = "2023-01-01"))
        val market = FakeDivMarket()
        market.dailyClosesBySymbol["M"] = mapOf("2025-03-15" to usd("50")) // present, but must be ignored
        market.eventsBySymbol["M"] = listOf(DividendEvent("M", date("2025-03-15"), usd("1.50")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = false)

        val outcomes = sut.execute(date("2025-06-01"))

        assertEquals(listOf(DividendOutcome.Credited("M", usd("30"), isBackfill = false)), outcomes)
        assertEquals(0, portfolioStore.portfolio.transactions.count { it.side == TradeSide.Buy && it.isDrip })
        assertEquals(usd("100030"), portfolioStore.portfolio.cash)
    }

    // MARK: (j) Regression: reset portfolio x stale `dividendsFirstRunDay`.
    //
    // `dividendsFirstRunDay` is a per-INSTALL marker (set once, on this app's very first
    // dividend-processing run) — it is never re-derived from the ledger. So if a user wipes/
    // resets their portfolio (a fresh, empty transaction ledger) long after that marker was
    // set, and then makes a brand-new buy, the marker is now "stale": it predates every
    // transaction the new ledger will ever contain.
    //
    // WHY THIS IS SAFE: the backfill check (`eventDay < firstRunDay`) only decides whether a
    // *credit-worthy* event is forced to cash instead of following the DRIP toggle. It never
    // grants eligibility by itself — eligibility is decided separately, from `sharesHeld`
    // computed strictly-before the ex-date against the CURRENT (reset) ledger. A dividend
    // event that predates the reset portfolio's only buy therefore always resolves to zero
    // shares held on the fresh ledger and is skipped outright, regardless of what the stale
    // marker says. And an event dated at or after the stale marker — which, because the ledger
    // was reset long after the marker was set, is effectively every event the reset portfolio
    // will ever see — correctly falls through to the live DRIP toggle rather than being
    // force-credited as cash, because backfill-suppression was only ever meant to protect the
    // first install run, not every later re-import. This test pins that interaction so the
    // Kotlin transcription reproduces it exactly.

    @Test
    fun resetPortfolioWithStaleFirstRunDay_preBuyEventSkipped_postBuyEventFollowsDripToggle() = runTest {
        // Stale marker: set long before the portfolio was reset and rebought.
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2020-01-01"))
        // Freshly-reset ledger: its only transaction is a NEW buy, dated well after the marker.
        val portfolioStore = FakeDivPortfolioStore(portfolio(symbol = "R", shares = "30", buyDay = "2025-01-01"))
        val market = FakeDivMarket()
        market.dailyClosesBySymbol["R"] = mapOf("2025-03-15" to usd("60"))
        market.eventsBySymbol["R"] = listOf(
            // (i) Predates the new ledger's only buy -> sharesHeld == 0 -> skipped, even though
            // its ex-date trading day is also (long) after the stale marker.
            DividendEvent("R", date("2024-06-15"), usd("1")),
            // (ii) Postdates the new buy, and its day >= the stale firstRunDay, so it is NOT
            // treated as backfill -> follows the live DRIP toggle (on) instead of being
            // force-credited as cash.
            DividendEvent("R", date("2025-03-15"), usd("2")),
        )
        val sut = makeSUT(portfolioStore, market, stateStore, drip = true)

        val outcomes = sut.execute(date("2025-06-01"))

        // (i) skipped (zero shares held); (ii) reinvested (DRIP): 30 x $2 = $60 at a $60 close
        // = 1.0 share exactly.
        val expectedShares = usd("60").amount.divide(usd("60").amount, MONEY_MATH)
        assertEquals(
            listOf(DividendOutcome.Reinvested("R", usd("60"), expectedShares, isBackfill = false)),
            outcomes,
        )
        assertEquals(1, portfolioStore.portfolio.transactions.count { it.side == TradeSide.Dividend })
        assertEquals(1, portfolioStore.portfolio.transactions.count { it.side == TradeSide.Buy && it.isDrip })
    }

    // MARK: (k) Carry-note 1 — the out-of-lock dedup pre-filter: a second run acquires the
    // mutex ZERO times for an already-credited event (proven by a spy Mutex).

    @Test
    fun carryNote1_secondRun_acquiresMutexZeroTimesForAlreadyCreditedEvent() = runTest {
        val portfolioStore = FakeDivPortfolioStore(portfolio(symbol = "PF", shares = "10", buyDay = "2024-01-01"))
        val market = FakeDivMarket()
        market.eventsBySymbol["PF"] = listOf(DividendEvent("PF", date("2025-03-15"), usd("1")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val spy = CountingMutex()
        val sut = makeSUT(portfolioStore, market, stateStore, drip = false, mutex = spy)

        // First run credits the event; it acquires the lock exactly once.
        val first = sut.execute(date("2025-06-01"))
        assertEquals(listOf(DividendOutcome.Credited("PF", usd("10"), isBackfill = false)), first)
        assertEquals(1, spy.lockCount)

        // Second run: the event is already in the snapshot ledger, so the pre-filter drops it
        // BEFORE any lock is taken — the mutex is never acquired again.
        spy.reset()
        val second = sut.execute(date("2025-06-01"))
        assertEquals(emptyList(), second)
        assertEquals(0, spy.lockCount)
    }

    // MARK: (l) Carry-note 3 — a position that vanished between the snapshot and the in-lock
    // reload credits as CASH, never a fabricated Asset(kind = Stock) DRIP buy.

    @Test
    fun carryNote3_vanishedPositionBetweenSnapshotAndLock_creditsCashNotFabricatedBuy() = runTest {
        val asset = Asset("V", "V", AssetKind.Stock)
        val buy = Transaction("buy-V", "V", TradeSide.Buy, BigDecimal.parseString("40"), usd("10"), date("2024-01-01"))
        // Snapshot (first load): the position is present, so V is a candidate symbol.
        val snapshot = Portfolio(
            usd("100000"),
            listOf(Position(asset, BigDecimal.parseString("40"), usd("10"), Money(BigDecimal.ZERO, "USD"))),
            listOf(buy),
        )
        // In-lock reload: the position has vanished (fully sold elsewhere), but the pre-ex-date
        // buy remains in the ledger, so sharesHeld strictly-before the ex-date is still 40 > 0.
        val afterVanish = Portfolio(usd("100000"), emptyList(), listOf(buy))
        val portfolioStore = VanishingPortfolioStore(snapshot, afterVanish)
        val market = FakeDivMarket()
        market.dailyClosesBySymbol["V"] = mapOf("2025-03-15" to usd("60")) // a close DOES exist
        market.eventsBySymbol["V"] = listOf(DividendEvent("V", date("2025-03-15"), usd("2")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = true)

        val outcomes = sut.execute(date("2025-06-01"))

        // Even with DRIP on and a valid close, the missing position forces a cash credit.
        assertEquals(listOf(DividendOutcome.Credited("V", usd("80"), isBackfill = false)), outcomes)
        val saved = portfolioStore.saved ?: error("expected a save")
        assertEquals(usd("100080"), saved.cash)
        assertEquals(0, saved.transactions.count { it.side == TradeSide.Buy && it.isDrip })
        assertEquals(1, saved.transactions.count { it.side == TradeSide.Dividend })
    }

    // MARK: (m) Carry-note 2 — the DRIP cost clamp: an inexact quotient that rounds UP is
    // clamped so the reinvestment buy's cost never exceeds the credited cash.

    @Test
    fun carryNote2_dripCostClamp_reinvestmentCostNeverExceedsCredit() = runTest {
        // credit = 2 shares x $1 = $2; close = $3 -> 2/3 rounds UP under MONEY_MATH, so the
        // unclamped cost (quantity x $3) would be a rounding-ulp above $2.
        val portfolioStore = FakeDivPortfolioStore(portfolio(symbol = "C", shares = "2", buyDay = "2023-01-01"))
        val market = FakeDivMarket()
        market.dailyClosesBySymbol["C"] = mapOf("2025-03-15" to usd("3"))
        market.eventsBySymbol["C"] = listOf(DividendEvent("C", date("2025-03-15"), usd("1")))
        val stateStore = FakeDivStateStore(SchedulerState(dividendsFirstRunDay = "2024-01-01"))
        val sut = makeSUT(portfolioStore, market, stateStore, drip = true)

        val outcomes = sut.execute(date("2025-06-01"))

        val outcome = outcomes.single() as DividendOutcome.Reinvested
        val credit = usd("2").amount
        val close = usd("3").amount
        // The clamp holds the invariant cost <= credit, and never loses a whole share of value.
        assertTrue(outcome.shares.multiply(close) <= credit)
        assertTrue((credit - outcome.shares.multiply(close)) < close)
        assertTrue(outcome.shares > BigDecimal.ZERO)
        val dripBuys = portfolioStore.portfolio.transactions.filter { it.side == TradeSide.Buy && it.isDrip }
        assertEquals(1, dripBuys.size)
        assertEquals(outcome.shares, dripBuys.first().quantity)
    }
}

// MARK: - Fakes

private class FakeDivPortfolioStore(initial: Portfolio) : PortfolioStore {
    var portfolio: Portfolio = initial
    override suspend fun load(): Portfolio = portfolio
    override suspend fun save(portfolio: Portfolio) {
        this.portfolio = portfolio
    }
}

/** Returns [snapshot] on the FIRST load, then [afterVanish] on every subsequent load —
 *  simulating a position deleted between the engine's out-of-lock snapshot and its in-lock
 *  reload (carry-note 3). */
private class VanishingPortfolioStore(snapshot: Portfolio, private val afterVanish: Portfolio) : PortfolioStore {
    private var current: Portfolio = snapshot
    var saved: Portfolio? = null
        private set

    override suspend fun load(): Portfolio {
        val result = current
        current = afterVanish
        return result
    }

    override suspend fun save(portfolio: Portfolio) {
        saved = portfolio
        current = portfolio
    }
}

private class FakeDivStateStore(initial: SchedulerState = SchedulerState()) : SchedulerStateStore {
    var state: SchedulerState = initial
    override suspend fun load(): SchedulerState = state
    override suspend fun save(state: SchedulerState) {
        this.state = state
    }
}

/** Market with a canned daily-close table indexed by `yyyy-MM-dd` exactly as
 *  [ProcessDueDividends] indexes `history`, plus per-symbol dividend events and forced errors. */
private class FakeDivMarket : MarketDataRepository {
    val dailyClosesBySymbol: MutableMap<String, Map<String, Money>> = mutableMapOf()
    val eventsBySymbol: MutableMap<String, List<DividendEvent>> = mutableMapOf()
    val errorsBySymbol: MutableMap<String, Throwable> = mutableMapOf()
    private val calendar = MarketCalendar()

    override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
        errorsBySymbol[symbol]?.let { throw it }
        val closes = dailyClosesBySymbol[symbol] ?: emptyMap()
        return closes.mapNotNull { (day, close) ->
            val epochDay = PieSchedule.parseDay(day) ?: return@mapNotNull null
            PricePoint(epochSeconds = epochDay * 86_400L + 12L * 3_600L, close = close)
        }
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
    override suspend fun search(query: String): List<Asset> = emptyList()

    override suspend fun dividendEvents(symbol: String, fromEpochSeconds: Long): List<DividendEvent> {
        errorsBySymbol[symbol]?.let { throw it }
        return (eventsBySymbol[symbol] ?: emptyList()).filter { it.exDateEpochSeconds >= fromEpochSeconds }
    }
}

/** A [Mutex] that counts how many times a critical section is entered, delegating everything
 *  else to a real mutex. Used by carry-note-1's probe to prove the pre-filter avoids the lock
 *  entirely for already-credited events. */
private class CountingMutex(private val delegate: Mutex = Mutex()) : Mutex by delegate {
    var lockCount = 0
        private set

    fun reset() {
        lockCount = 0
    }

    override suspend fun lock(owner: Any?) {
        lockCount += 1
        delegate.lock(owner)
    }
}
