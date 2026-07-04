import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

/// Returns a rising history for any symbol; a flag lets the benchmark fetch "fail" (empty).
private final class RisingRepo: MarketDataRepository, @unchecked Sendable {
    let benchmarkFails: Bool
    init(benchmarkFails: Bool = false) { self.benchmarkFails = benchmarkFails }
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 110), previousClose: Money(amount: 100))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        if benchmarkFails && symbol == "SPY" { return [] }
        return (0..<10).map { i in
            PricePoint(date: Date(timeIntervalSince1970: TimeInterval(i) * 86_400),
                       close: Money(amount: Decimal(100 + i)))   // 100,101,...,109
        }
    }
}

/// Serves a fixed holding history (AAPL, days 5..9) and a benchmark (SPY) whose closes
/// START BEFORE the holding history — days 0..9. The pre-curve benchmark closes (days 0..4)
/// must be head-trimmed out of the metrics input so beta/alpha describe the same span.
private final class StaggeredRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 110), previousClose: Money(amount: 100))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        if symbol == "SPY" {
            // Benchmark trades all 10 days; days 0..4 predate the equity curve start.
            return (0..<10).map { i in
                PricePoint(date: Date(timeIntervalSince1970: TimeInterval(i) * 86_400),
                           close: Money(amount: Decimal(200 + i)))
            }
        }
        // Holding history only from day 5 → equity curve starts at day 5.
        return (5..<10).map { i in
            PricePoint(date: Date(timeIntervalSince1970: TimeInterval(i) * 86_400),
                       close: Money(amount: Decimal(100 + i)))
        }
    }
}

final class PerformanceUseCasesTests: XCTestCase {
    private func portfolioWithAAPL() -> Portfolio {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        return try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100),
                    on: Date(timeIntervalSince1970: 0))
    }

    func test_emptyPortfolio_returnsEmptyReport() async {
        let useCase = ComputePerformanceMetricsUseCase(repository: RisingRepo(), store: MemoryStore(.starting()))
        let report = await useCase(timeframe: .oneYear, benchmark: "SPY")
        XCTAssertTrue(report.isEmpty)
    }

    func test_computesMetricsAndBenchmark() async {
        let useCase = ComputePerformanceMetricsUseCase(repository: RisingRepo(), store: MemoryStore(portfolioWithAAPL()))
        let report = await useCase(timeframe: .oneYear, benchmark: "SPY")
        XCTAssertFalse(report.isEmpty)
        XCTAssertGreaterThan(report.equityCurve.count, 1)
        XCTAssertGreaterThan(report.metrics.totalReturn, 0)          // rising prices
        XCTAssertNotNil(report.metrics.beta)                          // benchmark available
        XCTAssertEqual(report.benchmarkSymbol, "SPY")
        XCTAssertFalse(report.benchmarkCurve.isEmpty)
        XCTAssertEqual(report.concentration, 1.0, accuracy: 1e-9)     // single holding
    }

    func test_benchmarkFailure_stillReturnsPortfolioMetrics() async {
        let useCase = ComputePerformanceMetricsUseCase(repository: RisingRepo(benchmarkFails: true), store: MemoryStore(portfolioWithAAPL()))
        let report = await useCase(timeframe: .oneYear, benchmark: "SPY")
        XCTAssertFalse(report.isEmpty)
        XCTAssertGreaterThan(report.metrics.totalReturn, 0)
        XCTAssertNil(report.metrics.beta)            // no benchmark → no beta
        XCTAssertNil(report.metrics.alpha)
        XCTAssertTrue(report.benchmarkCurve.isEmpty)
    }

    /// Head-trim (increment 6b.3): benchmark closes that predate the equity curve's first
    /// date must be excluded before the count>1 gate, so the overlay and beta/alpha describe
    /// the same span. Equity curve starts at day 5; SPY has closes from day 0.
    func test_benchmarkHeadTrimmedToCurveStart() async {
        let useCase = ComputePerformanceMetricsUseCase(repository: StaggeredRepo(),
                                                       store: MemoryStore(portfolioWithAAPL()))
        let report = await useCase(timeframe: .oneYear, benchmark: "SPY")

        XCTAssertFalse(report.isEmpty)
        let curveStart = report.equityCurve.first!.date
        // No benchmark point may precede the curve start.
        XCTAssertFalse(report.benchmarkCurve.contains { $0.date < curveStart })
        // SPY served 10 closes (days 0..9); trimming to day 5 leaves exactly 5.
        XCTAssertEqual(report.benchmarkCurve.count, 5)
        XCTAssertEqual(report.benchmarkCurve.first?.date, curveStart)
        XCTAssertNotNil(report.metrics.beta)
    }
}
