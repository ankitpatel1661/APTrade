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
    private var loadTask: Task<Void, Never>?

    init(compute: ComputePerformanceMetricsUseCase) { self.compute = compute }

    /// Loads once on first appearance; no-op if already loaded/loading.
    func onAppear() async {
        if case .idle = state { await load() }
    }

    /// Recomputes the report for the current timeframe/benchmark selection.
    func load() async {
        state = .loading
        let requestedTimeframe = timeframe
        let requestedBenchmark = benchmark
        let report = await compute(timeframe: requestedTimeframe, benchmark: requestedBenchmark)
        // Ignore a result a newer selection has already superseded.
        guard requestedTimeframe == timeframe, requestedBenchmark == benchmark else { return }
        state = report.isEmpty ? .empty : .loaded(report)
    }

    private func reload() {
        loadTask?.cancel()
        loadTask = Task { await load() }
    }
}
