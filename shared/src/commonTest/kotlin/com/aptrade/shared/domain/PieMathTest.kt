package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transcribed from `Tests/APTradeDomainTests/PieMathDistributeTests.swift` (7 fixtures) and
 * `Tests/APTradeDomainTests/PieMathRebalanceTests.swift` (8 fixtures), byte-value-equal,
 * plus two [PieMath.equalWeights] fixtures per its doc comment (untested directly in Swift
 * XCTest — only exercised via `PieWizardViewModel` there — but its largest-remainder
 * contract is specified precisely enough to pin here).
 */
class PieMathTest {

    private fun money(s: String, currency: String = "USD"): Money = Money(BigDecimal.parseString(s), currency)
    private fun pp(s: String): BigDecimal = BigDecimal.parseString(s)
    private fun slice(symbol: String, weight: String, kind: AssetKind = AssetKind.Stock): PieSlice =
        PieSlice(symbol = symbol, assetKind = kind, targetWeightPP = pp(weight))

    // ============================================================
    // MARK: - distribute()
    // ============================================================

    // -- (a) Empty Pie, 50/50, $100 -> exact split --
    @Test
    fun emptyPieEvenSplitSplitsExactlyInHalf() {
        val contribution = money("100")
        val currentValues: Map<String, Money> = emptyMap()
        val targets = listOf(slice("A", "50"), slice("B", "50"))

        val result = PieMath.distribute(contribution, currentValues, targets)

        assertEquals(BigDecimal.parseString("50"), result["A"]?.amount)
        assertEquals(BigDecimal.parseString("50"), result["B"]?.amount)

        val sum = result.values.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount }
        assertEquals(contribution.amount, sum)
    }

    // -- (b) Drifted Pie (A=$70, B=$30, targets 50/50), $20 -> all to B --
    @Test
    fun driftedPieAllToUnderweightSlice() {
        val contribution = money("20")
        val currentValues = mapOf("A" to money("70"), "B" to money("30"))
        val targets = listOf(slice("A", "50"), slice("B", "50"))

        val result = PieMath.distribute(contribution, currentValues, targets)

        // totalAfter = 100 + 20 = 120: A ideal=60 current=70 deficit=0; B ideal=60 current=30 deficit=30.
        // sumDeficits=30 > contribution(20) -> deficit case: A=20*0/30=0, B=20*30/30=20.
        assertEquals(BigDecimal.parseString("0"), result["A"]?.amount)
        assertEquals(BigDecimal.parseString("20"), result["B"]?.amount)

        val sum = result.values.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount }
        assertEquals(contribution.amount, sum)
    }

    // -- (c) Drift larger than contribution (A=$90, B=$10, 50/50, $10) --
    @Test
    fun driftLargerThanContributionSplitsByDeficitShare() {
        val contribution = money("10")
        val currentValues = mapOf("A" to money("90"), "B" to money("10"))
        val targets = listOf(slice("A", "50"), slice("B", "50"))

        val result = PieMath.distribute(contribution, currentValues, targets)

        // totalAfter = 90+10+10=110: A ideal=55 current=90 deficit=0; B ideal=55 current=10 deficit=45.
        // sumDeficits=45 > contribution(10): A=10*0/45=0, B=10*45/45=10.
        assertEquals(BigDecimal.parseString("0"), result["A"]?.amount)
        assertEquals(BigDecimal.parseString("10"), result["B"]?.amount)

        val sum = result.values.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount }
        assertEquals(contribution.amount, sum)
    }

    // -- (d) Three slices with cent-rounding --
    @Test
    fun threeSlicesCentRoundingSumsExactlyToContribution() {
        val contribution = money("100")
        val currentValues: Map<String, Money> = emptyMap()
        val targets = listOf(slice("A", "33.33"), slice("B", "33.33"), slice("C", "33.34"))

        val result = PieMath.distribute(contribution, currentValues, targets)

        val a = result["A"]?.amount ?: BigDecimal.ZERO
        val b = result["B"]?.amount ?: BigDecimal.ZERO
        val c = result["C"]?.amount ?: BigDecimal.ZERO

        assertTrue(a >= BigDecimal.ZERO)
        assertTrue(b >= BigDecimal.ZERO)
        assertTrue(c >= BigDecimal.ZERO)

        val sum = a + b + c
        assertEquals(contribution.amount, sum)
    }

    // -- Regression: rounding-induced negative via remainder --
    // Reviewer counterexample: deficits cause .plain rounding UP, making remainder negative.
    // targets A=60/B=20/C=20, current A=$32.30, B=$0.97, C=$0.97, contribution=$6.53
    @Test
    fun regressionNegativeRemainderCounterexampleStaysNonNegative() {
        val contribution = money("6.53")
        val currentValues = mapOf(
            "A" to money("32.30"),
            "B" to money("0.97"),
            "C" to money("0.97"),
        )
        val targets = listOf(slice("A", "60"), slice("B", "20"), slice("C", "20"))

        val result = PieMath.distribute(contribution, currentValues, targets)

        // Invariant 1: Sum equals contribution exactly
        val sum = result.values.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount }
        assertEquals(contribution.amount, sum, "Sum must equal contribution exactly")

        // Invariant 2: All values non-negative (the critical defect being fixed)
        for ((symbol, m) in result) {
            assertTrue(m.amount >= BigDecimal.ZERO, "Symbol $symbol must be non-negative, got ${m.amount}")
        }

        // Pinned expected split (transcribed from the Swift regression comment):
        // A=0, B=3.26, C=3.27
        assertEquals(BigDecimal.parseString("0"), result["A"]?.amount)
        assertEquals(BigDecimal.parseString("3.26"), result["B"]?.amount)
        assertEquals(BigDecimal.parseString("3.27"), result["C"]?.amount)
    }

    // -- Weight-tie lexicographic remainder rule --
    // Targets A=35/B=35/C=30, empty pie, contribution $10.01.
    // Rounded: A=3.50, B=3.50, C=3.00 (sum=10.00, remainder=+0.01)
    // Sorted by weight(desc)/symbol(asc): [A(35), B(35), C(30)] -> A absorbs the remainder.
    @Test
    fun remainderDistributionByWeightAndLexicographicTieBreak() {
        val contribution = money("10.01")
        val currentValues: Map<String, Money> = emptyMap()
        val targets = listOf(slice("A", "35"), slice("B", "35"), slice("C", "30"))

        val result = PieMath.distribute(contribution, currentValues, targets)

        val sum = result.values.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount }
        assertEquals(contribution.amount, sum, "Sum must equal contribution exactly")

        assertEquals(BigDecimal.parseString("3.51"), result["A"]?.amount, "A should get the remainder (3.50 + 0.01)")
        assertEquals(BigDecimal.parseString("3.50"), result["B"]?.amount, "B should get exactly 3.50")
        assertEquals(BigDecimal.parseString("3.00"), result["C"]?.amount, "C should get exactly 3.00")
    }

    // -- (e) Property loop over 25 seeded cases --
    @Test
    fun propertyLoopInvariantsHoldAcross25FixedCases() {
        data class Case(val contribution: String, val currents: Map<String, String>, val targets: List<Pair<String, String>>)

        val cases = listOf(
            Case("100", emptyMap(), listOf("A" to "50", "B" to "50")),
            Case("50", mapOf("A" to "50", "B" to "50"), listOf("A" to "50", "B" to "50")),
            Case("25", mapOf("A" to "100", "B" to "0"), listOf("A" to "50", "B" to "50")),
            Case("75", mapOf("A" to "10", "B" to "90"), listOf("A" to "60", "B" to "40")),
            Case("200", emptyMap(), listOf("A" to "33", "B" to "33", "C" to "34")),
            Case("15", mapOf("A" to "70", "B" to "30"), listOf("A" to "50", "B" to "50")),
            Case("1000", mapOf("A" to "5000", "B" to "5000"), listOf("A" to "50", "B" to "50")),
            Case("123", mapOf("A" to "77", "B" to "100", "C" to "23"), listOf("A" to "40", "B" to "40", "C" to "20")),
            Case("99", mapOf("A" to "1"), listOf("A" to "50", "B" to "50")),
            Case("250", mapOf("X" to "1000"), listOf("A" to "25", "B" to "25", "C" to "25", "D" to "25")),
            Case("500", emptyMap(), listOf("A" to "20", "B" to "20", "C" to "20", "D" to "20", "E" to "20")),
            Case("333", mapOf("A" to "100", "B" to "200", "C" to "300"), listOf("A" to "40", "B" to "30", "C" to "30")),
            Case("1", mapOf("A" to "0", "B" to "0"), listOf("A" to "50", "B" to "50")),
            Case("10000", emptyMap(), listOf("A" to "50", "B" to "50")),
            Case("777", mapOf("A" to "50", "B" to "50", "C" to "50"), listOf("A" to "33", "B" to "33", "C" to "34")),
            Case("42", mapOf("X" to "100", "Y" to "200"), listOf("A" to "50", "B" to "50")),
            Case("999", mapOf("A" to "1", "B" to "1", "C" to "1"), listOf("A" to "25", "B" to "25", "C" to "25", "D" to "25")),
            Case("100", mapOf("A" to "1000"), listOf("A" to "50", "B" to "50")),
            Case("50", mapOf("A" to "25", "B" to "75"), listOf("A" to "50", "B" to "50")),
            Case("2000", emptyMap(), listOf("A" to "10", "B" to "20", "C" to "30", "D" to "40")),
            Case("88", mapOf("X" to "11", "Y" to "22"), listOf("A" to "33", "B" to "33", "C" to "34")),
            Case("144", mapOf("A" to "56", "B" to "44"), listOf("A" to "50", "B" to "50")),
            Case("201", mapOf("A" to "100", "B" to "100", "C" to "100"), listOf("A" to "25", "B" to "25", "C" to "25", "D" to "25")),
            Case("55", mapOf("A" to "45"), listOf("A" to "50", "B" to "50")),
            Case("666", mapOf("A" to "333", "B" to "333"), listOf("A" to "33", "B" to "33", "C" to "34")),
        )

        cases.forEachIndexed { idx, case ->
            val contribution = money(case.contribution)
            val currentValues = case.currents.mapValues { (_, v) -> money(v) }
            val targets = case.targets.map { (symbol, weight) -> slice(symbol, weight) }

            val result = PieMath.distribute(contribution, currentValues, targets)

            // Invariant 1: Sum equals contribution
            val sum = result.values.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount }
            assertEquals(contribution.amount, sum, "Case $idx: sum mismatch")

            // Invariant 2: All values non-negative
            for ((symbol, m) in result) {
                assertTrue(m.amount >= BigDecimal.ZERO, "Case $idx: $symbol is negative")
            }

            // Invariant 3: No output for symbols absent from targets
            val targetSymbols = targets.map { it.symbol }.toSet()
            for (symbol in result.keys) {
                assertTrue(symbol in targetSymbols, "Case $idx: symbol $symbol not in targets")
            }
        }
    }

    // ============================================================
    // MARK: - drift()
    // ============================================================

    // Test drift calculation: A=$70, B=$30, targets 50/50 -> A +20.00, B -20.00
    @Test
    fun driftSimple5050SignedActualMinusTarget() {
        val currentValues = mapOf("A" to money("70"), "B" to money("30"))
        val targets = listOf(slice("A", "50"), slice("B", "50"))

        val result = PieMath.drift(currentValues, targets)

        assertEquals(BigDecimal.parseString("20.00"), result["A"], "A should drift +20.00")
        assertEquals(BigDecimal.parseString("-20.00"), result["B"], "B should drift -20.00")
    }

    // Test drift at-target: all slices at target -> all zero
    @Test
    fun driftAtTargetIsZeroForAllSlices() {
        val currentValues = mapOf("A" to money("50"), "B" to money("30"), "C" to money("20"))
        val targets = listOf(slice("A", "50"), slice("B", "30"), slice("C", "20"))

        val result = PieMath.drift(currentValues, targets)

        assertEquals(BigDecimal.ZERO, result["A"], "A at target should have 0 drift")
        assertEquals(BigDecimal.ZERO, result["B"], "B at target should have 0 drift")
        assertEquals(BigDecimal.ZERO, result["C"], "C at target should have 0 drift")
    }

    // ============================================================
    // MARK: - rebalancePlan()
    // ============================================================

    // Test rebalancePlan: A=$70, B=$30, targets 50/50 -> sell A $20, buy B $20
    @Test
    fun rebalancePlanSimple5050SellsOverweightBuysUnderweight() {
        val currentValues = mapOf("A" to money("70"), "B" to money("30"))
        val targets = listOf(slice("A", "50"), slice("B", "50"))

        val result = PieMath.rebalancePlan(currentValues, targets)

        assertEquals(2, result.size, "Should have 2 orders")

        val sellA = result.firstOrNull { it.symbol == "A" }
        val buyB = result.firstOrNull { it.symbol == "B" }

        assertTrue(sellA != null, "Should have a sell order for A")
        assertTrue(buyB != null, "Should have a buy order for B")

        assertEquals(RebalanceSide.Sell, sellA?.side, "A should be a sell")
        assertEquals(money("20"), sellA?.amount, "A should sell \$20")

        assertEquals(RebalanceSide.Buy, buyB?.side, "B should be a buy")
        assertEquals(money("20"), buyB?.amount, "B should buy \$20")
    }

    // Test rebalancePlan at-target: all slices at target -> empty plan
    @Test
    fun rebalancePlanAtTargetIsEmpty() {
        val currentValues = mapOf("A" to money("50"), "B" to money("30"), "C" to money("20"))
        val targets = listOf(slice("A", "50"), slice("B", "30"), slice("C", "20"))

        val result = PieMath.rebalancePlan(currentValues, targets)

        assertEquals(0, result.size, "At-target pie should have empty rebalance plan")
    }

    // Test rebalancePlan zero-total pie: empty currentValues -> empty plan
    @Test
    fun rebalancePlanZeroTotalPieIsEmpty() {
        val currentValues: Map<String, Money> = emptyMap()
        val targets = listOf(slice("A", "50"), slice("B", "50"))

        val result = PieMath.rebalancePlan(currentValues, targets)

        assertEquals(0, result.size, "Zero-total pie should have empty rebalance plan")
    }

    // Test rebalancePlan three-slice cent case: net cash residual folds exactly to zero
    // A=$100, B=$100, C=$100 (total=$300). Targets: A=40%($120), B=40%($120), C=20%($60).
    // Deltas: A=$20 buy, B=$20 buy, C=-$40 sell. Nets perfectly: $20+$20-$40=$0
    @Test
    fun rebalancePlanThreeSliceCentResidualNetsToExactlyZero() {
        val currentValues = mapOf("A" to money("100"), "B" to money("100"), "C" to money("100"))
        val targets = listOf(slice("A", "40"), slice("B", "40"), slice("C", "20"))

        val result = PieMath.rebalancePlan(currentValues, targets)

        val buysSum = result.filter { it.side == RebalanceSide.Buy }.fold(BigDecimal.ZERO) { acc, o -> acc + o.amount.amount }
        val sellsSum = result.filter { it.side == RebalanceSide.Sell }.fold(BigDecimal.ZERO) { acc, o -> acc + o.amount.amount }

        assertEquals(sellsSum, buysSum, "Net cash (buys - sells) must equal exactly \$0")
    }

    // Reviewer-verified counterexample: shipped fold added +netCash instead of -netCash,
    // doubling the imbalance. current {A:$1, B:$0, C:$0}, targets {A:33.34, B:33.33, C:33.33}
    // must net to exactly $0.00 (shipped code produced sell A 0.68 / buy B 0.33 / buy C 0.33,
    // net -0.02).
    @Test
    fun rebalancePlanNetZeroFoldDoesNotDoubleImbalance() {
        val currentValues = mapOf("A" to money("1"), "B" to money("0"), "C" to money("0"))
        val targets = listOf(slice("A", "33.34"), slice("B", "33.33"), slice("C", "33.33"))

        val result = PieMath.rebalancePlan(currentValues, targets)

        val buysSum = result.filter { it.side == RebalanceSide.Buy }.fold(BigDecimal.ZERO) { acc, o -> acc + o.amount.amount }
        val sellsSum = result.filter { it.side == RebalanceSide.Sell }.fold(BigDecimal.ZERO) { acc, o -> acc + o.amount.amount }

        assertEquals(sellsSum, buysSum, "Net cash must be exactly \$0, got buys=$buysSum sells=$sellsSum")
        for (order in result) {
            assertTrue(order.amount.amount > BigDecimal.ZERO, "Order for ${order.symbol} must have a positive amount")
        }
    }

    // Test rebalancePlan with partial holdings (some symbols missing)
    // A=$50, B missing (treat as $0), targets 50/50. Total=$50: A=$25, B=$25.
    // Deltas: A=-$25 (sell), B=+$25 (buy)
    @Test
    fun rebalancePlanPartialHoldingsTreatsMissingSymbolAsZero() {
        val currentValues = mapOf("A" to money("50"))
        val targets = listOf(slice("A", "50"), slice("B", "50"))

        val result = PieMath.rebalancePlan(currentValues, targets)

        assertEquals(2, result.size, "Should have 2 orders")

        val sellA = result.firstOrNull { it.symbol == "A" }
        val buyB = result.firstOrNull { it.symbol == "B" }

        assertEquals(RebalanceSide.Sell, sellA?.side)
        assertEquals(money("25"), sellA?.amount)

        assertEquals(RebalanceSide.Buy, buyB?.side)
        assertEquals(money("25"), buyB?.amount)
    }

    // ============================================================
    // MARK: - equalWeights()
    // ============================================================

    // Doc-specified largest-remainder split: 3-way split of 100pp -> 33.33/33.33/33.34,
    // summing to exactly 100 (never 99.99 or 100.01 from naive division).
    @Test
    fun equalWeightsThreeWaySplitSumsToExactly100() {
        val result = PieMath.equalWeights(3)

        assertEquals(3, result.size)
        assertEquals(BigDecimal.parseString("33.33"), result[0])
        assertEquals(BigDecimal.parseString("33.33"), result[1])
        assertEquals(BigDecimal.parseString("33.34"), result[2])

        val sum = result.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        assertEquals(BigDecimal.parseString("100"), sum)
    }

    @Test
    fun equalWeightsZeroOrNegativeCountReturnsEmptyList() {
        assertTrue(PieMath.equalWeights(0).isEmpty())
        assertTrue(PieMath.equalWeights(-1).isEmpty())
    }
}
