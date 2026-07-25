import XCTest
@testable import APTradeDomain

final class PortfolioStartingCashTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }

    func test_starting_recordsChosenCashAsStartingCash() {
        let p = Portfolio.starting(cash: usd("25000"))
        XCTAssertEqual(p.cash, usd("25000"))
        XCTAssertEqual(p.startingCash, usd("25000"))
    }

    func test_starting_defaultsToOneHundredThousand() {
        XCTAssertEqual(Portfolio.starting().startingCash, usd("100000"))
    }

    func test_init_defaultsStartingCashToOpeningCash() {
        let p = Portfolio(cash: usd("5000"))
        XCTAssertEqual(p.startingCash, usd("5000"))
    }

    func test_decode_legacyPayloadWithoutStartingCash_fallsBackToCash() throws {
        let legacy = #"{"cash":{"amount":42000,"currencyCode":"USD"},"positions":[],"transactions":[]}"#
        let decoded = try JSONDecoder().decode(Portfolio.self, from: Data(legacy.utf8))
        XCTAssertEqual(decoded.startingCash, decoded.cash)
        XCTAssertEqual(decoded.startingCash, usd("42000"))
    }

    func test_roundTrip_preservesStartingCashIndependentOfCurrentCash() throws {
        let original = Portfolio(cash: usd("31000"), positions: [], transactions: [],
                                 startingCash: usd("50000"))
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(Portfolio.self, from: data)
        XCTAssertEqual(decoded.startingCash, usd("50000"))
        XCTAssertEqual(decoded.cash, usd("31000"))
    }
}
