package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class QuoteTest {
    @Test
    fun holdsSymbolPricePreviousCloseAndChange() {
        val quote = Quote(
            symbol = "AAPL",
            price = Money.usd("229.35"),
            previousClose = Money.usd("227.45"),
            changePercent = 0.84,
        )
        assertEquals("AAPL", quote.symbol)
        assertEquals(Money.usd("229.35"), quote.price)
        assertEquals(Money.usd("227.45"), quote.previousClose)
        assertEquals(0.84, quote.changePercent)
    }
}
