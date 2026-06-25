import Foundation
import APTradeApplication
import APTradeDomain

public final class UserDefaultsAlertStore: AlertStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "priceAlerts") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> [PriceAlert] {
        guard let data = defaults.data(forKey: key),
              let alerts = try? JSONDecoder().decode([PriceAlert].self, from: data) else { return [] }
        return alerts
    }

    public func save(_ alerts: [PriceAlert]) {
        guard let data = try? JSONEncoder().encode(alerts) else { return }
        defaults.set(data, forKey: key)
    }
}
