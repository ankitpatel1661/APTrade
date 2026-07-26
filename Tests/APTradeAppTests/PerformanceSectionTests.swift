import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

/// Pins `PerformanceSection`'s state → body mapping (M11.1 UAT F4).
///
/// The section used to switch on `viewModel.state` in `content` itself, rendering a bare
/// `ProgressView()` for `.idle`/`.loading` — so the value-goal card, which its own doc
/// comment promises "must render even before the user holds a single position", did not
/// exist at all until the first report landed. `content` now has no state switch: the goal
/// card is unconditional and the ONLY switch left is this mapping, for the body below it.
///
/// The mapping is exhaustive over `PerformanceViewModel.State`, so a future state cannot be
/// added without deciding — at compile time — which body it drives, rather than silently
/// taking the whole section (goal card included) away again.
@MainActor
final class PerformanceSectionTests: XCTestCase {
    private var loadedReport: PerformanceReport {
        PerformanceReport(
            metrics: PerformanceMetrics(totalReturn: 0.12, annualizedReturn: 0.10, volatility: 0.2,
                                        maxDrawdown: -0.05, sharpe: 0.4, beta: 1.0, alpha: 0.01),
            equityCurve: [EquityPoint(date: Date(timeIntervalSince1970: 0), value: Money(amount: 1_000))],
            benchmarkCurve: [], benchmarkSymbol: "SPY",
            concentration: 0.5, effectiveHoldings: 2, warnings: [], isEmpty: false)
    }

    /// Rejects mapping `.idle` to `.empty` or `.report`: before the very first load there is
    /// nothing to report on yet, and claiming "not enough history" then would be a guess.
    func test_idle_drivesSpinner() {
        XCTAssertEqual(PerformanceSection.reportBody(for: .idle), .spinner)
    }

    /// Rejects mapping `.loading` to `.empty` — the specific regression F3 makes reachable:
    /// every appearance now re-enters `.loading`, so an `.empty` mapping would flash
    /// "Not enough history yet" across a portfolio that has plenty, on every visit.
    func test_loading_drivesSpinner_notTheEmptyState() {
        XCTAssertEqual(PerformanceSection.reportBody(for: .loading), .spinner)
        XCTAssertNotEqual(PerformanceSection.reportBody(for: .loading), .empty)
    }

    /// Rejects collapsing `.empty` into the spinner: a genuinely all-cash portfolio is a
    /// durable state, not a transient one, and must read as the empty state — beneath a goal
    /// card that still renders — rather than spinning forever.
    func test_empty_drivesEmptyState() {
        XCTAssertEqual(PerformanceSection.reportBody(for: .empty), .empty)
    }

    /// Rejects dropping the associated value (e.g. a `.report` case carrying no report, or
    /// re-reading the state a second time inside the view): the body must render the SAME
    /// report the mapping was given.
    func test_loaded_drivesReportCarryingThatExactReport() {
        let report = loadedReport
        XCTAssertEqual(PerformanceSection.reportBody(for: .loaded(report)), .report(report))
    }
}
