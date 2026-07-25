import Foundation
import APTradeApplication
import APTradeDomain

/// `UserDefaults`-backed goal persistence: one JSON array under a single key. A missing
/// key (no goals set yet, or a payload written before goals existed) decodes to an empty
/// list rather than failing, matching the fallback behaviour of the sibling stores.
public final class UserDefaultsGoalStore: GoalStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "portfolioGoals") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> [PortfolioGoal] {
        guard let data = defaults.data(forKey: key),
              let goals = try? JSONDecoder().decode([PortfolioGoal].self, from: data) else { return [] }
        return goals
    }

    public func save(_ goals: [PortfolioGoal]) {
        guard let data = try? JSONEncoder().encode(goals) else { return }
        defaults.set(data, forKey: key)
    }
}
