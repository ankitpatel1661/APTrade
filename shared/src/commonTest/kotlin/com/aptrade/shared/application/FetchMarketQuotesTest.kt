package com.aptrade.shared.application

import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Quote
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchMarketQuotesTest {
    @Test
    fun returnsQuotesFromRepository() = runTest {
        val expected = listOf(Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.2))
        val useCase = FetchMarketQuotes(
            object : QuoteRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = expected
            },
        )

        assertEquals(expected, useCase.execute(listOf("AAPL")))
    }
}
