package com.aptrade.desktop.detail

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private fun vm(repo: FakeMarketDataRepository, scope: kotlinx.coroutines.CoroutineScope) =
    DetailViewModel("AAPL", FetchProfile(repo), FetchMarketQuotes(repo),
        FetchHistory(repo), FetchChartWindow(repo), scope)

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
}
