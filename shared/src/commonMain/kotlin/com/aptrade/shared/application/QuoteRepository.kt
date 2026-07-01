package com.aptrade.shared.application

import com.aptrade.shared.domain.Quote

interface QuoteRepository {
    suspend fun quotes(symbols: List<String>): List<Quote>
}
