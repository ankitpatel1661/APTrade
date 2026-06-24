import XCTest
@testable import APTradeDomain

final class PositionTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s)!) }

    func test_marketValue() {
        let pos = Position(
            asset: Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock),
            quantity: Quantity(Decimal(string: "2")!),
            averageCost: usd("100"),
            realizedPnL: usd("0")
        )
        XCTAssertEqual(pos.marketValue(at: usd("150")), usd("300"))
    }

    func test_unrealizedPnL() {
        let pos = Position(
            asset: Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock),
            quantity: Quantity(Decimal(string: "2")!),
            averageCost: usd("100"),
            realizedPnL: usd("0")
        )
        XCTAssertEqual(pos.unrealizedPnL(at: usd("150")), usd("100"))   // (150-100)*2
    }
}
