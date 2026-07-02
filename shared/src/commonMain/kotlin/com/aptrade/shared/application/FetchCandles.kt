package com.aptrade.shared.application

import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Timeframe
import kotlin.coroutines.cancellation.CancellationException

class FetchCandles(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String, timeframe: Timeframe): List<Candle> =
        repository.candles(symbol, timeframe)
}
