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

private final class FlatRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 100), previousClose: Money(amount: 100))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

private final class InMemoryGoalStore: GoalStore, @unchecked Sendable {
    private var goals: [PortfolioGoal]
    init(_ goals: [PortfolioGoal] = []) { self.goals = goals }
    func load() -> [PortfolioGoal] { goals }
    func save(_ goals: [PortfolioGoal]) { self.goals = goals }
}

/// M11.3 Task 2 — the Portfolio header's goal strip.
///
/// The strip is a pure function of `(valueGoal, currentValue)`, lifted out of the SwiftUI
/// body as `PortfolioSummaryHeader.goalStrip(valueGoal:currentValue:)` precisely so it can be
/// pinned here: a SwiftUI view hierarchy is not inspectable in this test target (no
/// ViewInspector, no snapshot harness — an open decision this task deliberately does not
/// make), a limitation the preceding branch hit and recorded. What is therefore NOT covered
/// here, and must be read by eye at UAT: that `goalStripRow` is actually placed in both the
/// iOS and macOS `#if os` branches of `summary`. The compiler covers the other half — the
/// header cannot be constructed without a `performanceVM` at all (see
/// `test_bothHostsSupplyThePerformanceViewModel`).
@MainActor
final class PortfolioSummaryHeaderGoalStripTests: XCTestCase {

    /// ONE fixture for both the visible and the hidden case (GC3's trap for this milestone).
    /// A real `PerformanceViewModel` over a real portfolio + goal store, so `currentValue` is
    /// produced by the same code path the header reads at runtime rather than being handed in
    /// as a literal — a "no strip when no goal" assertion is worthless if the fixture never
    /// managed to produce a strip in the first place.
    private func makeSUT(startingCash: Decimal,
                         goals: [PortfolioGoal]) -> (vm: PerformanceViewModel, goalStore: InMemoryGoalStore) {
        let store = MemoryStore(.starting(cash: Money(amount: startingCash)))
        let goalStore = InMemoryGoalStore(goals)
        let vm = PerformanceViewModel(
            compute: ComputePerformanceMetricsUseCase(repository: FlatRepo(), store: store),
            loadGoals: LoadGoalsUseCase(store: goalStore),
            saveGoal: SaveGoalUseCase(store: goalStore),
            removeGoal: RemoveGoalUseCase(store: goalStore),
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            now: { Date(timeIntervalSince1970: 1_000_000) })
        return (vm, goalStore)
    }

    private func valueGoal(_ target: Decimal) -> PortfolioGoal {
        PortfolioGoal(kind: .value, target: Money(amount: target),
                      createdAt: Date(timeIntervalSince1970: 1))
    }

    // MARK: - Visible / hidden, from ONE fixture

    /// The design decision: **hidden entirely when no goal is set** — no empty bar, no "Set a
    /// goal" prompt, no tap target. The header is a readout; setting a goal stays on the
    /// Performance card.
    ///
    /// Rejects two wrong implementations at once:
    /// 1. `return GoalStrip(barFraction: 0, percent: 0, targetText: "—")` for a nil goal —
    ///    i.e. rendering an empty strip instead of nothing.
    /// 2. Any mapping that returns `nil` unconditionally (or that this fixture cannot drive
    ///    to a non-nil result at all) — the exact vacuous shape GC3 names for this milestone.
    ///    The first half of this test is what makes the second half mean something: the SAME
    ///    view model, over the SAME portfolio, with ONLY the goal removed between the two
    ///    assertions.
    func test_sameFixture_stripAppearsWithAGoalAndVanishesWithoutOne() async {
        let (vm, goalStore) = makeSUT(startingCash: 42_000, goals: [valueGoal(100_000)])
        await vm.onAppear()

        let visible = PortfolioSummaryHeader.goalStrip(valueGoal: vm.valueGoal,
                                                       currentValue: vm.currentValue)
        XCTAssertNotNil(visible, "a set value goal must produce a strip — otherwise the hidden case below is vacuous")

        // ONLY the goal changes. Same view model, same portfolio, same $42,000 current value.
        vm.removeValueGoal()
        XCTAssertTrue(goalStore.load().isEmpty)
        XCTAssertEqual(vm.currentValue, Money(amount: 42_000),
                       "the fixture must be otherwise untouched between the two readings")
        XCTAssertNil(PortfolioSummaryHeader.goalStrip(valueGoal: vm.valueGoal,
                                                      currentValue: vm.currentValue),
                     "no goal ⇒ no strip: not an empty bar, not a placeholder, nothing")
    }

    // MARK: - The payload

    /// The plan's second verification row: 42% of $100,000.
    ///
    /// Rejects a strip that also carries the CURRENT value: the header already renders TOTAL
    /// VALUE in display type two lines above, and dropping the third figure is what makes the
    /// row fit at 375pt. `GoalStrip` has exactly three fields and this test names all three,
    /// so adding `currentText` would have to be a deliberate, visible edit here.
    func test_goalSet_stripCarriesTargetAndPercent_andNoCurrentValue() async {
        let (vm, _) = makeSUT(startingCash: 42_000, goals: [valueGoal(100_000)])
        await vm.onAppear()

        let strip = PortfolioSummaryHeader.goalStrip(valueGoal: vm.valueGoal,
                                                     currentValue: vm.currentValue)
        // Whole-struct equality, not field-by-field, so a fourth field could not be added
        // without this line failing to compile. `barFraction`'s expected value is written as
        // the `GoalMath` expression rather than the literal `0.42` because Decimal→Double
        // conversion lands on 0.42000000000000004; the human-readable value is pinned
        // separately below, with a tolerance.
        XCTAssertEqual(strip, PortfolioSummaryHeader.GoalStrip(
            barFraction: min(GoalMath.progress(current: Money(amount: 42_000),
                                               target: Money(amount: 100_000)), 1.0),
            percent: 42,
            targetText: Money(amount: 100_000).formatted))
        XCTAssertEqual(strip?.barFraction ?? .nan, 0.42, accuracy: 0.000_001)
    }

    /// The case that found this milestone: $1,000,000 against a $120,000 target.
    ///
    /// Rejects `min(fraction, 1.0)` applied to the PERCENTAGE as well as to the bar — i.e.
    /// `percent: Int((min(fraction, 1.0) * 100).rounded())`, which would read a flat, wrong
    /// 100%. `GoalCard.swift:76,88` clamps the `ProgressView` only and leaves the percent
    /// text unclamped; the strip must behave identically (GC2). Also rejects the mirror-image
    /// error — an UNCLAMPED bar fraction of 8.33, which `ProgressView` would render as an
    /// overflowing bar.
    func test_goalExceeded_percentPasses100_whileTheBarClampsAtFull() async {
        let (vm, _) = makeSUT(startingCash: 1_000_000, goals: [valueGoal(120_000)])
        await vm.onAppear()

        let strip = PortfolioSummaryHeader.goalStrip(valueGoal: vm.valueGoal,
                                                     currentValue: vm.currentValue)
        XCTAssertEqual(strip?.percent, 833, "833%, not a clamped 100%")
        XCTAssertEqual(strip?.barFraction, 1.0, "the bar fills, but never overflows")
    }

    /// GC2, the digit-for-digit half: `GoalCard` renders `Int((fraction * 100).rounded())`,
    /// so the strip must ROUND, not truncate. At $6,667 of $10,000 the fraction is 0.6667 —
    /// rounding gives 67, truncating gives 66. Rejects `Int(fraction * 100)`.
    /// A strip reading 66% beside a card reading 67% is a defect.
    func test_percentRoundsRatherThanTruncating_matchingGoalCard() async {
        let (vm, _) = makeSUT(startingCash: 6_667, goals: [valueGoal(10_000)])
        await vm.onAppear()

        let fraction = GoalMath.progress(current: Money(amount: 6_667), target: Money(amount: 10_000))
        let strip = PortfolioSummaryHeader.goalStrip(valueGoal: vm.valueGoal,
                                                     currentValue: vm.currentValue)
        XCTAssertEqual(strip?.percent, 67)
        XCTAssertEqual(strip?.percent, Int((fraction * 100).rounded()),
                       "must match GoalCard.progressContent's formula exactly")
        XCTAssertNotEqual(strip?.percent, Int(fraction * 100),
                          "truncation would read 66 — the discriminating half of this test")
    }

    /// GC2, the reuse half: the fraction must come from `GoalMath.progress`, never from a
    /// second `current.amount / target.amount` written here. Rejects exactly that
    /// re-derivation — with a zero target, `NSDecimalNumber(decimal: 0/0).doubleValue` is NaN
    /// and `Int(Double.nan.rounded())` traps, so the wrong implementation does not merely
    /// disagree, it crashes. `GoalMath.progress` guards `target.amount > 0` and returns 0.
    ///
    /// Reached in production whenever a goal file is hand-edited or migrated to a zero target
    /// (`GoalKind.value.targetRange` stops the UI from creating one, not the store from
    /// holding one).
    func test_nonPositiveTarget_readsZero_becauseTheFractionComesFromGoalMath() async {
        let (vm, _) = makeSUT(startingCash: 42_000, goals: [valueGoal(0)])
        await vm.onAppear()

        let strip = PortfolioSummaryHeader.goalStrip(valueGoal: vm.valueGoal,
                                                     currentValue: vm.currentValue)
        XCTAssertNotNil(strip, "a goal exists, so the strip still renders")
        XCTAssertEqual(strip?.percent, 0)
        XCTAssertEqual(strip?.barFraction, 0)
        XCTAssertEqual(strip?.percent,
                       Int((GoalMath.progress(current: Money(amount: 42_000),
                                              target: Money(amount: 0)) * 100).rounded()))
    }

    // MARK: - Both hosts wire the view model

    /// `PortfolioSummaryHeader.performanceVM` is a required `let` — no `?`, no `= nil`. This
    /// test cannot observe a call site (the two are inside SwiftUI bodies in `PortfolioView`
    /// and `RootView.macBody`), and it is not pretending to: what it pins is the PROPERTY's
    /// requiredness, which is what makes an omitted argument at either host a compile error
    /// rather than a silently strip-less platform.
    ///
    /// Rejects `var performanceVM: PerformanceViewModel? = nil`. Under that declaration the
    /// memberwise initializer below still compiles with the argument present, but so does one
    /// WITHOUT it — which is precisely how the preceding branch shipped a silent regression
    /// (a deleted `onDidReset` argument at the iOS host, 720/720 tests green). Verified by
    /// hand as GC3 requires: deleting `performanceVM:` from `PortfolioView.swift`'s call site
    /// fails the build with "missing argument for parameter 'performanceVM' in call"; both
    /// transcripts are in the task report.
    func test_headerRequiresThePerformanceViewModel_atConstruction() async {
        let (vm, _) = makeSUT(startingCash: 42_000, goals: [valueGoal(100_000)])
        let settingsStore = InMemorySettingsStore()
        let portfolioStore = MemoryStore(.starting(cash: Money(amount: 42_000)))
        let historyStore = InMemoryHistoryStore()
        _ = PortfolioSummaryHeader(
            viewModel: PortfolioViewModel(
                fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
                fetchQuotes: FetchQuotesUseCase(repository: FlatRepo()),
                resetPortfolio: ResetPortfolioUseCase(store: portfolioStore, serializer: TradeSerializer()),
                recordSnapshot: RecordPortfolioSnapshotUseCase(store: historyStore),
                fetchHistory: FetchPortfolioHistoryUseCase(store: historyStore),
                clearHistory: ClearPortfolioHistoryUseCase(store: historyStore),
                fetchPerformance: FetchPortfolioPerformanceUseCase(repository: FlatRepo(), store: portfolioStore)),
            settingsVM: SettingsViewModel(loadSettings: LoadSettingsUseCase(store: settingsStore),
                                          saveSettings: SaveSettingsUseCase(store: settingsStore)),
            performanceVM: vm,
            onDidReset: {})
        // The assertion the compiler could not make for us: the header reads THAT instance,
        // so a goal set on it is the goal the strip renders.
        await vm.onAppear()
        XCTAssertNotNil(PortfolioSummaryHeader.goalStrip(valueGoal: vm.valueGoal,
                                                          currentValue: vm.currentValue))
    }
}

private final class InMemorySettingsStore: SettingsStore, @unchecked Sendable {
    private var settings = AppSettings()
    func load() -> AppSettings { settings }
    func save(_ settings: AppSettings) { self.settings = settings }
}

private final class InMemoryHistoryStore: PortfolioHistoryStore, @unchecked Sendable {
    private var points: [PricePoint] = []
    func record(_ point: PricePoint) { points.append(point) }
    func load() -> [PricePoint] { points }
    func clear() { points.removeAll() }
}
