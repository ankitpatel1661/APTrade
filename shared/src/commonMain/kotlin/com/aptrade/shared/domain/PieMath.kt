package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.RoundingMode

/**
 * Self-balancing contribution distribution and rebalance math for investment pies.
 * Transcribed from `Sources/APTradeDomain/PieMath.swift` (the shipped M7.1 Swift/macOS
 * reference) — semantics must not drift, including the two M7.1 review corrections baked
 * into the AS-BUILT Swift: (1) [distribute]'s negative-remainder walk clamps at zero and
 * carries the unclamped portion onward (never an unconditional largest-slice add), and
 * (2) [rebalancePlan]'s net-cash fold uses `remaining = -netCash` (negation, not addition)
 * so the correction cancels the residual instead of doubling it.
 */

/** Direction of a rebalance trade: buy or sell. */
enum class RebalanceSide { Buy, Sell }

/** A single rebalance trade to restore a pie to its target allocation. */
data class RebalanceOrder(
    val symbol: String,
    val side: RebalanceSide,
    val amount: Money, // always positive
)

object PieMath {

    private val ZERO: BigDecimal = BigDecimal.ZERO
    private val ONE_HUNDRED: BigDecimal = BigDecimal.parseString("100")
    private val CENT: BigDecimal = BigDecimal.parseString("0.01")

    /**
     * Rounds [value] to 2 decimal places using half-away-from-zero rounding — the Kotlin
     * equivalent of Swift's `NSDecimalRound(&result, &input, 2, .plain)`. Centralized here so
     * [distribute], [drift], [rebalancePlan], and [equalWeights] share exactly one rounding
     * rule rather than each re-deriving it (and risking divergence at a boundary case).
     * Internal (not private): reused across this file's top-level functions only.
     */
    internal fun roundedCents(value: BigDecimal): BigDecimal =
        value.roundToDigitPositionAfterDecimalPoint(2, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)

    /** Slices ordered by target weight (descending), then symbol (ascending) for ties — the
     *  deterministic walk order used by both [distribute]'s remainder fold and
     *  [rebalancePlan]'s net-cash fold. */
    private fun sortedByWeightDescThenSymbolAsc(targets: List<PieSlice>): List<PieSlice> =
        targets.sortedWith(
            Comparator<PieSlice> { a, b -> b.targetWeightPP.compareTo(a.targetWeightPP) }
                .thenBy { it.symbol },
        )

    /**
     * Splits a [contribution] across pie slices, preferring underweight slices.
     *
     * @param contribution The amount of cash to distribute.
     * @param currentValues Map of symbol to current money value in the pie.
     * @param targets The target allocation (slices with symbol and target weight).
     *
     * @return A map from each target symbol to its allocation from the contribution. Output
     *   values are >= 0, rounded to cents, and sum EXACTLY to [contribution] (remainder cents
     *   go to the largest-target slice; ties -> lexicographically first symbol).
     */
    fun distribute(
        contribution: Money,
        currentValues: Map<String, Money>,
        targets: List<PieSlice>,
    ): Map<String, Money> {
        val contrib = contribution.amount
        val currencyCode = contribution.currencyCode

        // Compute current values for each target symbol (0 if not in currentValues)
        val currentBySymbol: Map<String, BigDecimal> = targets.associate { slice ->
            slice.symbol to (currentValues[slice.symbol]?.amount ?: ZERO)
        }

        // Compute totalAfter
        val totalCurrent = currentBySymbol.values.fold(ZERO) { acc, v -> acc + v }
        val totalAfter = totalCurrent + contrib

        // Compute deficits
        val deficits = LinkedHashMap<String, BigDecimal>()
        var sumDeficits = ZERO
        for (slice in targets) {
            val ideal = (totalAfter * slice.targetWeightPP).divide(ONE_HUNDRED, MONEY_MATH)
            val current = currentBySymbol[slice.symbol] ?: ZERO
            val deficit = maxOf(ZERO, ideal - current)
            deficits[slice.symbol] = deficit
            sumDeficits += deficit
        }

        // Determine allocation strategy
        val allocations = LinkedHashMap<String, BigDecimal>()

        if (sumDeficits <= contrib) {
            // Sufficient case: give each slice its deficit, then split leftover pro-rata by target weight
            for (slice in targets) {
                allocations[slice.symbol] = deficits[slice.symbol] ?: ZERO
            }

            val leftover = contrib - sumDeficits
            if (leftover > ZERO) {
                val sumWeights = targets.fold(ZERO) { acc, s -> acc + s.targetWeightPP }
                for (slice in targets) {
                    val share = (leftover * slice.targetWeightPP).divide(sumWeights, MONEY_MATH)
                    allocations[slice.symbol] = (allocations[slice.symbol] ?: ZERO) + share
                }
            }
        } else {
            // Deficit case: give contribution x deficit/sumDeficits
            for (slice in targets) {
                val deficit = deficits[slice.symbol] ?: ZERO
                allocations[slice.symbol] = (contrib * deficit).divide(sumDeficits, MONEY_MATH)
            }
        }

        // Round each share to 2 decimal places (cents)
        val rounded = LinkedHashMap<String, BigDecimal>()
        var sumRounded = ZERO
        for (slice in targets) {
            val value = allocations[slice.symbol] ?: ZERO
            val r = roundedCents(value)
            rounded[slice.symbol] = r
            sumRounded += r
        }

        // Distribute remainder, clamping to avoid negative values (CLAMPED WALK — never an
        // unconditional largest-slice add; see file doc comment).
        var remainder = contrib - sumRounded
        if (!remainder.isZero()) {
            // Sort slices by target weight (desc), then symbol (asc for ties)
            val sortedByWeightAndSymbol = sortedByWeightDescThenSymbolAsc(targets)

            // Walk through slices, adding remainder and clamping at 0
            for (slice in sortedByWeightAndSymbol) {
                val current = rounded[slice.symbol] ?: ZERO
                val newValue = current + remainder
                if (newValue >= ZERO) {
                    // Can fully absorb the remainder
                    rounded[slice.symbol] = newValue
                    remainder = ZERO
                    break
                } else {
                    // Clamp to 0, roll over the unclamped portion
                    rounded[slice.symbol] = ZERO
                    remainder = newValue // newValue is negative, so remainder becomes more negative
                }
            }
        }

        // Convert back to Money
        return rounded.mapValues { (_, v) -> Money(v, currencyCode) }
    }

    /**
     * Splits 100 percentage points evenly across [count] shares using the largest-remainder
     * method, so the result always sums to EXACTLY 100 (never 99.99 or 100.01 from naive
     * division). Each share is `100 / count` rounded to 2dp via [roundedCents]; the leftover
     * (always a small multiple of 0.01pp, since 2dp rounding bounds each share's error to
     * under half a cent-of-a-point) is distributed one 0.01pp unit at a time, walking from
     * the LAST share backward — an arbitrary but deterministic tie-break, since every share
     * is equally entitled to the remainder in an equal split.
     *
     * @param count Number of shares. `count <= 0` returns an empty list.
     */
    fun equalWeights(count: Int): List<BigDecimal> {
        if (count <= 0) return emptyList()
        val base = roundedCents(ONE_HUNDRED.divide(BigDecimal.fromInt(count), MONEY_MATH))
        val totals = MutableList(count) { base }
        var remainder = ONE_HUNDRED - (base * BigDecimal.fromInt(count))
        var index = count - 1
        while (remainder > ZERO && index >= 0) {
            totals[index] = totals[index] + CENT
            remainder -= CENT
            index -= 1
        }
        return totals
    }

    /**
     * Calculates the signed drift (actual % minus target %) for each slice, in percentage
     * points to 2 dp.
     *
     * Drift measures how far each asset is from its target allocation:
     * - Positive drift: overweight (should sell)
     * - Negative drift: underweight (should buy)
     * - Zero drift: at target
     *
     * @param currentValues Map of symbol to current money value in the pie.
     * @param targets The target allocation (slices with symbol and target weight).
     *
     * @return A map from each target symbol to its drift in percentage points, rounded to 2
     *   decimal places.
     */
    fun drift(
        currentValues: Map<String, Money>,
        targets: List<PieSlice>,
    ): Map<String, BigDecimal> {
        // Compute total current value (sums ALL currentValues entries, not just target symbols)
        val totalCurrent = currentValues.values.fold(ZERO) { acc, m -> acc + m.amount }

        // Compute drift per slice: actual% - target%
        val result = LinkedHashMap<String, BigDecimal>()

        for (slice in targets) {
            val current = currentValues[slice.symbol]?.amount ?: ZERO
            val actualPercent = if (totalCurrent > ZERO) {
                current.divide(totalCurrent, MONEY_MATH) * ONE_HUNDRED
            } else {
                ZERO
            }
            val driftValue = actualPercent - slice.targetWeightPP
            result[slice.symbol] = roundedCents(driftValue)
        }

        return result
    }

    /**
     * Generates rebalance orders to restore each slice to its target allocation.
     *
     * The plan achieves exact net-zero cash by:
     * 1. Computing delta (buy/sell amount) for each slice: `delta_i = (target_i% / 100) * total - current_i`
     * 2. Rounding each delta to cents
     * 3. Folding the negated net-cash remainder (`-netCash`) into the largest-target slice's
     *    order, so the correction cancels the residual rather than doubling it (if that fold
     *    would flip the order side or make amount negative, walk to the next slice)
     *
     * Orders are always 2-dp amounts and zero deltas are excluded above, so no sub-cent
     * orders can arise — no separate sub-cent filter is needed.
     *
     * @param currentValues Map of symbol to current money value in the pie.
     * @param targets The target allocation (slices with symbol and target weight).
     *
     * @return A list of rebalance orders, each with a positive amount, such that
     *   sum(buy amounts) == sum(sell amounts) exactly. Empty if already at target or the
     *   pie is empty.
     */
    fun rebalancePlan(
        currentValues: Map<String, Money>,
        targets: List<PieSlice>,
    ): List<RebalanceOrder> {
        // Compute total current value (sums ALL currentValues entries, not just target symbols)
        val totalCurrent = currentValues.values.fold(ZERO) { acc, m -> acc + m.amount }

        // Empty or at-target pie: no rebalancing needed
        if (totalCurrent.isZero()) {
            return emptyList()
        }

        // Compute raw deltas (target - current) for each slice
        val deltas = LinkedHashMap<String, BigDecimal>()
        for (slice in targets) {
            val current = currentValues[slice.symbol]?.amount ?: ZERO
            val ideal = slice.targetWeightPP.divide(ONE_HUNDRED, MONEY_MATH) * totalCurrent
            deltas[slice.symbol] = ideal - current
        }

        // Round each delta to cents
        val rounded = LinkedHashMap<String, BigDecimal>()
        var netCash = ZERO // sum of all rounded deltas (should be ~0 after folding)

        for (slice in targets) {
            val delta = deltas[slice.symbol] ?: ZERO
            val r = roundedCents(delta)
            rounded[slice.symbol] = r
            netCash += r
        }

        // Fold remainder into largest-target slice, walking if needed
        if (!netCash.isZero()) {
            // Sort slices by target weight (descending), then symbol (ascending for ties)
            val sortedByWeight = sortedByWeightDescThenSymbolAsc(targets)

            // Walk through slices, trying to fold the correction (-netCash) so it cancels
            // the residual instead of doubling it.
            var remaining = -netCash
            for (slice in sortedByWeight) {
                val current = rounded[slice.symbol] ?: ZERO
                val newValue = current + remaining

                // Check if this fold would flip the order side or make it negative
                if ((current >= ZERO && newValue >= ZERO) || (current < ZERO && newValue < ZERO)) {
                    // Same side or stays zero: safe to absorb
                    rounded[slice.symbol] = newValue
                    remaining = ZERO
                    break
                } else if (newValue.isZero()) {
                    // Exactly zero: safe to absorb
                    rounded[slice.symbol] = ZERO
                    remaining = ZERO
                    break
                } else if (current.isZero() && newValue.abs() < remaining.abs()) {
                    // This slice is zero and the fold reduces the magnitude: safe to take what we can
                    rounded[slice.symbol] = newValue
                    remaining = ZERO
                    break
                } else {
                    // Flip or negative: clamp to 0 and walk on
                    rounded[slice.symbol] = ZERO
                    remaining = newValue
                }
            }

            // If remainder still not absorbed (all slices clamped to 0), leave it
            // This should not happen in well-formed cases
        }

        // Build orders. Zero deltas are excluded; all nonzero deltas are already
        // 2-dp rounded, so every remaining amount is >= $0.01 (no sub-cent filter needed).
        val orders = mutableListOf<RebalanceOrder>()
        val currencyCode = currentValues.values.firstOrNull()?.currencyCode ?: "USD"

        for (slice in targets) {
            val delta = rounded[slice.symbol] ?: ZERO
            if (delta.isZero()) {
                continue // No change needed
            }

            val amount = delta.abs()
            val side = if (delta > ZERO) RebalanceSide.Buy else RebalanceSide.Sell
            orders += RebalanceOrder(
                symbol = slice.symbol,
                side = side,
                amount = Money(amount, currencyCode),
            )
        }

        return orders
    }
}
