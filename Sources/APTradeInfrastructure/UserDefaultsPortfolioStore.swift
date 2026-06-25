import Foundation
import APTradeApplication
import APTradeDomain

public final class UserDefaultsPortfolioStore: PortfolioStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "portfolio") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> Portfolio {
        guard let data = defaults.data(forKey: key) else {
            let seed = Portfolio.starting()
            save(seed)
            return seed
        }
        guard let portfolio = try? JSONDecoder().decode(Portfolio.self, from: data) else {
            // Data exists but failed to decode (corruption / schema drift).
            // Do NOT overwrite the stored bytes — return an in-memory fallback only,
            // so the undecodable data is preserved for future recovery/migration.
            return Portfolio.starting()
        }
        return portfolio
    }

    public func save(_ portfolio: Portfolio) {
        guard let data = try? JSONEncoder().encode(portfolio) else { return }
        defaults.set(data, forKey: key)
    }
}
