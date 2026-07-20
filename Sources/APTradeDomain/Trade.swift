import Foundation

public enum TradeSide: String, Codable, Sendable {
    case buy, sell, dividend
}

public enum TradeError: Error, Equatable, Sendable {
    case insufficientFunds
    case insufficientShares
    case invalidQuantity
}

public struct Transaction: Identifiable, Equatable, Codable, Sendable {
    public let id: UUID
    public let symbol: String
    public let side: TradeSide
    public let quantity: Quantity
    public let price: Money
    public let date: Date
    public let pieId: String?
    public let isDrip: Bool

    public init(id: UUID = UUID(), symbol: String, side: TradeSide,
                quantity: Quantity, price: Money, date: Date, pieId: String? = nil,
                isDrip: Bool = false) {
        self.id = id
        self.symbol = symbol
        self.side = side
        self.quantity = quantity
        self.price = price
        self.date = date
        self.pieId = pieId
        self.isDrip = isDrip
    }

    /// Custom decoding so ledgers persisted before M8 (no `pieId`/`isDrip` keys)
    /// decode unchanged, defaulting `pieId` to `nil` and `isDrip` to `false`.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        symbol = try container.decode(String.self, forKey: .symbol)
        side = try container.decode(TradeSide.self, forKey: .side)
        quantity = try container.decode(Quantity.self, forKey: .quantity)
        price = try container.decode(Money.self, forKey: .price)
        date = try container.decode(Date.self, forKey: .date)
        pieId = try container.decodeIfPresent(String.self, forKey: .pieId)
        isDrip = try container.decodeIfPresent(Bool.self, forKey: .isDrip) ?? false
    }
}
