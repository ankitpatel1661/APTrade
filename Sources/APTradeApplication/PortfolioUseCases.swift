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
    public init(repository: MarketDataRepository, store: PortfolioStore) {
        self.repository = repository
        self.store = store
    }
    public func callAsFunction(asset: Asset, quantity: Quantity) async throws -> Portfolio {
        let quote = try await repository.quote(for: asset.symbol)
        let updated = try store.load().buying(asset, quantity: quantity, at: quote.price)
        store.save(updated)
        return updated
    }
}

public struct SellAssetUseCase: Sendable {
    private let repository: MarketDataRepository
    private let store: PortfolioStore
    public init(repository: MarketDataRepository, store: PortfolioStore) {
        self.repository = repository
        self.store = store
    }
    public func callAsFunction(symbol: String, quantity: Quantity) async throws -> Portfolio {
        let quote = try await repository.quote(for: symbol)
        let updated = try store.load().selling(symbol, quantity: quantity, at: quote.price)
        store.save(updated)
        return updated
    }
}

public struct ResetPortfolioUseCase: Sendable {
    private let store: PortfolioStore
    public init(store: PortfolioStore) { self.store = store }
    public func callAsFunction() -> Portfolio {
        let fresh = Portfolio.starting()
        store.save(fresh)
        return fresh
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
        if sinceInception, let firstDate = portfolio.transactions.map(\.date).min() {
            let inceptionDay = Calendar.current.startOfDay(for: firstDate)
            let trimmed = series.filter { $0.date >= inceptionDay }
            if !trimmed.isEmpty { series = trimmed }
        }
        return series
    }
}
