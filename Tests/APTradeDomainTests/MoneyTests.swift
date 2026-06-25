import XCTest
@testable import APTradeDomain

final class MoneyTests: XCTestCase {
    func test_subtraction_sameCurrency() {
        let a = Money(amount: 100)
        let b = Money(amount: 30)
        XCTAssertEqual(a - b, Money(amount: 70))
    }

    func test_addition() {
        XCTAssertEqual(Money(amount: 3) + Money(amount: 4), Money(amount: 7))
    }

    func test_money_formatted_usd() {
        XCTAssertEqual(Money(amount: Decimal(string: "294.3")!).formatted, "$294.30")
    }

    func test_percentage_sign_and_format() {
        XCTAssertTrue(Percentage(value: Decimal(string: "1.25")!).isPositive)
        XCTAssertEqual(Percentage(value: Decimal(string: "1.25")!).formatted, "+1.25%")
        XCTAssertEqual(Percentage(value: Decimal(string: "-1.25")!).formatted, "-1.25%")
    }
}
