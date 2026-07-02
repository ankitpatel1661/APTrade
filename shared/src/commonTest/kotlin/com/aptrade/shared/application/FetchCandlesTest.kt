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
}
