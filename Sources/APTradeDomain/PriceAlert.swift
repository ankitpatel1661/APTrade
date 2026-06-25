import Foundation

/// The threshold a `PriceAlert` watches for. Pure value type — no notion of how the
/// match is delivered to the user.
public enum AlertCondition: Equatable, Codable, Sendable {
    case priceAbove(Money)
    case priceBelow(Money)
    /// Fires once the day's `changePercent` magnitude reaches or exceeds this value,
    /// regardless of direction (e.g. "5%" fires on either +5% or -5%).
    case percentChange(Percentage)

    public var summary: String {
        switch self {
        case .priceAbove(let money): return "Price above \(money.formatted)"
        case .priceBelow(let money): return "Price below \(money.formatted)"
        case .percentChange(let pct): return "Moves \(abs(pct.value))% in a day"
        }
    }
}

/// A user-defined watch on one symbol: fire when `condition` is met against a live
/// quote. No framework imports, no networking, no persistence — pure business logic.
public struct PriceAlert: Identifiable, Equatable, Codable, Sendable {
    public let id: UUID
    public let symbol: String
    public let condition: AlertCondition
    public let createdAt: Date
    public var isTriggered: Bool

    public init(id: UUID = UUID(), symbol: String, condition: AlertCondition,
                createdAt: Date = Date(), isTriggered: Bool = false) {
        self.id = id
        self.symbol = symbol
        self.condition = condition
        self.createdAt = createdAt
        self.isTriggered = isTriggered
    }

    /// Pure check: does `quote` satisfy this alert's condition right now?
    public func isMet(by quote: Quote) -> Bool {
        switch condition {
        case .priceAbove(let threshold):
            return quote.price.amount >= threshold.amount
        case .priceBelow(let threshold):
            return quote.price.amount <= threshold.amount
        case .percentChange(let magnitude):
            return abs(quote.changePercent.value) >= abs(magnitude.value)
        }
    }

    public func triggered() -> PriceAlert {
        PriceAlert(id: id, symbol: symbol, condition: condition, createdAt: createdAt, isTriggered: true)
    }
}
