package com.aptrade.android.detail

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.android.FakePortfolioStore
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        repo: FakeMarketDataRepository,
        symbol: String = "AAPL",
        store: FakePortfolioStore = FakePortfolioStore(),
        now: () -> Long = { 1_700_000_000L },
    ) = DetailViewModel(
        symbol = symbol,
        fetchProfile = FetchProfile(repo),
        fetchHistory = FetchHistory(repo),
        fetchCandles = FetchCandles(repo),
        buyAsset = BuyAsset(repo, store),
        sellAsset = SellAsset(repo, store),
        nowEpochSeconds = now,
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

    @Test
    fun buyPersistsToStoreAndBumpsTransactionCount() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("300"), Money.usd("300"), 0.0)) }
        val store = FakePortfolioStore() // empty -> Portfolio.starting() ($100k cash)
        val viewModel = vm(repo, store = store)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.buy("10")
        dispatcher.scheduler.advanceUntilIdle()

        val s = viewModel.state.value
        assertNull(s.tradeError)
        assertEquals(1, s.transactionCount) // one appended transaction dismisses the sheet
        assertTrue(store.saveCallCount >= 1)
        // The persisted portfolio holds the position the Portfolio screen will re-read on return.
        assertEquals(1, store.saved!!.positions.size)
        assertEquals("AAPL", store.saved!!.positions.first().asset.symbol)
    }

    @Test
    fun buyUsesProfileNameAndKindForTheAsset() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "SPDR S&P 500", AssetKind.Etf) }
        repo.quotesImpl = { listOf(Quote("SPY", Money.usd("400"), Money.usd("400"), 0.0)) }
        val store = FakePortfolioStore()
        val viewModel = vm(repo, symbol = "SPY", store = store)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.buy("1")
        dispatcher.scheduler.advanceUntilIdle()

        val position = store.saved!!.positions.first()
        assertEquals("SPDR S&P 500", position.asset.name)
        assertEquals(AssetKind.Etf, position.asset.kind)
    }

    @Test
    fun buyInsufficientFundsSetsMappedTradeError() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("300"), Money.usd("300"), 0.0)) }
        val store = FakePortfolioStore()
        val viewModel = vm(repo, store = store)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.buy("1000") // 1000 * 300 = $300k > $100k cash
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Insufficient funds.", viewModel.state.value.tradeError)
        assertEquals(0, viewModel.state.value.transactionCount)
    }

    @Test
    fun buyInvalidQuantitySetsErrorWithoutHittingUseCase() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        val store = FakePortfolioStore()
        val viewModel = vm(repo, store = store)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.buy("-5")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Enter a valid quantity.", viewModel.state.value.tradeError)
        assertEquals(0, store.saveCallCount) // never reached the store-mediated use case
    }
}
