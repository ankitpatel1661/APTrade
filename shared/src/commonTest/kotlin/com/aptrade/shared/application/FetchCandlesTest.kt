package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchCandlesTest {
    @Test
    fun returnsCandlesFromRepository() = runTest {
        val expected = listOf(
            Candle(
                epochSeconds = 1000L,
                open = Money.usd("100.00"), high = Money.usd("101.00"),
                low = Money.usd("99.00"), close = Money.usd("100.50"), volume = 500.0,
            ),
        )
        val useCase = FetchCandles(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> {
                    assertEquals("AAPL", symbol)
                    assertEquals(Timeframe.OneMonth, timeframe)
                    return expected
                }
                override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
                override suspend fun search(query: String): List<Asset> = emptyList()
            },
        )

        assertEquals(expected, useCase.execute("AAPL", Timeframe.OneMonth))
    }

    // Moved from YahooMarketDataRepositoryTest: the repository now returns RAW (unclamped)
    // candles, so this use case is the layer responsible for clamping to the plain visible
    // window (windowDurationSeconds), anchored to the newest bar.
    @Test
    fun clampsRawCandlesToTheVisibleWindow() = runTest {
        fun candle(epochSeconds: Long) = Candle(
            epochSeconds = epochSeconds,
            open = Money.usd("1.00"), high = Money.usd("1.00"),
            low = Money.usd("1.00"), close = Money.usd("1.00"), volume = 0.0,
        )
        // OneDay's windowDurationSeconds is 86400s; three bars 1000s apart from the newest
        // (2000) all survive that window, matching clampToWindow's own contract.
        val raw = listOf(candle(1000), candle(1500), candle(2000))
        val useCase = FetchCandles(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = raw
                override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
                override suspend fun search(query: String): List<Asset> = emptyList()
            },
        )

        val result = useCase.execute("AAPL", Timeframe.OneDay)

        assertEquals(raw, result)
    }

    @Test
    fun clampDropsBarsOutsideTheVisibleWindow() = runTest {
        fun candle(epochSeconds: Long) = Candle(
            epochSeconds = epochSeconds,
            open = Money.usd("1.00"), high = Money.usd("1.00"),
            low = Money.usd("1.00"), close = Money.usd("1.00"), volume = 0.0,
        )
        // A raw candle far outside OneDay's 86400s window (anchored to the newest bar) must
        // be dropped by FetchCandles, proving the clamp actually moved here (not a no-op).
        val newest = 1_000_000L
        val stale = candle(newest - 200_000L) // well outside 86400s
        val fresh = candle(newest)
        val useCase = FetchCandles(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = listOf(stale, fresh)
                override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
                override suspend fun search(query: String): List<Asset> = emptyList()
            },
        )

        val result = useCase.execute("AAPL", Timeframe.OneDay)

        assertEquals(listOf(fresh), result)
    }
}
