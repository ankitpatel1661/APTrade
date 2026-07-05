package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe

interface MarketDataRepository {
    suspend fun quotes(symbols: List<String>): List<Quote>
    suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint>

    /**
     * RAW candles for [symbol]/[timeframe] — deliberately NOT clamped to
     * [Timeframe.windowDurationSeconds]. Implementations are expected to over-fetch (e.g. a
     * 1W chart may return a full month of hourly bars) so callers that need indicator
     * warm-up lookback (SMA/EMA/BB/MACD) have bars to compute over before the visible
     * window begins.
     *
     * Callers that only want the plain visible window (no lookback) MUST clamp the result
     * themselves — see [com.aptrade.shared.application.FetchCandles], which applies
     * `clampToWindow(candles, timeframe.windowDurationSeconds)`. Callers that also need a
     * warm-up prefix should use [com.aptrade.shared.application.FetchChartWindow] instead,
     * which clamps to a wider window and reports where the "visible" slice begins.
     *
     * This contract intentionally does NOT change this method's signature — only what it's
     * documented (and implemented) to return.
     */
    suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle>
    suspend fun profile(symbol: String): Asset
    suspend fun search(query: String): List<Asset>
}
