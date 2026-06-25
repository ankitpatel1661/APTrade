import Foundation
import APTradeApplication
import APTradeDomain

/// Persists the notification scheduler's markers as a JSON blob in UserDefaults.
/// A missing or undecodable value falls back to an empty (unseeded) state.
public final class UserDefaultsSchedulerStateStore: SchedulerStateStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "schedulerState") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> SchedulerState {
        guard let data = defaults.data(forKey: key),
              let state = try? JSONDecoder().decode(SchedulerState.self, from: data) else {
            return SchedulerState()
        }
        return state
    }

    public func save(_ state: SchedulerState) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        defaults.set(data, forKey: key)
    }
}
