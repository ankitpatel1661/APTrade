package com.aptrade.desktop.detail

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.EmptyEarningsRepository
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchDividendEvents
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.DividendMath
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun vm(repo: FakeMarketDataRepository, scope: kotlinx.coroutines.CoroutineScope) =
    DetailViewModel("AAPL", FetchProfile(repo), FetchMarketQuotes(repo),
        FetchHistory(repo), FetchChartWindow(repo),
        // Not under test here (see DetailViewModelEarningsTest) — an always-empty fetch keeps
        // these chart-focused tests unaffected by the Next-earnings load.
        FetchEarningsCalendar(EmptyEarningsRepository) { emptySet() },
        FetchDividendEvents(repo), scope)

/** Dividend-focused construction: exposes `now`/`symbol` so each test can fix the "asOf"
 *  instant (mirrors AssetDetailViewModelTests' `now: @escaping () -> Date` override) and
 *  the asset under test (e.g. a crypto symbol for the skip-without-fetching case). */
private fun dividendVm(
    repo: FakeMarketDataRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    now: Long,
    symbol: String = "AAPL",
) = DetailViewModel(
    symbol = symbol,
    fetchProfile = FetchProfile(repo),
    fetchMarketQuotes = FetchMarketQuotes(repo),
    fetchHistory = FetchHistory(repo),
    fetchChartWindow = FetchChartWindow(repo),
    fetchEarningsCalendar = FetchEarningsCalendar(EmptyEarningsRepository) { emptySet() },
    fetchDividendEvents = FetchDividendEvents(repo),
    scope = scope,
    nowEpochSeconds = { now },
)

class DetailViewModelTest {

    @Test
    fun loadsProfileQuoteAndLineChart() = runTest {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset("AAPL", "Apple Inc.", AssetKind.Stock) }
        // .45 cents: Money.amountText drops trailing zeros (known shared-core debt)
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("308.63"), Money.usd("294.45"), 4.84)) }
        repo.historyImpl = { _, _ -> listOf(PricePoint(1, Money.usd("294.00")), PricePoint(2, Money.usd("308.63"))) }
        val vm = vm(repo, backgroundScope); runCurrent()

        val s = vm.state.value
        assertEquals("Apple Inc.", s.name)
        assertEquals("308.63", s.amountText)
        assertEquals("294.45", s.previousCloseText)
        assertEquals(4.84, s.changePercent)
        assertEquals(listOf(294.00, 308.63), s.lineValues)
        assertEquals(false, s.isLoadingChart)
    }

    @Test
    fun candlesModeFetchesCandles() = runTest {
        val repo = FakeMarketDataRepository()
        repo.candlesImpl = { _, _ -> listOf(
            Candle(1, Money.usd("1.00"), Money.usd("3.00"), Money.usd("0.50"), Money.usd("2.00"))) }
        val vm = vm(repo, backgroundScope); runCurrent()
        vm.onModeChange(ChartMode.Candles); runCurrent()
        assertEquals(1, vm.state.value.candles.size)
        assertEquals(2.0, vm.state.value.candles.single().close)
    }

    @Test
    fun timeframeChangeRefetchesForThatTimeframe() = runTest {
        val repo = FakeMarketDataRepository()
        var lastTf: Timeframe? = null
        repo.historyImpl = { _, tf -> lastTf = tf; emptyList() }
        val vm = vm(repo, backgroundScope); runCurrent()
        vm.onTimeframeChange(Timeframe.OneMonth); runCurrent()
        assertEquals(Timeframe.OneMonth, lastTf)
        assertEquals(Timeframe.OneMonth, vm.state.value.timeframe)
    }

    @Test
    fun staleResponseNeverOverwritesNewerSelection() = runTest {
        val repo = FakeMarketDataRepository()
        val slowFirst = CompletableDeferred<Unit>()
        repo.historyImpl = { _, tf ->
            if (tf == Timeframe.OneDay) { slowFirst.await() }   // first (1D) request hangs
            listOf(PricePoint(1, Money.usd(if (tf == Timeframe.OneDay) "1.00" else "2.00")))
        }
        val vm = vm(repo, backgroundScope); runCurrent()        // 1D fetch in flight
        vm.onTimeframeChange(Timeframe.OneMonth); runCurrent()  // cancels it, fetches 1M
        slowFirst.complete(Unit); runCurrent()
        assertEquals(listOf(2.0), vm.state.value.lineValues)    // 1M won; stale 1D never rendered
    }

    @Test
    fun staleCandleResponseNeverOverwritesNewerSelection() = runTest {
        // Symmetric to the history-path stale test, but on the candles data path: a slow
        // in-flight candle fetch for the first timeframe must not write after the selection
        // moves on. loadChart() cancels the prior chartJob, so the hung fetch is abandoned.
        val repo = FakeMarketDataRepository()
        val slowFirst = CompletableDeferred<Unit>()
        repo.candlesImpl = { _, tf ->
            if (tf == Timeframe.OneDay) { slowFirst.await() }   // first (1D) candle fetch hangs
            val close = if (tf == Timeframe.OneDay) "1.00" else "2.00"
            listOf(Candle(1, Money.usd("1.00"), Money.usd("3.00"), Money.usd("0.50"), Money.usd(close)))
        }
        val vm = vm(repo, backgroundScope); runCurrent()
        vm.onModeChange(ChartMode.Candles); runCurrent()        // 1D candle fetch in flight
        vm.onTimeframeChange(Timeframe.OneMonth); runCurrent()  // cancels it, fetches 1M candles
        slowFirst.complete(Unit); runCurrent()

        // 1M candles won; the stale 1D response never overwrote them.
        assertEquals(listOf(2.0), vm.state.value.candles.map { it.close })
    }

    @Test
    fun chartErrorSurfacesAndRetryRecovers() = runTest {
        val repo = FakeMarketDataRepository()
        var fail = true
        repo.historyImpl = { _, _ ->
            if (fail) throw QuoteError.Network("boom")
            else listOf(PricePoint(1, Money.usd("1.00")))
        }
        val vm = vm(repo, backgroundScope); runCurrent()
        assertNotNull(vm.state.value.chartError)
        fail = false
        vm.retryChart(); runCurrent()
        assertEquals(listOf(1.0), vm.state.value.lineValues)
    }

    @Test
    fun reactivatingIndicatorsAfterTimeframeChangeRefetchesCandlesForNewTimeframe() = runTest {
        val repo = FakeMarketDataRepository()
        val candleFetches = mutableListOf<Timeframe>()
        repo.candlesImpl = { _, tf ->
            candleFetches += tf
            listOf(Candle(1, Money.usd("1.00"), Money.usd("3.00"), Money.usd("0.50"), Money.usd("2.00")))
        }
        val vm = vm(repo, backgroundScope); runCurrent()

        // Activate: first fetch, seeded for the initial timeframe (OneDay).
        vm.onIndicatorsActiveChange(true); runCurrent()
        assertEquals(listOf(Timeframe.OneDay), candleFetches)

        // Deactivate: no fetch (candles just sit there, now a stale cache waiting to happen).
        vm.onIndicatorsActiveChange(false); runCurrent()
        assertEquals(listOf(Timeframe.OneDay), candleFetches)

        // Timeframe change while inactive: Line mode with no active indicator doesn't need
        // candles, so this must NOT fetch — the stale OneDay candles are left in state.
        vm.onTimeframeChange(Timeframe.OneMonth); runCurrent()
        assertEquals(listOf(Timeframe.OneDay), candleFetches)

        // Reactivate: must detect the cached candles were fetched for OneDay while the
        // selection is now OneMonth, and refetch for the NEW timeframe.
        vm.onIndicatorsActiveChange(true); runCurrent()
        assertEquals(listOf(Timeframe.OneDay, Timeframe.OneMonth), candleFetches)
    }

    @Test
    fun candleModeTimeframeChangeFetchesCandlesExactlyOnce() = runTest {
        val repo = FakeMarketDataRepository()
        val candleFetches = mutableListOf<Timeframe>()
        repo.candlesImpl = { _, tf ->
            candleFetches += tf
            listOf(Candle(1, Money.usd("1.00"), Money.usd("3.00"), Money.usd("0.50"), Money.usd("2.00")))
        }
        val vm = vm(repo, backgroundScope); runCurrent()
        vm.onModeChange(ChartMode.Candles); runCurrent()
        assertEquals(listOf(Timeframe.OneDay), candleFetches)

        vm.onTimeframeChange(Timeframe.OneWeek); runCurrent()
        assertEquals(listOf(Timeframe.OneDay, Timeframe.OneWeek), candleFetches)
    }

    @Test
    fun firstIndicatorActivationFetchesCandlesExactlyOnce() = runTest {
        val repo = FakeMarketDataRepository()
        val candleFetches = mutableListOf<Timeframe>()
        repo.candlesImpl = { _, tf ->
            candleFetches += tf
            listOf(Candle(1, Money.usd("1.00"), Money.usd("3.00"), Money.usd("0.50"), Money.usd("2.00")))
        }
        val vm = vm(repo, backgroundScope); runCurrent()
        vm.onIndicatorsActiveChange(true); runCurrent()
        assertEquals(listOf(Timeframe.OneDay), candleFetches)
    }

    // MARK: - Dividend info

    /** (a) A payer with events in the trailing window gets a computed `DividendInfo`: rate =
     *  trailing 365d per-share sum, yield = rate / current quote price. Mirrors
     *  AssetDetailViewModelTests.test_load_payerWithEvents_computesDividendInfo. */
    @Test
    fun payerWithEventsComputesDividendInfo() = runTest {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset("AAPL", "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("10.00"), Money.usd("10.00"), 0.0)) }
        val now = 1_700_000_000L
        val events = listOf(
            DividendEvent("AAPL", now - 90 * 86_400L, Money.usd("0.24")),
            DividendEvent("AAPL", now - 180 * 86_400L, Money.usd("0.23")),
            DividendEvent("AAPL", now - 270 * 86_400L, Money.usd("0.23")),
            DividendEvent("AAPL", now - 360 * 86_400L, Money.usd("0.22")),
        )
        repo.dividendEventsImpl = { _, _ -> events }
        val vm = dividendVm(repo, backgroundScope, now = now)
        runCurrent()

        val expectedRate = DividendMath.trailingAnnualPerShare(events, now)
        val info = assertNotNull(vm.state.value.dividendInfo)
        assertEquals(expectedRate, info.trailingAnnualRate)
        val expectedYield = expectedRate.amount.divide(BigDecimal.parseString("10.00"), MONEY_MATH).doubleValue(false)
        assertEquals(expectedYield, info.yieldFraction, 0.0001)
        assertEquals(events.sortedBy { it.exDateEpochSeconds }.map { it.amountPerShare }, info.recentAmounts)
        assertEquals(1, repo.requestedDividendEventSymbols.size)
    }

    /** (b) Crypto assets never fetch dividend events — `dividendInfo` degrades to nil without
     *  the repository ever being called. Mirrors
     *  AssetDetailViewModelTests.test_load_cryptoAsset_dividendInfoNilWithoutFetching. */
    @Test
    fun cryptoAssetDividendInfoNullWithoutFetching() = runTest {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset("BTC-USD", "Bitcoin", AssetKind.Crypto) }
        val vm = dividendVm(repo, backgroundScope, now = 1_700_000_000L, symbol = "BTC-USD")
        runCurrent()

        assertNull(vm.state.value.dividendInfo)
        assertEquals(0, repo.requestedDividendEventSymbols.size)
    }

    /** (c) Zero dividend events in the trailing-2-year window → nil (non-payer, card hidden).
     *  Mirrors AssetDetailViewModelTests.test_load_noEvents_dividendInfoNil. */
    @Test
    fun noEventsDividendInfoNull() = runTest {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset("AAPL", "Apple Inc.", AssetKind.Stock) }
        repo.dividendEventsImpl = { _, _ -> emptyList() }
        val vm = dividendVm(repo, backgroundScope, now = 1_700_000_000L)
        runCurrent()

        assertNull(vm.state.value.dividendInfo)
    }

    /** (d) A dividend-events fetch failure degrades to nil — never an error state — and leaves
     *  the rest of the detail load (quote/chart) unaffected. Mirrors
     *  AssetDetailViewModelTests.test_load_dividendFetchFailure_dividendInfoNilOtherStateUnaffected. */
    @Test
    fun dividendFetchFailureDividendInfoNullOtherStateUnaffected() = runTest {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset("AAPL", "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("5.25"), Money.usd("4.50"), 1.2)) }
        repo.historyImpl = { _, _ -> listOf(PricePoint(1, Money.usd("5.25"))) }
        repo.dividendEventsImpl = { _, _ -> throw QuoteError.Network("boom") }
        val vm = dividendVm(repo, backgroundScope, now = 1_700_000_000L)
        runCurrent()

        assertNull(vm.state.value.dividendInfo)
        assertEquals(false, vm.state.value.isLoadingChart)
        assertEquals("5.25", vm.state.value.amountText)
    }

    /** Regression: a payer whose last event within the fetch window is old enough that
     *  `lastEvent.exDate + cadenceInterval` lands before "now" must NOT surface a past date
     *  under the "Est." badge — `nextEstimatedExDateEpochSeconds` hides (null) while the rest
     *  of `DividendInfo` (rate/yield/recentAmounts) still shows. Mirrors
     *  AssetDetailViewModelTests.test_load_projectedNextExDateInPast_hidesDateButKeepsOtherFields. */
    @Test
    fun staleProjectionHidesDateKeepsOtherFields() = runTest {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset("AAPL", "Apple Inc.", AssetKind.Stock) }
        val now = 1_700_000_000L
        // Annual cadence (365-day gap), last event ~700 days ago: projected next ex-date =
        // lastEvent + 365d = now - 335d, i.e. still in the past relative to `now`.
        val events = listOf(
            DividendEvent("AAPL", now - 700 * 86_400L, Money.usd("0.20")),
            DividendEvent("AAPL", now - 1_065 * 86_400L, Money.usd("0.20")),
        )
        repo.dividendEventsImpl = { _, _ -> events }
        val vm = dividendVm(repo, backgroundScope, now = now)
        runCurrent()

        val info = assertNotNull(vm.state.value.dividendInfo)
        assertNull(info.nextEstimatedExDateEpochSeconds)
        assertEquals(events.sortedBy { it.exDateEpochSeconds }.map { it.amountPerShare }, info.recentAmounts)
    }
}
