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

    private(set) var valueGoal: PortfolioGoal?
    private(set) var valueGoalProjection: GoalProjection?
    private(set) var currentValue: Money = Money(amount: 0)
    private var equityCurve: [EquityPoint] = []

    private let compute: ComputePerformanceMetricsUseCase
    private let loadGoals: LoadGoalsUseCase
    private let saveGoal: SaveGoalUseCase
    private let removeGoal: RemoveGoalUseCase
    private let fetchPortfolio: FetchPortfolioUseCase
    private let now: () -> Date
    private var loadTask: Task<Void, Never>?

    /// Inert default for `loadGoals`/`saveGoal`/`removeGoal` so existing construction
    /// sites (tests predating goals) keep compiling without wiring a real `GoalStore`.
    /// Mirrors `IncomeViewModel`'s `NoOpGoalStore` rationale — goals simply read back
    /// empty and writes are discarded until a real store is supplied (always the case in
    /// production via `CompositionRoot`).
    private struct NoOpGoalStore: GoalStore {
        func load() -> [PortfolioGoal] { [] }
        func save(_ goals: [PortfolioGoal]) {}
    }

    /// Inert default for `fetchPortfolio` — same rationale as `NoOpGoalStore`: existing
    /// construction sites that predate the all-cash fix keep compiling, reading back a
    /// fresh `.starting()` portfolio until a real `PortfolioStore` is supplied (always the
    /// case in production via `CompositionRoot`).
    private struct NoOpPortfolioStore: PortfolioStore {
        func load() -> Portfolio { .starting() }
        func save(_ portfolio: Portfolio) {}
    }

    init(compute: ComputePerformanceMetricsUseCase,
         loadGoals: LoadGoalsUseCase = LoadGoalsUseCase(store: NoOpGoalStore()),
         saveGoal: SaveGoalUseCase = SaveGoalUseCase(store: NoOpGoalStore()),
         removeGoal: RemoveGoalUseCase = RemoveGoalUseCase(store: NoOpGoalStore()),
         fetchPortfolio: FetchPortfolioUseCase = FetchPortfolioUseCase(store: NoOpPortfolioStore()),
         now: @escaping () -> Date = Date.init) {
        self.compute = compute
        self.loadGoals = loadGoals
        self.saveGoal = saveGoal
        self.removeGoal = removeGoal
        self.fetchPortfolio = fetchPortfolio
        self.now = now
    }

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
        // `PerformanceReport` has no dedicated "current value" field — the equity curve's
        // last point *is* the current total account value (cash + holdings), so read it
        // from there rather than adding a second data path. `ComputePerformanceMetricsUseCase`
        // returns an empty report (and hence an empty curve) in two distinct situations:
        //   1. Genuinely all-cash (`portfolio.positions.isEmpty`) — durable, not transient.
        //      Here "current value" is simply the cash balance, read through
        //      `FetchPortfolioUseCase` (never a raw store) so a value-goal card is usable
        //      from day one, before the first trade.
        //   2. Positions exist but their priced history is too thin (`equity.count <= 1`)
        //      — transient (the first day or two of a new position). Fixing that would mean
        //      changing `ComputePerformanceMetricsUseCase`'s behavior for every consumer, so
        //      it's out of scope here: `currentValue` keeps its old zero fallback in that case.
        equityCurve = report.equityCurve
        if let last = report.equityCurve.last {
            currentValue = last.value
        } else {
            let portfolio = fetchPortfolio()
            currentValue = portfolio.positions.isEmpty ? portfolio.cash : Money(amount: 0)
        }
        valueGoal = loadGoals().first { $0.kind == .value }
        refreshValueProjection()
    }

    private func refreshValueProjection() {
        guard let goal = valueGoal else { valueGoalProjection = nil; return }
        valueGoalProjection = GoalMath.valueProjection(current: currentValue,
                                                       target: goal.target,
                                                       curve: equityCurve)
    }

    func setValueGoal(_ target: Money) {
        let goal = PortfolioGoal(kind: .value, target: target, createdAt: now())
        saveGoal(goal)
        valueGoal = goal
        refreshValueProjection()
    }

    func removeValueGoal() {
        removeGoal(kind: .value)
        valueGoal = nil
        valueGoalProjection = nil
    }

    private func reload() {
        loadTask?.cancel()
        loadTask = Task { await load() }
    }
}
