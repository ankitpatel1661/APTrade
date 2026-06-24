import Foundation

/// A non-negative amount of an asset held or traded. Supports fractional units
/// (e.g. 0.05 BTC). Negative inputs are clamped to zero to preserve the invariant.
public struct Quantity: Equatable, Hashable, Codable, Sendable {
    public let amount: Decimal

    public init(_ amount: Decimal) {
        self.amount = Swift.max(0, amount)
    }

    public var isZero: Bool { amount == 0 }

    /// Up to 8 fraction digits, trailing zeros trimmed (so "2.50" → "2.5", "3.0" → "3").
    public var formatted: String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .decimal
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 8
        return f.string(from: amount as NSDecimalNumber) ?? "\(amount)"
    }
}
