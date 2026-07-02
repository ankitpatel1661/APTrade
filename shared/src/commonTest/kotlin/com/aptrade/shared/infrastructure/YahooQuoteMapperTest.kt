package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Money
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YahooQuoteMapperTest {
    @Test
    fun mapsMetaToQuoteWithExactPrice() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "regularMarketPrice":229.35,"chartPreviousClose":227.45}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val quote = YahooQuoteMapper.quote(parsed)

        assertEquals("AAPL", quote.symbol)
        assertEquals(Money(BigDecimal.parseString("229.35"), "USD"), quote.price)
        assertTrue(quote.changePercent > 0.0) // 229.35 > 227.45
    }

    @Test
    fun emptyResultThrowsNotFound() {
        val parsed = yahooJson.decodeFromString(
            YahooChartResponse.serializer(),
            """{"chart":{"result":[]}}""",
        )
        val ex = assertFailsWith<QuoteError> { YahooQuoteMapper.quote(parsed) }
        assertTrue(ex is QuoteError.NotFound)
    }

    @Test
    fun mapsPreviousCloseExactly() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "regularMarketPrice":229.35,"chartPreviousClose":227.45}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val quote = YahooQuoteMapper.quote(parsed)

        assertEquals(Money(BigDecimal.parseString("227.45"), "USD"), quote.previousClose)
    }

    @Test
    fun missingPreviousCloseDefaultsToPrice() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "regularMarketPrice":229.35}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val quote = YahooQuoteMapper.quote(parsed)

        assertEquals(quote.price, quote.previousClose)
        assertEquals(0.0, quote.changePercent)
    }
}
