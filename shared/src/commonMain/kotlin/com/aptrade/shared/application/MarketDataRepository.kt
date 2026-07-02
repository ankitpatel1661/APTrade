package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe

interface MarketDataRepository {
    suspend fun quotes(symbols: List<String>): List<Quote>
    suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint>
    suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle>
    suspend fun profile(symbol: String): Asset
}
