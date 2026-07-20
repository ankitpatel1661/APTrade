package com.aptrade.shared.infrastructure

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YahooDividendMapperTest {
    // Cells, unordered by exDate on purpose so ascending-sort behavior is exercised:
    //  - 1690000000 / 0.24   -> valid (later)
    //  - 1680000000 / 0.22   -> valid (earlier)
    //  - 1685000000 / (no amount) -> malformed, dropped
    //  - (no date)  / 0.20   -> malformed, dropped
    private val body = """
        {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
        "events":{"dividends":{
            "1690000000":{"amount":0.24,"date":1690000000},
            "1680000000":{"amount":0.22,"date":1680000000},
            "1685000000":{"date":1685000000},
            "1682000000":{"amount":0.20}
        }}}]}}
    """.trimIndent()

    @Test
    fun mapsAscendingByExDateDroppingMalformedCells() {
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val events = YahooQuoteMapper.dividends(parsed, fromEpochSeconds = 0L)

        assertEquals(2, events.size)
        assertEquals(1680000000L, events[0].exDateEpochSeconds)
        assertEquals(1690000000L, events[1].exDateEpochSeconds)
    }

    @Test
    fun mapsExactAmountsViaBigDecimal() {
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val events = YahooQuoteMapper.dividends(parsed, fromEpochSeconds = 0L)

        assertEquals(BigDecimal.parseString("0.22"), events[0].amountPerShare.amount)
        assertEquals("USD", events[0].amountPerShare.currencyCode)
        assertEquals(BigDecimal.parseString("0.24"), events[1].amountPerShare.amount)
        assertEquals("AAPL", events[0].symbol)
    }

    @Test
    fun fromEpochSecondsPastFirstEventFiltersIt() {
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val events = YahooQuoteMapper.dividends(parsed, fromEpochSeconds = 1681000000L)

        assertEquals(1, events.size)
        assertEquals(1690000000L, events[0].exDateEpochSeconds)
    }

    @Test
    fun noEventsBlockReturnsEmptyList() {
        val noEventsBody = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), noEventsBody)

        val events = YahooQuoteMapper.dividends(parsed, fromEpochSeconds = 0L)

        assertTrue(events.isEmpty())
    }

    @Test
    fun nonPositiveAmountIsDropped() {
        val zeroAndNegativeBody = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
            "events":{"dividends":{
                "1680000000":{"amount":0.00,"date":1680000000},
                "1690000000":{"amount":-0.10,"date":1690000000},
                "1700000000":{"amount":0.24,"date":1700000000}
            }}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), zeroAndNegativeBody)

        val events = YahooQuoteMapper.dividends(parsed, fromEpochSeconds = 0L)

        assertEquals(1, events.size)
        assertEquals(1700000000L, events[0].exDateEpochSeconds)
    }

    @Test
    fun defaultsCurrencyToUsdWhenMetaCurrencyMissing() {
        val noCurrencyBody = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL"},
            "events":{"dividends":{
                "1680000000":{"amount":0.24,"date":1680000000}
            }}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), noCurrencyBody)

        val events = YahooQuoteMapper.dividends(parsed, fromEpochSeconds = 0L)

        assertEquals("USD", events[0].amountPerShare.currencyCode)
    }
}
