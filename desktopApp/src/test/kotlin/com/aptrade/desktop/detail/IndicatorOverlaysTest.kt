package com.aptrade.desktop.detail

import com.aptrade.desktop.designkit.ChartCandle
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IndicatorOverlaysTest {

    private fun candle(close: Double) = ChartCandle(
        open = close, high = close + 0.5, low = close - 0.5, close = close, volume = 100.0,
    )

    // Mirrors FetchChartWindow's lookback pad (26 bars) followed by a visible window — proves
    // that when computeIndicators runs over the FULL series, SMA 20 / Bollinger 20 are already
    // fully formed (non-null) by the time the visible window begins at visibleStartIndex, which
    // is exactly the bug this task fixes (indicators used to only cover the right half of the
    // chart because their warm-up prefix ate into the visible range).
    @Test
    fun smaAndBollingerAreFullyFormedAtVisibleStartIndex() {
        val visibleStartIndex = 26
        val totalBars = visibleStartIndex + 40 // plenty of visible bars past the lookback pad
        val candles = (0 until totalBars).map { i -> candle(100.0 + i * 0.1) }

        val series = computeIndicators(candles, setOf(Indicator.Sma, Indicator.Bollinger))

        assertNotNull(series.sma[visibleStartIndex], "SMA 20 should be warmed up by visibleStartIndex")
        assertNotNull(series.bollinger[visibleStartIndex], "Bollinger 20 should be warmed up by visibleStartIndex")
    }

    @Test
    fun macdIsFullyFormedAtVisibleStartIndexGivenTheFullLookbackPad() {
        // MACD's slow EMA is period 26 — the exact pad size FetchChartWindow uses, so with the
        // full 26-bar lookback prefix, MACD should be non-null right at visibleStartIndex.
        val visibleStartIndex = 26
        val totalBars = visibleStartIndex + 40
        val candles = (0 until totalBars).map { i -> candle(100.0 + i * 0.1) }

        val series = computeIndicators(candles, setOf(Indicator.Macd))

        assertNotNull(series.macd[visibleStartIndex], "MACD should be warmed up by visibleStartIndex")
    }

    @Test
    fun withoutLookbackPrefixIndicatorsWouldStillBeNullAtWindowStart() {
        // Sanity check on the OLD (bug) behavior: computing SMA 20 over ONLY the visible
        // window (no lookback prefix) leaves the first ~19 bars null — this is the bug being
        // fixed. Confirms the fix is meaningful (not trivially true for any input).
        val visibleOnly = (0 until 40).map { i -> candle(100.0 + i * 0.1) }
        val series = computeIndicators(visibleOnly, setOf(Indicator.Sma))
        assertTrue(series.sma[0] == null, "expected null warm-up prefix without lookback")
    }
}
