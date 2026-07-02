import Foundation
import APTradeApplication
import APTradeDomain
// @preconcurrency: silences non-Sendable diagnostics on KMP-bridged types (Shared.Quote,
// Shared.Money, and the Kotlin exception types surfaced through error handling below).
@preconcurrency import Shared

/// Serves `quote(for:)` from the shared Kotlin core (KMP FetchMarketQuotes → Ktor);
/// the four not-yet-ported calls delegate to the Swift-native fallback repository.
/// @unchecked Sendable: all stored properties are immutable, and Kotlin/Native
/// objects are safely shareable across threads under the current KMP memory model.
public final class SharedCoreMarketDataRepository: MarketDataRepository, @unchecked Sendable {
    private let fetch: @Sendable ([String]) async throws -> [Shared.Quote]
    private let fallback: MarketDataRepository

    public convenience init(fallback: MarketDataRepository) {
        let useCase = Shared.FetchMarketQuotes(repository: Shared.YahooMarketDataRepository())
        self.init(fallback: fallback, fetch: { try await useCase.execute(symbols: $0) })
    }

    init(fallback: MarketDataRepository, fetch: @escaping @Sendable ([String]) async throws -> [Shared.Quote]) {
        self.fetch = fetch
        self.fallback = fallback
    }

    public func quote(for symbol: String) async throws -> APTradeDomain.Quote {
        do {
            let quotes = try await fetch([symbol])
            guard let first = quotes.first else { throw AppError.notFound }
            return try Self.mapQuote(first)
        } catch {
            throw Self.mapError(error)
        }
    }

    public func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        try await fallback.history(for: symbol, timeframe: timeframe)
    }

    public func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        try await fallback.candles(for: symbol, timeframe: timeframe)
    }

    public func profile(for symbol: String) async throws -> Asset {
        try await fallback.profile(for: symbol)
    }

    public func search(query: String) async throws -> [Asset] {
        try await fallback.search(query: query)
    }

    // MARK: - Mapping (internal statics so tests hit them directly)

    static func mapQuote(_ quote: Shared.Quote) throws -> APTradeDomain.Quote {
        APTradeDomain.Quote(
            symbol: quote.symbol,
            price: try mapMoney(quote.price),
            previousClose: try mapMoney(quote.previousClose))
    }

    static func mapMoney(_ money: Shared.Money) throws -> APTradeDomain.Money {
        guard let amount = Decimal(string: money.amountText) else { throw AppError.decoding }
        return APTradeDomain.Money(amount: amount, currencyCode: money.currencyCode)
    }

    /// Kotlin exceptions cross the @Throws bridge as NSError carrying the original
    /// exception under the "KotlinException" userInfo key.
    static func mapError(_ error: Error) -> AppError {
        if let app = error as? AppError { return app }
        switch (error as NSError).userInfo["KotlinException"] {
        case is Shared.QuoteError.RateLimited: return .rateLimited
        case is Shared.QuoteError.NotFound: return .notFound
        default: return .network
        }
    }
}
