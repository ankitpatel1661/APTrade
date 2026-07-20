package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode

/** Division mode for portfolio math: mirrors Swift Decimal's 38-significant-digit plain
 *  rounding. ionspin BigDecimal THROWS on non-terminating division without a mode. */
val MONEY_MATH = DecimalMode(38, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)

/** A derived snapshot of a portfolio valued against current quotes. Pure. */
data class PortfolioValuation(
    val cash: Money,
    val holdingsValue: Money,
    val totalValue: Money,
    val unrealizedPnL: Money,
    val dayChange: Money,
)

/** A simulated (paper-trading) portfolio: virtual cash plus average-cost positions and a
 *  transaction log. All transitions are pure and return a new Portfolio. Transcribed from
 *  the Swift original (Sources/APTradeDomain/Portfolio.swift) — semantics must not drift. */
data class Portfolio(
    val cash: Money,
    val positions: List<Position> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
) {
    fun positionFor(symbol: String): Position? = positions.firstOrNull { it.asset.symbol == symbol }

    fun buying(
        asset: Asset,
        quantity: BigDecimal,
        price: Money,
        epochSeconds: Long,
        id: String = generateTradeId(),
        pieId: String? = null,
    ): Portfolio {
        if (quantity.isZero()) throw TradeError.InvalidQuantity
        val cost = price.amount * quantity
        if (cash.amount < cost) throw TradeError.InsufficientFunds

        val index = positions.indexOfFirst { it.asset.symbol == asset.symbol }
        val updated = positions.toMutableList()
        if (index >= 0) {
            val old = positions[index]
            val newQty = old.quantity + quantity
            val newAvg = (old.averageCost.amount * old.quantity + cost).divide(newQty, MONEY_MATH)
            updated[index] = Position(old.asset, newQty, Money(newAvg, price.currencyCode), old.realizedPnL)
        } else {
            updated += Position(asset, quantity, price, Money(BigDecimal.ZERO, price.currencyCode))
        }

        val txn = Transaction(id, asset.symbol, TradeSide.Buy, quantity, price, epochSeconds, pieId)
        return Portfolio(
            cash = Money(cash.amount - cost, cash.currencyCode),
            positions = updated,
            transactions = transactions + txn,
        )
    }

    fun selling(
        symbol: String,
        quantity: BigDecimal,
        price: Money,
        epochSeconds: Long,
        id: String = generateTradeId(),
        pieId: String? = null,
    ): Portfolio {
        if (quantity.isZero()) throw TradeError.InvalidQuantity
        val index = positions.indexOfFirst { it.asset.symbol == symbol }
        if (index < 0 || positions[index].quantity < quantity) throw TradeError.InsufficientShares

        val old = positions[index]
        val proceeds = price.amount * quantity
        val realizedDelta = (price.amount - old.averageCost.amount) * quantity
        val newQty = old.quantity - quantity

        val updated = positions.toMutableList()
        if (newQty.isZero()) {
            updated.removeAt(index)
        } else {
            updated[index] = Position(
                old.asset, newQty, old.averageCost,
                Money(old.realizedPnL.amount + realizedDelta, old.realizedPnL.currencyCode),
            )
        }

        val txn = Transaction(id, symbol, TradeSide.Sell, quantity, price, epochSeconds, pieId)
        return Portfolio(
            cash = Money(cash.amount + proceeds, cash.currencyCode),
            positions = updated,
            transactions = transactions + txn,
        )
    }

    /** Values every position against `quotes` (cost-basis fallback when a quote is
     *  missing). Day change per share is derived as price − previousClose. Pure. */
    fun valuation(quotes: Map<String, Quote>): PortfolioValuation {
        var holdings = BigDecimal.ZERO
        var unrealized = BigDecimal.ZERO
        var day = BigDecimal.ZERO
        for (position in positions) {
            val q = position.quantity
            val quote = quotes[position.asset.symbol]
            if (quote != null) {
                holdings += quote.price.amount * q
                unrealized += (quote.price.amount - position.averageCost.amount) * q
                day += (quote.price.amount - quote.previousClose.amount) * q
            } else {
                holdings += position.averageCost.amount * q   // cost-basis fallback
            }
        }
        val code = cash.currencyCode
        return PortfolioValuation(
            cash = cash,
            holdingsValue = Money(holdings, code),
            totalValue = Money(cash.amount + holdings, code),
            unrealizedPnL = Money(unrealized, code),
            dayChange = Money(day, code),
        )
    }

    companion object {
        /** The starting paper portfolio: $100,000 cash, no holdings. */
        fun starting(): Portfolio = Portfolio(Money.usd("100000"))
    }
}
