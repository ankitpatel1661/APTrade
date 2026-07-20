import Foundation

/// A derived snapshot of a portfolio valued against a set of current quotes. Pure.
public struct PortfolioValuation: Equatable, Sendable {
    public let cash: Money
    public let holdingsValue: Money
    public let totalValue: Money
    public let unrealizedPnL: Money
    public let dayChange: Money

    public init(cash: Money, holdingsValue: Money, totalValue: Money,
                unrealizedPnL: Money, dayChange: Money) {
        self.cash = cash
        self.holdingsValue = holdingsValue
        self.totalValue = totalValue
        self.unrealizedPnL = unrealizedPnL
        self.dayChange = dayChange
    }
}

/// A simulated (paper-trading) portfolio: virtual cash plus average-cost positions and
/// a transaction log. All transitions are pure and return a new `Portfolio`.
public struct Portfolio: Equatable, Codable, Sendable {
    public let cash: Money
    public let positions: [Position]
    public let transactions: [Transaction]

    public init(cash: Money, positions: [Position] = [], transactions: [Transaction] = []) {
        self.cash = cash
        self.positions = positions
        self.transactions = transactions
    }

    /// The starting paper portfolio: $100,000 cash, no holdings.
    public static func starting(cash: Money = Money(amount: 100_000)) -> Portfolio {
        Portfolio(cash: cash)
    }

    public func position(for symbol: String) -> Position? {
        positions.first { $0.asset.symbol == symbol }
    }

    public func buying(_ asset: Asset, quantity: Quantity, at price: Money,
                       on date: Date = Date(), pieId: String? = nil, isDrip: Bool = false) throws -> Portfolio {
        guard !quantity.isZero else { throw TradeError.invalidQuantity }
        let cost = price.amount * quantity.amount
        guard cash.amount >= cost else { throw TradeError.insufficientFunds }

        var updated = positions
        if let index = positions.firstIndex(where: { $0.asset.symbol == asset.symbol }) {
            let old = positions[index]
            let newQty = old.quantity.amount + quantity.amount
            let newAvg = (old.averageCost.amount * old.quantity.amount + cost) / newQty
            updated[index] = Position(
                asset: old.asset,
                quantity: Quantity(newQty),
                averageCost: Money(amount: newAvg, currencyCode: price.currencyCode),
                realizedPnL: old.realizedPnL
            )
        } else {
            updated.append(Position(
                asset: asset,
                quantity: quantity,
                averageCost: price,
                realizedPnL: Money(amount: 0, currencyCode: price.currencyCode)
            ))
        }

        let txn = Transaction(symbol: asset.symbol, side: .buy,
                              quantity: quantity, price: price, date: date, pieId: pieId, isDrip: isDrip)
        return Portfolio(
            cash: Money(amount: cash.amount - cost, currencyCode: cash.currencyCode),
            positions: updated,
            transactions: transactions + [txn]
        )
    }

    /// Credits a dividend payout: cash increases by `shares × amountPerShare` and a
    /// `.dividend` transaction is appended. Positions and cost basis are untouched —
    /// this is a cash event, not a trade. Pure.
    public func receivingDividend(_ symbol: String, amountPerShare: Money,
                                  shares: Quantity, on exDate: Date) throws -> Portfolio {
        guard !shares.isZero, amountPerShare.amount > 0 else { throw TradeError.invalidQuantity }
        let credit = amountPerShare.amount * shares.amount

        let txn = Transaction(symbol: symbol, side: .dividend,
                              quantity: shares, price: amountPerShare, date: exDate)
        return Portfolio(
            cash: Money(amount: cash.amount + credit, currencyCode: cash.currencyCode),
            positions: positions,
            transactions: transactions + [txn]
        )
    }

    public func selling(_ symbol: String, quantity: Quantity, at price: Money,
                        on date: Date = Date(), pieId: String? = nil) throws -> Portfolio {
        guard !quantity.isZero else { throw TradeError.invalidQuantity }
        guard let index = positions.firstIndex(where: { $0.asset.symbol == symbol }),
              positions[index].quantity.amount >= quantity.amount else {
            throw TradeError.insufficientShares
        }

        let old = positions[index]
        let proceeds = price.amount * quantity.amount
        let realizedDelta = (price.amount - old.averageCost.amount) * quantity.amount
        let newQty = old.quantity.amount - quantity.amount

        var updated = positions
        if newQty == 0 {
            updated.remove(at: index)
        } else {
            updated[index] = Position(
                asset: old.asset,
                quantity: Quantity(newQty),
                averageCost: old.averageCost,
                realizedPnL: Money(amount: old.realizedPnL.amount + realizedDelta,
                                   currencyCode: old.realizedPnL.currencyCode)
            )
        }

        let txn = Transaction(symbol: symbol, side: .sell,
                              quantity: quantity, price: price, date: date, pieId: pieId)
        return Portfolio(
            cash: Money(amount: cash.amount + proceeds, currencyCode: cash.currencyCode),
            positions: updated,
            transactions: transactions + [txn]
        )
    }

    /// Values every position against `quotes` (falling back to cost basis when a quote
    /// is missing) and rolls them into account totals. Pure.
    public func valuation(quotes: [String: Quote]) -> PortfolioValuation {
        var holdings = Decimal(0)
        var unrealized = Decimal(0)
        var day = Decimal(0)
        for position in positions {
            let q = position.quantity.amount
            if let quote = quotes[position.asset.symbol] {
                holdings += quote.price.amount * q
                unrealized += (quote.price.amount - position.averageCost.amount) * q
                day += quote.change.amount * q
            } else {
                holdings += position.averageCost.amount * q   // cost-basis fallback
            }
        }
        let code = cash.currencyCode
        return PortfolioValuation(
            cash: cash,
            holdingsValue: Money(amount: holdings, currencyCode: code),
            totalValue: Money(amount: cash.amount + holdings, currencyCode: code),
            unrealizedPnL: Money(amount: unrealized, currencyCode: code),
            dayChange: Money(amount: day, currencyCode: code)
        )
    }
}
