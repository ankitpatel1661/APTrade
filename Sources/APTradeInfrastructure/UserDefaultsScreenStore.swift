import Foundation
import APTradeApplication
import APTradeDomain

/// Persists the user's saved custom screens as Codable JSON in `UserDefaults`.
/// A decode failure (corruption/schema drift) returns an empty list without overwriting
/// the stored bytes.
public final class UserDefaultsScreenStore: ScreenStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "screens") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> [CustomScreen] {
        guard let data = defaults.data(forKey: key),
              let items = try? JSONDecoder().decode([CustomScreen].self, from: data) else { return [] }
        return items
    }

    public func save(_ screens: [CustomScreen]) {
        guard let data = try? JSONEncoder().encode(screens) else { return }
        defaults.set(data, forKey: key)
    }
}
