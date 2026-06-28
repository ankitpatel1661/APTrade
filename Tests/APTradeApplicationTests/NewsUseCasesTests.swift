import XCTest
@testable import APTradeApplication
import APTradeDomain

private func article(_ id: String) -> NewsArticle {
    NewsArticle(id: id, headline: "H\(id)", summary: "", source: "src",
                url: URL(string: "https://e.com/\(id)")!, imageURL: nil,
                publishedAt: Date(timeIntervalSince1970: 0), category: nil, relatedSymbol: nil)
}

private final class StubNewsRepo: NewsRepository, @unchecked Sendable {
    var market: [NewsArticle]
    var company: [NewsArticle]
    var shouldThrow: Bool
    init(market: [NewsArticle] = [], company: [NewsArticle] = [], shouldThrow: Bool = false) {
        self.market = market; self.company = company; self.shouldThrow = shouldThrow
    }
    func marketNews(category: NewsCategory) async throws -> [NewsArticle] {
        if shouldThrow { throw AppError.network }; return market
    }
    func companyNews(symbol: String) async throws -> [NewsArticle] {
        if shouldThrow { throw AppError.network }; return company
    }
}

private final class MemoryBookmarkStore: BookmarkStore, @unchecked Sendable {
    var items: [NewsArticle]
    init(_ items: [NewsArticle] = []) { self.items = items }
    func load() -> [NewsArticle] { items }
    func save(_ articles: [NewsArticle]) { items = articles }
}

final class NewsUseCasesTests: XCTestCase {
    func test_fetchMarketNews_returnsRepoResults() async {
        let useCase = FetchMarketNewsUseCase(repository: StubNewsRepo(market: [article("1"), article("2")]))
        let result = await useCase(category: .general)
        XCTAssertEqual(result.map(\.id), ["1", "2"])
    }

    func test_fetchMarketNews_onError_returnsEmpty() async {
        let useCase = FetchMarketNewsUseCase(repository: StubNewsRepo(shouldThrow: true))
        let result = await useCase(category: .crypto)
        XCTAssertTrue(result.isEmpty)
    }

    func test_fetchCompanyNews_returnsRepoResults() async {
        let useCase = FetchCompanyNewsUseCase(repository: StubNewsRepo(company: [article("9")]))
        let result = await useCase(symbol: "AAPL")
        XCTAssertEqual(result.map(\.id), ["9"])
    }

    func test_toggleBookmark_addsThenRemoves_andPersists() {
        let store = MemoryBookmarkStore()
        let toggle = ToggleBookmarkUseCase(store: store)
        let added = toggle(article("1"))
        XCTAssertEqual(added.map(\.id), ["1"])
        XCTAssertEqual(store.items.map(\.id), ["1"])
        let removed = toggle(article("1"))
        XCTAssertTrue(removed.isEmpty)
        XCTAssertTrue(store.items.isEmpty)
    }

    func test_loadBookmarks_returnsStored() {
        let store = MemoryBookmarkStore([article("7")])
        XCTAssertEqual(LoadBookmarksUseCase(store: store)().map(\.id), ["7"])
    }
}
