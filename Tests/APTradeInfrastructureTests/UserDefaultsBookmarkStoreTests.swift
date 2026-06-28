import XCTest
@testable import APTradeInfrastructure
import APTradeDomain

final class UserDefaultsBookmarkStoreTests: XCTestCase {
    private func makeDefaults() -> UserDefaults {
        let suite = "bookmarks.test.\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    private func article(_ id: String) -> NewsArticle {
        NewsArticle(id: id, headline: "H\(id)", summary: "", source: "s",
                    url: URL(string: "https://e.com/\(id)")!, imageURL: nil,
                    publishedAt: Date(timeIntervalSince1970: 0), category: nil, relatedSymbol: nil)
    }

    func test_saveThenLoad_roundTrips() {
        let store = UserDefaultsBookmarkStore(defaults: makeDefaults(), key: "bm")
        store.save([article("1"), article("2")])
        XCTAssertEqual(store.load().map(\.id), ["1", "2"])
    }

    func test_load_whenEmpty_returnsEmpty() {
        let store = UserDefaultsBookmarkStore(defaults: makeDefaults(), key: "bm")
        XCTAssertTrue(store.load().isEmpty)
    }

    func test_load_withCorruptData_returnsEmpty() {
        let defaults = makeDefaults()
        defaults.set(Data("garbage".utf8), forKey: "bm")
        let store = UserDefaultsBookmarkStore(defaults: defaults, key: "bm")
        XCTAssertTrue(store.load().isEmpty)
    }
}
