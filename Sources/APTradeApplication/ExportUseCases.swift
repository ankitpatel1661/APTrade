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
    private let store: PortfolioStore
    private let fetchQuotes: FetchQuotesUseCase
    private let renderer: PortfolioExportRenderer
    private let accountName: String

    public init(store: PortfolioStore, fetchQuotes: FetchQuotesUseCase,
                renderer: PortfolioExportRenderer, accountName: String) {
        self.store = store
        self.fetchQuotes = fetchQuotes
        self.renderer = renderer
        self.accountName = accountName
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
        let export = PortfolioExport(portfolio: portfolio, quotes: quotes, accountName: accountName)
        return try renderer.render(export, as: format)
    }
}
