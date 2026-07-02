package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import kotlin.test.Test
import kotlin.test.assertEquals

class YahooSearchMapperTest {
    @Test
    fun mapsSupportedQuoteTypesWithNamePriority() {
        val response = YahooSearchResponse(
            quotes = listOf(
                YahooSearchResponse.Item(
                    symbol = "AAPL", shortname = "Apple Inc.",
                    longname = "Apple Incorporated", quoteType = "EQUITY",
                ),
                YahooSearchResponse.Item(
                    symbol = "SPY", shortname = null,
                    longname = "SPDR S&P 500", quoteType = "ETF",
                ),
                YahooSearchResponse.Item(
                    symbol = "BTC-USD", shortname = null,
                    longname = null, quoteType = "CRYPTOCURRENCY",
                ),
            ),
        )

        val assets = YahooSearchMapper.assets(response)

        assertEquals(3, assets.size)
        assertEquals(Asset("AAPL", "Apple Inc.", AssetKind.Stock), assets[0])
        assertEquals(Asset("SPY", "SPDR S&P 500", AssetKind.Etf), assets[1])
        assertEquals(Asset("BTC-USD", "BTC-USD", AssetKind.Crypto), assets[2])
    }

    @Test
    fun filtersOutUnsupportedQuoteTypes() {
        val response = YahooSearchResponse(
            quotes = listOf(
                YahooSearchResponse.Item(symbol = "^GSPC", shortname = "S&P 500", quoteType = "INDEX"),
                YahooSearchResponse.Item(symbol = "CLQ24", shortname = "Crude Oil", quoteType = "FUTURE"),
                YahooSearchResponse.Item(symbol = "EURUSD=X", shortname = "EUR/USD", quoteType = "CURRENCY"),
                YahooSearchResponse.Item(symbol = "AAPL240119C00190000", shortname = "AAPL Call", quoteType = "OPTION"),
                YahooSearchResponse.Item(symbol = "AAPL", shortname = "Apple Inc.", quoteType = "EQUITY"),
            ),
        )

        val assets = YahooSearchMapper.assets(response)

        assertEquals(listOf("AAPL"), assets.map { it.symbol })
    }

    @Test
    fun returnsEmptyListWhenQuotesIsNull() {
        assertEquals(emptyList(), YahooSearchMapper.assets(YahooSearchResponse(quotes = null)))
    }
}
