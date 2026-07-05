package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.clampToWindow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchChartWindowTest {

    private fun candle(epochSeconds: Long) = Candle(
        epochSeconds = epochSeconds,
        open = Money.usd("1.00"), high = Money.usd("1.00"),
        low = Money.usd("1.00"), close = Money.usd("1.00"), volume = 0.0,
    )

    private fun repoReturning(candles: List<Candle>) = object : MarketDataRepository {
        override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
        override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
        override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = candles
        override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
        override suspend fun search(query: String): List<Asset> = emptyList()
    }

    @Test
    fun visibleSliceMatchesThePlainWindowClampedSet() = runTest {
        // OneDay: intervalSeconds=300s, windowDurationSeconds=86400s.
        // Build far more raw bars than the window + pad needs (every 300s over ~2 days).
        val timeframe = Timeframe.OneDay
        val last = 200_000L
        val raw = (0..600).map { i -> candle(last - i * timeframe.intervalSeconds) }.reversed()
        val useCase = FetchChartWindow(repoReturning(raw))

        val window = useCase.execute("AAPL", timeframe)

        assertTrue(window.visibleStartIndex > 0, "expected lookback bars ahead of the visible window")

        val plainWindow = clampToWindow(raw, timeframe.windowDurationSeconds) { it.epochSeconds }
        assertEquals(plainWindow, window.candles.drop(window.visibleStartIndex))
    }

    @Test
    fun tooFewBarsYieldsZeroVisibleStartIndex() = runTest {
        val timeframe = Timeframe.OneDay
        // Only 3 bars total — nowhere near enough to have lookback ahead of the window.
        val raw = listOf(candle(1000), candle(1300), candle(1600))
        val useCase = FetchChartWindow(repoReturning(raw))

        val window = useCase.execute("AAPL", timeframe)

        assertEquals(0, window.visibleStartIndex)
        assertEquals(raw, window.candles)
    }

    @Test
    fun padCoversAtLeast26Bars() = runTest {
        val timeframe = Timeframe.OneWeek // intervalSeconds=3600s, windowDurationSeconds=7*24*3600s
        val last = 10_000_000L
        // Plenty of bars: window (7 days hourly = 168 bars) + pad (26 bars) + extra margin.
        val raw = (0..400).map { i -> candle(last - i * timeframe.intervalSeconds) }.reversed()
        val useCase = FetchChartWindow(repoReturning(raw))

        val window = useCase.execute("AAPL", timeframe)

        // The lookback prefix (candles before visibleStartIndex) must span at least
        // 26 * intervalSeconds so warm-up periods up to 26 bars (MACD's 26 EMA) are covered.
        assertTrue(window.visibleStartIndex >= 26, "expected at least 26 lookback bars, got ${window.visibleStartIndex}")
        val lookbackSpan = window.candles[window.visibleStartIndex].epochSeconds - window.candles[0].epochSeconds
        assertTrue(lookbackSpan >= 26 * timeframe.intervalSeconds, "lookback span too small: $lookbackSpan")
    }

    @Test
    fun symbolAndTimeframeArePassedThrough() = runTest {
        var seenSymbol: String? = null
        var seenTimeframe: Timeframe? = null
        val repo = object : MarketDataRepository {
            override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
            override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
            override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> {
                seenSymbol = symbol
                seenTimeframe = timeframe
                return listOf(candle(1000))
            }
            override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
            override suspend fun search(query: String): List<Asset> = emptyList()
        }
        val useCase = FetchChartWindow(repo)

        useCase.execute("MSFT", Timeframe.OneMonth)

        assertEquals("MSFT", seenSymbol)
        assertEquals(Timeframe.OneMonth, seenTimeframe)
    }
}
