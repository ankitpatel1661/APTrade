import Foundation

public struct Quote: Equatable, Sendable {
    public let symbol: String
    public let price: Money
    public let previousClose: Money

    public init(symbol: String, price: Money, previousClose: Money) {
        self.symbol = symbol
        self.price = price
        self.previousClose = previousClose
    }

    public var change: Money { price - previousClose }

    public var changePercent: Percentage {
        guard previousClose.amount != 0 else { return Percentage(value: 0) }
        let ratio = (price.amount - previousClose.amount) / previousClose.amount * 100
        return Percentage(value: ratio)
    }
}
