import XCTest
@testable import APTradeDomain

final class RiskMetricsTests: XCTestCase {
    func test_dailyReturns_computesSimpleReturns() {
        let r = RiskMetrics.dailyReturns([100, 110, 99])
        XCTAssertEqual(r.count, 2)
        XCTAssertEqual(r[0], 0.10, accuracy: 1e-9)
        XCTAssertEqual(r[1], -0.10, accuracy: 1e-9)
    }

    func test_dailyReturns_shortInput_returnsEmpty() {
        XCTAssertTrue(RiskMetrics.dailyReturns([100]).isEmpty)
        XCTAssertTrue(RiskMetrics.dailyReturns([]).isEmpty)
    }

    func test_totalReturn_isEndOverStart() {
        XCTAssertEqual(RiskMetrics.totalReturn([100, 150]), 0.5, accuracy: 1e-9)
        XCTAssertEqual(RiskMetrics.totalReturn([100]), 0, accuracy: 1e-9)
    }

    func test_annualizedReturn_compoundsToPeriodsPerYear() {
        // 1% over 1 day, annualized over 252 trading days.
        let a = RiskMetrics.annualizedReturn([100, 101], periodsPerYear: 252)
        XCTAssertEqual(a, pow(1.01, 252) - 1, accuracy: 1e-6)
    }

    func test_annualizedVolatility_scalesBySqrtPeriods() {
        let returns = [0.01, -0.01, 0.02, -0.02]
        let mean = returns.reduce(0, +) / 4
        let variance = returns.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / 3
        let expected = (variance).squareRoot() * (252.0).squareRoot()
        XCTAssertEqual(RiskMetrics.annualizedVolatility(returns), expected, accuracy: 1e-9)
    }

    func test_maxDrawdown_findsWorstPeakToTrough() {
        // peak 120 then trough 90 → -25%
        let dd = RiskMetrics.maxDrawdown([100, 120, 90, 110])
        XCTAssertEqual(dd, 90.0 / 120.0 - 1, accuracy: 1e-9)
    }

    func test_sharpe_nilWhenZeroVolatility() {
        XCTAssertNil(RiskMetrics.sharpe(annualizedReturn: 0.1, annualizedVolatility: 0, riskFree: 0.04))
        XCTAssertEqual(RiskMetrics.sharpe(annualizedReturn: 0.10, annualizedVolatility: 0.20, riskFree: 0.04)!,
                       (0.10 - 0.04) / 0.20, accuracy: 1e-9)
    }

    func test_beta_ofIdenticalSeriesIsOne() {
        let r = [0.01, -0.02, 0.03, -0.01]
        XCTAssertEqual(RiskMetrics.beta(portfolioReturns: r, benchmarkReturns: r)!, 1.0, accuracy: 1e-9)
    }

    func test_beta_nilWhenMismatchedOrZeroVariance() {
        XCTAssertNil(RiskMetrics.beta(portfolioReturns: [0.01, 0.02], benchmarkReturns: [0.01]))
        XCTAssertNil(RiskMetrics.beta(portfolioReturns: [0.01, 0.02], benchmarkReturns: [0.0, 0.0]))
    }

    func test_alpha_isZeroWhenReturnMatchesCAPM() {
        // If annualReturn == riskFree + beta*(bench - riskFree), alpha == 0.
        let alpha = RiskMetrics.alpha(annualizedReturn: 0.04 + 1.2 * (0.10 - 0.04),
                                      beta: 1.2, benchmarkAnnualizedReturn: 0.10, riskFree: 0.04)
        XCTAssertEqual(alpha, 0, accuracy: 1e-9)
    }
}
