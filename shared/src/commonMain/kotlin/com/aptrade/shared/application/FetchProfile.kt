package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import kotlin.coroutines.cancellation.CancellationException

class FetchProfile(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String): Asset = repository.profile(symbol)
}
