package com.aptrade.shared.domain

/**
 * A candle series that includes indicator warm-up lookback bars ahead of the visible
 * window. [candles] spans lookback + visible; [visibleStartIndex] is the index of the first
 * candle that falls inside the plain visible window (`Timeframe.windowDurationSeconds`) —
 * i.e. `candles.drop(visibleStartIndex)` is exactly what a plain (non-indicator) chart would
 * show. Indicators (SMA/EMA/RSI/MACD/Bollinger) are computed over the FULL [candles] list so
 * their warm-up prefix is consumed by the lookback bars rather than eating into the visible
 * chart — by the time index [visibleStartIndex] is reached, every indicator with a period
 * under the lookback pad is already fully formed.
 */
data class ChartWindow(
    val candles: List<Candle>,
    val visibleStartIndex: Int,
)
