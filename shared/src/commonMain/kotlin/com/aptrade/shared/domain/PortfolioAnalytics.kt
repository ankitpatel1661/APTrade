package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * Transcribed from `Sources/APTradeDomain/PortfolioAnalytics.swift` (`realizedPnL`) and
 * `Sources/APTradeApp/PortfolioViewModel.swift:104-125` (`allocationByHolding`,
 * `allocationByKind`). Semantics must not drift from the Swift originals.
 */

/** Total realized P&L across the entire transaction history, computed with the
 *  average-cost method. Derived from the transaction log rather than current positions,
 *  so gains/losses from fully-closed positions still count. Pure. */
val Portfolio.realizedPnL: Money
    get() {
        val quantity = mutableMapOf<String, BigDecimal>()
        val averageCost = mutableMapOf<String, BigDecimal>()
        var realized = BigDecimal.ZERO

        for (transaction in transactions.sortedBy { it.epochSeconds }) {
            val symbol = transaction.symbol
            val tradeQty = transaction.quantity
            when (transaction.side) {
                TradeSide.Buy -> {
                    val heldQty = quantity[symbol] ?: BigDecimal.ZERO
                    val heldAvg = averageCost[symbol] ?: BigDecimal.ZERO
                    val newQty = heldQty + tradeQty
                    averageCost[symbol] = if (newQty.isZero()) {
                        BigDecimal.ZERO
                    } else {
                        (heldAvg * heldQty + transaction.price.amount * tradeQty).divide(newQty, MONEY_MATH)
                    }
                    quantity[symbol] = newQty
                }
                TradeSide.Sell -> {
                    val heldAvg = averageCost[symbol] ?: transaction.price.amount
                    realized += (transaction.price.amount - heldAvg) * tradeQty
                    quantity[symbol] = (quantity[symbol] ?: BigDecimal.ZERO) - tradeQty
                }
                TradeSide.Dividend -> {
                    // Cash event only — does not affect quantity, cost basis, or realized
                    // P&L. Matches Sources/APTradeDomain/PortfolioAnalytics.swift's `case .dividend: break`.
                }
            }
        }
        return Money(realized, cash.currencyCode)
    }

/** One slice of the allocation breakdown — a holding or an asset class — with its share
 *  of total holdings value. [kind] is set only on by-class slices so a UI can localize the
 *  class name at render time; [label] stays the untranslated fallback (and IS the symbol on
 *  by-holding slices, where [kind] is null). */
data class AllocationSlice(
    val id: String,
    val label: String,
    val value: Double,
    val fraction: Double,
    val kind: AssetKind? = null,
)

private fun labelForKind(kind: AssetKind): String = when (kind) {
    AssetKind.Stock -> "Stocks"
    AssetKind.Etf -> "ETFs"
    AssetKind.Crypto -> "Crypto"
}

private fun marketValueOf(position: Position, quotes: Map<String, Quote>): BigDecimal {
    val price = quotes[position.asset.symbol]?.price?.amount ?: position.averageCost.amount
    return price * position.quantity
}

/** Each holding's share of total holdings value, largest first. */
fun Portfolio.allocationByHolding(quotes: Map<String, Quote>): List<AllocationSlice> {
    val total = valuation(quotes).holdingsValue.amount.doubleValue(false)
    return positions
        .map { position ->
            val value = marketValueOf(position, quotes).doubleValue(false)
            AllocationSlice(
                id = position.asset.symbol,
                label = position.asset.symbol,
                value = value,
                fraction = if (total == 0.0) 0.0 else value / total,
            )
        }
        .sortedByDescending { it.value }
}

/** Holdings value grouped by asset class (Stocks / ETFs / Crypto). */
fun Portfolio.allocationByKind(quotes: Map<String, Quote>): List<AllocationSlice> {
    val total = valuation(quotes).holdingsValue.amount.doubleValue(false)
    val sums = mutableMapOf<AssetKind, Double>()
    for (position in positions) {
        val value = marketValueOf(position, quotes).doubleValue(false)
        sums[position.asset.kind] = (sums[position.asset.kind] ?: 0.0) + value
    }
    return listOf(AssetKind.Stock, AssetKind.Etf, AssetKind.Crypto).mapNotNull { kind ->
        val value = sums[kind]
        if (value == null || value <= 0.0) {
            null
        } else {
            AllocationSlice(
                id = kind.name,
                label = labelForKind(kind),
                value = value,
                fraction = if (total == 0.0) 0.0 else value / total,
                kind = kind,
            )
        }
    }
}
