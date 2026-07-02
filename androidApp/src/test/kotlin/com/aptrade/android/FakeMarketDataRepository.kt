package com.aptrade.android

import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe

class FakeMarketDataRepository : MarketDataRepository {
    var quotesImpl: suspend (List<String>) -> List<Quote> = { emptyList() }
    var historyImpl: suspend (String, Timeframe) -> List<PricePoint> = { _, _ -> emptyList() }
    var candlesImpl: suspend (String, Timeframe) -> List<Candle> = { _, _ -> emptyList() }
    var profileImpl: suspend (String) -> Asset = { Asset(it, it, AssetKind.Stock) }
    var searchImpl: suspend (String) -> List<Asset> = { emptyList() }

    var searchCallCount = 0
        private set

    override suspend fun quotes(symbols: List<String>): List<Quote> = quotesImpl(symbols)
    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> =
        historyImpl(symbol, timeframe)
    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> =
        candlesImpl(symbol, timeframe)
    override suspend fun profile(symbol: String): Asset = profileImpl(symbol)
    override suspend fun search(query: String): List<Asset> {
        searchCallCount++
        return searchImpl(query)
    }
}
