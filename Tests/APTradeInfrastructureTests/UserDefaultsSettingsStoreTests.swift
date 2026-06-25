import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class UserDefaultsSettingsStoreTests: XCTestCase {
    private func makeDefaults() -> UserDefaults {
        UserDefaults(suiteName: "settings.test.\(UUID().uuidString)")!
    }

    func test_firstLoad_returnsDefaults() {
        let store = UserDefaultsSettingsStore(defaults: makeDefaults(), key: "s")
        XCTAssertEqual(store.load(), .default)
    }

    func test_saveThenLoad_roundTrips() {
        let store = UserDefaultsSettingsStore(defaults: makeDefaults(), key: "s")
        var settings = AppSettings.default
        settings.marketOpenClose = true
        settings.confirmTrades = false
        store.save(settings)
        XCTAssertEqual(store.load(), settings)
    }

    func test_load_withCorruptData_fallsBackToDefaults() {
        let defaults = makeDefaults()
        defaults.set(Data("not json".utf8), forKey: "s")
        let store = UserDefaultsSettingsStore(defaults: defaults, key: "s")
        XCTAssertEqual(store.load(), .default)
    }
}
