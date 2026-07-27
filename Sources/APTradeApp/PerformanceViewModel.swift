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
    /// construction sites that predate the all-cash fix keep compiling, reading back an
    /// empty (never a fabricated $100,000) portfolio until a real `PortfolioStore` is
    /// supplied (always the case in production via `CompositionRoot`). Every other inert
    /// default in this file (`NoOpGoalStore.load() -> []`) reads back nothing; this one
    /// must match rather than materialize a specific dollar figure nobody configured.
    private struct NoOpPortfolioStore: PortfolioStore {
        func load() -> Portfolio { .starting(cash: Money(amount: 0)) }
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

    /// Reloads EVERYTHING on every appearance — deliberately ungated, matching
    /// `IncomeSection`'s ungated `.task { await viewModel.load() }` (`IncomeSection.swift:46`).
    ///
    /// The previous `if case .idle = state { await load() }` gate re-read only the goal:
    /// `currentValue`, the equity curve, and the whole metric grid are assigned ONLY inside
    /// `load()`, so once the first load had left `.idle` they froze forever. A portfolio
    /// reset (or any other out-of-band change to the portfolio) then left the value-goal
    /// card quoting a dollar figure from a portfolio that no longer existed — the M11.1 UAT
    /// defect where storage held $500,000 while the card rendered "$100,115.77 / $1,200,000".
    ///
    /// The goal is re-read up front, before the (network-bound) recompute, so the card
    /// corrects itself immediately rather than a round-trip later; `load()` re-reads it
    /// again at the end, which is harmless.
    ///
    /// No `loadTask?.cancel()` here on purpose: `load()` does not poll `Task.isCancelled`
    /// and `ComputePerformanceMetricsUseCase` swallows every failure into an EMPTY report
    /// (`try?`), so cancelling a same-selection load would let the cancelled task's empty
    /// result pass `load()`'s requested-vs-current guard and blank a good report. The guard
    /// is what keeps a SUPERSEDED result from landing; SwiftUI's `.task` cancellation is what
    /// bounds this one's lifetime.
    ///
    /// The guard does NOT dedupe. Two concurrent loads for the SAME timeframe/benchmark — a
    /// timeframe reload still in flight when the view re-appears — both run the full network
    /// fan-out and both write state. They compute the same report, so the outcome is correct,
    /// but do not read the guard as a de-duplicator: it only rejects results whose selection
    /// no longer matches.
    /// M11.3 whole-branch review (minor): the seed on the first line is load-bearing and must
    /// stay ABOVE the goal read. `valueGoal` is published here, before the network-bound
    /// `load()`; `currentValue` was assigned only inside `load()`, so on a COLD LAUNCH there
    /// was a whole fetch's worth of time where a goal existed against a `currentValue` still
    /// sitting at its `Money(amount: 0)` initial value. The header strip rendered
    /// `GOAL ▬ $120,000 0%` and the goal card rendered "not on track" (`yearsToTarget` bails
    /// on `current > 0`) for the duration — on a funded portfolio, on every launch. Neither
    /// Kotlin platform shows this: both publish `state.valueGoal` only after the current value
    /// is set.
    ///
    /// Seeding — rather than moving the goal read below the `await` — is what keeps this
    /// method's contract intact. The early goal read is not incidental: it is M11.1 UAT F3, so
    /// a reset (which reaches this method via `onDidReset`) corrects a stale goal immediately
    /// instead of a network round-trip later. Deferring it would trade this flash for that
    /// regression. Seeding fixes both readings at once and costs one synchronous store read.
    ///
    /// Guarded on `== 0` so it can only ever fill in a value that was never computed: on a
    /// re-appearance `currentValue` already holds a real (curve-derived) figure and must not
    /// be clobbered by the lower cost-basis floor, which would flicker the number downwards.
    /// A genuinely empty portfolio floors to 0 as well, so the seed is a no-op there rather
    /// than a fabrication.
    func onAppear() async {
        if currentValue.amount == 0 { currentValue = costBasisFloor() }
        valueGoal = loadGoals().first { $0.kind == .value }
        refreshValueProjection()
        await load()
    }

    /// Cash plus every position's OWN cost basis — the honest floor under the account's value
    /// when no priced equity curve is available. Extracted so `load()`'s empty-curve branch
    /// and `onAppear()`'s cold-launch seed cannot drift into two different floors; see
    /// `load()` for why this figure, and never a hardcoded zero, is the right fallback.
    private func costBasisFloor() -> Money {
        let portfolio = fetchPortfolio()
        return portfolio.positions.reduce(portfolio.cash) {
            $0 + $1.marketValue(at: $1.averageCost)
        }
    }

    /// Recomputes the report for the current timeframe/benchmark selection.
    ///
    /// ⚠️ This method does not poll `Task.isCancelled`, and `ComputePerformanceMetricsUseCase`
    /// swallows a cancellation into `PerformanceReport.empty` like any other failure. So a
    /// `.task`-cancelled load (view dismissed mid-flight) still runs to completion and writes
    /// `.empty` plus a cash-floor `currentValue` over whatever good report was there. That
    /// pre-dates this milestone; what makes it self-heal today is F3 — `onAppear()` reloads
    /// unconditionally, so the next appearance repairs the state. **Re-introducing an
    /// `.idle`-style gate "for performance" would therefore resurrect a stale-value bug this
    /// method has always had.** The durable fix is a cancellation check here, deliberately not
    /// bundled into the UAT fixes.
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
        // returns an empty report (and hence an empty curve) in (at least) two distinct
        // situations:
        //   1. Genuinely all-cash (`portfolio.positions.isEmpty`) — durable, not transient.
        //   2. Positions exist but their priced history fetch failed or came back too thin
        //      (`ComputePerformanceMetricsUseCase` swallows every history failure via
        //      `try?`) — offline, rate-limited, or shared-core-error sessions all land
        //      here, and this is common, not exotic.
        // Neither case may ever fabricate a dollar figure nobody's portfolio actually
        // holds: the floor is cash plus every position's OWN cost basis (which collapses
        // to just `cash` when there are no positions, preserving the all-cash reading
        // exactly) — never a hardcoded zero that would understate a real value-goal
        // card's progress.
        equityCurve = report.equityCurve
        if let last = report.equityCurve.last {
            currentValue = last.value
        } else {
            currentValue = costBasisFloor()
        }
        valueGoal = loadGoals().first { $0.kind == .value }
        refreshValueProjection()
    }

    /// Recomputes the value-goal card from the CURRENT goal / value / curve snapshot.
    ///
    /// The account age fed to `GoalMath` is derived FRESH on every call from a fresh
    /// `fetchPortfolio()` read — never cached and never gated on a first-load flag —
    /// because `ResetPortfolioUseCase` can replace the portfolio out from under this view
    /// model, and `onAppear()` reloads on every appearance for exactly that reason. A cached
    /// age would let a reset portfolio keep projecting off the age of the account it
    /// replaced. (The GOAL itself survives a reset since M11.1 UAT F1 — it is the age and
    /// the current value that must not.)
    private func refreshValueProjection() {
        guard let goal = valueGoal else { valueGoalProjection = nil; return }
        // ACCOUNT age, not the price window's span: fed from the ONE named derivation
        // `Portfolio.inceptionDate`, the same signal `FetchPortfolioPerformanceUseCase`'s
        // `sinceInception` trim uses, so the metric and the floor cannot drift apart. A
        // brand-new account holding a seasoned symbol therefore honestly reports
        // insufficient history instead of extrapolating three weeks of price movement.
        let accountAgeDays = GoalMath.accountAgeDays(inception: fetchPortfolio().inceptionDate,
                                                     asOf: now())
        valueGoalProjection = GoalMath.valueProjection(current: currentValue,
                                                       target: goal.target,
                                                       curve: equityCurve,
                                                       accountAgeDays: accountAgeDays)
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
