package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * Transcribed from `Sources/APTradeDomain/PortfolioPerformance.swift` (`performanceSeries`).
 * Semantics must not drift from the Swift original.
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
        var priced = false
        for (position in positions) {
            val symbol = position.asset.symbol
            val points = sorted[symbol] ?: continue
            var i = cursor[symbol] ?: 0
            while (i < points.size && points[i].epochSeconds <= date) {
                lastClose[symbol] = points[i].close.amount
                i += 1
            }
            cursor[symbol] = i
            val close = lastClose[symbol] ?: continue  // no data yet at this date
            val quantity = position.quantity
            holdings += close * quantity
            pnl += (close - position.averageCost.amount) * quantity
            priced = true
        }
        if (!priced) continue
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
