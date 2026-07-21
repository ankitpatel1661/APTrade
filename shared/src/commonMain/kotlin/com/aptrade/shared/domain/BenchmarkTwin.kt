package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * Desktop-first design (increment 6c.5, user-mandated) — this is NOT a Swift transcription;
 * there is no equivalent in `Sources/APTradeDomain`. It answers a different question than
 * [performanceSeries]: "what if the same dollars, at the same moments, had instead gone into
 * a benchmark (e.g. SPY/QQQ/VTI)?" — a cash-flow replay twin, rather than a valuation of the
 * *current* holdings.
 *
 * Symmetry with [performanceSeries]: both hold cash constant at its CURRENT value across every
 * date in the series (neither backtest re-derives historical cash balances), and both
 * forward-fill a single price series across the requested dates. [performanceSeries] answers
 * "what are today's positions worth on past dates"; [benchmarkTwinSeries] answers "what would
 * today's cash flows be worth if they'd bought the benchmark instead" — the two are meant to be
 * plotted side by side as an apples-to-apples overlay.
 *
 * macOS adoption of this twin is NOT yet decided — flagged for increment 6b.3 (do not backport
 * into the Swift domain layer without that decision).
 */

/** Forward-fills the benchmark's close at time [t]: the close of the last [points] entry with
 *  `epochSeconds <= t`. If [t] precedes the first point, the FIRST point's close is used —
 *  a documented approximation for trades older than the fetched benchmark window (we don't
 *  have history further back to know the "true" benchmark price at that moment). [points]
 *  MUST be sorted ascending by `epochSeconds` and non-empty.
 */
private fun closeAt(points: List<PricePoint>, t: Long): BigDecimal {
    var result = points[0].close.amount
    for (point in points) {
        if (point.epochSeconds > t) break
        result = point.close.amount
    }
    return result
}

/**
 * Replays [transactions] as if every BUY/SELL dollar amount had instead traded a benchmark
 * priced by [benchmarkPoints], and values the resulting synthetic position against [cash]
 * (the CURRENT portfolio cash, held constant across every date — see class-level symmetry
 * note) at each date in [curveDates].
 *
 * Returns `null` when [benchmarkPoints] is empty (nothing to replay against). Otherwise the
 * result is EXACTLY aligned 1:1 with [curveDates] (same size, same order).
 *
 * Replay mechanics (processed in ascending `epochSeconds` order, units U starts at ZERO):
 * - BUY: `U += (price × quantity) / closeAt(t)` — the division uses [MONEY_MATH] (ionspin
 *   BigDecimal throws on non-terminating division without an explicit mode).
 * - SELL: `U -= minOf(U, proceeds / closeAt(t))`, clamped so U never goes negative. A twin
 *   that has already fallen to zero units cannot fund further withdrawals — this mirrors the
 *   real portfolio's inability to sell shares it doesn't hold, applied to the synthetic
 *   benchmark position instead.
 *
 * Twin value at date `d` = `cash.amount + U × closeAt(d)`, returned as [Money] in `cash`'s
 * currency.
 */
fun benchmarkTwinSeries(
    transactions: List<Transaction>,
    benchmarkPoints: List<PricePoint>,
    cash: Money,
    curveDates: List<Long>,
): List<Money>? {
    if (benchmarkPoints.isEmpty()) return null
    val sortedBenchmark = benchmarkPoints.sortedBy { it.epochSeconds }
    val sortedTransactions = transactions.sortedBy { it.epochSeconds }

    var units = BigDecimal.ZERO
    for (txn in sortedTransactions) {
        val close = closeAt(sortedBenchmark, txn.epochSeconds)
        val amount = txn.price.amount * txn.quantity
        when (txn.side) {
            TradeSide.Buy -> units += amount.divide(close, MONEY_MATH)
            TradeSide.Sell -> {
                val soldUnits = amount.divide(close, MONEY_MATH)
                units -= minOf(units, soldUnits)
            }
            // Units unchanged — a dividend is a cash credit, not a benchmark trade. No
            // cash-side action is needed either: unlike the buy/sell replay above, [cash]
            // here is the CURRENT portfolio cash (see class KDoc), which already reflects
            // every historical dividend credit. Mirrors the Swift equity-curve
            // reconstruction's exhaustive switch (PortfolioEquityCurve.swift's
            // `case .dividend: break` on the quantity side).
            TradeSide.Dividend -> Unit
        }
    }

    return curveDates.map { date ->
        val close = closeAt(sortedBenchmark, date)
        Money(cash.amount + units * close, cash.currencyCode)
    }
}
