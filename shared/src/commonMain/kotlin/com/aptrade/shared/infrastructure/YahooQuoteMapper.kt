package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.ionspin.kotlin.bignum.decimal.BigDecimal

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
        val previousClose = prev?.let { Money(it, currency) } ?: Money(price, currency)
        return Quote(
            symbol = meta.symbol,
            price = Money(price, currency),
            previousClose = previousClose,
            changePercent = changePercent,
        )
    }

    fun history(response: YahooChartResponse): List<PricePoint> {
        val item = response.chart.result?.firstOrNull() ?: throw QuoteError.NotFound
        val stamps = item.timestamp ?: return emptyList()
        val closes = item.indicators?.quote?.firstOrNull()?.close ?: return emptyList()
        val currency = item.meta.currency ?: "USD"
        val points = mutableListOf<PricePoint>()
        for (i in stamps.indices) {
            if (i >= closes.size) break
            val close = closes[i] ?: continue
            points.add(PricePoint(epochSeconds = stamps[i], close = Money(close, currency)))
        }
        return points
    }

    fun candles(response: YahooChartResponse): List<Candle> {
        val item = response.chart.result?.firstOrNull() ?: throw QuoteError.NotFound
        val stamps = item.timestamp ?: return emptyList()
        val block = item.indicators?.quote?.firstOrNull() ?: return emptyList()
        val closes = block.close ?: return emptyList()
        val currency = item.meta.currency ?: "USD"
        val candles = mutableListOf<Candle>()
        for (i in stamps.indices) {
            if (i >= closes.size) break
            val close = closes[i] ?: continue
            // Fall back to close for any missing OHLC field so the bar still renders.
            val open = block.open?.getOrNull(i) ?: close
            val high = block.high?.getOrNull(i) ?: if (open.compareTo(close) >= 0) open else close
            val low = block.low?.getOrNull(i) ?: if (open.compareTo(close) <= 0) open else close
            val volume = block.volume?.getOrNull(i) ?: 0.0
            candles.add(
                Candle(
                    epochSeconds = stamps[i],
                    open = Money(open, currency), high = Money(high, currency),
                    low = Money(low, currency), close = Money(close, currency),
                    volume = volume,
                ),
            )
        }
        return candles
    }

    fun asset(response: YahooChartResponse): Asset {
        val meta = response.chart.result?.firstOrNull()?.meta ?: throw QuoteError.NotFound
        val name = meta.longName ?: meta.shortName ?: meta.symbol
        return Asset(symbol = meta.symbol, name = name, kind = kind(meta))
    }

    /**
     * Parses `events.dividends` into ascending-by-exDate events. Cells with a null
     * amount or date, or a non-positive amount, are dropped (never throw). Events
     * strictly before [fromEpochSeconds] are filtered out. Currency from meta ("USD"
     * fallback). Symbol from meta.symbol.
     */
    fun dividends(response: YahooChartResponse, fromEpochSeconds: Long): List<DividendEvent> {
        val item = response.chart.result?.firstOrNull() ?: throw QuoteError.NotFound
        val cells = item.events?.dividends ?: return emptyList()
        val currency = item.meta.currency ?: "USD"
        val symbol = item.meta.symbol
        return cells.values
            .mapNotNull { cell ->
                val amount = cell.amount ?: return@mapNotNull null
                val date = cell.date ?: return@mapNotNull null
                if (amount.compareTo(BigDecimal.ZERO) <= 0) return@mapNotNull null
                if (date < fromEpochSeconds) return@mapNotNull null
                DividendEvent(symbol = symbol, exDateEpochSeconds = date, amountPerShare = Money(amount, currency))
            }
            .sortedBy { it.exDateEpochSeconds }
    }

    private fun kind(meta: YahooChartResponse.Meta): AssetKind = when (meta.instrumentType?.uppercase()) {
        "ETF" -> AssetKind.Etf
        "CRYPTOCURRENCY" -> AssetKind.Crypto
        "EQUITY" -> AssetKind.Stock
        else -> if (meta.symbol.uppercase().endsWith("-USD")) AssetKind.Crypto else AssetKind.Stock
    }
}
