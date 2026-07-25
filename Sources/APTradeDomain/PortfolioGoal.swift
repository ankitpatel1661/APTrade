import Foundation

/// What a goal measures.
public enum GoalKind: String, Codable, Sendable {
    /// Total portfolio value (holdings + cash).
    case value
    /// Projected annual dividend income.
    case income
}

/// A user-set target for the whole portfolio. At most one per `GoalKind`.
/// The user sets an amount; the app projects when it will be reached.
public struct PortfolioGoal: Equatable, Codable, Sendable {
    public let kind: GoalKind
    public let target: Money
    public let createdAt: Date

    public init(kind: GoalKind, target: Money, createdAt: Date = Date()) {
        self.kind = kind
        self.target = target
        self.createdAt = createdAt
    }
}
