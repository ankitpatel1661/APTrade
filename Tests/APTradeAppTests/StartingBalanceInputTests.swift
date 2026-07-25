import XCTest
import APTradeDomain
@testable import APTradeApp

final class StartingBalanceInputTests: XCTestCase {
    private let us = Locale(identifier: "en_US")
    private let de = Locale(identifier: "de_DE")

    func test_parse_plainAmount() {
        XCTAssertEqual(StartingBalanceInput.parse("25000", locale: us), Money(amount: 25_000))
    }

    func test_parse_groupedAmount_usLocale() {
        XCTAssertEqual(StartingBalanceInput.parse("1,250,000", locale: us), Money(amount: 1_250_000))
    }

    func test_parse_germanDecimalComma() {
        XCTAssertEqual(StartingBalanceInput.parse("25.000,50", locale: de),
                       Money(amount: Decimal(string: "25000.50") ?? 0))
    }

    func test_parse_rejectsBelowMinimum() {
        XCTAssertNil(StartingBalanceInput.parse("999", locale: us))
    }

    func test_parse_rejectsAboveMaximum() {
        XCTAssertNil(StartingBalanceInput.parse("10000001", locale: us))
    }

    func test_parse_acceptsInclusiveBounds() {
        XCTAssertEqual(StartingBalanceInput.parse("1000", locale: us), Money(amount: 1_000))
        XCTAssertEqual(StartingBalanceInput.parse("10000000", locale: us), Money(amount: 10_000_000))
    }

    func test_parse_rejectsGarbageAndEmpty() {
        XCTAssertNil(StartingBalanceInput.parse("", locale: us))
        XCTAssertNil(StartingBalanceInput.parse("abc", locale: us))
        XCTAssertNil(StartingBalanceInput.parse("-5000", locale: us))
    }
}
