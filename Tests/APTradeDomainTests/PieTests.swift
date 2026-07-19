import XCTest
@testable import APTradeDomain

final class PieTests: XCTestCase {
    // MARK: - Construction Validation

    func testWeightsExactly100Constructs() throws {
        let slices = [
            PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 60)),
            PieSlice(symbol: "BTC", assetKind: .crypto, targetWeight: Percentage(value: 40))
        ]

        let pie = try Pie(
            name: "Test Pie",
            slices: slices,
            schedule: nil,
            createdDay: "2026-07-20"
        )

        XCTAssertEqual(pie.name, "Test Pie")
        XCTAssertEqual(pie.slices.count, 2)
    }

    func testWeightsSumToLessThan100Throws() throws {
        let slices = [
            PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 60)),
            PieSlice(symbol: "BTC", assetKind: .crypto, targetWeight: Percentage(value: 39))
        ]

        XCTAssertThrowsError(
            try Pie(
                name: "Test Pie",
                slices: slices,
                schedule: nil,
                createdDay: "2026-07-20"
            )
        ) { error in
            XCTAssertEqual(error as? PieError, .invalidWeights)
        }
    }

    func testDuplicateSymbolsThrows() throws {
        let slices = [
            PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 50)),
            PieSlice(symbol: "AAPL", assetKind: .etf, targetWeight: Percentage(value: 50))
        ]

        XCTAssertThrowsError(
            try Pie(
                name: "Test Pie",
                slices: slices,
                schedule: nil,
                createdDay: "2026-07-20"
            )
        ) { error in
            XCTAssertEqual(error as? PieError, .duplicateSymbols)
        }
    }

    func testEmptySlicesThrows() throws {
        XCTAssertThrowsError(
            try Pie(
                name: "Test Pie",
                slices: [],
                schedule: nil,
                createdDay: "2026-07-20"
            )
        ) { error in
            XCTAssertEqual(error as? PieError, .emptySlices)
        }
    }

    // MARK: - Codable Round Trip

    func testCodableRoundTrip() throws {
        let schedule = ContributionSchedule(
            amount: Money(amount: 1000, currencyCode: "USD"),
            cadence: .monthly,
            nextDueDay: "2026-08-20"
        )

        let slices = [
            PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 60)),
            PieSlice(symbol: "BTC", assetKind: .crypto, targetWeight: Percentage(value: 40))
        ]

        let ledgerEntries = [
            PieLedgerEntry(symbol: "AAPL", quantity: Quantity(10)),
            PieLedgerEntry(symbol: "BTC", quantity: Quantity(Decimal(string: "0.5")!))
        ]

        let activityEntries = [
            PieActivityEntry(
                kind: .contribution,
                day: "2026-07-20",
                amount: Money(amount: 1000, currencyCode: "USD")
            )
        ]

        let original = try Pie(
            name: "Test Pie",
            slices: slices,
            schedule: schedule,
            createdDay: "2026-07-20",
            ledger: ledgerEntries,
            activity: activityEntries
        )

        let encoded = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(Pie.self, from: encoded)

        XCTAssertEqual(decoded, original)
        XCTAssertEqual(decoded.name, "Test Pie")
        XCTAssertEqual(decoded.slices.count, 2)
        XCTAssertEqual(decoded.schedule?.cadence, .monthly)
        XCTAssertEqual(decoded.ledger.count, 2)
        XCTAssertEqual(decoded.activity.count, 1)
    }

    // MARK: - Quantity Lookup

    func testQuantityOfReturnsPresentEntry() throws {
        let slices = [
            PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 100))
        ]

        let ledgerEntries = [
            PieLedgerEntry(symbol: "AAPL", quantity: Quantity(25))
        ]

        let pie = try Pie(
            name: "Test Pie",
            slices: slices,
            schedule: nil,
            createdDay: "2026-07-20",
            ledger: ledgerEntries
        )

        let quantity = pie.quantity(of: "AAPL")
        XCTAssertEqual(quantity.amount, 25)
    }

    func testQuantityOfReturnsZeroForAbsentSymbol() throws {
        let slices = [
            PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 100))
        ]

        let pie = try Pie(
            name: "Test Pie",
            slices: slices,
            schedule: nil,
            createdDay: "2026-07-20"
        )

        let quantity = pie.quantity(of: "BTC")
        XCTAssertTrue(quantity.isZero)
        XCTAssertEqual(quantity.amount, 0)
    }

    // MARK: - ID Generation

    func testIDDefaultsToUUID() throws {
        let slices = [
            PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 100))
        ]

        let pie = try Pie(
            name: "Test Pie",
            slices: slices,
            schedule: nil,
            createdDay: "2026-07-20"
        )

        XCTAssertFalse(pie.id.isEmpty)
        // Should be a valid UUID string
        XCTAssertNotNil(UUID(uuidString: pie.id))
    }

    func testIDCanBeProvided() throws {
        let slices = [
            PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 100))
        ]

        let customID = "custom-id-123"
        let pie = try Pie(
            id: customID,
            name: "Test Pie",
            slices: slices,
            schedule: nil,
            createdDay: "2026-07-20"
        )

        XCTAssertEqual(pie.id, customID)
    }

    // MARK: - Identifiable Conformance

    func testIdentifiable() throws {
        let slices = [
            PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 100))
        ]

        let pie = try Pie(
            name: "Test Pie",
            slices: slices,
            schedule: nil,
            createdDay: "2026-07-20"
        )

        // Pie is Identifiable and id property comes from Identifiable
        let id: String = pie.id
        XCTAssertFalse(id.isEmpty)
    }
}
