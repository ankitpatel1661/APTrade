package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import kotlin.coroutines.cancellation.CancellationException

class FetchSearch(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(query: String): List<Asset> = repository.search(query)
}
