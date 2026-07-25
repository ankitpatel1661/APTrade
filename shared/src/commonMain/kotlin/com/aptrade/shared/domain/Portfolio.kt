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
    /** The cash this portfolio OPENED with — fixed at reset time, never moved by a trade.
     *
     *  RECORDED DIVERGENCE FROM SWIFT (M11.2 kickoff decision 4a.1, carry-notes §2.1): the Swift
     *  twin carries this field with NO reader — total return there derives from the equity
     *  curve's own first point, and the reset flow reads `AppSettings.defaultStartingCash`.
     *  Kotlin ports it AND gives it a real consumer: `PerformanceMetrics.sinceInceptionReturn`
     *  (see `FetchPerformanceReport`), which measures return against the balance the user
     *  actually chose rather than against whatever value the priced curve happened to open at.
     *  A $10k practice run and a $1M one must not both report return against the same baseline.
     *  This is a Swift BACKPORT CANDIDATE once the metric proves out here.
     *
     *  Defaults to [cash] so every existing three-argument construction site keeps its meaning:
     *  a portfolio built with no explicit opening balance opened at its current cash. */
    val startingCash: Money = cash,
) {
    fun positionFor(symbol: String): Position? = positions.firstOrNull { it.asset.symbol == symbol }

    /** Epoch-seconds of the EARLIEST transaction — the account's inception instant — or `null`
     *  when nothing has ever traded.
     *
     *  ONE named derivation, deliberately: `FetchPortfolioPerformance`'s `sinceInception` trim
     *  (M11.2 Task 7) and `GoalMath`'s account-age history floor (Task 6) both need exactly this
     *  signal, and the M11 carry-notes require they cannot drift apart. Every consumer calls
     *  here; nobody re-derives `transactions.minOfOrNull { it.epochSeconds }` locally. */
    fun inceptionEpochSeconds(): Long? = transactions.minOfOrNull { it.epochSeconds }

    fun buying(
        asset: Asset,
        quantity: BigDecimal,
        price: Money,
        epochSeconds: Long,
        id: String = generateTradeId(),
        pieId: String? = null,
        isDrip: Boolean = false,
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

        val txn = Transaction(id, asset.symbol, TradeSide.Buy, quantity, price, epochSeconds, pieId, isDrip)
        return Portfolio(
            cash = Money(cash.amount - cost, cash.currencyCode),
            positions = updated,
            transactions = transactions + txn,
            startingCash = startingCash,
        )
    }

    /** Credits a dividend payout: cash increases by `shares × amountPerShare` and a
     *  `Dividend` transaction is appended. Positions and cost basis are untouched — this is
     *  a cash event, not a trade. Pure. Transcribed from
     *  `Sources/APTradeDomain/Portfolio.swift:receivingDividend`. */
    fun receivingDividend(
        id: String = generateTradeId(),
        symbol: String,
        amountPerShare: Money,
        shares: BigDecimal,
        exDateEpochSeconds: Long,
    ): Portfolio {
        if (shares <= BigDecimal.ZERO || amountPerShare.amount <= BigDecimal.ZERO) {
            throw TradeError.InvalidQuantity
        }
        val credit = amountPerShare.amount * shares

        val txn = Transaction(id, symbol, TradeSide.Dividend, shares, amountPerShare, exDateEpochSeconds)
        return Portfolio(
            cash = Money(cash.amount + credit, cash.currencyCode),
            positions = positions,
            transactions = transactions + txn,
            startingCash = startingCash,
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
            startingCash = startingCash,
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
        /** The ONE hardcoded opening balance in the shared core (carry-notes §2.7 permits exactly
         *  two literals: this one, and `AppSettings.defaultStartingCash`'s default). Named rather
         *  than inlined so a repo grep for a stray balance finds every real call site instead of
         *  a scatter of bare `100000`s. */
        val DEFAULT_STARTING_CASH: Money = Money.usd("100000")

        /** A fresh paper portfolio opened at [cash], with [startingCash] recorded to match. */
        fun starting(cash: Money = DEFAULT_STARTING_CASH): Portfolio =
            Portfolio(cash = cash, startingCash = cash)
    }
}
