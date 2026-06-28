import Foundation

/// One holding's share of total holdings value, for concentration analysis.
public struct HoldingWeight: Equatable, Sendable {
    public let label: String   // display name or symbol
    public let kind: String    // asset-class label (e.g. "stock", "crypto")
    public let weight: Double  // 0...1 fraction of total holdings value
    public init(label: String, kind: String, weight: Double) {
        self.label = label
        self.kind = kind
        self.weight = weight
    }
}

/// A flagged concentration risk.
public enum ConcentrationWarning: Equatable, Sendable {
    case singleName(label: String, weight: Double)
    case assetClass(kind: String, weight: Double)
}

/// Pure portfolio-concentration analytics.
public enum Diversification {
    /// Herfindahl-Hirschman index: Σ wᵢ². 1.0 = everything in one name; → 0 as spread out.
    public static func concentration(_ weights: [Double]) -> Double {
        weights.reduce(0) { $0 + $1 * $1 }
    }

    /// Effective number of equally-weighted holdings = 1 / HHI. Empty → 0.
    public static func effectiveHoldings(_ weights: [Double]) -> Double {
        let hhi = concentration(weights)
        return hhi > 0 ? 1 / hhi : 0
    }

    /// Warnings for single holdings above `singleNameThreshold` (default 25%) and asset
    /// classes above `assetClassThreshold` (default 60%). Sorted by descending weight.
    public static func warnings(_ holdings: [HoldingWeight],
                                singleNameThreshold: Double = 0.25,
                                assetClassThreshold: Double = 0.60) -> [ConcentrationWarning] {
        var result: [ConcentrationWarning] = []
        for h in holdings where h.weight > singleNameThreshold {
            result.append(.singleName(label: h.label, weight: h.weight))
        }
        var byClass: [String: Double] = [:]
        for h in holdings { byClass[h.kind, default: 0] += h.weight }
        for (kind, w) in byClass where w > assetClassThreshold {
            result.append(.assetClass(kind: kind, weight: w))
        }
        return result.sorted { weight(of: $0) > weight(of: $1) }
    }

    private static func weight(of warning: ConcentrationWarning) -> Double {
        switch warning {
        case .singleName(_, let w): return w
        case .assetClass(_, let w): return w
        }
    }
}
