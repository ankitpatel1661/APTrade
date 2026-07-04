package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * Transcribed from `Sources/APTradeDomain/PortfolioPerformance.swift` (`performanceSeries`).
 *
 * ALL-PRICED GATE (kmp-portfolio-6b2, user-mandated; ADOPTED by macOS in 6b.3): the Swift
 * original once silently dropped any symbol that had no `lastClose` yet at a given union date,
 * so a leading window before a mixed-calendar symbol's first candle (e.g. crypto trading 24/7
 * alongside equities that only have market-hours candles) still emitted a point valuing only
 * the already-priced symbols — producing a valuation cliff the moment the late symbol's first
 * candle joined. This Kotlin implementation instead gates the curve: it emits nothing until
 * every symbol with any history is priced, so the curve starts flat at the first fully-priced
 * date. As of increment 6b.3 the Swift `performanceSeries` adopts this exact gate (reversing
 * the original macOS-first direction), so the two implementations are back in lockstep.
 */

/** One point on a reconstructed portfolio performance curve: the account's value and its
 *  unrealized P&L at a past date, valued against that date's market prices. */
data class PortfolioPerformancePoint(
    val epochSeconds: Long,
    /** Cash plus the market value of current holdings at this date. */
    val value: Money,
    /** Unrealized P&L of current holdings versus average cost at this date. */
    val pnl: Money,
)

/** Reconstructs a value / unrealized-P&L time series by valuing the *current* holdings
 *  against each symbol's historical closes, forward-filling each symbol's last known close
 *  at every date so symbols with different trading calendars (e.g. crypto vs. equities)
 *  stay aligned. Cash is treated as constant. Pure — callers supply the per-symbol
 *  histories; this does no networking. */
fun Portfolio.performanceSeries(histories: Map<String, List<PricePoint>>): List<PortfolioPerformancePoint> {
    if (positions.isEmpty()) return emptyList()
    val code = cash.currencyCode

    val sorted = mutableMapOf<String, List<PricePoint>>()
    val allDates = mutableSetOf<Long>()
    for (position in positions) {
        val points = (histories[position.asset.symbol] ?: emptyList()).sortedBy { it.epochSeconds }
        if (points.isEmpty()) continue
        sorted[position.asset.symbol] = points
        for (point in points) allDates.add(point.epochSeconds)
    }
    if (allDates.isEmpty()) return emptyList()

    val cursor = mutableMapOf<String, Int>()        // forward-fill pointer per symbol
    val lastClose = mutableMapOf<String, BigDecimal>() // last close seen at or before the date
    val result = mutableListOf<PortfolioPerformancePoint>()

    for (date in allDates.sorted()) {
        var holdings = BigDecimal.ZERO
        var pnl = BigDecimal.ZERO
        var allPriced = true
        for (position in positions) {
            val symbol = position.asset.symbol
            val points = sorted[symbol] ?: continue  // no history at all: excluded, doesn't gate
            var i = cursor[symbol] ?: 0
            while (i < points.size && points[i].epochSeconds <= date) {
                lastClose[symbol] = points[i].close.amount
                i += 1
            }
            cursor[symbol] = i
            val close = lastClose[symbol]
            if (close == null) {
                // Gate: this symbol has history but no close yet at this date. Per the
                // recorded divergence above, skip the whole date rather than valuing only
                // the already-priced symbols.
                allPriced = false
                continue
            }
            val quantity = position.quantity
            holdings += close * quantity
            pnl += (close - position.averageCost.amount) * quantity
        }
        if (!allPriced) continue
        result.add(
            PortfolioPerformancePoint(
                epochSeconds = date,
                value = Money(cash.amount + holdings, code),
                pnl = Money(pnl, code),
            ),
        )
    }
    return result
}
