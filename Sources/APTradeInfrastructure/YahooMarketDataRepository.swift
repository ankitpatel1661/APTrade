import Foundation
import APTradeApplication
import APTradeDomain

public final class YahooMarketDataRepository: MarketDataRepository {
    private let session: URLSession
    private let base = "https://query1.finance.yahoo.com/v8/finance/chart/"
    private let searchBase = "https://query1.finance.yahoo.com/v1/finance/search"

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
        let points = try YahooMapper.history(from: data)
        let cutoff = Date().addingTimeInterval(-timeframe.windowDuration)
        let clamped = points.filter { $0.date >= cutoff }
        // Yahoo can omit the freshest bar near market close; never clamp to an empty chart.
        return clamped.isEmpty ? points : clamped
    }

    public func profile(for symbol: String) async throws -> Asset {
        let data = try await fetch(symbol: symbol, range: "1d", interval: "1d")
        return try YahooMapper.asset(from: data)
    }

    public func search(query: String) async throws -> [Asset] {
        guard var components = URLComponents(string: searchBase) else { throw AppError.network }
        components.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "quotesCount", value: "8"),
            URLQueryItem(name: "newsCount", value: "0"),
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
            return try YahooSearchMapper.assets(from: data)
        } catch let error as AppError {
            throw error
        } catch {
            throw AppError.network
        }
    }
}
