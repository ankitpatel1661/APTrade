import XCTest
@testable import APTradeDomain

final class PortfolioTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s)!) }
    private func qty(_ s: String) -> Quantity { Quantity(Decimal(string: s)!) }
    private let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
    private let date = Date(timeIntervalSince1970: 0)

    func test_buy_debitsCashAndCreatesPosition() throws {
        let p = try Portfolio.starting().buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
        XCTAssertEqual(p.cash, usd("99800"))   // 100_000 - 200
        XCTAssertEqual(p.position(for: "AAPL")?.quantity, qty("2"))
        XCTAssertEqual(p.position(for: "AAPL")?.averageCost, usd("100"))
        XCTAssertEqual(p.transactions.count, 1)
    }

    func test_secondBuy_averagesCost() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
            .buying(aapl, quantity: qty("2"), at: usd("200"), on: date)
        XCTAssertEqual(p.position(for: "AAPL")?.quantity, qty("4"))
        XCTAssertEqual(p.position(for: "AAPL")?.averageCost, usd("150"))   // (200+400)/4
    }

    func test_buy_insufficientFunds_throws() {
        XCTAssertThrowsError(
            try Portfolio.starting().buying(aapl, quantity: qty("2"), at: usd("100000"), on: date)
        ) { XCTAssertEqual($0 as? TradeError, .insufficientFunds) }
    }

    func test_buy_zeroQuantity_throws() {
        XCTAssertThrowsError(
            try Portfolio.starting().buying(aapl, quantity: qty("0"), at: usd("100"), on: date)
        ) { XCTAssertEqual($0 as? TradeError, .invalidQuantity) }
    }

    func test_sell_creditsCashAccruesRealizedPnL() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty("4"), at: usd("100"), on: date)
            .selling("AAPL", quantity: qty("2"), at: usd("150"), on: date)
        XCTAssertEqual(p.position(for: "AAPL")?.quantity, qty("2"))
        XCTAssertEqual(p.position(for: "AAPL")?.realizedPnL, usd("100"))   // (150-100)*2
        XCTAssertEqual(p.cash, usd("99900"))   // 100000 - 400 + 300
    }

    func test_sellAll_removesPosition() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
            .selling("AAPL", quantity: qty("2"), at: usd("120"), on: date)
        XCTAssertNil(p.position(for: "AAPL"))
    }

    func test_sell_insufficientShares_throws() {
        XCTAssertThrowsError(
            try Portfolio.starting().selling("AAPL", quantity: qty("1"), at: usd("100"), on: date)
        ) { XCTAssertEqual($0 as? TradeError, .insufficientShares) }
    }

    func test_valuation_totalsCashPlusHoldings() throws {
        let p = try Portfolio.starting().buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
        let quote = Quote(symbol: "AAPL", price: usd("150"), previousClose: usd("140"))
        let v = p.valuation(quotes: ["AAPL": quote])
        XCTAssertEqual(v.holdingsValue, usd("300"))                 // 150*2
        XCTAssertEqual(v.totalValue, usd("100100"))                // 99800 cash + 300
        XCTAssertEqual(v.unrealizedPnL, usd("100"))                // (150-100)*2
        XCTAssertEqual(v.dayChange, usd("20"))                     // (150-140)*2
    }

    func test_valuation_missingQuote_fallsBackToCostBasis() throws {
        let p = try Portfolio.starting().buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
        let v = p.valuation(quotes: [:])
        XCTAssertEqual(v.holdingsValue, usd("200"))   // cost basis 100*2
        XCTAssertEqual(v.unrealizedPnL, usd("0"))
    }
}
