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
        guard let data = defaults.data(forKey: key),
              let portfolio = try? JSONDecoder().decode(Portfolio.self, from: data) else {
            let seed = Portfolio.starting()
            save(seed)
            return seed
        }
        return portfolio
    }

    public func save(_ portfolio: Portfolio) {
        guard let data = try? JSONEncoder().encode(portfolio) else { return }
        defaults.set(data, forKey: key)
    }
}
