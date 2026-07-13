package com.aptrade.android.detail

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.android.FakePortfolioStore
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeSide
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
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
        notifyOrderFill: suspend (TradeSide, String, String, String) -> Unit = { _, _, _, _ -> },
    ) = DetailViewModel(
        symbol = symbol,
        fetchProfile = FetchProfile(repo),
        fetchHistory = FetchHistory(repo),
        fetchChartWindow = FetchChartWindow(repo),
        fetchMarketQuotes = FetchMarketQuotes(repo),
        buyAsset = BuyAsset(repo, store, Mutex()),
        sellAsset = SellAsset(repo, store, Mutex()),
        nowEpochSeconds = now,
        notifyOrderFill = notifyOrderFill,
    )

    @Test
    fun loadsProfileHeaderOnInit() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Apple Inc.", viewModel.state.value.name)
        assertEquals(AssetKind.Stock, viewModel.state.value.kind)
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

        val state = viewModel.state.value
        assertEquals(1, state.candles.size)
        // FetchChartWindow (not the plain window-only FetchCandles) is now the sole candle
        // source, carrying volume through for VWAP — and a single-candle series has no
        // lookback ahead of it, so visibleStartIndex stays 0 (the full list IS the visible
        // list, matching FetchChartWindow's own documented degenerate case).
        assertEquals(CandleBar(100.00, 102.00, 99.50, 101.50, 1000.0), state.candles[0])
        assertEquals(0, state.visibleStartIndex)
        assertEquals(1, state.candleCloseTexts.size)
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

    // --- Task 5: kind-gate + quote pass-through -----------------------------------------------

    @Test
    fun cryptoKindIsResolvedBeforeTradeSoBuyNeverMisclassifiesAsStock() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Bitcoin", AssetKind.Crypto) }
        repo.quotesImpl = { listOf(Quote("BTC-USD", Money.usd("60000"), Money.usd("60000"), 0.0)) }
        val store = FakePortfolioStore()
        val viewModel = vm(repo, symbol = "BTC-USD", store = store)

        // Profile hasn't resolved yet: the gate must hold.
        assertEquals(false, viewModel.state.value.profileResolved)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.state.value.profileResolved)
        assertEquals(AssetKind.Crypto, viewModel.state.value.kind)

        viewModel.buy("0.1")
        dispatcher.scheduler.advanceUntilIdle()

        val position = store.saved!!.positions.first()
        assertEquals(AssetKind.Crypto, position.asset.kind) // NOT Stock
    }

    @Test
    fun profileResolvedGateIsFalseUntilProfileSucceeds() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        val viewModel = vm(repo)

        assertEquals(false, viewModel.state.value.profileResolved)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.state.value.profileResolved)
    }

    @Test
    fun profileResolvedGateIsTrueAfterProfileErrorToo() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { throw QuoteError.NotFound }
        val viewModel = vm(repo)

        assertEquals(false, viewModel.state.value.profileResolved)

        dispatcher.scheduler.advanceUntilIdle()

        // Resolved (error path) — the gate opens, and the Stock fallback now applies.
        assertEquals(true, viewModel.state.value.profileResolved)
        assertNotNull(viewModel.state.value.profileError)
    }

    @Test
    fun priceTextIsPopulatedFromTheLiveQuote() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("187.50"), Money.usd("185.00"), 1.35)) }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("$187.50", viewModel.state.value.priceText)
    }

    @Test
    fun quoteFailureLeavesTradingAliveWithNullPriceText() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { throw QuoteError.Network("timeout") }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.state.value.priceText)
        assertEquals(true, viewModel.state.value.profileResolved) // gate still opens normally
    }

    // --- Order-fill notifications (spec A2 — mirrors PortfolioViewModelTest's own cases) ---

    private data class Notified(
        val side: TradeSide,
        val symbol: String,
        val quantityText: String,
        val amountFormatted: String,
    )

    @Test
    fun successfulBuyFiresOrderFillNotification() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("100.00"), Money.usd("100.00"), 0.0)) }
        val store = FakePortfolioStore()
        val notified = mutableListOf<Notified>()
        val viewModel = vm(repo, store = store, notifyOrderFill = { side, symbol, qty, amount ->
            notified += Notified(side, symbol, qty, amount)
        })
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.buy("10")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, notified.size)
        assertEquals(TradeSide.Buy, notified.single().side)
        assertEquals("AAPL", notified.single().symbol)
        assertEquals("10", notified.single().quantityText)
        assertEquals("$1,000.00", notified.single().amountFormatted)
    }

    @Test
    fun successfulSellFiresOrderFillNotification() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("100.00"), Money.usd("100.00"), 0.0)) }
        val seeded = Portfolio(
            cash = Money.usd("99000"),
            positions = listOf(
                Position(
                    Asset("AAPL", "Apple Inc.", AssetKind.Stock),
                    BigDecimal.parseString("10"),
                    Money.usd("100.00"),
                    Money.usd("0"),
                ),
            ),
        )
        val store = FakePortfolioStore().apply { loadImpl = { seeded } }
        val notified = mutableListOf<Notified>()
        val viewModel = vm(repo, store = store, notifyOrderFill = { side, symbol, qty, amount ->
            notified += Notified(side, symbol, qty, amount)
        })
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.sell("4")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, notified.size)
        assertEquals(TradeSide.Sell, notified.single().side)
        assertEquals("AAPL", notified.single().symbol)
        assertEquals("4", notified.single().quantityText)
        assertEquals("$400.00", notified.single().amountFormatted)
    }

    @Test
    fun failedBuyNeverFiresOrderFillNotification() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("300"), Money.usd("300"), 0.0)) }
        val store = FakePortfolioStore()
        var notifyCount = 0
        val viewModel = vm(repo, store = store, notifyOrderFill = { _, _, _, _ -> notifyCount++ })
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.buy("1000") // 1000 * 300 = $300k > $100k cash
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Insufficient funds.", viewModel.state.value.tradeError)
        assertEquals(0, notifyCount)
    }

    // --- Indicator-active candle fetch (desktop parity — DetailViewModelTest.kt) -------------

    @Test
    fun firstIndicatorActivationFetchesCandlesExactlyOnce() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        val candleFetches = mutableListOf<Timeframe>()
        repo.candlesImpl = { _, tf ->
            candleFetches += tf
            listOf(Candle(1, Money.usd("1.00"), Money.usd("3.00"), Money.usd("0.50"), Money.usd("2.00")))
        }
        val viewModel = vm(repo)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onIndicatorsActiveChange(true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(Timeframe.OneDay), candleFetches)
        assertEquals(true, viewModel.state.value.indicatorsActive)
        assertEquals(1, viewModel.state.value.candles.size)
    }

    @Test
    fun candleModeTimeframeChangeFetchesCandlesExactlyOnce() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        val candleFetches = mutableListOf<Timeframe>()
        repo.candlesImpl = { _, tf ->
            candleFetches += tf
            listOf(Candle(1, Money.usd("1.00"), Money.usd("3.00"), Money.usd("0.50"), Money.usd("2.00")))
        }
        val viewModel = vm(repo)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onModeChange(ChartMode.Candles)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(Timeframe.OneDay), candleFetches)

        viewModel.onTimeframeChange(Timeframe.OneWeek)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(Timeframe.OneDay, Timeframe.OneWeek), candleFetches)
    }

    @Test
    fun reactivatingIndicatorsAfterTimeframeChangeRefetchesCandlesForNewTimeframe() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        val candleFetches = mutableListOf<Timeframe>()
        repo.candlesImpl = { _, tf ->
            candleFetches += tf
            listOf(Candle(1, Money.usd("1.00"), Money.usd("3.00"), Money.usd("0.50"), Money.usd("2.00")))
        }
        val viewModel = vm(repo)
        dispatcher.scheduler.advanceUntilIdle()

        // Activate: first fetch, seeded for the initial timeframe (OneDay).
        viewModel.onIndicatorsActiveChange(true)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(Timeframe.OneDay), candleFetches)

        // Deactivate: no fetch (candles just sit there, now a stale cache waiting to happen).
        viewModel.onIndicatorsActiveChange(false)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(Timeframe.OneDay), candleFetches)

        // Timeframe change while inactive: Line mode with no active indicator doesn't need
        // candles, so this must NOT fetch — the stale OneDay candles are left in state.
        viewModel.onTimeframeChange(Timeframe.OneMonth)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(Timeframe.OneDay), candleFetches)

        // Reactivate: must detect the cached candles were fetched for OneDay while the
        // selection is now OneMonth, and refetch for the NEW timeframe.
        viewModel.onIndicatorsActiveChange(true)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(Timeframe.OneDay, Timeframe.OneMonth), candleFetches)
    }

    @Test
    fun deactivatingIndicatorsNeverRefetches() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        val candleFetches = mutableListOf<Timeframe>()
        repo.candlesImpl = { _, tf -> candleFetches += tf; emptyList() }
        val viewModel = vm(repo)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onIndicatorsActiveChange(true)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.onIndicatorsActiveChange(false)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.indicatorsActive)
        assertEquals(1, candleFetches.size) // only the activation fetch, none on deactivation
    }

    @Test
    fun tradeNeverFailsWhenNotifierThrows() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("100.00"), Money.usd("100.00"), 0.0)) }
        val store = FakePortfolioStore()
        val viewModel = vm(repo, store = store, notifyOrderFill = { _, _, _, _ -> throw RuntimeException("notifier unavailable") })
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.buy("10")
        dispatcher.scheduler.advanceUntilIdle()

        val s = viewModel.state.value
        assertNull(s.tradeError)
        assertEquals(1, s.transactionCount)
    }
}
