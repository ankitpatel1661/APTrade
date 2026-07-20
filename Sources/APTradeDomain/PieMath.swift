import Foundation

/// Direction of a rebalance trade: buy or sell.
public enum RebalanceSide: String, Codable, Sendable {
    case buy
    case sell
}

/// A single rebalance trade to restore a pie to its target allocation.
public struct RebalanceOrder: Equatable, Sendable {
    public let symbol: String
    public let side: RebalanceSide
    public let amount: Money  // always positive

    public init(symbol: String, side: RebalanceSide, amount: Money) {
        self.symbol = symbol
        self.side = side
        self.amount = amount
    }
}

/// Self-balancing contribution distribution for investment pies.
public enum PieMath {
    /// Splits a contribution across pie slices, preferring underweight slices.
    ///
    /// - Parameters:
    ///   - contribution: The amount of cash to distribute.
    ///   - currentValues: Map of symbol to current money value in the pie.
    ///   - targets: The target allocation (slices with symbol and target weight).
    ///
    /// - Returns: A dictionary mapping each target symbol to its allocation from the contribution.
    ///   Output values are ≥ 0, rounded to cents, and sum EXACTLY to `contribution`
    ///   (remainder cents go to the largest-target slice; ties → lexicographically first symbol).
    public static func distribute(
        contribution: Money,
        currentValues: [String: Money],
        targets: [PieSlice]
    ) -> [String: Money] {
        let contrib = contribution.amount
        let currencyCode = contribution.currencyCode

        // Compute current values for each target symbol (0 if not in currentValues)
        var currentBySymbol: [String: Decimal] = [:]
        for slice in targets {
            currentBySymbol[slice.symbol] = currentValues[slice.symbol]?.amount ?? Decimal(0)
        }

        // Compute totalAfter
        let totalCurrent = currentBySymbol.values.reduce(Decimal(0), +)
        let totalAfter = totalCurrent + contrib

        // Compute deficits
        var deficits: [String: Decimal] = [:]
        var sumDeficits = Decimal(0)
        for slice in targets {
            let ideal = (totalAfter * slice.targetWeight.value) / 100
            let current = currentBySymbol[slice.symbol] ?? Decimal(0)
            let deficit = max(Decimal(0), ideal - current)
            deficits[slice.symbol] = deficit
            sumDeficits += deficit
        }

        // Determine allocation strategy
        var allocations: [String: Decimal] = [:]

        if sumDeficits <= contrib {
            // Sufficient case: give each slice its deficit, then split leftover pro-rata by target weight
            for slice in targets {
                allocations[slice.symbol] = deficits[slice.symbol] ?? Decimal(0)
            }

            let leftover = contrib - sumDeficits
            if leftover > 0 {
                let sumWeights = targets.reduce(Decimal(0)) { $0 + $1.targetWeight.value }
                for slice in targets {
                    let share = (leftover * slice.targetWeight.value) / sumWeights
                    allocations[slice.symbol] = (allocations[slice.symbol] ?? Decimal(0)) + share
                }
            }
        } else {
            // Deficit case: give contribution × deficit/Σdeficits
            for slice in targets {
                let deficit = deficits[slice.symbol] ?? Decimal(0)
                allocations[slice.symbol] = (contrib * deficit) / sumDeficits
            }
        }

        // Round each share to 2 decimal places (cents)
        var rounded: [String: Decimal] = [:]
        var sumRounded = Decimal(0)
        for slice in targets {
            let value = allocations[slice.symbol] ?? Decimal(0)
            let r = Self.rounded(value)
            rounded[slice.symbol] = r
            sumRounded += r
        }

        // Distribute remainder, clamping to avoid negative values
        var remainder = contrib - sumRounded
        if remainder != 0 {
            // Sort slices by target weight (desc), then symbol (asc for ties)
            let sortedByWeightAndSymbol = targets.sorted { a, b in
                if a.targetWeight.value == b.targetWeight.value {
                    return a.symbol < b.symbol
                }
                return a.targetWeight.value > b.targetWeight.value
            }

            // Walk through slices, adding remainder and clamping at 0
            for slice in sortedByWeightAndSymbol {
                let current = rounded[slice.symbol] ?? Decimal(0)
                let newValue = current + remainder
                if newValue >= 0 {
                    // Can fully absorb the remainder
                    rounded[slice.symbol] = newValue
                    remainder = 0
                    break
                } else {
                    // Clamp to 0, roll over the unclamped portion
                    rounded[slice.symbol] = 0
                    remainder = newValue  // newValue is negative, so remainder becomes more negative
                }
            }
        }

        // Convert back to Money
        return rounded.mapValues { Money(amount: $0, currencyCode: currencyCode) }
    }

    /// Rounds a Decimal to 2 decimal places using NSDecimalRound with .plain rounding mode.
    /// Internal (not `private`) so `equalWeights` below — and this type's own test target via
    /// `@testable import` — can reuse it directly rather than duplicating the rounding rule.
    static func rounded(_ d: Decimal) -> Decimal {
        var result = d
        var input = d
        NSDecimalRound(&result, &input, 2, .plain)
        return result
    }

    /// Splits 100 percentage points evenly across `count` shares using the largest-remainder
    /// method, so the result always sums to EXACTLY 100 (never 99.99 or 100.01 from naive
    /// division). Each share is `100 / count` rounded to 2dp via `rounded(_:)`; the leftover
    /// (always a small multiple of 0.01pp, since 2dp rounding bounds each share's error to
    /// under half a cent-of-a-point) is distributed one 0.01pp unit at a time, walking from
    /// the LAST share backward — an arbitrary but deterministic tie-break, since every share
    /// is equally entitled to the remainder in an equal split.
    ///
    /// - Parameter count: Number of shares. `count <= 0` returns an empty array.
    public static func equalWeights(count: Int) -> [Percentage] {
        guard count > 0 else { return [] }
        let base = Self.rounded(Decimal(100) / Decimal(count))
        var totals = Array(repeating: base, count: count)
        let unit = Decimal(1) / Decimal(100)
        var remainder = Decimal(100) - (base * Decimal(count))
        var index = count - 1
        while remainder > 0 && index >= 0 {
            totals[index] += unit
            remainder -= unit
            index -= 1
        }
        return totals.map { Percentage(value: $0) }
    }

    /// Calculates the signed drift (actual % − target %) for each slice, in percentage points to 2 dp.
    ///
    /// Drift measures how far each asset is from its target allocation:
    /// - Positive drift: overweight (should sell)
    /// - Negative drift: underweight (should buy)
    /// - Zero drift: at target
    ///
    /// - Parameters:
    ///   - currentValues: Map of symbol to current money value in the pie.
    ///   - targets: The target allocation (slices with symbol and target weight).
    ///
    /// - Returns: A dictionary mapping each target symbol to its drift in percentage points,
    ///   rounded to 2 decimal places.
    public static func drift(
        currentValues: [String: Money],
        targets: [PieSlice]
    ) -> [String: Percentage] {
        // Compute total current value
        let totalCurrent = currentValues.values.reduce(Decimal(0)) { $0 + $1.amount }

        // Compute drift per slice: actual% - target%
        var result: [String: Percentage] = [:]

        for slice in targets {
            let current = currentValues[slice.symbol]?.amount ?? Decimal(0)
            let actualPercent = totalCurrent > 0
                ? (current / totalCurrent) * 100
                : Decimal(0)
            let driftValue = actualPercent - slice.targetWeight.value
            let rounded = Self.rounded(driftValue)
            result[slice.symbol] = Percentage(value: rounded)
        }

        return result
    }

    /// Generates rebalance orders to restore each slice to its target allocation.
    ///
    /// The plan achieves exact net-zero cash by:
    /// 1. Computing delta (buy/sell amount) for each slice: delta_i = (target_i% ÷ 100) × total − current_i
    /// 2. Rounding each delta to cents
    /// 3. Folding the negated net-cash remainder (−netCash) into the largest-target slice's
    ///    order, so the correction cancels the residual rather than doubling it
    ///    (if that fold would flip the order side or make amount negative, walk to the next slice)
    ///
    /// Orders are always 2-dp amounts and zero deltas are excluded above, so no sub-cent
    /// orders can arise — no separate sub-cent filter is needed.
    ///
    /// - Parameters:
    ///   - currentValues: Map of symbol to current money value in the pie.
    ///   - targets: The target allocation (slices with symbol and target weight).
    ///
    /// - Returns: An array of rebalance orders, each with a positive amount, such that
    ///   Σ(buy amounts) == Σ(sell amounts) exactly. Empty if already at target or pie is empty.
    public static func rebalancePlan(
        currentValues: [String: Money],
        targets: [PieSlice]
    ) -> [RebalanceOrder] {
        // Compute total current value
        let totalCurrent = currentValues.values.reduce(Decimal(0)) { $0 + $1.amount }

        // Empty or at-target pie: no rebalancing needed
        if totalCurrent == 0 {
            return []
        }

        // Compute raw deltas (target - current) for each slice
        var deltas: [String: Decimal] = [:]
        for slice in targets {
            let current = currentValues[slice.symbol]?.amount ?? Decimal(0)
            let ideal = (slice.targetWeight.value / 100) * totalCurrent
            deltas[slice.symbol] = ideal - current
        }

        // Round each delta to cents
        var rounded: [String: Decimal] = [:]
        var netCash = Decimal(0)  // sum of all rounded deltas (should be ~0 after folding)

        for slice in targets {
            let delta = deltas[slice.symbol] ?? Decimal(0)
            let r = Self.rounded(delta)
            rounded[slice.symbol] = r
            netCash += r
        }

        // Fold remainder into largest-target slice, walking if needed
        if netCash != 0 {
            // Sort slices by target weight (descending), then symbol (ascending for ties)
            let sortedByWeight = targets.sorted { a, b in
                if a.targetWeight.value == b.targetWeight.value {
                    return a.symbol < b.symbol
                }
                return a.targetWeight.value > b.targetWeight.value
            }

            // Walk through slices, trying to fold the correction (−netCash) so it cancels
            // the residual instead of doubling it.
            var remaining = -netCash
            for slice in sortedByWeight {
                let current = rounded[slice.symbol] ?? Decimal(0)
                let newValue = current + remaining

                // Check if this fold would flip the order side or make it negative
                if (current >= 0 && newValue >= 0) || (current < 0 && newValue < 0) {
                    // Same side or stays zero: safe to absorb
                    rounded[slice.symbol] = newValue
                    remaining = 0
                    break
                } else if newValue == 0 {
                    // Exactly zero: safe to absorb
                    rounded[slice.symbol] = 0
                    remaining = 0
                    break
                } else if current == 0 && abs(newValue) < abs(remaining) {
                    // This slice is zero and the fold reduces the magnitude: safe to take what we can
                    rounded[slice.symbol] = newValue
                    remaining = 0
                    break
                } else {
                    // Flip or negative: clamp to 0 and walk on
                    rounded[slice.symbol] = 0
                    remaining = newValue
                }
            }

            // If remainder still not absorbed (all slices clamped to 0), leave it
            // This should not happen in well-formed cases
        }

        // Build orders. Zero deltas are excluded; all nonzero deltas are already
        // 2-dp rounded, so every remaining amount is ≥ $0.01 (no sub-cent filter needed).
        var orders: [RebalanceOrder] = []
        let currencyCode = currentValues.values.first?.currencyCode ?? "USD"

        for slice in targets {
            let delta = rounded[slice.symbol] ?? Decimal(0)
            if delta == 0 {
                continue  // No change needed
            }

            let amount = abs(delta)
            let side: RebalanceSide = delta > 0 ? .buy : .sell
            let order = RebalanceOrder(
                symbol: slice.symbol,
                side: side,
                amount: Money(amount: amount, currencyCode: currencyCode)
            )
            orders.append(order)
        }

        return orders
    }
}
