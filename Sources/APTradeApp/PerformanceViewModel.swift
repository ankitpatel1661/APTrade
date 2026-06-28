import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class PerformanceViewModel {
    enum State: Equatable {
        case idle
        case loading
        case loaded(PerformanceReport)
        case empty
    }

    private(set) var state: State = .idle
    var timeframe: Timeframe = .oneYear { didSet { if oldValue != timeframe { reload() } } }
    var benchmark: String = "SPY" { didSet { if oldValue != benchmark { reload() } } }

    let benchmarks = ["SPY", "QQQ", "VTI"]

    private let compute: ComputePerformanceMetricsUseCase

    init(compute: ComputePerformanceMetricsUseCase) { self.compute = compute }

    /// Loads once on first appearance; no-op if already loaded/loading.
    func onAppear() async {
        if case .idle = state { await load() }
    }

    /// Recomputes the report for the current timeframe/benchmark selection.
    func load() async {
        state = .loading
        let report = await compute(timeframe: timeframe, benchmark: benchmark)
        state = report.isEmpty ? .empty : .loaded(report)
    }

    private func reload() { Task { await load() } }
}
