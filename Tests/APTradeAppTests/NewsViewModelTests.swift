import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private func article(_ id: String, headline: String = "H", source: String = "src") -> NewsArticle {
    NewsArticle(id: id, headline: headline, summary: "", source: source,
                url: URL(string: "https://e.com/\(id)")!, imageURL: nil,
                publishedAt: Date(timeIntervalSince1970: 0), category: nil, relatedSymbol: nil)
}

private final class StubNewsRepo: NewsRepository, @unchecked Sendable {
    var market: [NewsArticle]
    init(market: [NewsArticle]) { self.market = market }
    func marketNews(category: NewsCategory) async throws -> [NewsArticle] { market }
    func companyNews(symbol: String) async throws -> [NewsArticle] { [] }
}

private final class MemoryBookmarkStore: BookmarkStore, @unchecked Sendable {
    var items: [NewsArticle] = []
    func load() -> [NewsArticle] { items }
    func save(_ articles: [NewsArticle]) { items = articles }
}

@MainActor
final class NewsViewModelTests: XCTestCase {
    private func makeVM(market: [NewsArticle], keyMissing: Bool = false,
                        store: MemoryBookmarkStore = MemoryBookmarkStore()) -> NewsViewModel {
        let repo = StubNewsRepo(market: market)
        return NewsViewModel(
            fetchMarketNews: FetchMarketNewsUseCase(repository: repo),
            loadBookmarks: LoadBookmarksUseCase(store: store),
            toggleBookmark: ToggleBookmarkUseCase(store: store),
            keyMissing: keyMissing)
    }

    func test_load_populatesArticles() async {
        let vm = makeVM(market: [article("1"), article("2")])
        await vm.load()
        XCTAssertEqual(vm.articles.map(\.id), ["1", "2"])
    }

    func test_keyMissing_loadIsNoOp() async {
        let vm = makeVM(market: [article("1")], keyMissing: true)
        await vm.load()
        XCTAssertTrue(vm.articles.isEmpty)
    }

    func test_filter_narrowsVisibleArticles() async {
        let vm = makeVM(market: [article("1", headline: "Apple soars"),
                                 article("2", headline: "Tesla dips")])
        await vm.load()
        vm.filter = "apple"
        XCTAssertEqual(vm.visibleArticles.map(\.id), ["1"])
    }

    func test_toggleBookmark_updatesSetAndPersists() async {
        let store = MemoryBookmarkStore()
        let vm = makeVM(market: [], store: store)
        vm.toggleBookmark(article("9"))
        XCTAssertTrue(vm.isBookmarked(article("9")))
        XCTAssertEqual(store.items.map(\.id), ["9"])
        vm.toggleBookmark(article("9"))
        XCTAssertFalse(vm.isBookmarked(article("9")))
    }

    func test_showingSaved_showsBookmarksFilteredToo() async {
        let store = MemoryBookmarkStore()
        let vm = makeVM(market: [article("1")], store: store)
        await vm.load()
        vm.toggleBookmark(article("5", headline: "Saved one"))
        vm.showingSaved = true
        XCTAssertEqual(vm.visibleArticles.map(\.id), ["5"])
    }

    func test_setCategory_reloads() async {
        let vm = makeVM(market: [article("1")])
        await vm.setCategory(.crypto)
        XCTAssertEqual(vm.category, .crypto)
        XCTAssertEqual(vm.articles.map(\.id), ["1"])
    }
}
