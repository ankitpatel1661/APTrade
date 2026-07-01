package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Quote

object YahooQuoteMapper {
    fun quote(response: YahooChartResponse): Quote {
        val meta = response.chart.result?.firstOrNull()?.meta ?: throw QuoteError.NotFound
        val price = meta.regularMarketPrice ?: throw QuoteError.NotFound
        val currency = meta.currency ?: "USD"
        val prev = meta.chartPreviousClose
        // Percentage is a Double, not a money amount — computing it via doubleValue is fine
        // and avoids BigDecimal non-terminating-division pitfalls. Price stays exact.
        val changePercent = if (prev != null && prev.doubleValue(false) != 0.0) {
            (price.doubleValue(false) - prev.doubleValue(false)) / prev.doubleValue(false) * 100.0
        } else {
            0.0
        }
        return Quote(symbol = meta.symbol, price = Money(price, currency), changePercent = changePercent)
    }
}
