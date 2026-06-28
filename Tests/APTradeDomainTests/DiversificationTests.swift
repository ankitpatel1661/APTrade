import XCTest
@testable import APTradeDomain

final class DiversificationTests: XCTestCase {
    func test_concentration_hhi() {
        // Two equal holdings: 0.5² + 0.5² = 0.5
        XCTAssertEqual(Diversification.concentration([0.5, 0.5]), 0.5, accuracy: 1e-9)
        // Single holding: 1.0
        XCTAssertEqual(Diversification.concentration([1.0]), 1.0, accuracy: 1e-9)
        XCTAssertEqual(Diversification.concentration([]), 0, accuracy: 1e-9)
    }

    func test_effectiveHoldings_isInverseHHI() {
        XCTAssertEqual(Diversification.effectiveHoldings([0.5, 0.5]), 2.0, accuracy: 1e-9)
        XCTAssertEqual(Diversification.effectiveHoldings([]), 0, accuracy: 1e-9)
    }

    func test_warnings_flagsSingleNameOver25Percent() {
        // Binary-exact weights (multiples of 0.5/0.125) so summed class weights carry no
        // float drift; each class here is 0.5, below the 0.60 class threshold.
        let holdings = [
            HoldingWeight(label: "NVDA", kind: "stock", weight: 0.5),
            HoldingWeight(label: "BTC-USD", kind: "crypto", weight: 0.5)
        ]
        let warnings = Diversification.warnings(holdings)
        // Both exceed the 0.25 single-name threshold; neither class exceeds 0.60.
        XCTAssertTrue(warnings.contains(.singleName(label: "NVDA", weight: 0.5)))
        XCTAssertTrue(warnings.contains(.singleName(label: "BTC-USD", weight: 0.5)))
        XCTAssertFalse(warnings.contains { if case .assetClass = $0 { return true } else { return false } })
    }

    func test_warnings_flagsAssetClassDominance() {
        // Binary-exact weights: stock sums to 0.625 (> 0.60); every single weight is ≤ 0.25
        // (0.25 is not strictly > 0.25), so only the class warning fires.
        let holdings = [
            HoldingWeight(label: "AAPL", kind: "stock", weight: 0.25),
            HoldingWeight(label: "MSFT", kind: "stock", weight: 0.25),
            HoldingWeight(label: "GOOG", kind: "stock", weight: 0.125),
            HoldingWeight(label: "BTC-USD", kind: "crypto", weight: 0.25),
            HoldingWeight(label: "ETH-USD", kind: "crypto", weight: 0.125)
        ]
        let warnings = Diversification.warnings(holdings)
        // stock class = 0.625 > 0.60 → flagged; crypto = 0.375; no single name > 0.25.
        XCTAssertTrue(warnings.contains(.assetClass(kind: "stock", weight: 0.625)))
        XCTAssertFalse(warnings.contains { if case .singleName = $0 { return true } else { return false } })
    }

    func test_warnings_sortedByDescendingWeight() {
        let holdings = [
            HoldingWeight(label: "A", kind: "stock", weight: 0.30),
            HoldingWeight(label: "B", kind: "crypto", weight: 0.70)
        ]
        let warnings = Diversification.warnings(holdings)
        // Assert the emitted warnings are ordered by non-increasing weight without
        // depending on which warning type wins a weight tie (a dominant single name and
        // its sole-member class share the same weight).
        let weights = warnings.map { warning -> Double in
            switch warning {
            case .singleName(_, let w): return w
            case .assetClass(_, let w): return w
            }
        }
        XCTAssertFalse(weights.isEmpty)
        XCTAssertEqual(weights, weights.sorted(by: >))
    }
}
