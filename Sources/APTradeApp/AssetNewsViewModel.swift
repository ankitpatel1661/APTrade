import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class AssetNewsViewModel {
    private(set) var articles: [NewsArticle] = []
    private(set) var bookmarkedIDs: Set<String> = []
    private(set) var isLoading = false
    let keyMissing: Bool

    private let symbol: String
    private let fetchCompanyNews: FetchCompanyNewsUseCase
    private let loadBookmarks: LoadBookmarksUseCase
    private let toggleBookmarkUseCase: ToggleBookmarkUseCase

    init(symbol: String,
         fetchCompanyNews: FetchCompanyNewsUseCase,
         loadBookmarks: LoadBookmarksUseCase,
         toggleBookmark: ToggleBookmarkUseCase,
         keyMissing: Bool) {
        self.symbol = symbol
        self.fetchCompanyNews = fetchCompanyNews
        self.loadBookmarks = loadBookmarks
        self.toggleBookmarkUseCase = toggleBookmark
        self.keyMissing = keyMissing
    }

    func onAppear() async {
        bookmarkedIDs = Set(loadBookmarks().map(\.id))
        if articles.isEmpty && !keyMissing { await load() }
    }

    func load() async {
        guard !keyMissing else { return }
        isLoading = true
        articles = await fetchCompanyNews(symbol: symbol)
        isLoading = false
    }

    func toggleBookmark(_ article: NewsArticle) {
        bookmarkedIDs = Set(toggleBookmarkUseCase(article).map(\.id))
    }

    func isBookmarked(_ article: NewsArticle) -> Bool { bookmarkedIDs.contains(article.id) }
}
