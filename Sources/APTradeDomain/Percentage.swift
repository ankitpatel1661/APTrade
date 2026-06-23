import Foundation

public struct Percentage: Equatable, Hashable, Sendable {
    public let value: Decimal

    public init(value: Decimal) { self.value = value }

    public var isPositive: Bool { value > 0 }
    public var isNegative: Bool { value < 0 }

    public var formatted: String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .decimal
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        let body = f.string(from: value as NSDecimalNumber) ?? "\(value)"
        let sign = value > 0 ? "+" : ""
        return "\(sign)\(body)%"
    }
}
