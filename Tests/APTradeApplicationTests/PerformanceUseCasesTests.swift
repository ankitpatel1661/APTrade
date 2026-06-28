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
}
