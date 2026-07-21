import Foundation

/// A non-negative amount of an asset held or traded. Supports fractional units
/// (e.g. 0.05 BTC). Negative inputs are clamped to zero to preserve the invariant.
public struct Quantity: Equatable, Hashable, Codable, Sendable {
    public let amount: Decimal

    public init(_ amount: Decimal) {
        self.amount = Swift.max(0, amount)
    }

    public var isZero: Bool { amount == 0 }

    /// Up to 4 fraction digits, trailing zeros trimmed (so "2.50" → "2.5", "3.0" → "3").
    ///
    /// Matches the 4-decimal share display shipped on Windows/Android in M8 (KMP twin
    /// rounded half-away-from-zero; NumberFormatter here rounds half-to-even by default).
    /// This is a display-only divergence — the underlying `amount` retains full
    /// precision — and is not worth fighting NumberFormatter over for a cosmetic
    /// rounding-mode difference at the 5th decimal place.
    public var formatted: String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .decimal
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 4
        return f.string(from: amount as NSDecimalNumber) ?? "\(amount)"
    }
}
