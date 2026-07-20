import Foundation
import APTradeDomain

/// Turns a prepared `PortfolioExport` into the bytes of a concrete document format.
/// A port so the application layer never imports CoreGraphics or knows the OOXML wire
/// format — concrete renderers live in the infrastructure layer.
public protocol PortfolioExportRenderer: Sendable {
    func render(_ export: PortfolioExport, as format: PortfolioExportFormat) throws -> Data
}

/// Assembles the current portfolio (valued against freshly fetched quotes) and renders it
/// to the requested document format, returning the file bytes for the caller to save.
public struct ExportPortfolioUseCase: Sendable {
    /// How far back to fetch dividend events when projecting annual income: two years
    /// covers the trailing-annual window (365d) plus enough history for cadence inference
    /// on slower-paying assets. Mirrors `IncomeViewModel.lookbackStart`.
    private static let dividendEventsLookback: TimeInterval = 730 * 86_400

    private let store: PortfolioStore
    private let fetchQuotes: FetchQuotesUseCase
    private let renderer: PortfolioExportRenderer
    private let accountName: String
    private let dividendEventsRepository: DividendEventsRepository?

    public init(store: PortfolioStore, fetchQuotes: FetchQuotesUseCase,
                renderer: PortfolioExportRenderer, accountName: String,
                dividendEventsRepository: DividendEventsRepository? = nil) {
        self.store = store
        self.fetchQuotes = fetchQuotes
        self.renderer = renderer
        self.accountName = accountName
        self.dividendEventsRepository = dividendEventsRepository
    }

    public func callAsFunction(format: PortfolioExportFormat) async throws -> Data {
        let portfolio = store.load()
        var quotes: [String: Quote] = [:]
        let symbols = portfolio.positions.map { $0.asset.symbol }
        if !symbols.isEmpty {
            for (symbol, result) in await fetchQuotes(symbols: symbols) {
                if case .success(let quote) = result { quotes[symbol] = quote }
            }
        }
        let generatedAt = Date()
        let projectedAnnualIncome = await projectedAnnualIncome(portfolio: portfolio, asOf: generatedAt)
        let export = PortfolioExport(portfolio: portfolio, quotes: quotes, accountName: accountName,
                                     generatedAt: generatedAt, projectedAnnualIncome: projectedAnnualIncome)
        return try renderer.render(export, as: format)
    }

    /// Forward 12-month dividend income from held, non-crypto positions. Returns `0` when
    /// there's no repository to source events from (e.g. export is used before the shared
    /// dividend-events facet is wired for a given platform). A per-symbol fetch failure
    /// degrades only that symbol to zero events — it never blocks the others, mirroring
    /// `IncomeViewModel`'s failure-isolation policy.
    private func projectedAnnualIncome(portfolio: Portfolio, asOf: Date) async -> Decimal {
        guard let dividendEventsRepository else { return 0 }
        let nonCryptoPositions = portfolio.positions.filter { $0.asset.kind != .crypto }
        guard !nonCryptoPositions.isEmpty else { return 0 }

        let since = asOf.addingTimeInterval(-Self.dividendEventsLookback)
        var eventsBySymbol: [String: [DividendEvent]] = [:]
        for position in nonCryptoPositions {
            let symbol = position.asset.symbol
            do {
                eventsBySymbol[symbol] = try await dividendEventsRepository.dividendEvents(for: symbol, since: since)
            } catch {
                eventsBySymbol[symbol] = []
            }
        }
        return DividendMath.projectedAnnualIncome(positions: portfolio.positions,
                                                   eventsBySymbol: eventsBySymbol, asOf: asOf).amount
    }
}
