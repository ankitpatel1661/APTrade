import Foundation

public struct Money: Equatable, Hashable, Codable, Sendable {
    public let amount: Decimal
    public let currencyCode: String

    public init(amount: Decimal, currencyCode: String = "USD") {
        self.amount = amount
        self.currencyCode = currencyCode
    }

    public static func - (lhs: Money, rhs: Money) -> Money {
        precondition(lhs.currencyCode == rhs.currencyCode, "currency mismatch")
        return Money(amount: lhs.amount - rhs.amount, currencyCode: lhs.currencyCode)
    }

    public static func + (lhs: Money, rhs: Money) -> Money {
        precondition(lhs.currencyCode == rhs.currencyCode, "currency mismatch")
        return Money(amount: lhs.amount + rhs.amount, currencyCode: lhs.currencyCode)
    }

    public var formatted: String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .currency
        f.currencyCode = currencyCode
        f.maximumFractionDigits = 2
        return f.string(from: amount as NSDecimalNumber) ?? "\(amount)"
    }
}
