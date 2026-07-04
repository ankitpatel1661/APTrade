package com.aptrade.shared.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertClose(expected: Double, actual: Double?, tol: Double = 1e-9) {
    assertTrue(actual != null && abs(expected - actual) < tol, "expected $expected got $actual")
}

class TechnicalIndicatorsTest {
    @Test fun smaAlignsAndAverages() {
        val out = TechnicalIndicators.sma(listOf(1.0, 2.0, 3.0, 4.0), period = 2)
        assertNull(out[0]); assertClose(1.5, out[1]); assertClose(2.5, out[2]); assertClose(3.5, out[3])
    }
    @Test fun smaPeriodLongerThanSeriesIsAllNull() {
        assertTrue(TechnicalIndicators.sma(listOf(1.0, 2.0), 5).all { it == null })
    }
    @Test fun emaSeedsWithSmaThenSmooths() {
        // period 3 → multiplier 0.5; seed at index 2 = SMA(2,2,2)=2; next = (6−2)*0.5+2 = 4
        val out = TechnicalIndicators.ema(listOf(2.0, 2.0, 2.0, 6.0), period = 3)
        assertNull(out[0]); assertNull(out[1]); assertClose(2.0, out[2]); assertClose(4.0, out[3])
    }
    @Test fun rsiAllGainsIs100() {
        val values = (1..16).map { it.toDouble() }
        val out = TechnicalIndicators.rsi(values, period = 14)
        assertNull(out[13]); assertClose(100.0, out[14]); assertClose(100.0, out[15])
    }
    @Test fun rsiBalancedGainsAndLossesIs50() {
        // 15 values alternating +1/−1: 14 deltas = 7 gains of 1, 7 losses of 1 → RS=1 → RSI=50
        val values = MutableList(15) { i -> if (i % 2 == 0) 10.0 else 11.0 }
        assertClose(50.0, TechnicalIndicators.rsi(values, period = 14)[14])
    }
    @Test fun vwapIsNullUntilVolumeThenCumulative() {
        val out = TechnicalIndicators.vwap(
            highs = listOf(12.0, 12.0, 24.0), lows = listOf(8.0, 8.0, 16.0),
            closes = listOf(10.0, 10.0, 20.0), volumes = listOf(0.0, 1.0, 3.0),
        )
        assertNull(out[0])                    // cumulative volume still 0
        assertClose(10.0, out[1])             // typical (12+8+10)/3 = 10
        assertClose(17.5, out[2])             // (10*1 + 20*3) / 4
    }
    @Test fun bollingerConstantSeriesCollapsesBands() {
        val out = TechnicalIndicators.bollingerBands(listOf(5.0, 5.0, 5.0), period = 2)
        assertNull(out[0])
        assertClose(5.0, out[1]?.middle); assertClose(5.0, out[1]?.upper); assertClose(5.0, out[1]?.lower)
    }
    @Test fun bollingerUsesPopulationStddev() {
        // window [1,3]: mean 2, POPULATION stddev 1 → upper 4, lower 0 (sample stddev √2 would give ≈4.828)
        val out = TechnicalIndicators.bollingerBands(listOf(1.0, 3.0), period = 2, multiplier = 2.0)
        assertClose(2.0, out[1]?.middle); assertClose(4.0, out[1]?.upper); assertClose(0.0, out[1]?.lower)
    }
    @Test fun macdConstantSeriesIsZeroEverywhereDefined() {
        val out = TechnicalIndicators.macd(List(40) { 7.0 })
        assertNull(out[24])                                   // slow EMA seeds at index 25
        assertClose(0.0, out[25]?.macd)
        assertNull(out[25]?.signal)                           // signal needs 9 macd values
        assertClose(0.0, out[33]?.signal); assertClose(0.0, out[33]?.histogram)
        assertClose(0.0, out[39]?.macd); assertClose(0.0, out[39]?.signal)
    }
    @Test fun macdNullityBoundaries() {
        val out = TechnicalIndicators.macd(List(40) { it.toDouble() })
        assertTrue((0..24).all { out[it] == null })
        assertTrue(out[25] != null && out[25]?.signal == null)
        assertTrue(out[32]?.signal == null && out[33]?.signal != null)
    }
}
