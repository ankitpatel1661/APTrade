import APTradeApplication
import APTradeDomain

/// The fallback news source used when no API key is configured. Always returns no articles,
/// so the News surfaces render their "connect a news source" empty state.
public struct EmptyNewsRepository: NewsRepository {
    public init() {}
    public func marketNews(category: NewsCategory) async throws -> [NewsArticle] { [] }
    public func companyNews(symbol: String) async throws -> [NewsArticle] { [] }
}
