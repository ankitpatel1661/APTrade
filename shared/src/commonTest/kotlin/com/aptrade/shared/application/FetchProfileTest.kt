package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchProfileTest {
    @Test
    fun returnsProfileFromRepository() = runTest {
        val expected = Asset(symbol = "AAPL", name = "Apple Inc.", kind = AssetKind.Stock)
        val useCase = FetchProfile(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
                override suspend fun profile(symbol: String): Asset {
                    assertEquals("AAPL", symbol)
                    return expected
                }
                override suspend fun search(query: String): List<Asset> = emptyList()
            },
        )

        assertEquals(expected, useCase.execute("AAPL"))
    }
}
