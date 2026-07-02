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

class FetchMarketQuotesTest {
    @Test
    fun returnsQuotesFromRepository() = runTest {
        val expected = listOf(Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.2))
        val useCase = FetchMarketQuotes(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = expected
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
                override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
                override suspend fun search(query: String): List<Asset> = emptyList()
            },
        )

        assertEquals(expected, useCase.execute(listOf("AAPL")))
    }
}
