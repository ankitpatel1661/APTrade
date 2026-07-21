import Foundation

/// The metrics a screen can condition on — the custom builder's picker plus every value
/// `ScreenCondition` compares against a threshold.
public enum ScreenerMetric: String, Codable, CaseIterable, Sendable {
    case price, dayChangePercent, rsi14, bollingerPercentB, bollingerBandwidth,
         pctTo52wHigh, pctTo52wLow, relativeVolume, pctVsSma50, pctVsSma200

    /// The row's value for this metric (price → close). Nil-propagating: a metric backed
    /// by a nil field on the row yields nil, never a crash.
    public func value(in row: ScreenerSnapshotRow) -> Double? {
        switch self {
        case .price: return row.close
        case .dayChangePercent: return row.dayChangePercent
        case .rsi14: return row.rsi14
        case .bollingerPercentB: return row.bollingerPercentB
        case .bollingerBandwidth: return row.bollingerBandwidth
        case .pctTo52wHigh: return row.pctTo52wHigh
        case .pctTo52wLow: return row.pctTo52wLow
        case .relativeVolume: return row.relativeVolume
        case .pctVsSma50: return row.pctVsSma50
        case .pctVsSma200: return row.pctVsSma200
        }
    }
}

/// Direction of a threshold comparison. Both sides are STRICT — a value exactly at the
/// threshold matches neither.
public enum ScreenComparison: String, Codable, Sendable {
    case above, below
}

/// One metric/comparison/threshold predicate. `matches` is total: a row whose metric is
/// nil (insufficient history) never matches — it is excluded, not crashed on.
public struct ScreenCondition: Equatable, Codable, Sendable {
    public let metric: ScreenerMetric
    public let comparison: ScreenComparison
    public let threshold: Double

    public init(metric: ScreenerMetric, comparison: ScreenComparison, threshold: Double) {
        self.metric = metric
        self.comparison = comparison
        self.threshold = threshold
    }

    /// Nil metric → false. Otherwise strict `<`/`>` against `threshold`.
    public func matches(_ row: ScreenerSnapshotRow) -> Bool {
        guard let value = metric.value(in: row) else { return false }
        switch comparison {
        case .above: return value > threshold
        case .below: return value < threshold
        }
    }
}

/// A user-saved, AND-combined set of conditions. Persisted (unlike presets, which are code).
public struct CustomScreen: Equatable, Codable, Identifiable, Sendable {
    public let id: String
    public var name: String
    /// AND-combined. Empty conditions match nothing — an unbuilt screen should never
    /// appear to match the whole universe.
    public var conditions: [ScreenCondition]

    public init(id: String, name: String, conditions: [ScreenCondition]) {
        self.id = id
        self.name = name
        self.conditions = conditions
    }
}

/// The 9 curated signal screens. Presets are code, not storage — identified by case.
public enum PresetScreen: String, CaseIterable, Codable, Sendable {
    case rsiOversold, rsiOverbought, macdBullishCross, macdBearishCross,
         goldenCross, deathCross, bollingerSqueeze, near52wHigh, near52wLow

    /// Nil-backed numeric metrics never match (no data, no signal). Boolean-flag presets
    /// read the row's precomputed cross flags directly.
    public func matches(_ row: ScreenerSnapshotRow) -> Bool {
        switch self {
        case .rsiOversold:
            guard let rsi = row.rsi14 else { return false }
            return rsi < 30
        case .rsiOverbought:
            guard let rsi = row.rsi14 else { return false }
            return rsi > 70
        case .macdBullishCross:
            return row.macdCrossedUp
        case .macdBearishCross:
            return row.macdCrossedDown
        case .goldenCross:
            return row.goldenCross
        case .deathCross:
            return row.deathCross
        case .bollingerSqueeze:
            guard let bandwidth = row.bollingerBandwidth else { return false }
            return bandwidth < 0.05
        case .near52wHigh:
            guard let pct = row.pctTo52wHigh else { return false }
            return pct < 3
        case .near52wLow:
            guard let pct = row.pctTo52wLow else { return false }
            return pct < 3
        }
    }
}

/// The active screen the UI runs — preset or custom — with one evaluation door.
public enum ScreenSelection: Equatable, Sendable {
    case preset(PresetScreen)
    case custom(CustomScreen)

    /// Runs this selection's predicate over `rows`, preserving row order.
    public func evaluate(_ rows: [ScreenerSnapshotRow]) -> [ScreenerSnapshotRow] {
        switch self {
        case .preset(let preset):
            return rows.filter { preset.matches($0) }
        case .custom(let screen):
            guard !screen.conditions.isEmpty else { return [] }
            return rows.filter { row in screen.conditions.allSatisfy { $0.matches(row) } }
        }
    }
}
