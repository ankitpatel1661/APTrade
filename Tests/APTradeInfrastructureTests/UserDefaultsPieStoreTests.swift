import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class UserDefaultsPieStoreTests: XCTestCase {
    func makeDefaults() throws -> UserDefaults {
        let suite = "test.\(UUID().uuidString)"
        return try XCTUnwrap(UserDefaults(suiteName: suite))
    }

    func makePie(id: String = "test-pie-1", name: String = "My Pie") throws -> Pie {
        let slice1 = PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 50))
        let slice2 = PieSlice(symbol: "BTC", assetKind: .crypto, targetWeight: Percentage(value: 50))
        return try Pie(id: id, name: name, slices: [slice1, slice2], schedule: nil, createdDay: "2025-01-01")
    }

    func test_emptyStore_returnsEmpty() throws {
        let defaults = try makeDefaults()
        let store = UserDefaultsPieStore(defaults: defaults)
        XCTAssertEqual(store.load(), [])
    }

    func test_saveThenLoad_roundTrips() throws {
        let defaults = try makeDefaults()
        let store = UserDefaultsPieStore(defaults: defaults)
        let pie = try makePie()

        store.save([pie])
        let loaded = store.load()

        XCTAssertEqual(loaded.count, 1)
        XCTAssertEqual(loaded[0].id, pie.id)
        XCTAssertEqual(loaded[0].name, pie.name)
    }

    func test_saveMultiple_roundTrips() throws {
        let defaults = try makeDefaults()
        let store = UserDefaultsPieStore(defaults: defaults)
        let pie1 = try makePie(id: "pie-1", name: "Pie 1")
        let pie2 = try makePie(id: "pie-2", name: "Pie 2")

        store.save([pie1, pie2])
        let loaded = store.load()

        XCTAssertEqual(loaded.count, 2)
        XCTAssertTrue(loaded.contains { $0.id == "pie-1" })
        XCTAssertTrue(loaded.contains { $0.id == "pie-2" })
    }

    func test_corruptedData_returnsEmpty_withoutOverwriting() throws {
        let defaults = try makeDefaults()
        let store = UserDefaultsPieStore(defaults: defaults)

        // Store some valid data first
        let pie = try makePie()
        store.save([pie])

        // Corrupt the data
        let corruptedBytes = try XCTUnwrap("not valid json at all".data(using: .utf8))
        defaults.set(corruptedBytes, forKey: "pies")

        // Load should return empty without overwriting
        let loaded = store.load()
        XCTAssertEqual(loaded, [])

        // The corrupted data should still be in UserDefaults (exact bytes preserved)
        XCTAssertEqual(defaults.data(forKey: "pies"), corruptedBytes)
    }

    func test_persistsAcrossInstances() throws {
        let defaults = try makeDefaults()
        let pie = try makePie()

        // Store with first instance
        let store1 = UserDefaultsPieStore(defaults: defaults)
        store1.save([pie])

        // Load with second instance
        let store2 = UserDefaultsPieStore(defaults: defaults)
        let loaded = store2.load()

        XCTAssertEqual(loaded.count, 1)
        XCTAssertEqual(loaded[0].id, pie.id)
    }
}
