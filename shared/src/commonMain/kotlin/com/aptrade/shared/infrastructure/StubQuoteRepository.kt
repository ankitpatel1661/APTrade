package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteRepository
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Quote

class StubQuoteRepository : QuoteRepository {
    override suspend fun quotes(symbols: List<String>): List<Quote> = listOf(
        Quote("AAPL", Money.usd("229.35"), Money.usd("227.44"), 0.84),
        Quote("MSFT", Money.usd("430.16"), Money.usd("431.97"), -0.42),
        Quote("BTC", Money.usd("61234.00"), Money.usd("59944.98"), 2.15),
    )
}
