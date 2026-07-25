import APTradeDomain

public struct LoadGoalsUseCase: Sendable {
    private let store: GoalStore
    public init(store: GoalStore) { self.store = store }
    public func callAsFunction() -> [PortfolioGoal] { store.load() }
}

public struct SaveGoalUseCase: Sendable {
    private let store: GoalStore
    public init(store: GoalStore) { self.store = store }
    /// Upserts by kind — one value goal and one income goal at most.
    public func callAsFunction(_ goal: PortfolioGoal) {
        var goals = store.load().filter { $0.kind != goal.kind }
        goals.append(goal)
        store.save(goals)
    }
}

public struct RemoveGoalUseCase: Sendable {
    private let store: GoalStore
    public init(store: GoalStore) { self.store = store }
    public func callAsFunction(kind: GoalKind) {
        store.save(store.load().filter { $0.kind != kind })
    }
}
