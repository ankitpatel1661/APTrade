package com.aptrade.shared.infrastructure

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StubQuoteRepositoryTest {
    @Test
    fun returnsThreeHardcodedQuotes() = runTest {
        val quotes = StubQuoteRepository().quotes(listOf("AAPL"))
        assertEquals(3, quotes.size)
        assertEquals("AAPL", quotes.first().symbol)
    }
}
