import Foundation
import XCTest
@testable import APTradeDomain

final class PieMathRebalanceTests: XCTestCase {

    // MARK: - drift() tests

    /// Test drift calculation: A=$70, B=$30, targets 50/50 → A +20.00, B −20.00
    func testDriftSimple50_50() {
        let currentValues: [String: Money] = [
            "A": Money(amount: 70),
            "B": Money(amount: 30)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))
        ]

        let result = PieMath.drift(currentValues: currentValues, targets: targets)

        // Total = $100. Drift is signed (actual − target).
        // A: actual 70, target 50 → drift = 70 - 50 = +20.00 (overweight)
        // B: actual 30, target 50 → drift = 30 - 50 = -20.00 (underweight)
        XCTAssertEqual(result["A"], Percentage(value: Decimal(string: "20.00") ?? 0), "A should drift +20.00")
        XCTAssertEqual(result["B"], Percentage(value: Decimal(string: "-20.00") ?? 0), "B should drift −20.00")
    }

    /// Test drift at-target: all slices at target → all zero
    func testDriftAtTarget() {
        let currentValues: [String: Money] = [
            "A": Money(amount: 50),
            "B": Money(amount: 30),
            "C": Money(amount: 20)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 30)),
            PieSlice(symbol: "C", assetKind: .stock, targetWeight: Percentage(value: 20))
        ]

        let result = PieMath.drift(currentValues: currentValues, targets: targets)

        XCTAssertEqual(result["A"], Percentage(value: 0), "A at target should have 0 drift")
        XCTAssertEqual(result["B"], Percentage(value: 0), "B at target should have 0 drift")
        XCTAssertEqual(result["C"], Percentage(value: 0), "C at target should have 0 drift")
    }

    // MARK: - rebalancePlan() tests

    /// Test rebalancePlan: A=$70, B=$30, targets 50/50 → sell A $20, buy B $20
    func testRebalancePlanSimple50_50() {
        let currentValues: [String: Money] = [
            "A": Money(amount: 70),
            "B": Money(amount: 30)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))
        ]

        let result = PieMath.rebalancePlan(currentValues: currentValues, targets: targets)

        // Total = $100. To reach targets:
        // - A needs to sell: 70 - 50 = $20
        // - B needs to buy: 50 - 30 = $20
        XCTAssertEqual(result.count, 2, "Should have 2 orders")

        let sellA = result.first { $0.symbol == "A" }
        let buyB = result.first { $0.symbol == "B" }

        XCTAssertNotNil(sellA, "Should have a sell order for A")
        XCTAssertNotNil(buyB, "Should have a buy order for B")

        XCTAssertEqual(sellA?.side, .sell, "A should be a sell")
        XCTAssertEqual(sellA?.amount, Money(amount: 20), "A should sell $20")

        XCTAssertEqual(buyB?.side, .buy, "B should be a buy")
        XCTAssertEqual(buyB?.amount, Money(amount: 20), "B should buy $20")
    }

    /// Test rebalancePlan at-target: all slices at target → empty plan
    func testRebalancePlanAtTarget() {
        let currentValues: [String: Money] = [
            "A": Money(amount: 50),
            "B": Money(amount: 30),
            "C": Money(amount: 20)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 30)),
            PieSlice(symbol: "C", assetKind: .stock, targetWeight: Percentage(value: 20))
        ]

        let result = PieMath.rebalancePlan(currentValues: currentValues, targets: targets)

        XCTAssertEqual(result.count, 0, "At-target pie should have empty rebalance plan")
    }

    /// Test rebalancePlan zero-total pie: empty currentValues → empty plan
    func testRebalancePlanZeroTotal() {
        let currentValues: [String: Money] = [:]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))
        ]

        let result = PieMath.rebalancePlan(currentValues: currentValues, targets: targets)

        XCTAssertEqual(result.count, 0, "Zero-total pie should have empty rebalance plan")
    }

    /// Test rebalancePlan three-slice cent case: net cash residual folds exactly to zero
    func testRebalancePlanThreeSliceCentResidual() {
        // Construct a case where rounding creates a cent residual that nets to exactly $0.00
        // Example: A=$100, B=$100, C=$100 (total=$300)
        // Targets: A=40% ($120), B=40% ($120), C=20% ($60)
        // Deltas: A=$20 buy, B=$20 buy, C=-$40 sell
        // This nets perfectly: $20 + $20 - $40 = $0
        let currentValues: [String: Money] = [
            "A": Money(amount: 100),
            "B": Money(amount: 100),
            "C": Money(amount: 100)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 40)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 40)),
            PieSlice(symbol: "C", assetKind: .stock, targetWeight: Percentage(value: 20))
        ]

        let result = PieMath.rebalancePlan(currentValues: currentValues, targets: targets)

        // Verify net cash is exactly zero
        let buys = result.filter { $0.side == .buy }
        let sells = result.filter { $0.side == .sell }
        let buysSum = buys.reduce(Decimal(0)) { $0 + $1.amount.amount }
        let sellsSum = sells.reduce(Decimal(0)) { $0 + $1.amount.amount }

        XCTAssertEqual(buysSum, sellsSum, "Net cash (buys - sells) must equal exactly $0")
    }

    /// Reviewer-verified counterexample: shipped fold added +netCash instead of −netCash,
    /// doubling the imbalance. current {A:$1, B:$0, C:$0}, targets {A:33.34, B:33.33, C:33.33}
    /// must net to exactly $0.00 (shipped code produced sell A 0.68 / buy B 0.33 / buy C 0.33,
    /// net −0.02).
    func testRebalancePlanNetZeroFoldDoesNotDoubleImbalance() {
        let currentValues: [String: Money] = [
            "A": Money(amount: 1),
            "B": Money(amount: 0),
            "C": Money(amount: 0)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: Decimal(string: "33.34") ?? 0)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: Decimal(string: "33.33") ?? 0)),
            PieSlice(symbol: "C", assetKind: .stock, targetWeight: Percentage(value: Decimal(string: "33.33") ?? 0))
        ]

        let result = PieMath.rebalancePlan(currentValues: currentValues, targets: targets)

        let buys = result.filter { $0.side == .buy }
        let sells = result.filter { $0.side == .sell }
        let buysSum = buys.reduce(Decimal(0)) { $0 + $1.amount.amount }
        let sellsSum = sells.reduce(Decimal(0)) { $0 + $1.amount.amount }

        XCTAssertEqual(buysSum, sellsSum, "Net cash must be exactly $0, got buys=\(buysSum) sells=\(sellsSum)")
        for order in result {
            XCTAssertGreaterThan(order.amount.amount, 0, "Order for \(order.symbol) must have a positive amount")
        }
    }

    /// Test rebalancePlan with partial holdings (some symbols missing)
    func testRebalancePlanPartialHoldings() {
        // A=$50, B missing (treat as $0), targets 50/50
        let currentValues: [String: Money] = [
            "A": Money(amount: 50)
        ]
        let targets = [
            PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))
        ]

        let result = PieMath.rebalancePlan(currentValues: currentValues, targets: targets)

        // Total = $50. Targets: A=$25, B=$25
        // Deltas: A=-$25 (sell), B=+$25 (buy)
        XCTAssertEqual(result.count, 2, "Should have 2 orders")

        let sellA = result.first { $0.symbol == "A" }
        let buyB = result.first { $0.symbol == "B" }

        XCTAssertEqual(sellA?.side, .sell)
        XCTAssertEqual(sellA?.amount, Money(amount: 25))

        XCTAssertEqual(buyB?.side, .buy)
        XCTAssertEqual(buyB?.amount, Money(amount: 25))
    }
}
