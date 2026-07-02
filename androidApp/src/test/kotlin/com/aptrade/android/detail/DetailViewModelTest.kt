package com.aptrade.android.detail

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(repo: FakeMarketDataRepository, symbol: String = "AAPL") = DetailViewModel(
        symbol = symbol,
        fetchProfile = FetchProfile(repo),
        fetchHistory = FetchHistory(repo),
        fetchCandles = FetchCandles(repo),
    )

    @Test
    fun loadsProfileHeaderOnInit() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Apple Inc.", viewModel.state.value.name)
        assertEquals("Stock", viewModel.state.value.kindLabel)
    }

    @Test
    fun profileErrorIsSurfacedNotSwallowed() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { throw QuoteError.NotFound }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.state.value.profileError)
    }

    @Test
    fun loadsLineValuesOnInit() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.historyImpl = { _, _ ->
            listOf(
                PricePoint(1_700_000_000L, Money.usd("100.50")),
                PricePoint(1_700_003_600L, Money.usd("101.25")),
            )
        }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(false, state.isLoadingChart)
        assertEquals(listOf(100.50, 101.25), state.lineValues)
    }

    @Test
    fun switchingToCandlesFetchesCandles() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.candlesImpl = { _, _ ->
            listOf(
                Candle(
                    epochSeconds = 1_700_000_000L,
                    open = Money.usd("100.00"), high = Money.usd("102.00"),
                    low = Money.usd("99.50"), close = Money.usd("101.50"), volume = 1000.0,
                ),
            )
        }
        val viewModel = vm(repo)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onModeChange(ChartMode.Candles)
        dispatcher.scheduler.advanceUntilIdle()

        val candles = viewModel.state.value.candles
        assertEquals(1, candles.size)
        assertEquals(CandleBar(100.00, 102.00, 99.50, 101.50), candles[0])
    }

    @Test
    fun timeframeChangeRefetches() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        var requestedTimeframe: Timeframe? = null
        repo.historyImpl = { _, timeframe ->
            requestedTimeframe = timeframe
            listOf(PricePoint(1_700_000_000L, Money.usd("100.50")))
        }
        val viewModel = vm(repo)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onTimeframeChange(Timeframe.OneMonth)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Timeframe.OneMonth, requestedTimeframe)
        assertEquals(Timeframe.OneMonth, viewModel.state.value.timeframe)
    }

    @Test
    fun chartErrorIsMapped() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.historyImpl = { _, _ -> throw QuoteError.RateLimited }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.state.value.chartError)
        assertEquals(false, viewModel.state.value.isLoadingChart)
    }
}
