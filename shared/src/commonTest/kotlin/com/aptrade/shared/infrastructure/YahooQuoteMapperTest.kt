package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.AssetKind
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

    @Test
    fun mapsHistorySkippingNullClose() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
            "timestamp":[1782000000,1782086400,1782172800],
            "indicators":{"quote":[{"close":[299.24,null,298.01]}]}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val points = YahooQuoteMapper.history(parsed)

        assertEquals(2, points.size)
        assertEquals(1782000000L, points[0].epochSeconds)
        assertEquals(Money(BigDecimal.parseString("299.24"), "USD"), points[0].close)
        assertEquals(1782172800L, points[1].epochSeconds)
        assertEquals(Money(BigDecimal.parseString("298.01"), "USD"), points[1].close)
    }

    @Test
    fun mapsCandlesFallingBackToCloseForMissingOhlc() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
            "timestamp":[1782000000,1782086400],
            "indicators":{"quote":[{"open":[290.00,null],"high":[295.00,null],
            "low":[288.00,null],"close":[294.30,298.01],"volume":[1000.0,null]}]}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val candles = YahooQuoteMapper.candles(parsed)

        assertEquals(2, candles.size)
        assertEquals(Money(BigDecimal.parseString("290.00"), "USD"), candles[0].open)
        assertEquals(Money(BigDecimal.parseString("295.00"), "USD"), candles[0].high)
        assertEquals(Money(BigDecimal.parseString("288.00"), "USD"), candles[0].low)
        assertEquals(1000.0, candles[0].volume)
        // Second bar: open/high/low/volume missing -> fall back to close / 0.
        assertEquals(Money(BigDecimal.parseString("298.01"), "USD"), candles[1].open)
        assertEquals(Money(BigDecimal.parseString("298.01"), "USD"), candles[1].high)
        assertEquals(Money(BigDecimal.parseString("298.01"), "USD"), candles[1].low)
        assertEquals(0.0, candles[1].volume)
    }

    @Test
    fun mapsAssetFromInstrumentType() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "instrumentType":"EQUITY","longName":"Apple Inc."}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val asset = YahooQuoteMapper.asset(parsed)

        assertEquals("AAPL", asset.symbol)
        assertEquals("Apple Inc.", asset.name)
        assertEquals(AssetKind.Stock, asset.kind)
    }

    @Test
    fun mapsAssetKindFromCryptoSymbolSuffixWhenInstrumentTypeMissing() {
        val body = """{"chart":{"result":[{"meta":{"symbol":"BTC-USD","currency":"USD"}}]}}"""
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val asset = YahooQuoteMapper.asset(parsed)

        assertEquals(AssetKind.Crypto, asset.kind)
    }
}
