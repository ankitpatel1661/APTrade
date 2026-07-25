import XCTest
import APTradeDomain
@testable import APTradeInfrastructure

final class UserDefaultsGoalStoreTests: XCTestCase {
    private func makeDefaults() -> UserDefaults {
        let suite = "goal-store-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    func test_load_emptyByDefault() {
        XCTAssertTrue(UserDefaultsGoalStore(defaults: makeDefaults()).load().isEmpty)
    }

    func test_saveThenLoad_roundTrips() {
        let store = UserDefaultsGoalStore(defaults: makeDefaults())
        let goals = [PortfolioGoal(kind: .value, target: Money(amount: 250_000),
                                   createdAt: Date(timeIntervalSince1970: 1_700_000_000)),
                     PortfolioGoal(kind: .income, target: Money(amount: 5_000),
                                   createdAt: Date(timeIntervalSince1970: 1_700_000_000))]
        store.save(goals)
        XCTAssertEqual(store.load(), goals)
    }

    func test_load_corruptPayload_returnsEmpty() {
        let defaults = makeDefaults()
        defaults.set(Data("not json".utf8), forKey: "portfolioGoals")
        XCTAssertTrue(UserDefaultsGoalStore(defaults: defaults).load().isEmpty)
    }

    /// A payload written before goals existed at all has no `portfolioGoals` key present —
    /// simulated here by never writing it. `load()` must degrade to empty, not crash, so an
    /// existing user's app doesn't break the first time this store is read.
    func test_load_missingKey_fromPreGoalsPayload_returnsEmpty() {
        let defaults = makeDefaults()
        defaults.set("legacy value", forKey: "someOtherPreExistingKey")
        XCTAssertTrue(UserDefaultsGoalStore(defaults: defaults).load().isEmpty)
    }
}
