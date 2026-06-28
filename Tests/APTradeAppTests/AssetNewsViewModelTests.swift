import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private func article(_ id: String) -> NewsArticle {
    NewsArticle(id: id, headline: "H\(id)", summary: "", source: "s",
                url: URL(string: "https://e.com/\(id)")!, imageURL: nil,
                publishedAt: Date(timeIntervalSince1970: 0), category: nil, relatedSymbol: nil)
}

private final class StubNewsRepo: NewsRepository, @unchecked Sendable {
    var company: [NewsArticle]
    init(company: [NewsArticle]) { self.company = company }
    func marketNews(category: NewsCategory) async throws -> [NewsArticle] { [] }
    func companyNews(symbol: String) async throws -> [NewsArticle] { company }
}

private final class MemoryBookmarkStore: BookmarkStore, @unchecked Sendable {
    var items: [NewsArticle] = []
    func load() -> [NewsArticle] { items }
    func save(_ articles: [NewsArticle]) { items = articles }
}

@MainActor
final class AssetNewsViewModelTests: XCTestCase {
    private func makeVM(company: [NewsArticle], keyMissing: Bool = false) -> AssetNewsViewModel {
        let repo = StubNewsRepo(company: company)
        let store = MemoryBookmarkStore()
        return AssetNewsViewModel(
            symbol: "AAPL",
            fetchCompanyNews: FetchCompanyNewsUseCase(repository: repo),
            loadBookmarks: LoadBookmarksUseCase(store: store),
            toggleBookmark: ToggleBookmarkUseCase(store: store),
            keyMissing: keyMissing)
    }

    func test_load_populatesArticles() async {
        let vm = makeVM(company: [article("1")])
        await vm.load()
        XCTAssertEqual(vm.articles.map(\.id), ["1"])
    }

    func test_keyMissing_loadIsNoOp() async {
        let vm = makeVM(company: [article("1")], keyMissing: true)
        await vm.load()
        XCTAssertTrue(vm.articles.isEmpty)
    }

    func test_toggleBookmark_updatesSet() async {
        let vm = makeVM(company: [])
        vm.toggleBookmark(article("3"))
        XCTAssertTrue(vm.isBookmarked(article("3")))
        vm.toggleBookmark(article("3"))
        XCTAssertFalse(vm.isBookmarked(article("3")))
    }
}
