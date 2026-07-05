package com.aptrade.shared.domain

/**
 * Collapses a performance series to (at most) one point per UTC calendar day, keeping each
 * day's LAST (max-epochSeconds) point as that day's closing value.
 *
 * Why: [performanceSeries] builds its curve on the UNION of every held symbol's candle
 * timestamps and forward-fills each symbol's last known close across that union grid. When a
 * portfolio mixes 24/7 crypto with market-hours equities, that union grid is dense with
 * crypto-only timestamps overnight and on weekends — dates where every equity position is
 * simply forward-filled at its last close. Plotted directly, those stretches render as long
 * flat segments (no equity ever "moves" overnight/weekends) rather than a clean day-over-day
 * curve. The same union dates back [com.aptrade.shared.application.FetchPerformanceReport]'s
 * SPY/QQQ/VTI benchmark twin (`curveDates = points.map { it.epochSeconds }`), so the twin
 * inherits the identical dense intraday/weekend grid and renders as a stair-stepped line
 * instead of a smooth one. Resampling to one point per day (this function) fixes both: the
 * portfolio curve loses its overnight/weekend flats, and the benchmark twin — which rides
 * these exact dates — loses its staircase for free.
 *
 * This performs NO trading-calendar filtering: weekend days that have a point are kept as
 * ordinary days (crypto trades on weekends, so a weekend closing value is real data, not
 * padding). It only collapses same-day duplicates down to that day's last value.
 *
 * Pure and deterministic — no clock access, no I/O. The UTC day bucket is computed from
 * `epochSeconds / 86_400` (integer division), matching the union-grid's epoch-seconds domain
 * used throughout [PortfolioPerformancePoint] and its callers.
 */
fun List<PortfolioPerformancePoint>.resampledDaily(): List<PortfolioPerformancePoint> {
    if (isEmpty()) return emptyList()
    return groupBy { it.epochSeconds / 86_400 }
        .values
        .map { pointsInDay -> pointsInDay.maxBy { it.epochSeconds } }
        .sortedBy { it.epochSeconds }
}
