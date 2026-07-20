import Foundation
import APTradeApplication
import APTradeDomain

/// Persists portfolio allocation strategies (Pies) as Codable JSON in `UserDefaults`.
/// A decode failure (corruption/schema drift) returns an empty list without overwriting
/// the stored bytes.
public final class UserDefaultsPieStore: PieStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "pies") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> [Pie] {
        guard let data = defaults.data(forKey: key),
              let items = try? JSONDecoder().decode([Pie].self, from: data) else { return [] }
        return items
    }

    public func save(_ pies: [Pie]) {
        guard let data = try? JSONEncoder().encode(pies) else { return }
        defaults.set(data, forKey: key)
    }
}
