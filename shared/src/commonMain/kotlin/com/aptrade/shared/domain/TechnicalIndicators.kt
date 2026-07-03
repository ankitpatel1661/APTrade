package com.aptrade.shared.domain

import kotlin.math.sqrt

data class BollingerBand(val middle: Double, val upper: Double, val lower: Double)
data class MacdPoint(val macd: Double, val signal: Double?, val histogram: Double?)

/** Pure technical-indicator math, transcribed from Sources/APTradeDomain/TechnicalIndicators.swift.
 *  All outputs are index-aligned with the input series; null until the window/seed completes. */
object TechnicalIndicators {

    fun sma(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0 || values.size < period) return List(values.size) { null }
        val out = MutableList<Double?>(values.size) { null }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    fun ema(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0 || values.size < period) return List(values.size) { null }
        val out = MutableList<Double?>(values.size) { null }
        val multiplier = 2.0 / (period + 1)
        var prev = values.take(period).sum() / period          // seed: SMA of first `period`
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = (values[i] - prev) * multiplier + prev
            out[i] = prev
        }
        return out
    }

    fun rsi(values: List<Double>, period: Int = 14): List<Double?> {
        if (period <= 0 || values.size <= period) return List(values.size) { null }
        val out = MutableList<Double?>(values.size) { null }
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val delta = values[i] - values[i - 1]
            if (delta > 0) avgGain += delta else avgLoss -= delta
        }
        avgGain /= period
        avgLoss /= period
        out[period] = rsiValue(avgGain, avgLoss)
        for (i in period + 1 until values.size) {
            val delta = values[i] - values[i - 1]
            val gain = if (delta > 0) delta else 0.0
            val loss = if (delta < 0) -delta else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period   // Wilder's smoothing
            avgLoss = (avgLoss * (period - 1) + loss) / period
            out[i] = rsiValue(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double =
        if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)

    fun vwap(highs: List<Double>, lows: List<Double>, closes: List<Double>, volumes: List<Double>): List<Double?> {
        val n = minOf(highs.size, lows.size, closes.size, volumes.size)
        val out = MutableList<Double?>(n) { null }
        var cumTypVol = 0.0
        var cumVol = 0.0
        for (i in 0 until n) {
            val typical = (highs[i] + lows[i] + closes[i]) / 3.0
            cumTypVol += typical * volumes[i]
            cumVol += volumes[i]
            if (cumVol > 0.0) out[i] = cumTypVol / cumVol
        }
        return out
    }

    fun bollingerBands(values: List<Double>, period: Int = 20, multiplier: Double = 2.0): List<BollingerBand?> {
        if (period <= 0 || values.size < period) return List(values.size) { null }
        val middles = sma(values, period)
        val out = MutableList<BollingerBand?>(values.size) { null }
        for (i in period - 1 until values.size) {
            val mean = middles[i] ?: continue
            var sq = 0.0
            for (j in i - period + 1..i) {
                val d = values[j] - mean
                sq += d * d
            }
            val stddev = sqrt(sq / period)                       // POPULATION stddev (macOS parity)
            out[i] = BollingerBand(middle = mean, upper = mean + multiplier * stddev, lower = mean - multiplier * stddev)
        }
        return out
    }

    fun macd(values: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): List<MacdPoint?> {
        val emaFast = ema(values, fast)
        val emaSlow = ema(values, slow)
        val macdLine = values.indices.map { i ->
            val f = emaFast[i]
            val s = emaSlow[i]
            if (f != null && s != null) f - s else null
        }
        val defined = macdLine.filterNotNull()
        val signalOnDefined = ema(defined, signal)
        val out = MutableList<MacdPoint?>(values.size) { null }
        var d = 0
        for (i in values.indices) {
            val m = macdLine[i] ?: continue
            val sig = signalOnDefined.getOrNull(d)
            out[i] = MacdPoint(macd = m, signal = sig, histogram = sig?.let { m - it })
            d++
        }
        return out
    }
}
