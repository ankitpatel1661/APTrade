import APTradeDomain

/// Fetches market headlines for a category. Best-effort: network/rate-limit failures
/// resolve to an empty list (the view model distinguishes no-key from empty separately).
public struct FetchMarketNewsUseCase: Sendable {
    private let repository: NewsRepository
    public init(repository: NewsRepository) { self.repository = repository }
    public func callAsFunction(category: NewsCategory) async -> [NewsArticle] {
        (try? await repository.marketNews(category: category)) ?? []
    }
}

/// Fetches recent company news for a symbol. Best-effort, like `FetchMarketNewsUseCase`.
public struct FetchCompanyNewsUseCase: Sendable {
    private let repository: NewsRepository
    public init(repository: NewsRepository) { self.repository = repository }
    public func callAsFunction(symbol: String) async -> [NewsArticle] {
        (try? await repository.companyNews(symbol: symbol)) ?? []
    }
}

public struct LoadBookmarksUseCase: Sendable {
    private let store: BookmarkStore
    public init(store: BookmarkStore) { self.store = store }
    public func callAsFunction() -> [NewsArticle] { store.load() }
}

/// Toggles an article's bookmark by `id`: removes it if already saved, otherwise inserts it
/// at the front. Persists and returns the new list.
public struct ToggleBookmarkUseCase: Sendable {
    private let store: BookmarkStore
    public init(store: BookmarkStore) { self.store = store }
    public func callAsFunction(_ article: NewsArticle) -> [NewsArticle] {
        var items = store.load()
        if let index = items.firstIndex(where: { $0.id == article.id }) {
            items.remove(at: index)
        } else {
            items.insert(article, at: 0)
        }
        store.save(items)
        return items
    }
}
