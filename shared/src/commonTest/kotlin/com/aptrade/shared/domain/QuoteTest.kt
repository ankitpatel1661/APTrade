package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class QuoteTest {
    @Test
    fun holdsSymbolPriceAndChange() {
        val quote = Quote(symbol = "AAPL", price = Money.usd("229.35"), changePercent = 0.84)
        assertEquals("AAPL", quote.symbol)
        assertEquals(Money.usd("229.35"), quote.price)
        assertEquals(0.84, quote.changePercent)
    }
}
