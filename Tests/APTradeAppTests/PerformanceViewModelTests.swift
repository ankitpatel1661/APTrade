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

    /// A holding portfolio (so `load()` reaches `.loaded`, populating the equity curve the
    /// value-goal projection reads) wired to a caller-supplied `GoalStore`.
    private func makeSUT(goalStore: GoalStore = InMemoryGoalStore(),
                         now: Date = Date(timeIntervalSince1970: 1_000_000)) -> PerformanceViewModel {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let portfolio = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100),
                    on: Date(timeIntervalSince1970: 0))
        return PerformanceViewModel(
            compute: ComputePerformanceMetricsUseCase(repository: RisingRepo(), store: MemoryStore(portfolio)),
            loadGoals: LoadGoalsUseCase(store: goalStore),
            saveGoal: SaveGoalUseCase(store: goalStore),
            removeGoal: RemoveGoalUseCase(store: goalStore),
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
}
