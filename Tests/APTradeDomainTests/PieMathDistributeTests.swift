import Foundation
import XCTest
@testable import APTradeDomain

final class PieMathDistributeTests: XCTestCase {

    // MARK: - Test (a): Empty Pie, 50/50, $100 → exact split
    func testEmptyPieEvenSplit() {
        let contribution = Money(amount: 100)
        let currentValues: [String: Money] = [:]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))
        ]

        let result = PieMath.distribute(contribution: contribution, currentValues: currentValues, targets: targets)

        // Both should get exactly $50
        XCTAssertEqual(result["A"]?.amount, Decimal(50))
        XCTAssertEqual(result["B"]?.amount, Decimal(50))

        // Sum must equal contribution
        let sum = result.values.reduce(Decimal(0)) { $0 + $1.amount }
        XCTAssertEqual(sum, contribution.amount)
    }

    // MARK: - Test (b): Drifted Pie (A=$70, B=$30, targets 50/50), $20 → all to B
    func testDriftedPieAllToUnderweight() {
        let contribution = Money(amount: 20)
        let currentValues: [String: Money] = [
            "A": Money(amount: 70),
            "B": Money(amount: 30)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))
        ]

        let result = PieMath.distribute(contribution: contribution, currentValues: currentValues, targets: targets)

        // A is overweight (70 vs 50 target), B is underweight (30 vs 50 target)
        // With totalAfter = 100 + 20 = 120:
        //   - A ideal = 120 * 0.5 = 60, current = 70, deficit = max(0, 60-70) = 0
        //   - B ideal = 120 * 0.5 = 60, current = 30, deficit = max(0, 60-30) = 30
        // Σdeficits = 30, which equals contribution (20), so we're in "sufficient" case
        // Actually wait: 30 > 20, so we're in deficit case where Σdeficits > contribution
        // Give contribution × deficit/Σdeficits
        // A gets: 20 × 0/30 = 0
        // B gets: 20 × 30/30 = 20
        XCTAssertEqual(result["A"]?.amount, Decimal(0))
        XCTAssertEqual(result["B"]?.amount, Decimal(20))

        let sum = result.values.reduce(Decimal(0)) { $0 + $1.amount }
        XCTAssertEqual(sum, contribution.amount)
    }

    // MARK: - Test (c): Drift larger than contribution (A=$90, B=$10, 50/50, $10)
    func testDriftLargerThanContribution() {
        let contribution = Money(amount: 10)
        let currentValues: [String: Money] = [
            "A": Money(amount: 90),
            "B": Money(amount: 10)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))
        ]

        let result = PieMath.distribute(contribution: contribution, currentValues: currentValues, targets: targets)

        // With totalAfter = 90 + 10 + 10 = 110:
        //   - A ideal = 110 * 0.5 = 55, current = 90, deficit = max(0, 55-90) = 0
        //   - B ideal = 110 * 0.5 = 55, current = 10, deficit = max(0, 55-10) = 45
        // Σdeficits = 45 > contribution (10), so:
        // A gets: 10 × 0/45 = 0
        // B gets: 10 × 45/45 = 10
        XCTAssertEqual(result["A"]?.amount, Decimal(0))
        XCTAssertEqual(result["B"]?.amount, Decimal(10))

        let sum = result.values.reduce(Decimal(0)) { $0 + $1.amount }
        XCTAssertEqual(sum, contribution.amount)
    }

    // MARK: - Test (d): Three slices with cent-rounding
    func testThreeSlicesCentRounding() {
        let contribution = Money(amount: 100)
        let currentValues: [String: Money] = [:]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: Decimal(string: "33.33") ?? 0)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: Decimal(string: "33.33") ?? 0)),
            PieSlice(symbol: "C", assetKind: .stock, targetWeight: Percentage(value: Decimal(string: "33.34") ?? 0))
        ]

        let result = PieMath.distribute(contribution: contribution, currentValues: currentValues, targets: targets)

        // Should split roughly 33.33, 33.33, 33.34
        let a = result["A"]?.amount ?? 0
        let b = result["B"]?.amount ?? 0
        let c = result["C"]?.amount ?? 0

        // All must be non-negative
        XCTAssertGreaterThanOrEqual(a, 0)
        XCTAssertGreaterThanOrEqual(b, 0)
        XCTAssertGreaterThanOrEqual(c, 0)

        // Sum must be exactly 100
        let sum = a + b + c
        XCTAssertEqual(sum, contribution.amount)
    }

    // MARK: - Test: Regression — rounding-induced negative via remainder
    func testRegressionNegativeRemainderCounterexample() {
        // Reviewer counterexample: deficits cause .plain rounding UP, making remainder negative.
        // targets A=60/B=20/C=20, current A=$32.30, B=$0.97, C=$0.97, contribution=$6.53
        let contribution = Money(amount: Decimal(string: "6.53") ?? 0)
        let currentValues: [String: Money] = [
            "A": Money(amount: Decimal(string: "32.30") ?? 0),
            "B": Money(amount: Decimal(string: "0.97") ?? 0),
            "C": Money(amount: Decimal(string: "0.97") ?? 0)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 60)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 20)),
            PieSlice(symbol: "C", assetKind: .stock, targetWeight: Percentage(value: 20))
        ]

        let result = PieMath.distribute(contribution: contribution, currentValues: currentValues, targets: targets)

        // Invariant 1: Sum equals contribution exactly
        let sum = result.values.reduce(Decimal(0)) { $0 + $1.amount }
        XCTAssertEqual(sum, contribution.amount, "Sum must equal contribution exactly")

        // Invariant 2: All values non-negative (the critical defect being fixed)
        for (symbol, money) in result {
            XCTAssertGreaterThanOrEqual(money.amount, 0, "Symbol \(symbol) must be non-negative, got \(money.amount)")
        }
    }

    // MARK: - Test: Weight-tie lexicographic remainder rule
    func testRemainderDistributionByWeightAndLexicographic() {
        // Targets A=35/B=35/C=30, empty pie, contribution $10.01.
        // totalAfter = 10.01
        // Deficits: A = 10.01*35/100 = 3.5035, B = 3.5035, C = 10.01*30/100 = 3.003
        // sumDeficits = 10.01 (sufficient case)
        // Allocations: A = 3.5035, B = 3.5035, C = 3.003
        // Rounded: A = 3.50, B = 3.50, C = 3.00 (all .plain rounds 3.5035→3.50, 3.003→3.00)
        // Sum rounded = 10.00, remainder = +0.01
        // Sorted by weight (desc) and symbol (asc): [A(35), B(35), C(30)]
        // A gets remainder: A = 3.50 + 0.01 = 3.51 ✓
        // Expected: A = 3.51, B = 3.50, C = 3.00, sum = 10.01

        let contribution = Money(amount: Decimal(string: "10.01") ?? 0)
        let currentValues: [String: Money] = [:]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 35)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 35)),
            PieSlice(symbol: "C", assetKind: .stock, targetWeight: Percentage(value: 30))
        ]

        let result = PieMath.distribute(contribution: contribution, currentValues: currentValues, targets: targets)

        // Sum must be exactly $10.01
        let sum = result.values.reduce(Decimal(0)) { $0 + $1.amount }
        XCTAssertEqual(sum, contribution.amount, "Sum must equal contribution exactly")

        // Verify the weight-tie lexicographic rule: remainder lands on A (lex-first of 35/35 tie)
        let a = result["A"]?.amount ?? 0
        let b = result["B"]?.amount ?? 0
        let c = result["C"]?.amount ?? 0

        XCTAssertEqual(a, Decimal(string: "3.51") ?? 0, "A should get the remainder (3.50 + 0.01)")
        XCTAssertEqual(b, Decimal(string: "3.50") ?? 0, "B should get exactly 3.50")
        XCTAssertEqual(c, Decimal(string: "3.00") ?? 0, "C should get exactly 3.00")
    }

    // MARK: - Test (e): Property loop over 25 seeded cases
    func testPropertyLoopInvariants() {
        // Fixed array of (contribution, currents, targets) tuples
        let testCases: [(contribution: Decimal, currents: [String: Decimal], targets: [(symbol: String, weight: Decimal)])] = [
            (100, [:], [("A", 50), ("B", 50)]),
            (50, ["A": 50, "B": 50], [("A", 50), ("B", 50)]),
            (25, ["A": 100, "B": 0], [("A", 50), ("B", 50)]),
            (75, ["A": 10, "B": 90], [("A", 60), ("B", 40)]),
            (200, [:], [("A", 33), ("B", 33), ("C", 34)]),
            (15, ["A": 70, "B": 30], [("A", 50), ("B", 50)]),
            (1000, ["A": 5000, "B": 5000], [("A", 50), ("B", 50)]),
            (123, ["A": 77, "B": 100, "C": 23], [("A", 40), ("B", 40), ("C", 20)]),
            (99, ["A": 1], [("A", 50), ("B", 50)]),
            (250, ["X": 1000], [("A", 25), ("B", 25), ("C", 25), ("D", 25)]),
            (500, [:], [("A", 20), ("B", 20), ("C", 20), ("D", 20), ("E", 20)]),
            (333, ["A": 100, "B": 200, "C": 300], [("A", 40), ("B", 30), ("C", 30)]),
            (1, ["A": 0, "B": 0], [("A", 50), ("B", 50)]),
            (10000, [:], [("A", 50), ("B", 50)]),
            (777, ["A": 50, "B": 50, "C": 50], [("A", 33), ("B", 33), ("C", 34)]),
            (42, ["X": 100, "Y": 200], [("A", 50), ("B", 50)]),
            (999, ["A": 1, "B": 1, "C": 1], [("A", 25), ("B", 25), ("C", 25), ("D", 25)]),
            (100, ["A": 1000], [("A", 50), ("B", 50)]),
            (50, ["A": 25, "B": 75], [("A", 50), ("B", 50)]),
            (2000, [:], [("A", 10), ("B", 20), ("C", 30), ("D", 40)]),
            (88, ["X": 11, "Y": 22], [("A", 33), ("B", 33), ("C", 34)]),
            (144, ["A": 56, "B": 44], [("A", 50), ("B", 50)]),
            (201, ["A": 100, "B": 100, "C": 100], [("A", 25), ("B", 25), ("C", 25), ("D", 25)]),
            (55, ["A": 45], [("A", 50), ("B", 50)]),
            (666, ["A": 333, "B": 333], [("A", 33), ("B", 33), ("C", 34)])
        ]

        for (idx, testCase) in testCases.enumerated() {
            let contribution = Money(amount: testCase.contribution)
            let currentValues = testCase.currents.mapValues { Money(amount: $0) }
            let targets = testCase.targets.map {
                PieSlice(symbol: $0.symbol, assetKind: .stock, targetWeight: Percentage(value: $0.weight))
            }

            let result = PieMath.distribute(contribution: contribution, currentValues: currentValues, targets: targets)

            // Invariant 1: Sum equals contribution
            let sum = result.values.reduce(Decimal(0)) { $0 + $1.amount }
            XCTAssertEqual(sum, contribution.amount, "Case \(idx): sum mismatch")

            // Invariant 2: All values non-negative
            for (symbol, money) in result {
                XCTAssertGreaterThanOrEqual(money.amount, 0, "Case \(idx): \(symbol) is negative")
            }

            // Invariant 3: No output for symbols absent from targets
            let targetSymbols = Set(targets.map(\.symbol))
            for symbol in result.keys {
                XCTAssertTrue(targetSymbols.contains(symbol), "Case \(idx): symbol \(symbol) not in targets")
            }
        }
    }
}
