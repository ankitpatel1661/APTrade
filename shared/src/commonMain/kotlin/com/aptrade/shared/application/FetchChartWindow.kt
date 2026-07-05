package com.aptrade.shared.application

import com.aptrade.shared.domain.ChartWindow
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.clampToWindow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Candles clamped to the visible window PLUS an indicator warm-up lookback pad, so
 * SMA/EMA/RSI/MACD/Bollinger (computed over the full returned series) are already fully
 * formed by the time the visible window begins — fixing indicators only rendering across
 * the right half of the chart (their null warm-up prefix used to eat into the visible range
 * because [FetchCandles] only ever returned the bare visible window).
 *
 * `PadSeconds = 26 * timeframe.intervalSeconds` — 26 bars covers every indicator period used
 * on the chart (MACD's slow EMA is 26; SMA/BB are 20; RSI is 14), with intervalSeconds
 * matching the bar spacing Yahoo actually returns for the timeframe (yahooInterval).
 *
 * [ChartWindow.visibleStartIndex] is the count of clamped candles older than the visible
 * window (i.e. the first `windowDurationSeconds` candles from the newest bar begin at this
 * index); it is 0 when the raw series doesn't have enough lookback bars ahead of the visible
 * window (e.g. right after a symbol's IPO, or a mocked/test repository with few bars) — in
 * that case the full series IS the visible series, same as [FetchCandles].
 */
class FetchChartWindow(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String, timeframe: Timeframe): ChartWindow {
        val raw = repository.candles(symbol, timeframe)
        val padSeconds = PadBars * timeframe.intervalSeconds
        val clamped = clampToWindow(raw, timeframe.windowDurationSeconds + padSeconds) { it.epochSeconds }

        val lastEpoch = clamped.maxOfOrNull { it.epochSeconds }
        val visibleStartIndex = if (lastEpoch == null) {
            0
        } else {
            val visibleCutoff = lastEpoch - timeframe.windowDurationSeconds
            clamped.count { it.epochSeconds < visibleCutoff }
        }

        return ChartWindow(candles = clamped, visibleStartIndex = visibleStartIndex)
    }

    private companion object {
        /** Bars of lookback pad — covers MACD's 26-period slow EMA (the longest indicator
         *  warm-up used on the chart). */
        const val PadBars = 26L
    }
}
