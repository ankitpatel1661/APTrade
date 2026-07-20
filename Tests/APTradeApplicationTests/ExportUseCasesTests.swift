import XCTest
@testable import APTradeApplication
import APTradeDomain

// MARK: - Fakes

private final class FakeExportPortfolioStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

/// A minimal `MarketDataRepository` that always misses on quotes, forcing the export to
/// value holdings at cost basis — irrelevant to the dividend math under test here.
private final class FakeExportMarketRepository: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote { throw AppError.notFound }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
    func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] { [] }
    func search(query: String) async throws -> [Asset] { [] }
}

private final class FakeExportDividendRepo: DividendEventsRepository, @unchecked Sendable {
    var events: [String: [DividendEvent]] = [:]
    var errors: [String: Error] = [:]
    private(set) var requestedSymbols: [String] = []
    func dividendEvents(for symbol: String, since: Date) async throws -> [DividendEvent] {
        requestedSymbols.append(symbol)
        if let error = errors[symbol] { throw error }
        return events[symbol] ?? []
    }
}

/// Captures the `PortfolioExport` it was asked to render instead of producing real bytes,
/// so tests can assert on the export's fields directly.
private final class CapturingRenderer: PortfolioExportRenderer, @unchecked Sendable {
    private(set) var lastExport: PortfolioExport?
    func render(_ export: PortfolioExport, as format: PortfolioExportFormat) throws -> Data {
        lastExport = export
        return Data([0x01])
    }
}

final class ExportUseCasesTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func qty(_ s: String) -> Quantity { Quantity(Decimal(string: s) ?? 0) }

    /// A single-position portfolio (10 AAPL shares) with one `.dividend` transaction
    /// dated "now" — always inside the current UTC calendar year regardless of when the
    /// suite runs — crediting $5.00 (10 × $0.50).
    private func portfolioWithDividend() -> Portfolio {
        let asset = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let position = Position(asset: asset, quantity: qty("10"), averageCost: usd("100"), realizedPnL: Money(amount: 0))
        let buy = Transaction(symbol: "AAPL", side: .buy, quantity: qty("10"), price: usd("100"), date: Date().addingTimeInterval(-86_400 * 400))
        let dividend = Transaction(symbol: "AAPL", side: .dividend, quantity: qty("10"), price: usd("0.50"), date: Date())
        return Portfolio(cash: usd("50000"), positions: [position], transactions: [buy, dividend])
    }

    // MARK: (a) A ledger with dividends + a wired repository -> both Decimals populated.

    func test_callAsFunction_withDividendsAndRepository_populatesBothIncomeFields() async throws {
        let store = FakeExportPortfolioStore(portfolioWithDividend())
        let dividends = FakeExportDividendRepo()
        // Trailing-annual per-share = $0.75 (single event inside the 365-day window) ×
        // 10 held shares = $7.50 projected annual income.
        dividends.events["AAPL"] = [DividendEvent(symbol: "AAPL", exDate: Date(), amountPerShare: usd("0.75"))]
        let renderer = CapturingRenderer()
        let sut = ExportPortfolioUseCase(store: store, fetchQuotes: FetchQuotesUseCase(repository: FakeExportMarketRepository()),
                                         renderer: renderer, accountName: "Test", dividendEventsRepository: dividends)

        _ = try await sut(format: .pdf)

        let export = try XCTUnwrap(renderer.lastExport)
        XCTAssertEqual(export.dividendsReceivedYTD, Decimal(string: "5.00"))
        XCTAssertEqual(export.projectedAnnualIncome, Decimal(string: "7.50"))
        XCTAssertEqual(dividends.requestedSymbols, ["AAPL"])
    }

    // MARK: (b) nil repository -> projectedAnnualIncome is 0 (dividendsReceivedYTD still populates).

    func test_callAsFunction_withNilRepository_projectedAnnualIncomeIsZero() async throws {
        let store = FakeExportPortfolioStore(portfolioWithDividend())
        let renderer = CapturingRenderer()
        let sut = ExportPortfolioUseCase(store: store, fetchQuotes: FetchQuotesUseCase(repository: FakeExportMarketRepository()),
                                         renderer: renderer, accountName: "Test")
        // dividendEventsRepository defaults to nil.

        _ = try await sut(format: .pdf)

        let export = try XCTUnwrap(renderer.lastExport)
        XCTAssertEqual(export.projectedAnnualIncome, 0)
        XCTAssertEqual(export.dividendsReceivedYTD, Decimal(string: "5.00"),
                       "YTD dividends are ledger-derived and don't need the repository")
    }

    // MARK: (c) A per-symbol repository failure degrades that symbol to zero, not the whole export.

    func test_callAsFunction_repositoryThrows_degradesToZeroForThatSymbol() async throws {
        let store = FakeExportPortfolioStore(portfolioWithDividend())
        let dividends = FakeExportDividendRepo()
        dividends.errors["AAPL"] = AppError.notFound
        let renderer = CapturingRenderer()
        let sut = ExportPortfolioUseCase(store: store, fetchQuotes: FetchQuotesUseCase(repository: FakeExportMarketRepository()),
                                         renderer: renderer, accountName: "Test", dividendEventsRepository: dividends)

        _ = try await sut(format: .pdf)

        let export = try XCTUnwrap(renderer.lastExport)
        XCTAssertEqual(export.projectedAnnualIncome, 0)
    }

    // MARK: (d) Crypto-only portfolio never calls the repository and projects zero.

    func test_callAsFunction_cryptoOnlyPortfolio_skipsRepositoryAndProjectsZero() async throws {
        let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)
        let position = Position(asset: btc, quantity: qty("1"), averageCost: usd("50000"), realizedPnL: Money(amount: 0))
        let portfolio = Portfolio(cash: usd("50000"), positions: [position], transactions: [])
        let store = FakeExportPortfolioStore(portfolio)
        let dividends = FakeExportDividendRepo()
        let renderer = CapturingRenderer()
        let sut = ExportPortfolioUseCase(store: store, fetchQuotes: FetchQuotesUseCase(repository: FakeExportMarketRepository()),
                                         renderer: renderer, accountName: "Test", dividendEventsRepository: dividends)

        _ = try await sut(format: .pdf)

        let export = try XCTUnwrap(renderer.lastExport)
        XCTAssertEqual(export.projectedAnnualIncome, 0)
        XCTAssertTrue(dividends.requestedSymbols.isEmpty, "crypto positions should never be queried for dividend events")
    }
}
