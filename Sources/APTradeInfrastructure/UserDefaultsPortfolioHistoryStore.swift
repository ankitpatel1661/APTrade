import Foundation
import APTradeApplication
import APTradeDomain

/// Records portfolio value snapshots to UserDefaults, capped to a rolling window so
/// the chart has data without the store growing unbounded.
public final class UserDefaultsPortfolioHistoryStore: PortfolioHistoryStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String
    private let maxPoints: Int

    public init(defaults: UserDefaults = .standard, key: String = "portfolioHistory", maxPoints: Int = 500) {
        self.defaults = defaults
        self.key = key
        self.maxPoints = maxPoints
    }

    public func record(_ point: PricePoint) {
        var points = load()
        points.append(point)
        if points.count > maxPoints {
            points.removeFirst(points.count - maxPoints)
        }
        guard let data = try? JSONEncoder().encode(points) else { return }
        defaults.set(data, forKey: key)
    }

    public func load() -> [PricePoint] {
        guard let data = defaults.data(forKey: key),
              let points = try? JSONDecoder().decode([PricePoint].self, from: data) else { return [] }
        return points
    }
}
