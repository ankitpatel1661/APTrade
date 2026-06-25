import Foundation
import APTradeApplication
import APTradeDomain

/// Persists `AppSettings` as a single JSON blob in UserDefaults. A missing or
/// undecodable value falls back to `.default` rather than failing, so a fresh install
/// (or a forward-incompatible payload) opens on sensible defaults.
public final class UserDefaultsSettingsStore: SettingsStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "appSettings") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> AppSettings {
        guard let data = defaults.data(forKey: key),
              let settings = try? JSONDecoder().decode(AppSettings.self, from: data) else {
            return .default
        }
        return settings
    }

    public func save(_ settings: AppSettings) {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        defaults.set(data, forKey: key)
    }
}
