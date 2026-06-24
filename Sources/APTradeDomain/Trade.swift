import Foundation

public enum TradeSide: String, Codable, Sendable {
    case buy, sell
}

public enum TradeError: Error, Equatable, Sendable {
    case insufficientFunds
    case insufficientShares
    case invalidQuantity
}

public struct Transaction: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let symbol: String
    public let side: TradeSide
    public let quantity: Quantity
    public let price: Money
    public let date: Date

    public init(id: UUID = UUID(), symbol: String, side: TradeSide,
                quantity: Quantity, price: Money, date: Date) {
        self.id = id
        self.symbol = symbol
        self.side = side
        self.quantity = quantity
        self.price = price
        self.date = date
    }
}
