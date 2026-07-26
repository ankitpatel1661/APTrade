import Foundation
import APTradeDomain

public struct FetchPortfolioUseCase: Sendable {
    private let store: PortfolioStore
    public init(store: PortfolioStore) { self.store = store }
    public func callAsFunction() -> Portfolio { store.load() }
}

public struct BuyAssetUseCase: Sendable {
    private let repository: MarketDataRepository
    private let store: PortfolioStore
    private let serializer: TradeSerializer
    public init(repository: MarketDataRepository, store: PortfolioStore, serializer: TradeSerializer) {
        self.repository = repository
        self.store = store
        self.serializer = serializer
    }
    /// The whole load-modify-save sequence — including the quote fetch that prices the
    /// buy — runs inside `serializer.run`, matching `ContributeToPie`'s pattern: a
    /// manual trade and a pie mutation write the same `portfolioStore`, so both must
    /// serialize against each other or one can silently clobber the other's save.
    public func callAsFunction(asset: Asset, quantity: Quantity) async throws -> Portfolio {
        try await serializer.run {
            let quote = try await self.repository.quote(for: asset.symbol)
            let updated = try self.store.load().buying(asset, quantity: quantity, at: quote.price)
            self.store.save(updated)
            return updated
        }
    }
}

public struct SellAssetUseCase: Sendable {
    private let repository: MarketDataRepository
    private let store: PortfolioStore
    private let serializer: TradeSerializer
    public init(repository: MarketDataRepository, store: PortfolioStore, serializer: TradeSerializer) {
        self.repository = repository
        self.store = store
        self.serializer = serializer
    }
    /// Serialized like `BuyAssetUseCase` — see its doc comment.
    public func callAsFunction(symbol: String, quantity: Quantity) async throws -> Portfolio {
        try await serializer.run {
            let quote = try await self.repository.quote(for: symbol)
            let updated = try self.store.load().selling(symbol, quantity: quantity, at: quote.price)
            self.store.save(updated)
            return updated
        }
    }
}

public struct ResetPortfolioUseCase: Sendable {
    private let store: PortfolioStore
    private let serializer: TradeSerializer

    public init(store: PortfolioStore, serializer: TradeSerializer) {
        self.store = store
        self.serializer = serializer
    }
    /// Opens a fresh portfolio at `startingCash`. Goals are deliberately NOT touched.
    ///
    /// This use case took a `goalStore: GoalStore? = nil` and cleared every goal until M11.1
    /// UAT F1. The user ruled that wrong on 2026-07-27: resetting starting capital is "start
    /// over with more money", not "abandon my plan" — a $120,000 value goal survives a reset
    /// to $1,000,000 and simply reads as reached. The dependency is REMOVED rather than left
    /// unused: an optional defaulting to `nil` for compile convenience is exactly how goal
    /// clearing could be re-armed by a later edit. `GoalUseCasesTests.test_reset_leavesGoalsIntact`
    /// is the behavioural record; the Kotlin twin (`shared/.../application/ResetPortfolio.kt`)
    /// dropped its own `goalStore` in the same change.
    ///
    /// Serialized like `BuyAssetUseCase` — a reset overwriting the portfolio while a
    /// buy/sell/pie mutation is mid-flight would otherwise silently discard it (or vice
    /// versa).
    public func callAsFunction(startingCash: Money) async -> Portfolio {
        await serializer.run {
            let fresh = Portfolio.starting(cash: startingCash)
            self.store.save(fresh)
            return fresh
        }
    }
}

public struct RecordPortfolioSnapshotUseCase: Sendable {
    private let store: PortfolioHistoryStore
    public init(store: PortfolioHistoryStore) { self.store = store }
    public func callAsFunction(totalValue: Money, date: Date = Date()) {
        store.record(PricePoint(date: date, close: totalValue))
    }
}

public struct FetchPortfolioHistoryUseCase: Sendable {
    private let store: PortfolioHistoryStore
    public init(store: PortfolioHistoryStore) { self.store = store }
    public func callAsFunction() -> [PricePoint] { store.load() }
}

public struct ClearPortfolioHistoryUseCase: Sendable {
    private let store: PortfolioHistoryStore
    public init(store: PortfolioHistoryStore) { self.store = store }
    public func callAsFunction() { store.clear() }
}

/// Reconstructs the portfolio's value / unrealized-P&L curve over a timeframe from real
/// historical prices, fetching each held symbol's history concurrently. This produces a
/// dense, meaningful series — unlike the sparse in-session value snapshots.
public struct FetchPortfolioPerformanceUseCase: Sendable {
    private let repository: MarketDataRepository
    private let store: PortfolioStore

    public init(repository: MarketDataRepository, store: PortfolioStore) {
        self.repository = repository
        self.store = store
    }

    /// Builds the performance curve over `timeframe`. When `sinceInception` is set, the
    /// curve is trimmed to start at the first transaction's day, so a "Max / Since purchase"
    /// view begins when the portfolio actually started rather than at the fetch-range edge.
    public func callAsFunction(timeframe: Timeframe,
                               sinceInception: Bool = false) async -> [PortfolioPerformancePoint] {
        let portfolio = store.load()
        guard !portfolio.positions.isEmpty else { return [] }

        var histories: [String: [PricePoint]] = [:]
        await withTaskGroup(of: (String, [PricePoint]).self) { group in
            for position in portfolio.positions {
                let symbol = position.asset.symbol
                let repository = repository
                group.addTask {
                    let points = (try? await repository.history(for: symbol, timeframe: timeframe)) ?? []
                    return (symbol, points)
                }
            }
            for await (symbol, points) in group { histories[symbol] = points }
        }

        var series = portfolio.performanceSeries(histories: histories)
        // `Portfolio.inceptionDate` is the ONE named derivation of the account's first
        // transaction date, shared with `GoalMath`'s account-age history floor so this trim
        // and that floor cannot drift apart. Do not re-derive it locally here.
        if sinceInception, let firstDate = portfolio.inceptionDate {
            let inceptionDay = Calendar.current.startOfDay(for: firstDate)
            let trimmed = series.filter { $0.date >= inceptionDay }
            if !trimmed.isEmpty { series = trimmed }
        }
        return series
    }
}
