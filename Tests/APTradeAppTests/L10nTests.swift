import XCTest
@testable import APTradeApp
import APTradeDomain

@MainActor
final class L10nTests: XCTestCase {
    func test_everyKeyHasAllFourLanguages_nonEmpty() {
        for key in L10n.Key.allCases {
            let row = L10n.table[key]
            XCTAssertNotNil(row, "missing table row for \(key)")
            for lang in AppLanguage.allCases {
                let value = row?[lang]
                XCTAssertNotNil(value, "missing \(lang) for \(key)")
                XCTAssertFalse((value ?? "").isEmpty, "empty \(lang) for \(key)")
            }
        }
    }

    func test_tr_resolvesActiveLanguage() {
        LocalizationManager.shared.language = .german
        XCTAssertEqual(tr(.watchlist), "Beobachtungsliste")
        LocalizationManager.shared.language = .spanish
        XCTAssertEqual(tr(.watchlist), "Lista de seguimiento")
        LocalizationManager.shared.language = .english
        XCTAssertEqual(tr(.watchlist), "Watchlist")
    }
}
