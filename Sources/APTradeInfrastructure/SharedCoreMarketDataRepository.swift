import Foundation
import APTradeApplication
import APTradeDomain
// @preconcurrency: silences non-Sendable diagnostics on KMP-bridged types (Shared.Quote,
// Shared.Money, Shared.PricePoint, Shared.Candle, Shared.Asset, and the Kotlin exception
// types surfaced through error handling below).
@preconcurrency import Shared

/// Serves `quote`/`history`/`candles`/`profile`/`search` from the shared Kotlin core.
/// @unchecked Sendable: all stored properties are immutable, and Kotlin/Native
/// objects are safely shareable across threads under the current KMP memory model.
public final class SharedCoreMarketDataRepository: APTradeApplication.MarketDataRepository, @unchecked Sendable {
    private let fetch: @Sendable ([String]) async throws -> [Shared.Quote]
    private let fetchHistory: @Sendable (String, Shared.Timeframe) async throws -> [Shared.PricePoint]
    private let fetchCandles: @Sendable (String, Shared.Timeframe) async throws -> [Shared.Candle]
    private let fetchProfile: @Sendable (String) async throws -> Shared.Asset
    private let fetchSearch: @Sendable (String) async throws -> [Shared.Asset]
    private let fallback: APTradeApplication.MarketDataRepository

    public convenience init(fallback: APTradeApplication.MarketDataRepository) {
        let repository = Shared.YahooMarketDataRepository()
        let quotesUseCase = Shared.FetchMarketQuotes(repository: repository)
        let historyUseCase = Shared.FetchHistory(repository: repository)
        let candlesUseCase = Shared.FetchCandles(repository: repository)
        let profileUseCase = Shared.FetchProfile(repository: repository)
        let searchUseCase = Shared.FetchSearch(repository: repository)
        self.init(
            fallback: fallback,
            fetch: { try await quotesUseCase.execute(symbols: $0) },
            fetchHistory: { try await historyUseCase.execute(symbol: $0, timeframe: $1) },
            fetchCandles: { try await candlesUseCase.execute(symbol: $0, timeframe: $1) },
            fetchProfile: { try await profileUseCase.execute(symbol: $0) },
            fetchSearch: { try await searchUseCase.execute(query: $0) })
    }

    init(
        fallback: APTradeApplication.MarketDataRepository,
        fetch: @escaping @Sendable ([String]) async throws -> [Shared.Quote],
        fetchHistory: @escaping @Sendable (String, Shared.Timeframe) async throws -> [Shared.PricePoint],
        fetchCandles: @escaping @Sendable (String, Shared.Timeframe) async throws -> [Shared.Candle],
        fetchProfile: @escaping @Sendable (String) async throws -> Shared.Asset,
        fetchSearch: @escaping @Sendable (String) async throws -> [Shared.Asset]
    ) {
        self.fetch = fetch
        self.fetchHistory = fetchHistory
        self.fetchCandles = fetchCandles
        self.fetchProfile = fetchProfile
        self.fetchSearch = fetchSearch
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

    public func history(for symbol: String, timeframe: APTradeDomain.Timeframe) async throws -> [APTradeDomain.PricePoint] {
        do {
            let points = try await fetchHistory(symbol, Self.mapTimeframe(timeframe))
            return try points.map(Self.mapPricePoint)
        } catch {
            throw Self.mapError(error)
        }
    }

    public func candles(for symbol: String, timeframe: APTradeDomain.Timeframe) async throws -> [APTradeDomain.Candle] {
        do {
            let candles = try await fetchCandles(symbol, Self.mapTimeframe(timeframe))
            return try candles.map(Self.mapCandle)
        } catch {
            throw Self.mapError(error)
        }
    }

    public func profile(for symbol: String) async throws -> APTradeDomain.Asset {
        do {
            let asset = try await fetchProfile(symbol)
            return Self.mapAsset(asset)
        } catch {
            throw Self.mapError(error)
        }
    }

    public func search(query: String) async throws -> [APTradeDomain.Asset] {
        do {
            return try await fetchSearch(query).map(Self.mapAsset)
        } catch {
            throw Self.mapError(error)
        }
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

    static func mapPricePoint(_ point: Shared.PricePoint) throws -> APTradeDomain.PricePoint {
        APTradeDomain.PricePoint(
            date: Date(timeIntervalSince1970: TimeInterval(point.epochSeconds)),
            close: try mapMoney(point.close))
    }

    static func mapCandle(_ candle: Shared.Candle) throws -> APTradeDomain.Candle {
        APTradeDomain.Candle(
            date: Date(timeIntervalSince1970: TimeInterval(candle.epochSeconds)),
            open: try mapMoney(candle.open),
            high: try mapMoney(candle.high),
            low: try mapMoney(candle.low),
            close: try mapMoney(candle.close),
            volume: candle.volume)
    }

    static func mapAsset(_ asset: Shared.Asset) -> APTradeDomain.Asset {
        APTradeDomain.Asset(symbol: asset.symbol, name: asset.name, kind: mapAssetKind(asset.kind))
    }

    static func mapAssetKind(_ kind: Shared.AssetKind) -> APTradeDomain.AssetKind {
        switch kind {
        case .stock: return .stock
        case .etf: return .etf
        case .crypto: return .crypto
        // Shared.AssetKind is an ObjC-bridged class, not a closed Swift enum, so this switch cannot be proven exhaustive; this default is unreachable for the 3 real cases and should stay that way.
        default:
            fatalError("Unrecognized Shared.AssetKind case: \(kind) — the Kotlin AssetKind enum gained a case this switch doesn't handle yet")
        }
    }

    static func mapTimeframe(_ timeframe: APTradeDomain.Timeframe) -> Shared.Timeframe {
        switch timeframe {
        case .oneDay: return .oneday
        case .oneWeek: return .oneweek
        case .oneMonth: return .onemonth
        case .oneYear: return .oneyear
        }
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
