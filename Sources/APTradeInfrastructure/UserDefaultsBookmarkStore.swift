import Foundation
import APTradeApplication
import APTradeDomain

/// Persists bookmarked articles as Codable JSON in `UserDefaults`. A decode failure
/// (corruption/schema drift) returns an empty list without overwriting the stored bytes.
public final class UserDefaultsBookmarkStore: BookmarkStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "bookmarks") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> [NewsArticle] {
        guard let data = defaults.data(forKey: key),
              let items = try? JSONDecoder().decode([NewsArticle].self, from: data) else { return [] }
        return items
    }

    public func save(_ articles: [NewsArticle]) {
        guard let data = try? JSONEncoder().encode(articles) else { return }
        defaults.set(data, forKey: key)
    }
}
