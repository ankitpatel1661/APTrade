import Foundation

public struct Position: Equatable, Codable, Sendable {
    public let asset: Asset
    public let quantity: Quantity
    public let averageCost: Money
    public let realizedPnL: Money

    public init(asset: Asset, quantity: Quantity, averageCost: Money, realizedPnL: Money) {
        self.asset = asset
        self.quantity = quantity
        self.averageCost = averageCost
        self.realizedPnL = realizedPnL
    }

    public func marketValue(at price: Money) -> Money {
        Money(amount: price.amount * quantity.amount, currencyCode: price.currencyCode)
    }

    public func unrealizedPnL(at price: Money) -> Money {
        Money(amount: (price.amount - averageCost.amount) * quantity.amount,
              currencyCode: price.currencyCode)
    }
}
