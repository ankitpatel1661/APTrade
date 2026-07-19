import XCTest
@testable import APTradeDomain

final class TransactionPieIdTests: XCTestCase {
    private func usd(_ amount: Decimal) -> Money { Money(amount: amount) }
    private func qty(_ amount: Decimal) -> Quantity { Quantity(amount) }
    private let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
    private let date = Date(timeIntervalSince1970: 0)

    // Test 1: JSON of a pre-M7 transaction (hand-written literal without `pieId`)
    // decodes with `pieId == nil` (synthesized Codable uses `decodeIfPresent` for optionals)
    func test_legacyTransactionJSON_withoutPieId_decodesWithNilPieId() throws {
        // Hand-written JSON literal matching the true encoded shape, WITHOUT pieId key
        let legacyJSON = """
        {"date":774306000,"id":"E621E1F8-C36C-495A-93FC-0C247A3E6E5F","price":{"amount":100,"currencyCode":"USD"},"quantity":{"amount":5},"side":"buy","symbol":"AAPL"}
        """.data(using: .utf8)!

        // Decode the legacy JSON
        let decoder = JSONDecoder()
        let decodedTxn = try decoder.decode(Transaction.self, from: legacyJSON)

        // pieId should be nil for legacy transactions
        XCTAssertNil(decodedTxn.pieId)
        XCTAssertEqual(decodedTxn.symbol, "AAPL")
        XCTAssertEqual(decodedTxn.quantity, Quantity(5))
        XCTAssertEqual(decodedTxn.price, Money(amount: 100, currencyCode: "USD"))
        XCTAssertEqual(decodedTxn.side, .buy)
        let expectedId = try XCTUnwrap(UUID(uuidString: "E621E1F8-C36C-495A-93FC-0C247A3E6E5F"))
        XCTAssertEqual(decodedTxn.id, expectedId)
    }

    // Test 2: Round-trip preserves a set pieId
    func test_roundTrip_preservesPieId() throws {
        let originalTxn = Transaction(
            symbol: "AAPL",
            side: .buy,
            quantity: Quantity(10),
            price: Money(amount: 150),
            date: date,
            pieId: "p1"
        )

        let encoder = JSONEncoder()
        let encoded = try encoder.encode(originalTxn)

        let decoder = JSONDecoder()
        let decodedTxn = try decoder.decode(Transaction.self, from: encoded)

        XCTAssertEqual(decodedTxn.pieId, "p1")
        XCTAssertEqual(decodedTxn.symbol, originalTxn.symbol)
        XCTAssertEqual(decodedTxn.quantity, originalTxn.quantity)
    }

    // Test 3: buying(..., pieId: "p1") yields a transaction tagged "p1"
    func test_buying_withPieId_createsTaggedTransaction() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty(2), at: usd(100), on: date, pieId: "p1")

        let txn = try XCTUnwrap(p.transactions.last)
        XCTAssertEqual(txn.pieId, "p1")
        XCTAssertEqual(txn.symbol, "AAPL")
        XCTAssertEqual(txn.side, .buy)
    }

    // Test 4: untagged buying() call yields nil pieId
    func test_buying_withoutPieId_createsUntaggedTransaction() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty(2), at: usd(100), on: date)

        let txn = try XCTUnwrap(p.transactions.last)
        XCTAssertNil(txn.pieId)
        XCTAssertEqual(txn.symbol, "AAPL")
    }

    // Test 5: selling(..., pieId: "p1") yields a transaction tagged "p1"
    func test_selling_withPieId_createsTaggedTransaction() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty(4), at: usd(100), on: date)
            .selling("AAPL", quantity: qty(2), at: usd(150), on: date, pieId: "p1")

        let txn = try XCTUnwrap(p.transactions.last)
        XCTAssertEqual(txn.pieId, "p1")
        XCTAssertEqual(txn.symbol, "AAPL")
        XCTAssertEqual(txn.side, .sell)
    }

    // Test 6: untagged selling() call yields nil pieId
    func test_selling_withoutPieId_createsUntaggedTransaction() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty(4), at: usd(100), on: date)
            .selling("AAPL", quantity: qty(2), at: usd(150), on: date)

        let txn = try XCTUnwrap(p.transactions.last)
        XCTAssertNil(txn.pieId)
        XCTAssertEqual(txn.symbol, "AAPL")
        XCTAssertEqual(txn.side, .sell)
    }
}
