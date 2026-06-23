import Foundation
import APTradeApplication
import APTradeDomain

public final class YahooMarketDataRepository: MarketDataRepository {
    private let session: URLSession
    private let base = "https://query1.finance.yahoo.com/v8/finance/chart/"

    public init(session: URLSession = .shared) { self.session = session }

    private func fetch(symbol: String, range: String, interval: String) async throws -> Data {
        guard let encoded = symbol.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              var components = URLComponents(string: base + encoded) else { throw AppError.network }
        components.queryItems = [
            URLQueryItem(name: "range", value: range),
            URLQueryItem(name: "interval", value: interval),
        ]
        guard let url = components.url else { throw AppError.network }
        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await session.data(for: request)
            if let http = response as? HTTPURLResponse {
                if http.statusCode == 429 { throw AppError.rateLimited }
                guard (200..<300).contains(http.statusCode) else { throw AppError.network }
            }
            return data
        } catch let error as AppError {
            throw error
        } catch {
            throw AppError.network
        }
    }

    public func quote(for symbol: String) async throws -> Quote {
        let data = try await fetch(symbol: symbol, range: "1d", interval: "1d")
        return try YahooMapper.quote(from: data)
    }

    public func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        let data = try await fetch(symbol: symbol, range: timeframe.yahooRange, interval: timeframe.yahooInterval)
        return try YahooMapper.history(from: data)
    }

    public func profile(for symbol: String) async throws -> Asset {
        let data = try await fetch(symbol: symbol, range: "1d", interval: "1d")
        return try YahooMapper.asset(from: data)
    }
}
