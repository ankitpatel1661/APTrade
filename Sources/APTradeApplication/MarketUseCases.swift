import APTradeDomain

public struct FetchQuotesUseCase {
    private let repository: MarketDataRepository
    public init(repository: MarketDataRepository) { self.repository = repository }

    public func callAsFunction(symbols: [String]) async -> [String: Result<Quote, AppError>] {
        let repository = self.repository
        return await withTaskGroup(of: (String, Result<Quote, AppError>).self) { group in
            for symbol in symbols {
                group.addTask {
                    do {
                        let q = try await repository.quote(for: symbol)
                        return (symbol, .success(q))
                    } catch {
                        return (symbol, .failure((error as? AppError) ?? .network))
                    }
                }
            }
            var out: [String: Result<Quote, AppError>] = [:]
            for await (symbol, result) in group { out[symbol] = result }
            return out
        }
    }
}

public struct FetchHistoryUseCase {
    private let repository: MarketDataRepository
    public init(repository: MarketDataRepository) { self.repository = repository }

    public func callAsFunction(symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        try await repository.history(for: symbol, timeframe: timeframe)
    }
}

public struct SearchSymbolUseCase {
    private let repository: MarketDataRepository
    public init(repository: MarketDataRepository) { self.repository = repository }

    public func callAsFunction(query: String) async throws -> Asset {
        let symbol = query.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !symbol.isEmpty else { throw AppError.notFound }
        _ = try await repository.quote(for: symbol)   // validates existence
        let kind: AssetKind = symbol.hasSuffix("-USD") ? .crypto : .stock
        return Asset(symbol: symbol, name: symbol, kind: kind)
    }
}
