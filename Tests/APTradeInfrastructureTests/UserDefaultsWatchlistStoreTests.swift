import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class UserDefaultsWatchlistStoreTests: XCTestCase {
    func makeDefaults() -> UserDefaults {
        let suite = "test.\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }
    let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)

    func test_emptyStore_returnsSeed_andPersistsIt() {
        let defaults = makeDefaults()
        let store = UserDefaultsWatchlistStore(defaults: defaults, seed: [aapl])
        XCTAssertEqual(store.load(), [aapl])
        // A second store with no seed still sees the persisted value.
        let store2 = UserDefaultsWatchlistStore(defaults: defaults, seed: [])
        XCTAssertEqual(store2.load(), [aapl])
    }

    func test_saveThenLoad_roundTrips() {
        let defaults = makeDefaults()
        let store = UserDefaultsWatchlistStore(defaults: defaults)
        store.save([aapl])
        XCTAssertEqual(store.load(), [aapl])
    }
}
