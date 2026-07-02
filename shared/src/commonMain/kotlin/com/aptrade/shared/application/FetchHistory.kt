package com.aptrade.shared.application

import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Timeframe
import kotlin.coroutines.cancellation.CancellationException

class FetchHistory(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String, timeframe: Timeframe): List<PricePoint> =
        repository.history(symbol, timeframe)
}
