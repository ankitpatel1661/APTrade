import XCTest
@testable import APTradeDomain

final class QuantityTests: XCTestCase {
    func test_clampsNegativeToZero() {
        XCTAssertEqual(Quantity(Decimal(-3)).amount, 0)
        XCTAssertTrue(Quantity(Decimal(-3)).isZero)
    }

    func test_keepsFractionalValue() {
        XCTAssertEqual(Quantity(Decimal(string: "0.05")!).amount, Decimal(string: "0.05"))
    }

    func test_formatted_trimsTrailingZeros() {
        XCTAssertEqual(Quantity(Decimal(string: "2.50")!).formatted, "2.5")
        XCTAssertEqual(Quantity(Decimal(3)).formatted, "3")
    }

    func test_formatted_capsAtFourFractionDigits() {
        XCTAssertEqual(Quantity(Decimal(string: "0.16666666")!).formatted, "0.1667")
        XCTAssertEqual(Quantity(Decimal(string: "10")!).formatted, "10")
        XCTAssertEqual(Quantity(Decimal(string: "2.5")!).formatted, "2.5")
    }
}
