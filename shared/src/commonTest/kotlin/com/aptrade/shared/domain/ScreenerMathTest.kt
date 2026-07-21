package com.aptrade.shared.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transcribed from `Tests/APTradeDomainTests/ScreenerMathTests.swift`, byte-value-equal
 * fixtures (9 Swift test functions total — the Swift file's own header notes the fixtures
 * were independently verified by two reviewers; do not "improve" them here).
 *
 * The Swift `test_codableRoundTrip_rowAndSnapshot` test is replaced by
 * [testEqualityRoundTrip_rowAndSnapshot]: `ScreenerSnapshotRow`/`ScreenerSnapshot` are kept
 * as plain (non-`@Serializable`) domain data classes per house precedent — see
 * `ScreenerMath.kt`'s header doc — so there is no JSON encoder/decoder at this layer to
 * round-trip through. The safety net the Swift test protects (value equality survives a
 * full field round trip) is still worth keeping, so this test exercises Kotlin data class
 * structural equality/`copy()` instead.
 */
class ScreenerMathTest {

    // MARK: - Helpers

    private fun makeCandle(offset: Int, close: Double, high: Double? = null, low: Double? = null, volume: Double = 0.0): Candle {
        return Candle(
            epochSeconds = offset.toLong() * 86_400L,
            open = Money.usd(close.toString()),
            high = Money.usd((high ?: close).toString()),
            low = Money.usd((low ?: close).toString()),
            close = Money.usd(close.toString()),
            volume = volume,
        )
    }

    private fun makeCandles(
        closes: List<Double>,
        highs: List<Double>? = null,
        lows: List<Double>? = null,
        volumes: List<Double>? = null,
    ): List<Candle> = closes.indices.map { i ->
        makeCandle(i, closes[i], highs?.get(i), lows?.get(i), volumes?.get(i) ?: 0.0)
    }

    private fun assertClose(expected: Double, actual: Double?, tol: Double = 1e-9) {
        assertTrue(actual != null && abs(expected - actual) < tol, "expected $expected got $actual")
    }

    // MARK: - (a) Small rising series -> exact RSI / EMA / Bollinger / day-change

    /** closes = 100...119 (20 bars, strictly +1/day). See Swift test's derivation comments
     *  for the exact hand-computed RSI/EMA/Bollinger/day-change values reproduced below. */
    @Test
    fun testA_risingSeries_exactRSIEmaBollingerAndDayChange() {
        val closes = (0 until 20).map { 100.0 + it.toDouble() }
        val candles = makeCandles(closes)

        val row = ScreenerMath.snapshot(symbol = "RISE", name = "Rising Co", candles = candles)
        assertNotNull(row)

        assertClose(119.0, row.close)
        assertClose(100.0, row.rsi14)
        assertClose(109.5, row.ema20)
        assertClose(0.911877235523957, row.bollingerPercentB)
        assertClose(0.210640412688051, row.bollingerBandwidth)
        assertClose(100.0 / 118.0, row.dayChangePercent)

        assertNull(row.sma50)
        assertNull(row.sma200)
        assertNull(row.macd)
        assertNull(row.macdSignal)
        assertNull(row.macdHistogram)
        assertNull(row.pctVsSma50)
        assertNull(row.pctVsSma200)
    }

    // MARK: - (b) Short history (5 bars) -> most metrics nil/false, close + day-change present

    /** closes = 100,101,102,103,104 (5 bars). Every long-window indicator degrades to null
     *  independently — none of them share state, so a 5-bar history simply starves all of
     *  them at once. */
    @Test
    fun testB_shortHistory_degradesGracefullyToNilAndFalse() {
        val closes = (0 until 5).map { 100.0 + it.toDouble() }
        val candles = makeCandles(closes)

        val row = ScreenerMath.snapshot(symbol = "SHRT", name = "Short Co", candles = candles)
        assertNotNull(row)

        assertClose(104.0, row.close)
        assertClose(100.0 / 103.0, row.dayChangePercent)

        assertNull(row.rsi14)
        assertNull(row.ema20)
        assertNull(row.sma50)
        assertNull(row.sma200)
        assertNull(row.macd)
        assertNull(row.macdSignal)
        assertNull(row.macdHistogram)
        assertNull(row.bollingerPercentB)
        assertNull(row.bollingerBandwidth)
        assertNull(row.pctVsSma50)
        assertNull(row.pctVsSma200)

        assertFalse(row.macdCrossedUp)
        assertFalse(row.macdCrossedDown)
        assertFalse(row.goldenCross)
        assertFalse(row.deathCross)
    }

    // MARK: - (c) macdCrossedUp: flips <=0->>0 on the final bar; control stays positive -> false

    /** Construction: closes decline by exactly 1.0/day for 40 days (100 -> 60), then one
     *  rally day (+2.0) flips the histogram positive -> macdCrossedUp == true. Control:
     *  continue the rally for 3 days instead of 1 -> already positive by the final bar ->
     *  macdCrossedUp == false. See Swift test's derivation comments for the exact histogram
     *  values verified by replaying the sma/ema/macd recurrences. */
    @Test
    fun testC_macdCrossedUp_flipsTrueOnFinalBar_falseWhenAlreadyPositive() {
        val declineToFlip = mutableListOf(100.0)
        repeat(40) { declineToFlip.add(declineToFlip.last() - 1.0) }
        declineToFlip.add(declineToFlip.last() + 2.0) // single rally day -> the flip bar

        val flipRow = ScreenerMath.snapshot(symbol = "FLIP", name = "Flip Co", candles = makeCandles(declineToFlip))
        assertNotNull(flipRow)
        assertTrue(flipRow.macdCrossedUp)
        assertFalse(flipRow.macdCrossedDown)

        val declineThenSustainedRally = mutableListOf(100.0)
        repeat(40) { declineThenSustainedRally.add(declineThenSustainedRally.last() - 1.0) }
        repeat(3) { declineThenSustainedRally.add(declineThenSustainedRally.last() + 2.0) }

        val controlRow = ScreenerMath.snapshot(symbol = "CTRL", name = "Control Co", candles = makeCandles(declineThenSustainedRally))
        assertNotNull(controlRow)
        assertFalse(controlRow.macdCrossedUp)
    }

    // MARK: - (d) goldenCross: sma50 crosses above sma200 on the final bar; control already above -> false

    /** Construction: closes decline by 0.5/day for 220 days from 200 (200 -> 90), then rally
     *  by 3.0/day. After 38 rally days (259 bars total) sma50 crosses above sma200 for the
     *  first time on the final bar -> goldenCross == true. Control: one further rally day
     *  (39 total) -> sma50 was already above sma200 on the prior bar -> goldenCross == false.
     *  See Swift test's derivation comments for the exact sma50/sma200 values. */
    @Test
    fun testD_goldenCross_flipsTrueOnFinalBar_falseWhenAlreadyAbove() {
        fun decliningThenRallying(rallyDays: Int): List<Double> {
            val closes = mutableListOf(200.0)
            repeat(220) { closes.add(closes.last() - 0.5) }
            repeat(rallyDays) { closes.add(closes.last() + 3.0) }
            return closes
        }

        val flipCloses = decliningThenRallying(rallyDays = 38)
        val flipRow = ScreenerMath.snapshot(symbol = "GOLD", name = "Golden Co", candles = makeCandles(flipCloses))
        assertNotNull(flipRow)
        assertTrue(flipRow.goldenCross)
        assertFalse(flipRow.deathCross)

        val controlCloses = decliningThenRallying(rallyDays = 39)
        val controlRow = ScreenerMath.snapshot(symbol = "GCTL", name = "Golden Control Co", candles = makeCandles(controlCloses))
        assertNotNull(controlRow)
        assertFalse(controlRow.goldenCross)
    }

    // MARK: - (e) 52-week distance percentages, exact

    /** 5 candles; week52High/Low are the max/min of *all* highs/lows in the provided series
     *  (the caller is expected to hand in ~1y of daily candles; ScreenerMath just reduces
     *  over the whole thing). highs=[150,200,160,170,155] -> week52High=200;
     *  lows=[120,130,100,140,145] -> week52Low=100; close(last)=150. */
    @Test
    fun testE_52WeekDistancePercentages_exact() {
        val closes = listOf(140.0, 180.0, 110.0, 155.0, 150.0)
        val highs = listOf(150.0, 200.0, 160.0, 170.0, 155.0)
        val lows = listOf(120.0, 130.0, 100.0, 140.0, 145.0)
        val candles = makeCandles(closes, highs, lows)

        val row = ScreenerMath.snapshot(symbol = "WK52", name = "52 Week Co", candles = candles)
        assertNotNull(row)

        assertClose(200.0, row.week52High)
        assertClose(100.0, row.week52Low)
        assertClose(25.0, row.pctTo52wHigh)
        assertClose(50.0, row.pctTo52wLow)
    }

    // MARK: - (f) relativeVolume = today / mean(last 20), exact; nil when volumes all 0

    /** 20 candles: the first 19 days trade 100 shares, today trades 200.
     *  mean(last 20) = (19*100 + 200)/20 = 105; relativeVolume = 200/105 = 40/21. */
    @Test
    fun testF_relativeVolume_exactAndNilWhenVolumesAllZero() {
        val closes = List(20) { 50.0 }
        val volumes = List(19) { 100.0 } + listOf(200.0)

        val row = ScreenerMath.snapshot(symbol = "VOL", name = "Volume Co", candles = makeCandles(closes, volumes = volumes))
        assertNotNull(row)
        assertClose(40.0 / 21.0, row.relativeVolume)

        val zeroVolumeCandles = makeCandles(closes, volumes = List(20) { 0.0 })
        val zeroRow = ScreenerMath.snapshot(symbol = "ZERO", name = "Zero Volume Co", candles = zeroVolumeCandles)
        assertNotNull(zeroRow)
        assertNull(zeroRow.relativeVolume)
    }

    // MARK: - (g) Empty candles -> nil

    @Test
    fun testG_emptyCandles_returnsNil() {
        val row = ScreenerMath.snapshot(symbol = "NONE", name = "No Data Co", candles = emptyList())
        assertNull(row)
    }

    // MARK: - Bonus: flat-price Bollinger (reviewer-requested, not one of the 7 lettered scenarios)

    /** 20 bars, all closes == 100 (zero variance). middle=100, stddev=0, so upper==lower==100.
     *  bollingerPercentB's guard (upper != lower) trips -> null (avoids the 0/0 that would
     *  otherwise produce NaN). bollingerBandwidth has no such guard against a *zero* result
     *  -- only against null inputs -- so (upper-lower)/middle = 0/100 = 0.0 exactly: a real,
     *  well-defined "no band width" answer, not a degraded/missing value. */
    @Test
    fun testFlatPriceSeries_bollingerPercentBNil_bandwidthZero() {
        val closes = List(20) { 100.0 }
        val row = ScreenerMath.snapshot(symbol = "FLAT", name = "Flat Co", candles = makeCandles(closes))
        assertNotNull(row)
        assertNull(row.bollingerPercentB)
        assertClose(0.0, row.bollingerBandwidth)
    }

    // MARK: - Bonus: equality round trip (not one of the 7 lettered scenarios, cheap safety net)

    @Test
    fun testEqualityRoundTrip_rowAndSnapshot() {
        val candles = makeCandles((0 until 20).map { 100.0 + it.toDouble() })
        val row = ScreenerMath.snapshot(symbol = "RT", name = "Round Trip Co", candles = candles)
        assertNotNull(row)

        val snapshot = ScreenerSnapshot(
            tradingDay = "2026-07-20",
            scannedAtEpochSeconds = 1_753_000_000L,
            rows = listOf(row),
            failedSymbols = listOf("BAD"),
        )

        assertEquals(row, row.copy())
        assertEquals(snapshot, snapshot.copy())
        assertEquals(row, snapshot.rows.first())
    }
}
