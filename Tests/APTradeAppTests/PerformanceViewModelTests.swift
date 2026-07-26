import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

private final class RisingRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 110), previousClose: Money(amount: 100))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        (0..<10).map { i in
            PricePoint(date: Date(timeIntervalSince1970: TimeInterval(i) * 86_400),
                       close: Money(amount: Decimal(100 + i)))
        }
    }
}

/// Every `history(for:timeframe:)` call throws — reproduces the offline/rate-limited/
/// shared-core-error path `ComputePerformanceMetricsUseCase` swallows via `try?`
/// (`PerformanceUseCases.swift:61`), which yields an empty equity curve even though the
/// portfolio holds positions. Distinct from the all-cash empty-curve route.
private final class FailingHistoryRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 110), previousClose: Money(amount: 100))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        throw AppError.network
    }
}

/// 500 consecutive daily closes drifting gently upwards, so the equity curve spans ~499
/// days and clears `GoalMath.minimumHistoryDays`' CURVE-SPAN gate outright. That leaves the
/// ACCOUNT-AGE gate as the only thing a projection test can be moving.
private final class SeasonedPriceRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 125), previousClose: Money(amount: 124))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        (0..<500).map { i in
            PricePoint(date: Date(timeIntervalSince1970: TimeInterval(i) * 86_400),
                       close: Money(amount: Decimal(100) + Decimal(i) / 20))
        }
    }
}

private final class InMemoryGoalStore: GoalStore, @unchecked Sendable {
    private var goals: [PortfolioGoal]
    init(_ goals: [PortfolioGoal] = []) { self.goals = goals }
    func load() -> [PortfolioGoal] { goals }
    func save(_ goals: [PortfolioGoal]) { self.goals = goals }
}

@MainActor
final class PerformanceViewModelTests: XCTestCase {
    private func vm(_ portfolio: Portfolio) -> PerformanceViewModel {
        PerformanceViewModel(compute: ComputePerformanceMetricsUseCase(
            repository: RisingRepo(), store: MemoryStore(portfolio)))
    }

    /// Wires a `PerformanceViewModel` over `portfolio` (defaulting to a holding portfolio,
    /// so `load()` reaches `.loaded` and populates the equity curve the value-goal
    /// projection reads) and a caller-supplied `GoalStore`. `compute` and `fetchPortfolio`
    /// share the same underlying `MemoryStore` instance so both read the same portfolio.
    private func makeSUT(portfolio: Portfolio? = nil,
                         goalStore: GoalStore = InMemoryGoalStore(),
                         now: Date = Date(timeIntervalSince1970: 1_000_000)) -> PerformanceViewModel {
        let resolvedPortfolio: Portfolio
        if let portfolio {
            resolvedPortfolio = portfolio
        } else {
            let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
            resolvedPortfolio = try! Portfolio.starting()
                .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100),
                        on: Date(timeIntervalSince1970: 0))
        }
        let store = MemoryStore(resolvedPortfolio)
        return PerformanceViewModel(
            compute: ComputePerformanceMetricsUseCase(repository: RisingRepo(), store: store),
            loadGoals: LoadGoalsUseCase(store: goalStore),
            saveGoal: SaveGoalUseCase(store: goalStore),
            removeGoal: RemoveGoalUseCase(store: goalStore),
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            now: { now })
    }

    func test_load_withHoldings_entersLoaded() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let portfolio = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100),
                    on: Date(timeIntervalSince1970: 0))
        let model = vm(portfolio)
        await model.load()
        guard case .loaded(let report) = model.state else { return XCTFail("expected .loaded") }
        XCTAssertFalse(report.isEmpty)
    }

    func test_load_emptyPortfolio_entersEmpty() async {
        let model = vm(.starting())
        await model.load()
        XCTAssertEqual(model.state, .empty)
    }

    func test_defaultBenchmarkIsSPY() {
        XCTAssertEqual(vm(.starting()).benchmark, "SPY")
    }

    // MARK: - Value goal (Task 13)

    @MainActor
    func test_load_readsPersistedValueGoal() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 250_000),
                                                         createdAt: Date(timeIntervalSince1970: 1))])
        let vm = makeSUT(goalStore: goalStore)
        await vm.load()
        XCTAssertEqual(vm.valueGoal?.target, Money(amount: 250_000))
        XCTAssertNotNil(vm.valueGoalProjection)
    }

    @MainActor
    func test_setValueGoal_persistsAndProjects() async {
        let goalStore = InMemoryGoalStore()
        let vm = makeSUT(goalStore: goalStore)
        await vm.load()
        vm.setValueGoal(Money(amount: 500_000))
        XCTAssertEqual(vm.valueGoal?.target, Money(amount: 500_000))
        XCTAssertEqual(goalStore.load().count, 1)
        XCTAssertNotNil(vm.valueGoalProjection)
    }

    @MainActor
    func test_removeValueGoal_clearsStateAndStore() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 250_000),
                                                         createdAt: Date(timeIntervalSince1970: 1))])
        let vm = makeSUT(goalStore: goalStore)
        await vm.load()
        vm.removeValueGoal()
        XCTAssertNil(vm.valueGoal)
        XCTAssertNil(vm.valueGoalProjection)
        XCTAssertTrue(goalStore.load().isEmpty)
    }

    @MainActor
    func test_setValueGoal_doesNotDisturbIncomeGoal() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .income, target: Money(amount: 5_000),
                                                         createdAt: Date(timeIntervalSince1970: 1))])
        let vm = makeSUT(goalStore: goalStore)
        await vm.load()
        vm.setValueGoal(Money(amount: 500_000))
        XCTAssertEqual(Set(goalStore.load().map(\.kind)), [.value, .income])
    }

    /// Fix-round: an all-cash portfolio (no positions at all) makes
    /// `ComputePerformanceMetricsUseCase` return `.empty` before any equity curve is ever
    /// built — `report.equityCurve` is `[]`. Without reading the portfolio's cash balance
    /// as a fallback, `currentValue` would stay stuck at its zero default and the card would
    /// be unreachable (or always read "$0") for a brand-new user who deposited but never
    /// traded. Asserts a *specific* `GoalProjection` case (`.insufficientHistory` — a
    /// non-positive-target-free, current-below-target goal against an empty curve) rather
    /// than merely non-nil, since the empty curve makes that the only reachable case here.
    @MainActor
    func test_load_allCashPortfolio_exposesCashAsCurrentValueWithProjection() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 500_000),
                                                         createdAt: Date(timeIntervalSince1970: 1))])
        let vm = makeSUT(portfolio: .starting(cash: Money(amount: 300_000)), goalStore: goalStore)
        await vm.load()
        XCTAssertEqual(vm.state, .empty)
        XCTAssertEqual(vm.currentValue, Money(amount: 300_000))
        XCTAssertEqual(vm.valueGoalProjection, .insufficientHistory)
    }

    // MARK: - Whole-branch review fix 1: never fabricate $0 when positions exist but
    // history fetch fails.

    /// A portfolio holding positions whose priced-history fetch fails entirely (offline,
    /// rate-limited, shared-core-error) makes `ComputePerformanceMetricsUseCase` return
    /// `.empty` via the SAME code path as an all-cash portfolio, even though real value is
    /// held. Before the fix, `currentValue`'s `else` branch checked
    /// `portfolio.positions.isEmpty` and fell through to a hardcoded `Money(amount: 0)`
    /// for this (common, not exotic) case — a $500,000 value goal would then render as
    /// "$0 / $500,000 · 0%". The floor must be cash plus every position's own cost basis.
    @MainActor
    func test_load_positionsExist_historyFetchFails_currentValueIsCashPlusCostBasis_notZero() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let portfolio = try! Portfolio.starting(cash: Money(amount: 20_000))
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 150),
                    on: Date(timeIntervalSince1970: 0))
        let store = MemoryStore(portfolio)
        let vm = PerformanceViewModel(
            compute: ComputePerformanceMetricsUseCase(repository: FailingHistoryRepo(), store: store),
            fetchPortfolio: FetchPortfolioUseCase(store: store))
        await vm.load()
        XCTAssertEqual(vm.state, .empty, "history-fetch failure must still surface as an empty report")
        // Buying 10 shares at $150 moves $1,500 of the original $20,000 cash into the
        // position at the SAME price (cash drops to $18,500), so cash + cost basis
        // reproduces the original $20,000 total exactly — never a hardcoded $0.
        XCTAssertEqual(vm.currentValue, Money(amount: 20_000))
        XCTAssertNotEqual(vm.currentValue, Money(amount: 0))
    }

    // MARK: - Whole-branch review fix 3: onAppear re-reads the goal even when not `.idle`.

    /// A goal can be removed out from under this view model by a DIFFERENT instance of it —
    /// the iOS pill host and the macOS sidebar shell each own their own `PerformanceViewModel`
    /// over the same store — so a removal on one surface must not linger on the other. (Until
    /// M11.1 UAT F1 a portfolio reset was the motivating case; a reset no longer clears goals,
    /// but the cross-instance case is unchanged.) Before the fix, `onAppear()` gated ALL of its
    /// work (including the goal read) on `state == .idle`, so returning to an already-loaded
    /// Performance screen kept showing a goal that no longer existed.
    @MainActor
    func test_onAppear_reReadsGoal_evenWhenNotIdle_afterExternalReset() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 250_000),
                                                         createdAt: Date(timeIntervalSince1970: 1))])
        let vm = makeSUT(goalStore: goalStore)
        await vm.onAppear()
        XCTAssertNotEqual(vm.state, .idle)
        XCTAssertEqual(vm.valueGoal?.target, Money(amount: 250_000))

        // Simulate the goal being cleared out from under the view model — nothing routes
        // through this instance's `setValueGoal`/`removeValueGoal`, mirroring a removal that
        // happened on a completely different view model over the same store.
        goalStore.save([])

        await vm.onAppear()
        XCTAssertNil(vm.valueGoal, "a goal removed elsewhere must not linger after re-appearing")
        XCTAssertNil(vm.valueGoalProjection)
    }

    // MARK: - M11.1 UAT F3: `onAppear()` reloads the REPORT, not just the goal.

    /// Rejects the shipped implementation
    /// `func onAppear() { valueGoal = …; refreshValueProjection(); if case .idle = state { await load() } }`.
    /// `currentValue` (and the whole metric grid) is assigned ONLY inside `load()`, so with
    /// the `.idle` gate a second appearance after the portfolio was replaced underneath —
    /// exactly what `ResetPortfolioUseCase` does — left the value-goal card showing the
    /// PRE-reset dollar figure. This is the reported UAT defect: storage held $500,000 while
    /// the card rendered "$100,115.77 / $1,200,000 · 8%".
    ///
    /// Discrimination: the first `onAppear()` leaves `state == .empty` (all-cash portfolio),
    /// never `.idle`, so the gated implementation skips the second `load()` entirely and
    /// `currentValue` stays at $100,000. Only an ungated reload reads $1,000,000.
    @MainActor
    func test_onAppear_afterFirstLoad_reloadsCurrentValue_notJustTheGoal() async {
        let store = MemoryStore(.starting(cash: Money(amount: 100_000)))
        let vm = PerformanceViewModel(
            compute: ComputePerformanceMetricsUseCase(repository: RisingRepo(), store: store),
            fetchPortfolio: FetchPortfolioUseCase(store: store))
        await vm.onAppear()
        XCTAssertEqual(vm.currentValue, Money(amount: 100_000))
        XCTAssertNotEqual(vm.state, .idle, "the gate can only be discriminating once state has left .idle")

        // The portfolio is replaced out from under this view model (what a reset does).
        store.save(.starting(cash: Money(amount: 1_000_000)))

        await vm.onAppear()
        XCTAssertEqual(vm.currentValue, Money(amount: 1_000_000),
                       "re-appearing must reload the report, not only re-read the goal")
    }

    // MARK: - Account-age history gate wiring (Task 4d)

    /// Builds a view model over a portfolio whose single buy happened `boughtOnDay` days
    /// into a 500-day price window, evaluated `asOfDay` days into that same window. The
    /// equity curve is identical in both arrangements (~499 days, comfortably past the
    /// curve-span gate) — only the ACCOUNT's age changes.
    private func seasonedSUT(boughtOnDay: Int, asOfDay: Int) -> PerformanceViewModel {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let portfolio = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100),
                    on: Date(timeIntervalSince1970: TimeInterval(boughtOnDay) * 86_400))
        let store = MemoryStore(portfolio)
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 500_000),
                                                         createdAt: Date(timeIntervalSince1970: 1))])
        return PerformanceViewModel(
            compute: ComputePerformanceMetricsUseCase(repository: SeasonedPriceRepo(), store: store),
            loadGoals: LoadGoalsUseCase(store: goalStore),
            saveGoal: SaveGoalUseCase(store: goalStore),
            removeGoal: RemoveGoalUseCase(store: goalStore),
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            now: { Date(timeIntervalSince1970: TimeInterval(asOfDay) * 86_400) })
    }

    /// THE DIVERGENCE, at the wiring level: a 20-day-old account holding a symbol with 500
    /// days of price history has a curve that sails past the span gate. Only the account age
    /// — read from `Portfolio.inceptionDate` and passed by `refreshValueProjection` — can
    /// make this honestly report `.insufficientHistory`. If the view model failed to pass
    /// the age (or passed a constant), this reads as a concrete projection instead.
    @MainActor
    func test_load_newAccountHoldingASeasonedSymbol_projectsInsufficientHistory() async {
        let vm = seasonedSUT(boughtOnDay: 480, asOfDay: 500)
        await vm.load()
        XCTAssertEqual(vm.valueGoalProjection, .insufficientHistory)
    }

    /// The control for the test above: the SAME curve and the SAME goal, with the buy moved
    /// to the start of the window so the account is 500 days old. The projection must come
    /// back as a real reading — proving the test above fails on account age specifically and
    /// not because this fixture can never project at all.
    @MainActor
    func test_load_seasonedAccount_projectsFromTheCurve() async {
        let vm = seasonedSUT(boughtOnDay: 0, asOfDay: 500)
        await vm.load()
        XCTAssertNotNil(vm.valueGoalProjection)
        XCTAssertNotEqual(vm.valueGoalProjection, .insufficientHistory)
    }
}
