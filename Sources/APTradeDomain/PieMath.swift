import Foundation

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
    private static func rounded(_ d: Decimal) -> Decimal {
        var result = d
        var input = d
        NSDecimalRound(&result, &input, 2, .plain)
        return result
    }
}
