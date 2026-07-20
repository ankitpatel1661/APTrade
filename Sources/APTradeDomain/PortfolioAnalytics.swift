import Foundation

public extension Portfolio {
    /// Total realized P&L across the entire transaction history, computed with the
    /// average-cost method. Derived from the transaction log rather than current
    /// positions, so gains/losses from fully-closed positions still count. Pure.
    var realizedPnL: Money {
        var quantity: [String: Decimal] = [:]
        var averageCost: [String: Decimal] = [:]
        var realized = Decimal(0)

        for transaction in transactions.sorted(by: { $0.date < $1.date }) {
            let symbol = transaction.symbol
            let tradeQty = transaction.quantity.amount
            switch transaction.side {
            case .buy:
                let heldQty = quantity[symbol] ?? 0
                let heldAvg = averageCost[symbol] ?? 0
                let newQty = heldQty + tradeQty
                averageCost[symbol] = newQty == 0
                    ? 0
                    : (heldAvg * heldQty + transaction.price.amount * tradeQty) / newQty
                quantity[symbol] = newQty
            case .sell:
                let heldAvg = averageCost[symbol] ?? transaction.price.amount
                realized += (transaction.price.amount - heldAvg) * tradeQty
                quantity[symbol] = (quantity[symbol] ?? 0) - tradeQty
            case .dividend:
                break   // Cash event only — does not affect quantity, cost basis, or realized P&L.
            }
        }
        return Money(amount: realized, currencyCode: cash.currencyCode)
    }
}
