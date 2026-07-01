package com.aptrade.shared.application

import com.aptrade.shared.domain.Quote

class FetchMarketQuotes(private val repository: QuoteRepository) {
    suspend fun execute(symbols: List<String>): List<Quote> =
        repository.quotes(symbols)
}
