package com.aptrade.shared.application

import com.aptrade.shared.domain.Quote
import kotlin.coroutines.cancellation.CancellationException

class FetchMarketQuotes(private val repository: QuoteRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbols: List<String>): List<Quote> =
        repository.quotes(symbols)
}
