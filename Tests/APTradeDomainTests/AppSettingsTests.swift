import XCTest
@testable import APTradeDomain

final class AppSettingsStartingCashTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }

    func test_default_isOneHundredThousand() {
        XCTAssertEqual(AppSettings.default.defaultStartingCash, usd("100000"))
    }

    func test_decode_legacyPayloadWithoutKey_usesDefault() throws {
        let legacy = #"{"dripEnabled":true}"#
        let decoded = try JSONDecoder().decode(AppSettings.self, from: Data(legacy.utf8))
        XCTAssertEqual(decoded.defaultStartingCash, usd("100000"))
        XCTAssertTrue(decoded.dripEnabled)
    }

    func test_roundTrip_preservesCustomAmount() throws {
        var s = AppSettings.default
        s.defaultStartingCash = usd("250000")
        let decoded = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(s))
        XCTAssertEqual(decoded.defaultStartingCash, usd("250000"))
    }
}
