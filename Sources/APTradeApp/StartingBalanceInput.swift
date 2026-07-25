import Foundation
import APTradeDomain

/// Parses and range-validates the starting-balance field on the reset flow.
/// Locale-aware: accepts grouping separators and the locale's decimal separator.
enum StartingBalanceInput {
    static let minimum = Decimal(1_000)
    static let maximum = Decimal(10_000_000)

    static func parse(_ text: String, locale: Locale = .current) -> Money? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }

        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.generatesDecimalNumbers = true

        let amount: Decimal
        if let number = formatter.number(from: trimmed) as? NSDecimalNumber {
            amount = number.decimalValue
        } else if let plain = Decimal(string: trimmed, locale: locale) {
            amount = plain
        } else {
            return nil
        }

        guard amount >= minimum, amount <= maximum else { return nil }
        return Money(amount: amount)
    }
}
