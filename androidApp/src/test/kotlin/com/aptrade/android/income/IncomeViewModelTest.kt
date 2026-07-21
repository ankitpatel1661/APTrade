package com.aptrade.android.income

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.Quote
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android twin of desktopApp/src/test/kotlin/com/aptrade/desktop/income/IncomeViewModelTest.kt,
 *  transcribed AS-BUILT, including the `buildUpcoming` stale-projection regression (a
 *  `nextProjected` landing before `asOf` must never surface). [IncomeViewModel] is an
 *  androidx ViewModel using `viewModelScope` (Dispatchers.Main.immediate), mirroring
 *  [com.aptrade.android.plans.PlansViewModelTest]'s scheduler discipline: a
 *  [StandardTestDispatcher] installed as Main, with `runCurrent()` after each VM call. */
class IncomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakePortfolioStore(initial: Portfolio) : PortfolioStore {
        var portfolio: Portfolio = initial
        override suspend fun load(): Portfolio = portfolio
        override suspend fun save(portfolio: Portfolio) {
            this.portfolio = portfolio
        }
    }

    private fun usd(s: String): Money = Money(BigDecimal.parseString(s), "USD")
    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

    /** 2026-07-20 12:00:00 UTC — a fixed "now" so calendar-year and month-bucket math is
     *  deterministic across runs. Matches the desktop fixture's `fixedNow` exactly. */
    private val fixedNow: Long = LocalDateTime.of(2026, 7, 20, 12, 0, 0).toEpochSecond(ZoneOffset.UTC)

    private fun utc(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 12, 0, 0).toEpochSecond(ZoneOffset.UTC)

    private fun quote(symbol: String, price: String): Quote =
        Quote(symbol, usd(price), usd(price), 0.0)

    private fun makeVm(
        portfolio: Portfolio,
        quotes: Map<String, Quote> = emptyMap(),
        events: Map<String, () -> List<DividendEvent>> = emptyMap(),
        now: Long = fixedNow,
    ): Pair<IncomeViewModel, FakeMarketDataRepository> {
        val store = FakePortfolioStore(portfolio)
        val market = FakeMarketDataRepository()
        market.quotesImpl = { symbols -> symbols.mapNotNull { quotes[it] } }
        market.dividendEventsImpl = { symbol, _ ->
            events[symbol]?.invoke() ?: emptyList()
        }
        val vm = IncomeViewModel(
            portfolioStore = store,
            marketDataRepository = market,
            nowEpochSeconds = { now },
        )
        return vm to market
    }

    // MARK: - (a) cards computed from ledger + events fixture (exact Money math)

    @Test
    fun cardsComputedFromLedgerAndEventsExactMoneyMath() = runTest(dispatcher.scheduler) {
        var portfolio = Portfolio.starting()
        // 100 shares of KO bought at cost basis $60/share.
        portfolio = portfolio.buying(
            Asset("KO", "Coca-Cola", AssetKind.Stock), qty("100"), usd("60"), utc(2025, 1, 5),
        )
        // Two dividend payouts this year: $0.48/share and $0.51/share on 100 shares.
        portfolio = portfolio.receivingDividend(
            symbol = "KO", amountPerShare = usd("0.48"), shares = qty("100"),
            exDateEpochSeconds = utc(2026, 2, 14),
        )
        portfolio = portfolio.receivingDividend(
            symbol = "KO", amountPerShare = usd("0.51"), shares = qty("100"),
            exDateEpochSeconds = utc(2026, 5, 14),
        )
        // A dividend from LAST year must not count toward receivedYTD.
        portfolio = portfolio.receivingDividend(
            symbol = "KO", amountPerShare = usd("0.46"), shares = qty("100"),
            exDateEpochSeconds = utc(2025, 11, 14),
        )

        val koEvents = listOf(
            DividendEvent("KO", utc(2025, 11, 14), usd("0.46")),
            DividendEvent("KO", utc(2026, 2, 14), usd("0.48")),
            DividendEvent("KO", utc(2026, 5, 14), usd("0.51")),
        )
        val (vm, _) = makeVm(
            portfolio = portfolio,
            quotes = mapOf("KO" to quote("KO", "65")),
            events = mapOf("KO" to { koEvents }),
        )

        vm.load()
        runCurrent()
        val state = vm.state.value

        // receivedYTD = (0.48 + 0.51) x 100 = $99.00 -- the 2025 payout is excluded.
        assertEquals(usd("99"), state.cards?.receivedYTD)

        // trailingAnnualPerShare (asOf 2026-07-20, 365d window): all three events fall
        // within the trailing year -> 0.46 + 0.48 + 0.51 = 1.45/share x 100 shares = $145.
        assertEquals(usd("145"), state.cards?.projectedAnnual)

        // marketValue = 100 x $65 = $6,500 -> yield = 145 / 6500.
        val expectedYield = BigDecimal.parseString("145")
            .divide(BigDecimal.parseString("6500"), MONEY_MATH).doubleValue(false)
        assertEquals(expectedYield, state.cards?.portfolioYield ?: -1.0, 0.0001)

        // costBasis = 100 x $60 = $6,000 -> yieldOnCost = 145 / 6000.
        val expectedYoC = BigDecimal.parseString("145")
            .divide(BigDecimal.parseString("6000"), MONEY_MATH).doubleValue(false)
        assertEquals(expectedYoC, state.cards?.yieldOnCost ?: -1.0, 0.0001)
    }

    // MARK: - (b) history pairs the DRIP badge correctly

    @Test
    fun historyPairsDripBadgeCorrectly() = runTest(dispatcher.scheduler) {
        var portfolio = Portfolio.starting()
        portfolio = portfolio.buying(
            Asset("KO", "Coca-Cola", AssetKind.Stock), qty("100"), usd("60"), utc(2025, 1, 5),
        )
        // Dividend #1 (2026-02-14) is immediately reinvested -- a DRIP buy same symbol,
        // same trading day.
        portfolio = portfolio.receivingDividend(
            symbol = "KO", amountPerShare = usd("0.48"), shares = qty("100"),
            exDateEpochSeconds = utc(2026, 2, 14),
        )
        portfolio = portfolio.buying(
            Asset("KO", "Coca-Cola", AssetKind.Stock), qty("0.7"), usd("68.5"),
            utc(2026, 2, 14), isDrip = true,
        )
        // Dividend #2 (2026-05-14) is taken as cash -- no matching DRIP buy.
        portfolio = portfolio.receivingDividend(
            symbol = "KO", amountPerShare = usd("0.51"), shares = qty("100.7"),
            exDateEpochSeconds = utc(2026, 5, 14),
        )

        val (vm, _) = makeVm(portfolio = portfolio)
        vm.load()
        runCurrent()
        val state = vm.state.value

        assertEquals(2, state.history.size)
        // Newest first.
        assertEquals(utc(2026, 5, 14), state.history[0].epochSeconds)
        assertFalse(state.history[0].wasReinvested)
        assertEquals(utc(2026, 2, 14), state.history[1].epochSeconds)
        assertTrue(state.history[1].wasReinvested)
    }

    // MARK: - (c) months: 12 buckets, projected bars flagged

    @Test
    fun monthsTwelveBucketsPlusProjectedFlagged() = runTest(dispatcher.scheduler) {
        var portfolio = Portfolio.starting()
        portfolio = portfolio.buying(
            Asset("KO", "Coca-Cola", AssetKind.Stock), qty("100"), usd("60"), utc(2025, 1, 5),
        )
        portfolio = portfolio.receivingDividend(
            symbol = "KO", amountPerShare = usd("0.48"), shares = qty("100"),
            exDateEpochSeconds = utc(2026, 2, 14),
        )
        portfolio = portfolio.receivingDividend(
            symbol = "KO", amountPerShare = usd("0.51"), shares = qty("100"),
            exDateEpochSeconds = utc(2026, 5, 14),
        )

        // Quarterly cadence (91-day gaps) -> nextProjected lands ~2026-08-13, bucket "2026-08".
        val koEvents = listOf(
            DividendEvent("KO", utc(2025, 11, 14), usd("0.46")),
            DividendEvent("KO", utc(2026, 2, 14), usd("0.48")),
            DividendEvent("KO", utc(2026, 5, 14), usd("0.51")),
        )
        val (vm, _) = makeVm(portfolio = portfolio, events = mapOf("KO" to { koEvents }))
        vm.load()
        runCurrent()
        val state = vm.state.value

        val receivedBars = state.months.filter { !it.isProjected }
        assertEquals(12, receivedBars.size)
        assertEquals("2025-08", receivedBars.first().id)
        assertEquals("2026-07", receivedBars.last().id)
        assertEquals(usd("48"), receivedBars.first { it.id == "2026-02" }.amount)
        assertEquals(usd("51"), receivedBars.first { it.id == "2026-05" }.amount)

        val projectedBars = state.months.filter { it.isProjected }
        assertTrue(projectedBars.isNotEmpty())
        assertTrue(projectedBars.all { it.id > "2026-07" })
        assertTrue(projectedBars.size <= 3)
        // last.exDate (2026-05-14) + 91d ~= 2026-08-13 -> 100 shares x $0.51 = $51.
        assertEquals("2026-08", projectedBars.first().id)
        assertEquals(usd("51"), projectedBars.first().amount)
    }

    // MARK: - (d) upcoming sorted by estimatedExDate

    @Test
    fun upcomingSortedByEstimatedExDate() = runTest(dispatcher.scheduler) {
        var portfolio = Portfolio.starting()
        portfolio = portfolio.buying(
            Asset("KO", "Coca-Cola", AssetKind.Stock), qty("100"), usd("60"), utc(2025, 1, 5),
        )
        portfolio = portfolio.buying(
            Asset("JNJ", "Johnson & Johnson", AssetKind.Stock), qty("50"), usd("150"), utc(2025, 1, 5),
        )

        // KO: quarterly, next projected ~= 2026-08-13.
        val koEvents = listOf(
            DividendEvent("KO", utc(2025, 11, 14), usd("0.46")),
            DividendEvent("KO", utc(2026, 2, 14), usd("0.48")),
            DividendEvent("KO", utc(2026, 5, 14), usd("0.51")),
        )
        // JNJ: quarterly, next projected ~= 2026-08-01 (earlier than KO's).
        val jnjEvents = listOf(
            DividendEvent("JNJ", utc(2025, 11, 2), usd("1.19")),
            DividendEvent("JNJ", utc(2026, 2, 1), usd("1.24")),
            DividendEvent("JNJ", utc(2026, 5, 2), usd("1.24")),
        )
        val (vm, _) = makeVm(
            portfolio = portfolio,
            events = mapOf("KO" to { koEvents }, "JNJ" to { jnjEvents }),
        )
        vm.load()
        runCurrent()
        val state = vm.state.value

        assertEquals(2, state.upcoming.size)
        assertEquals("JNJ", state.upcoming[0].symbol)
        assertEquals("KO", state.upcoming[1].symbol)
        assertTrue(state.upcoming[0].estimatedExDateEpochSeconds < state.upcoming[1].estimatedExDateEpochSeconds)
        assertEquals(usd("62"), state.upcoming[0].estimatedAmount) // 1.24 x 50
        assertEquals(usd("51"), state.upcoming[1].estimatedAmount) // 0.51 x 100
    }

    // MARK: - Regression: a stale nextProjected (landing in the past) must not surface

    /** `DividendMath.nextProjected` is just `lastEvent.exDate + cadenceInterval` -- it has
     *  no awareness of "now". A symbol whose last recorded event is old enough that its
     *  projected next payout has already elapsed (e.g. an annual payer last seen ~700 days
     *  ago) must NOT show up in `upcoming` as a future-dated row. A symbol with a genuinely
     *  future projection must still appear. */
    @Test
    fun upcomingExcludesStaleProjectionPastAsOf() = runTest(dispatcher.scheduler) {
        var portfolio = Portfolio.starting()
        portfolio = portfolio.buying(
            Asset("OLD", "Old Annual Payer", AssetKind.Stock), qty("10"), usd("50"), utc(2022, 1, 5),
        )
        portfolio = portfolio.buying(
            Asset("KO", "Coca-Cola", AssetKind.Stock), qty("100"), usd("60"), utc(2025, 1, 5),
        )

        // OLD: annual cadence, last event 2024-08-01 -> nextProjected ~= 2025-08-01,
        // which is BEFORE fixedNow (2026-07-20) -- a stale, already-elapsed projection.
        val oldEvents = listOf(
            DividendEvent("OLD", utc(2023, 7, 28), usd("2.00")),
            DividendEvent("OLD", utc(2024, 8, 1), usd("2.10")),
        )
        // KO: quarterly, last event 2026-05-14 -> nextProjected ~= 2026-08-13, genuinely
        // in the future relative to fixedNow.
        val koEvents = listOf(
            DividendEvent("KO", utc(2025, 11, 14), usd("0.46")),
            DividendEvent("KO", utc(2026, 2, 14), usd("0.48")),
            DividendEvent("KO", utc(2026, 5, 14), usd("0.51")),
        )
        val (vm, _) = makeVm(
            portfolio = portfolio,
            events = mapOf("OLD" to { oldEvents }, "KO" to { koEvents }),
        )
        vm.load()
        runCurrent()
        val state = vm.state.value

        assertFalse(
            state.upcoming.any { it.symbol == "OLD" },
            "a projection dated before 'now' must not appear as an upcoming payout",
        )
        assertTrue(state.upcoming.any { it.symbol == "KO" })
        assertEquals(1, state.upcoming.size)
    }

    // MARK: - (e) event-fetch failure degrades upcoming only

    @Test
    fun eventFetchFailureUpcomingEmptyHistoryAndReceivedYTDStillPopulate() = runTest(dispatcher.scheduler) {
        var portfolio = Portfolio.starting()
        portfolio = portfolio.buying(
            Asset("KO", "Coca-Cola", AssetKind.Stock), qty("100"), usd("60"), utc(2025, 1, 5),
        )
        portfolio = portfolio.receivingDividend(
            symbol = "KO", amountPerShare = usd("0.48"), shares = qty("100"),
            exDateEpochSeconds = utc(2026, 2, 14),
        )

        // The events repo throws for KO -- the only held symbol.
        val (vm, market) = makeVm(
            portfolio = portfolio,
            events = mapOf("KO" to { throw QuoteError.NotFound }),
        )
        vm.load()
        runCurrent()
        val state = vm.state.value

        assertTrue(state.upcoming.isEmpty())
        assertEquals(1, state.history.size)
        assertEquals(usd("48"), state.cards?.receivedYTD)
        // projectedAnnual degrades to zero for the failed symbol -- never blocks the rest.
        assertEquals(usd("0"), state.cards?.projectedAnnual)
        assertEquals(listOf("KO"), market.requestedDividendEventSymbols)
    }
}
