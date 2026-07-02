package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe

class StubMarketDataRepository : MarketDataRepository {
    override suspend fun quotes(symbols: List<String>): List<Quote> = listOf(
        Quote("AAPL", Money.usd("229.35"), Money.usd("227.44"), 0.84),
        Quote("MSFT", Money.usd("430.16"), Money.usd("431.97"), -0.42),
        Quote("BTC", Money.usd("61234.00"), Money.usd("59944.98"), 2.15),
    )

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = listOf(
        PricePoint(epochSeconds = 1_700_000_000L, close = Money.usd("229.35")),
        PricePoint(epochSeconds = 1_700_003_600L, close = Money.usd("230.10")),
    )

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = listOf(
        Candle(
            epochSeconds = 1_700_000_000L,
            open = Money.usd("228.50"), high = Money.usd("230.10"),
            low = Money.usd("228.00"), close = Money.usd("229.35"), volume = 1_000_000.0,
        ),
    )

    override suspend fun profile(symbol: String): Asset =
        Asset(symbol = symbol, name = symbol, kind = AssetKind.Stock)
}
