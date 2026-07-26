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

private final class FixedRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 150), previousClose: Money(amount: 140))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

private final class MemoryHistoryStore: PortfolioHistoryStore, @unchecked Sendable {
    var points: [PricePoint] = []
    func record(_ point: PricePoint) { points.append(point) }
    func load() -> [PricePoint] { points }
    func clear() { points.removeAll() }
}

private final class MemorySettingsStore: SettingsStore, @unchecked Sendable {
    private var settings = AppSettings()
    func load() -> AppSettings { settings }
    func save(_ settings: AppSettings) { self.settings = settings }
}

private final class MemoryGoalStore: GoalStore, @unchecked Sendable {
    private var goals: [PortfolioGoal]
    init(_ goals: [PortfolioGoal] = []) { self.goals = goals }
    func load() -> [PortfolioGoal] { goals }
    func save(_ goals: [PortfolioGoal]) { self.goals = goals }
}

@MainActor
final class PortfolioViewModelTests: XCTestCase {
    func test_onAppear_loadsHoldingsAndValues() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let start = try! Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(2)), at: Money(amount: 100))
        let store = MemoryStore(start)
        let repo = FixedRepo()
        let historyStore = MemoryHistoryStore()
        let vm = PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            resetPortfolio: ResetPortfolioUseCase(store: store, serializer: TradeSerializer()),
            recordSnapshot: RecordPortfolioSnapshotUseCase(store: historyStore),
            fetchHistory: FetchPortfolioHistoryUseCase(store: historyStore),
            clearHistory: ClearPortfolioHistoryUseCase(store: historyStore),
            fetchPerformance: FetchPortfolioPerformanceUseCase(repository: repo, store: store)
        )
        await vm.onAppear()
        XCTAssertEqual(vm.holdings.count, 1)
        XCTAssertEqual(vm.valuation.holdingsValue, Money(amount: 300))   // 150*2
    }

    func test_reset_clearsHoldings() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let start = try! Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(1)), at: Money(amount: 100))
        let store = MemoryStore(start)
        let historyStore = MemoryHistoryStore()
        let vm = PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: FixedRepo()),
            resetPortfolio: ResetPortfolioUseCase(store: store, serializer: TradeSerializer()),
            recordSnapshot: RecordPortfolioSnapshotUseCase(store: historyStore),
            fetchHistory: FetchPortfolioHistoryUseCase(store: historyStore),
            clearHistory: ClearPortfolioHistoryUseCase(store: historyStore),
            fetchPerformance: FetchPortfolioPerformanceUseCase(repository: FixedRepo(), store: store)
        )
        await vm.onAppear()
        await vm.reset(startingCash: Money(amount: 100_000))
        XCTAssertTrue(vm.holdings.isEmpty)
        XCTAssertEqual(vm.portfolio.cash, Money(amount: 100_000))
    }

    // MARK: - M11.1 UAT F2: a reset must reach the Performance screen.

    /// Exercises the RESET PATH itself — `PortfolioSummaryHeader.applyReset`, the exact
    /// function the reset sheet's confirm button runs — rather than calling
    /// `PerformanceViewModel.onAppear()` by hand. That distinction is the whole point:
    /// `PerformanceViewModelTests.test_onAppear_reReadsGoal_evenWhenNotIdle_afterExternalReset`
    /// already proves the view model refreshes WHEN ASKED; the shipped bug was that nothing
    /// asked. The reset menu lives in this header, which renders ABOVE the section picker,
    /// so the user never leaves Performance and no view lifecycle event fires.
    ///
    /// Rejects the shipped confirm body:
    /// `{ settingsVM.settings.defaultStartingCash = amount; Task { await viewModel.reset(startingCash: amount) } }`
    /// — i.e. any implementation that resets the portfolio without invoking `onDidReset`
    /// afterwards. `PerformanceViewModel` holds its own `@State` instance over the same
    /// store, so without the call its `currentValue` stays at the pre-reset $100,000 while
    /// the header's own total updates to $1,000,000 (precisely the reported UAT screen).
    ///
    /// Also pins the ORDER: `onDidReset` must fire AFTER the awaited reset has persisted the
    /// fresh portfolio. Firing it before would re-read the OLD portfolio and re-freeze the
    /// screen it exists to unfreeze.
    func test_applyReset_notifiesTheHostAfterTheFreshPortfolioIsPersisted() async {
        let store = MemoryStore(.starting(cash: Money(amount: 100_000)))
        let historyStore = MemoryHistoryStore()
        let settingsStore = MemorySettingsStore()
        let portfolioVM = PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: FixedRepo()),
            resetPortfolio: ResetPortfolioUseCase(store: store, serializer: TradeSerializer()),
            recordSnapshot: RecordPortfolioSnapshotUseCase(store: historyStore),
            fetchHistory: FetchPortfolioHistoryUseCase(store: historyStore),
            clearHistory: ClearPortfolioHistoryUseCase(store: historyStore),
            fetchPerformance: FetchPortfolioPerformanceUseCase(repository: FixedRepo(), store: store)
        )
        let settingsVM = SettingsViewModel(loadSettings: LoadSettingsUseCase(store: settingsStore),
                                           saveSettings: SaveSettingsUseCase(store: settingsStore))
        let goalStore = MemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 120_000),
                                                       createdAt: Date(timeIntervalSince1970: 1))])
        // The separate `@State` instance `PortfolioView`/`RootView` own beside the header.
        let performanceVM = PerformanceViewModel(
            compute: ComputePerformanceMetricsUseCase(repository: FixedRepo(), store: store),
            loadGoals: LoadGoalsUseCase(store: goalStore),
            fetchPortfolio: FetchPortfolioUseCase(store: store))
        await performanceVM.onAppear()
        XCTAssertEqual(performanceVM.currentValue, Money(amount: 100_000))

        await PortfolioSummaryHeader.applyReset(
            amount: Money(amount: 1_000_000),
            viewModel: portfolioVM,
            settingsVM: settingsVM,
            onDidReset: { await performanceVM.onAppear() })

        XCTAssertEqual(portfolioVM.portfolio.cash, Money(amount: 1_000_000))
        XCTAssertEqual(settingsVM.settings.defaultStartingCash, Money(amount: 1_000_000),
                       "confirm still persists the new default starting cash")
        XCTAssertEqual(performanceVM.currentValue, Money(amount: 1_000_000),
                       "the reset must reach the Performance screen's own view model")
    }
}
