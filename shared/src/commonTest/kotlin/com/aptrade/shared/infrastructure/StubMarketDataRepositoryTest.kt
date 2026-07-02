package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StubMarketDataRepositoryTest {
    private val repo = StubMarketDataRepository()

    @Test
    fun returnsThreeHardcodedQuotes() = runTest {
        val quotes = repo.quotes(listOf("AAPL"))
        assertEquals(3, quotes.size)
        assertEquals("AAPL", quotes.first().symbol)
    }

    @Test
    fun returnsHardcodedHistory() = runTest {
        val points = repo.history("AAPL", Timeframe.OneDay)
        assertEquals(2, points.size)
    }

    @Test
    fun returnsHardcodedCandles() = runTest {
        val candles = repo.candles("AAPL", Timeframe.OneDay)
        assertEquals(1, candles.size)
    }

    @Test
    fun returnsProfileWithGivenSymbol() = runTest {
        val asset = repo.profile("AAPL")
        assertEquals("AAPL", asset.symbol)
    }

    @Test
    fun returnsEmptySearchResults() = runTest {
        val assets = repo.search("AAPL")
        assertEquals(0, assets.size)
    }
}
