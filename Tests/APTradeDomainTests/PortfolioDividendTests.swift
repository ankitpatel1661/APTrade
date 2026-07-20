import XCTest
@testable import APTradeDomain

final class PortfolioDividendTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s)!) }
    private func qty(_ s: String) -> Quantity { Quantity(Decimal(string: s)!) }
    private let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
    private let date = Date(timeIntervalSince1970: 0)

    // MARK: (a) receivingDividend credits cash, appends txn, leaves positions untouched

    func test_receivingDividend_creditsCashAndAppendsTransaction() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty("10"), at: usd("100"), on: date)
            .receivingDividend("AAPL", amountPerShare: usd("0.50"), shares: qty("10"), on: date)

        XCTAssertEqual(p.cash, usd("99005"))   // 100_000 - 1000 + 5
        XCTAssertEqual(p.transactions.count, 2)

        let txn = p.transactions.last!
        XCTAssertEqual(txn.side, .dividend)
        XCTAssertEqual(txn.symbol, "AAPL")
        XCTAssertEqual(txn.quantity, qty("10"))
        XCTAssertEqual(txn.price, usd("0.50"))
        XCTAssertEqual(txn.date, date)

        // Positions and cost basis untouched.
        XCTAssertEqual(p.position(for: "AAPL")?.quantity, qty("10"))
        XCTAssertEqual(p.position(for: "AAPL")?.averageCost, usd("100"))
    }

    // MARK: (b) invalid inputs throw invalidQuantity

    func test_receivingDividend_zeroShares_throws() {
        XCTAssertThrowsError(
            try Portfolio.starting().receivingDividend("AAPL", amountPerShare: usd("0.50"), shares: qty("0"), on: date)
        ) { XCTAssertEqual($0 as? TradeError, .invalidQuantity) }
    }

    func test_receivingDividend_nonPositiveAmount_throws() {
        XCTAssertThrowsError(
            try Portfolio.starting().receivingDividend("AAPL", amountPerShare: usd("0"), shares: qty("10"), on: date)
        ) { XCTAssertEqual($0 as? TradeError, .invalidQuantity) }

        XCTAssertThrowsError(
            try Portfolio.starting().receivingDividend("AAPL", amountPerShare: usd("-0.50"), shares: qty("10"), on: date)
        ) { XCTAssertEqual($0 as? TradeError, .invalidQuantity) }
    }

    // MARK: (c) buying(..., isDrip:) records isDrip on the transaction

    func test_buying_isDrip_recordsOnTransaction() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty("1"), at: usd("100"), on: date, isDrip: true)
        XCTAssertEqual(p.transactions.last?.isDrip, true)
    }

    func test_buying_defaultIsDrip_isFalse() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty("1"), at: usd("100"), on: date)
        XCTAssertEqual(p.transactions.last?.isDrip, false)
    }

    // MARK: (d) back-compat: pre-M8 JSON (no isDrip key) decodes with isDrip == false

    func test_transaction_decodesPreM8JSON_withIsDripFalse() throws {
        let json = """
        {
            "id": "\(UUID().uuidString)",
            "symbol": "AAPL",
            "side": "buy",
            "quantity": {"amount": 2},
            "price": {"amount": 100, "currencyCode": "USD"},
            "date": \(date.timeIntervalSinceReferenceDate)
        }
        """.data(using: .utf8)!

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .deferredToDate
        let txn = try decoder.decode(Transaction.self, from: json)
        XCTAssertEqual(txn.isDrip, false)
        XCTAssertNil(txn.pieId)
    }

    // MARK: (e) a .dividend transaction round-trips through Codable

    func test_dividendTransaction_roundTripsThroughCodable() throws {
        let txn = Transaction(symbol: "AAPL", side: .dividend,
                               quantity: qty("10"), price: usd("0.50"), date: date)
        let encoder = JSONEncoder()
        let decoder = JSONDecoder()
        let data = try encoder.encode(txn)
        let decoded = try decoder.decode(Transaction.self, from: data)
        XCTAssertEqual(decoded, txn)
    }
}
