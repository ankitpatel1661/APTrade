import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class UserDefaultsPortfolioStoreTests: XCTestCase {
    private func makeDefaults() -> UserDefaults {
        let d = UserDefaults(suiteName: "portfolio.test.\(UUID().uuidString)")!
        return d
    }

    func test_firstLoad_seedsStartingPortfolio() {
        let store = UserDefaultsPortfolioStore(defaults: makeDefaults(), key: "pf")
        XCTAssertEqual(store.load().cash, Money(amount: 100_000))
        XCTAssertTrue(store.load().positions.isEmpty)
    }

    func test_saveThenLoad_roundTrips() {
        let store = UserDefaultsPortfolioStore(defaults: makeDefaults(), key: "pf")
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let updated = try! store.load().buying(aapl, quantity: Quantity(Decimal(1)), at: Money(amount: 100))
        store.save(updated)
        XCTAssertEqual(store.load().position(for: "AAPL")?.quantity, Quantity(Decimal(1)))
        XCTAssertEqual(store.load().cash, Money(amount: 99_900))
    }

    func test_load_withCorruptData_doesNotOverwriteStoredBytes() {
        let defaults = makeDefaults()
        let originalGarbage = Data("not json".utf8)
        defaults.set(originalGarbage, forKey: "pf")
        let store = UserDefaultsPortfolioStore(defaults: defaults, key: "pf")

        let loaded = store.load()

        XCTAssertEqual(loaded.cash, Money(amount: 100_000))
        XCTAssertTrue(loaded.positions.isEmpty)
        XCTAssertEqual(defaults.data(forKey: "pf"), originalGarbage)
    }
}
