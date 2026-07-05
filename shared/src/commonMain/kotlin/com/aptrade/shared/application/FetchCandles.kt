package com.aptrade.shared.application

import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.clampToWindow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Plain window-clamped candles: fetches the (deliberately over-fetched) raw candles from the
 * repository and clamps them to the exact visible window
 * (`timeframe.windowDurationSeconds`), anchored to the newest bar. This is the byte-preserved
 * behavior every existing consumer (desktop Line-mode indicator seeding, Android's candle
 * chart) relied on when the repository itself used to do this clamping internally.
 *
 * Callers that additionally need indicator warm-up lookback (so SMA/EMA/BB/MACD are fully
 * formed at the left edge of the visible window) should use [FetchChartWindow] instead.
 */
class FetchCandles(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String, timeframe: Timeframe): List<Candle> {
        val raw = repository.candles(symbol, timeframe)
        return clampToWindow(raw, timeframe.windowDurationSeconds) { it.epochSeconds }
    }
}
