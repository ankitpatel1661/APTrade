import Foundation
import APTradeApplication
import APTradeDomain

public final class UserDefaultsWatchlistStore: WatchlistStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String
    private let seed: [Asset]

    public init(defaults: UserDefaults = .standard, key: String = "watchlist", seed: [Asset] = []) {
        self.defaults = defaults
        self.key = key
        self.seed = seed
    }

    public func load() -> [Asset] {
        guard let data = defaults.data(forKey: key),
              let assets = try? JSONDecoder().decode([Asset].self, from: data) else {
            if !seed.isEmpty { save(seed) }
            return seed
        }
        return assets
    }

    public func save(_ assets: [Asset]) {
        guard let data = try? JSONEncoder().encode(assets) else { return }
        defaults.set(data, forKey: key)
    }
}
