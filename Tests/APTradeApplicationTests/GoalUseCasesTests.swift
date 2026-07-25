import XCTest
import APTradeDomain
@testable import APTradeApplication

private final class InMemoryGoalStore: GoalStore, @unchecked Sendable {
    private var goals: [PortfolioGoal]
    init(_ goals: [PortfolioGoal] = []) { self.goals = goals }
    func load() -> [PortfolioGoal] { goals }
    func save(_ goals: [PortfolioGoal]) { self.goals = goals }
}

/// `PortfolioUseCasesTests.swift` also declares a `MemoryStore` double, but a top-level
/// `private` declaration is file-scoped in Swift, so it isn't visible here — this is a
/// separate, equivalent double for this file.
private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

final class GoalUseCasesTests: XCTestCase {
    private let epoch = Date(timeIntervalSince1970: 1_700_000_000)

    func test_save_addsGoal() {
        let store = InMemoryGoalStore()
        SaveGoalUseCase(store: store)(PortfolioGoal(kind: .value, target: Money(amount: 100), createdAt: epoch))
        XCTAssertEqual(LoadGoalsUseCase(store: store)().count, 1)
    }

    func test_save_replacesExistingGoalOfSameKind() {
        let store = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 100), createdAt: epoch)])
        SaveGoalUseCase(store: store)(PortfolioGoal(kind: .value, target: Money(amount: 900), createdAt: epoch))
        let goals = LoadGoalsUseCase(store: store)()
        XCTAssertEqual(goals.count, 1)
        XCTAssertEqual(goals.first?.target, Money(amount: 900))
    }

    func test_save_keepsGoalsOfOtherKinds() {
        let store = InMemoryGoalStore([PortfolioGoal(kind: .income, target: Money(amount: 50), createdAt: epoch)])
        SaveGoalUseCase(store: store)(PortfolioGoal(kind: .value, target: Money(amount: 900), createdAt: epoch))
        XCTAssertEqual(Set(LoadGoalsUseCase(store: store)().map(\.kind)), [.value, .income])
    }

    func test_remove_deletesOnlyRequestedKind() {
        let store = InMemoryGoalStore([
            PortfolioGoal(kind: .value, target: Money(amount: 100), createdAt: epoch),
            PortfolioGoal(kind: .income, target: Money(amount: 50), createdAt: epoch)])
        RemoveGoalUseCase(store: store)(kind: .value)
        XCTAssertEqual(LoadGoalsUseCase(store: store)().map(\.kind), [.income])
    }

    func test_reset_clearsGoals() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 100), createdAt: epoch)])
        let portfolioStore = MemoryStore(.starting())
        let sut = ResetPortfolioUseCase(store: portfolioStore, serializer: TradeSerializer(), goalStore: goalStore)
        _ = await sut(startingCash: Money(amount: 10_000))
        XCTAssertTrue(goalStore.load().isEmpty, "a fresh practice run must not inherit old goals")
    }

    /// Gap the brief's test block didn't cover: `goalStore` defaults to `nil` so existing
    /// call sites (which predate goals) keep compiling. A reset must still succeed — and
    /// still reset the portfolio itself — when no goal store is supplied at all, rather
    /// than crashing on the optional chain or silently no-op'ing the portfolio reset too.
    func test_reset_withoutGoalStore_stillResetsPortfolio() async {
        let portfolioStore = MemoryStore(.starting(cash: Money(amount: 500)))
        let sut = ResetPortfolioUseCase(store: portfolioStore, serializer: TradeSerializer())
        let result = await sut(startingCash: Money(amount: 10_000))
        XCTAssertEqual(result.cash, Money(amount: 10_000))
        XCTAssertEqual(portfolioStore.portfolio.cash, Money(amount: 10_000))
    }
}
