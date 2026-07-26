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

    /// USER RULING 2026-07-27 (M11.1 UAT F1) — the INVERSION of `test_reset_clearsGoals`,
    /// which asserted `goalStore.load().isEmpty` here until this change. Resetting starting
    /// capital is "start over with more money", not "abandon my plan": a $120,000 value goal
    /// set before a reset to $1,000,000 survives and simply recomputes as reached.
    ///
    /// Rejects the shipped `self.goalStore?.save([])` inside `callAsFunction`. Note
    /// `ResetPortfolioUseCase` no longer TAKES a `GoalStore` — the optional dependency was
    /// removed rather than left unused (it defaulted to `nil` "so existing construction sites
    /// keep compiling", the re-armable pattern this project has been burned by), so goal
    /// clearing is now unreachable by construction. This test is the behavioural record of
    /// why, and it deliberately never hands the store to the use case.
    func test_reset_leavesGoalsIntact() async {
        let goalStore = InMemoryGoalStore([
            PortfolioGoal(kind: .value, target: Money(amount: 120_000), createdAt: epoch),
            PortfolioGoal(kind: .income, target: Money(amount: 6_000), createdAt: epoch)])
        let portfolioStore = MemoryStore(.starting(cash: Money(amount: 500)))
        let sut = ResetPortfolioUseCase(store: portfolioStore, serializer: TradeSerializer())
        let result = await sut(startingCash: Money(amount: 10_000))
        XCTAssertEqual(goalStore.load().map(\.target),
                       [Money(amount: 120_000), Money(amount: 6_000)],
                       "a reset changes the starting capital, never the user's plan")
        // The reset itself still happens — this is not a test that passes by doing nothing.
        XCTAssertEqual(result.cash, Money(amount: 10_000))
        XCTAssertEqual(portfolioStore.portfolio.cash, Money(amount: 10_000))
    }
}
