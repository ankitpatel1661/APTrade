import Foundation
import APTradeApplication
import APTradeDomain

/// Live news source backed by Finnhub. Error handling:
/// 429 → `.rateLimited`, non-2xx → `.network`, decode/other → mapped to `AppError`.
public final class FinnhubNewsRepository: NewsRepository, @unchecked Sendable {
    private let apiKey: String
    private let session: URLSession
    private let base = "https://finnhub.io/api/v1"

    public init(apiKey: String, session: URLSession = .shared) {
        self.apiKey = apiKey
        self.session = session
    }

    public func marketNews(category: NewsCategory) async throws -> [NewsArticle] {
        guard var components = URLComponents(string: base + "/news") else { throw AppError.network }
        components.queryItems = [
            URLQueryItem(name: "category", value: category.finnhubValue),
            URLQueryItem(name: "token", value: apiKey),
        ]
        return try await fetch(components)
    }

    public func companyNews(symbol: String) async throws -> [NewsArticle] {
        let to = Date()
        let from = to.addingTimeInterval(-7 * 24 * 3600)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd"
        guard var components = URLComponents(string: base + "/company-news") else { throw AppError.network }
        components.queryItems = [
            URLQueryItem(name: "symbol", value: symbol.uppercased()),
            URLQueryItem(name: "from", value: formatter.string(from: from)),
            URLQueryItem(name: "to", value: formatter.string(from: to)),
            URLQueryItem(name: "token", value: apiKey),
        ]
        return try await fetch(components)
    }

    private func fetch(_ components: URLComponents) async throws -> [NewsArticle] {
        guard let url = components.url else { throw AppError.network }
        do {
            let (data, response) = try await session.data(from: url)
            if let http = response as? HTTPURLResponse {
                if http.statusCode == 429 { throw AppError.rateLimited }
                guard (200..<300).contains(http.statusCode) else { throw AppError.network }
            }
            return try FinnhubNewsMapper.articles(from: data)
        } catch let error as AppError {
            throw error
        } catch {
            throw AppError.network
        }
    }
}
