import XCTest
@testable import APTradeDomain

final class AppSettingsLanguageTests: XCTestCase {
    func test_default_languageIsEnglish() {
        XCTAssertEqual(AppSettings.default.language, .english)
    }

    func test_codableRoundTrip_preservesLanguage() throws {
        var s = AppSettings.default
        s.language = .spanish
        let data = try JSONEncoder().encode(s)
        XCTAssertEqual(try JSONDecoder().decode(AppSettings.self, from: data).language, .spanish)
    }

    func test_legacyPayloadWithoutLanguage_decodesToEnglishAndKeepsOtherFields() throws {
        // A payload saved before `language` existed: omits the key, sets a non-default elsewhere.
        let legacy = #"{ "isDarkMode": false, "confirmTrades": false }"#.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(AppSettings.self, from: legacy)
        XCTAssertEqual(decoded.language, .english)      // absent → default
        XCTAssertFalse(decoded.isDarkMode)              // existing field preserved
        XCTAssertFalse(decoded.confirmTrades)           // existing field preserved
    }

    func test_legacyPayloadWithoutEarningsReports_decodesToDefaultTrueAndKeepsOtherFields() throws {
        // A payload saved before `earningsReports` existed: omits the key, sets a non-default elsewhere.
        let legacy = #"{ "isDarkMode": false, "confirmTrades": false }"#.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(AppSettings.self, from: legacy)
        XCTAssertTrue(decoded.earningsReports)          // absent → default (true)
        XCTAssertFalse(decoded.isDarkMode)               // existing field preserved
        XCTAssertFalse(decoded.confirmTrades)            // existing field preserved
    }
}
