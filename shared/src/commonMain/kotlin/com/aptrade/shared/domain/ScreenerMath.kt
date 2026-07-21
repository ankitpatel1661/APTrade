package com.aptrade.shared.domain

/**
 * Builds per-symbol technical snapshots from daily candle history. Transcribed from
 * `Sources/APTradeDomain/ScreenerMath.swift` (the shipped M9.1 Swift/macOS reference) —
 * semantics must not drift. Built on top of [TechnicalIndicators] (sma/ema/rsi/
 * bollingerBands/macd) rather than reimplementing any of that math.
 *
 * `ScreenerSnapshotRow`/`ScreenerSnapshot` are kept as PLAIN data classes here — no
 * `@Serializable` — following house precedent: `Pie.kt` is a plain domain type with no
 * serialization annotations at all; its persistence DTO doesn't exist yet either and is
 * deferred to the store that needs it (`Pie.kt`'s own doc: "Kotlin has no decoder yet for
 * this type — FilePieStore (Task 6) is where that... gets a real DTO"). `PortfolioExport.kt`
 * confirms the shape that DTO takes: a separate `@Serializable ...Dto` class living next to
 * (not on) the domain type. Task 4 of this milestone (Kotlin screener stores) is where a
 * `ScreenerSnapshotRowDto`/`ScreenerSnapshotDto` pair should be introduced for persistence,
 * mirroring that pattern — this file stays serialization-free.
 */

/** One symbol's fully computed technical snapshot. All metrics nullable — insufficient
 *  history yields null, never a crash. */
data class ScreenerSnapshotRow(
    val symbol: String,
    val name: String,
    val close: Double,
    val dayChangePercent: Double?,
    val rsi14: Double?,
    val macd: Double?,
    val macdSignal: Double?,
    val macdHistogram: Double?,
    val sma50: Double?,
    val sma200: Double?,
    val ema20: Double?,
    /** (close - sma50)/sma50 x 100 */
    val pctVsSma50: Double?,
    val pctVsSma200: Double?,
    /** (close - lower)/(upper - lower). Null when upper == lower (zero-variance/flat-price
     *  window) -- the division would be 0/0, so this degrades to null rather than NaN. */
    val bollingerPercentB: Double?,
    /** (upper - lower)/middle. On a flat-price window upper == lower, so this is 0.0 exactly
     *  (a well-defined zero, not null) -- bandwidth of 0 correctly says "no band width." */
    val bollingerBandwidth: Double?,
    /** Max/min of the highs/lows across *whatever candles the caller passed in* --
     *  [ScreenerMath] does no date filtering of its own. The caller is responsible for
     *  supplying ~1 year of daily candles; if it passes more or less, these silently reflect
     *  that different window rather than a true rolling 52-week range. */
    val week52High: Double?,
    val week52Low: Double?,
    /** (high - close)/high x 100, >= 0 */
    val pctTo52wHigh: Double?,
    /** (close - low)/low x 100, >= 0 */
    val pctTo52wLow: Double?,
    /** today / mean(last 20 daily volumes). The 20-day window is INCLUSIVE of today's own
     *  volume (the trailing window ending today) -- chosen deliberately; preserve this
     *  convention in any further transcription, since inclusive vs. exclusive materially
     *  changes the value. Null when that average is 0. */
    val relativeVolume: Double?,
    /** histogram <= 0 yesterday, > 0 today */
    val macdCrossedUp: Boolean,
    val macdCrossedDown: Boolean,
    val goldenCross: Boolean,
    val deathCross: Boolean,
)

/** One scanner run's results across all symbols in scope. */
data class ScreenerSnapshot(
    /** `MarketCalendar` day-string (`yyyy-MM-dd`) of the scan, used to gate once-per-day
     *  scans. */
    val tradingDay: String,
    val scannedAtEpochSeconds: Long,
    val rows: List<ScreenerSnapshotRow>,
    /** Symbols whose data fetch failed and were excluded from [rows]. */
    val failedSymbols: List<String>,
)

object ScreenerMath {
    /** Builds one row from ascending daily candles (oldest first, last = today). Needs >= 2
     *  bars for day change, >= 201 for SMA-200/crosses; every metric degrades to null
     *  independently on short history -- never a crash. Cross flags need BOTH yesterday's
     *  and today's indicator values, computed here because only the scanner sees the full
     *  series.
     *
     *  Null only when [candles] is empty (no close at all). */
    fun snapshot(symbol: String, name: String, candles: List<Candle>): ScreenerSnapshotRow? {
        if (candles.isEmpty()) return null

        val closes = candles.map { it.close.amount.doubleValue(false) }
        val highs = candles.map { it.high.amount.doubleValue(false) }
        val lows = candles.map { it.low.amount.doubleValue(false) }
        val volumes = candles.map { it.volume }

        val close = closes.last()

        val dayChangePercent: Double? =
            if (closes.size >= 2 && closes[closes.size - 2] != 0.0) {
                (closes[closes.size - 1] - closes[closes.size - 2]) / closes[closes.size - 2] * 100
            } else {
                null
            }

        val rsiSeries = TechnicalIndicators.rsi(closes, period = 14)
        val rsi14 = rsiSeries.lastOrNull()

        val macdSeries = TechnicalIndicators.macd(closes)
        val macdLast = macdSeries.lastOrNull()
        val macd = macdLast?.macd
        val macdSignal = macdLast?.signal
        val macdHistogram = macdLast?.histogram

        val sma50Series = TechnicalIndicators.sma(closes, period = 50)
        val sma50 = sma50Series.lastOrNull()
        val sma200Series = TechnicalIndicators.sma(closes, period = 200)
        val sma200 = sma200Series.lastOrNull()
        val ema20Series = TechnicalIndicators.ema(closes, period = 20)
        val ema20 = ema20Series.lastOrNull()

        val pctVsSma50 = sma50?.let { if (it != 0.0) (close - it) / it * 100 else null }
        val pctVsSma200 = sma200?.let { if (it != 0.0) (close - it) / it * 100 else null }

        val bands = TechnicalIndicators.bollingerBands(closes)
        val lastBand = bands.lastOrNull()
        val upper = lastBand?.upper
        val middle = lastBand?.middle
        val lower = lastBand?.lower
        // u == l on a flat-price window (zero variance): (close-l)/(u-l) would be 0/0 -> null.
        val bollingerPercentB: Double? =
            if (upper != null && lower != null && upper != lower) (close - lower) / (upper - lower) else null
        // u == l on a flat-price window collapses this to 0.0/m == 0.0 (a real, defined
        // zero-width band), not null -- only missing inputs (null bands) yield null here.
        val bollingerBandwidth: Double? =
            if (upper != null && lower != null && middle != null && middle != 0.0) (upper - lower) / middle else null

        val week52High = highs.maxOrNull()
        val week52Low = lows.minOrNull()
        val pctTo52wHigh = week52High?.let { if (it != 0.0) ((it - close) / it) * 100 else null }
        val pctTo52wLow = week52Low?.let { if (it != 0.0) ((close - it) / it) * 100 else null }

        // INCLUSIVE 20-day window: takeLast(20) is the trailing window ending at (and
        // including) today, so today's own volume is counted in both the numerator and the
        // average it's divided by. This is deliberate, not incidental -- do not change to an
        // exclusive window without updating every consumer/port, since inclusive vs.
        // exclusive materially changes the resulting ratio.
        val relativeVolume: Double? = run {
            val window = volumes.takeLast(20)
            if (window.isEmpty()) return@run null
            val average = window.sum() / window.size
            if (average <= 0.0) null else volumes.last() / average
        }

        return ScreenerSnapshotRow(
            symbol = symbol,
            name = name,
            close = close,
            dayChangePercent = dayChangePercent,
            rsi14 = rsi14,
            macd = macd,
            macdSignal = macdSignal,
            macdHistogram = macdHistogram,
            sma50 = sma50,
            sma200 = sma200,
            ema20 = ema20,
            pctVsSma50 = pctVsSma50,
            pctVsSma200 = pctVsSma200,
            bollingerPercentB = bollingerPercentB,
            bollingerBandwidth = bollingerBandwidth,
            week52High = week52High,
            week52Low = week52Low,
            pctTo52wHigh = pctTo52wHigh,
            pctTo52wLow = pctTo52wLow,
            relativeVolume = relativeVolume,
            macdCrossedUp = crossedUp(macdSeries.map { it?.histogram }),
            macdCrossedDown = crossedDown(macdSeries.map { it?.histogram }),
            goldenCross = crossedUp(sma50Series, sma200Series),
            deathCross = crossedDown(sma50Series, sma200Series),
        )
    }

    /** True when [series] was <= 0 at index n-2 and > 0 at index n-1 (both non-null). */
    private fun crossedUp(series: List<Double?>): Boolean {
        if (series.size < 2) return false
        val yesterday = series[series.size - 2] ?: return false
        val today = series[series.size - 1] ?: return false
        return yesterday <= 0 && today > 0
    }

    /** True when [series] was >= 0 at index n-2 and < 0 at index n-1 (both non-null). */
    private fun crossedDown(series: List<Double?>): Boolean {
        if (series.size < 2) return false
        val yesterday = series[series.size - 2] ?: return false
        val today = series[series.size - 1] ?: return false
        return yesterday >= 0 && today < 0
    }

    /** True when [series] was <= [other] at index n-2 and > [other] at index n-1 (all four
     *  non-null). */
    private fun crossedUp(series: List<Double?>, other: List<Double?>): Boolean {
        if (series.size < 2 || other.size < 2) return false
        val ySelf = series[series.size - 2] ?: return false
        val yOther = other[other.size - 2] ?: return false
        val tSelf = series[series.size - 1] ?: return false
        val tOther = other[other.size - 1] ?: return false
        return ySelf <= yOther && tSelf > tOther
    }

    /** True when [series] was >= [other] at index n-2 and < [other] at index n-1 (all four
     *  non-null). */
    private fun crossedDown(series: List<Double?>, other: List<Double?>): Boolean {
        if (series.size < 2 || other.size < 2) return false
        val ySelf = series[series.size - 2] ?: return false
        val yOther = other[other.size - 2] ?: return false
        val tSelf = series[series.size - 1] ?: return false
        val tOther = other[other.size - 1] ?: return false
        return ySelf >= yOther && tSelf < tOther
    }
}
