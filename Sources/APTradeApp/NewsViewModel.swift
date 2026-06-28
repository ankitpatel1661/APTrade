import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class NewsViewModel {
    private(set) var articles: [NewsArticle] = []
    private(set) var bookmarkedIDs: Set<String> = []
    private(set) var isLoading = false
    let keyMissing: Bool
    var category: NewsCategory = .general
    var filter: String = ""
    var showingSaved = false

    private let fetchMarketNews: FetchMarketNewsUseCase
    private let loadBookmarks: LoadBookmarksUseCase
    private let toggleBookmarkUseCase: ToggleBookmarkUseCase
    private var bookmarks: [NewsArticle] = []

    init(fetchMarketNews: FetchMarketNewsUseCase,
         loadBookmarks: LoadBookmarksUseCase,
         toggleBookmark: ToggleBookmarkUseCase,
         keyMissing: Bool) {
        self.fetchMarketNews = fetchMarketNews
        self.loadBookmarks = loadBookmarks
        self.toggleBookmarkUseCase = toggleBookmark
        self.keyMissing = keyMissing
    }

    /// The list to render: the saved set or the live feed, narrowed by the filter text.
    var visibleArticles: [NewsArticle] {
        let base = showingSaved ? bookmarks : articles
        let query = filter.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !query.isEmpty else { return base }
        return base.filter {
            $0.headline.lowercased().contains(query) || $0.source.lowercased().contains(query)
        }
    }

    func onAppear() async {
        refreshBookmarks()
        if articles.isEmpty && !keyMissing { await load() }
    }

    func load() async {
        guard !keyMissing else { return }
        isLoading = true
        articles = await fetchMarketNews(category: category)
        isLoading = false
    }

    func setCategory(_ newValue: NewsCategory) async {
        guard newValue != category else { return }
        category = newValue
        await load()
    }

    func toggleBookmark(_ article: NewsArticle) {
        bookmarks = toggleBookmarkUseCase(article)
        bookmarkedIDs = Set(bookmarks.map(\.id))
    }

    func isBookmarked(_ article: NewsArticle) -> Bool { bookmarkedIDs.contains(article.id) }

    private func refreshBookmarks() {
        bookmarks = loadBookmarks()
        bookmarkedIDs = Set(bookmarks.map(\.id))
    }
}
