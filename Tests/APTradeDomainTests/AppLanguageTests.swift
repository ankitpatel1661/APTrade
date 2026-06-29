import XCTest
@testable import APTradeDomain

final class AppLanguageTests: XCTestCase {
    func test_rawValues_areBCP47Codes() {
        XCTAssertEqual(AppLanguage.english.rawValue, "en")
        XCTAssertEqual(AppLanguage.german.rawValue, "de")
        XCTAssertEqual(AppLanguage.italian.rawValue, "it")
        XCTAssertEqual(AppLanguage.spanish.rawValue, "es")
    }

    func test_displayName_usesEndonyms() {
        XCTAssertEqual(AppLanguage.english.displayName, "English")
        XCTAssertEqual(AppLanguage.german.displayName, "Deutsch")
        XCTAssertEqual(AppLanguage.italian.displayName, "Italiano")
        XCTAssertEqual(AppLanguage.spanish.displayName, "Español")
    }

    func test_allCases_orderAndCount() {
        XCTAssertEqual(AppLanguage.allCases, [.english, .german, .italian, .spanish])
    }

    func test_codableRoundTrip() throws {
        let data = try JSONEncoder().encode(AppLanguage.german)
        XCTAssertEqual(try JSONDecoder().decode(AppLanguage.self, from: data), .german)
    }
}
