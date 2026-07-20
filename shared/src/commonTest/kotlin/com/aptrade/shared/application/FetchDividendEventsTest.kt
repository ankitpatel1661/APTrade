package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchDividendEventsTest {
    @Test
    fun returnsDividendEventsFromRepository() = runTest {
        val expected = listOf(
            DividendEvent(symbol = "AAPL", exDateEpochSeconds = 1000L, amountPerShare = Money.usd("0.24")),
            DividendEvent(symbol = "AAPL", exDateEpochSeconds = 2000L, amountPerShare = Money.usd("0.25")),
        )
        val useCase = FetchDividendEvents(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
                override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
                override suspend fun search(query: String): List<Asset> = emptyList()
                override suspend fun dividendEvents(symbol: String, fromEpochSeconds: Long): List<DividendEvent> {
                    assertEquals("AAPL", symbol)
                    assertEquals(500L, fromEpochSeconds)
                    return expected
                }
            },
        )

        assertEquals(expected, useCase.execute("AAPL", 500L))
    }

    @Test
    fun defaultRepositoryImplementationReturnsEmptyList() = runTest {
        val useCase = FetchDividendEvents(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
                override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
                override suspend fun search(query: String): List<Asset> = emptyList()
                // dividendEvents not overridden — relies on the interface default.
            },
        )

        assertEquals(emptyList(), useCase.execute("AAPL", 0L))
    }
}
