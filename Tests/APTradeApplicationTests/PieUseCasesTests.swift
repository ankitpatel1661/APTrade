import XCTest
@testable import APTradeApplication
import APTradeDomain

final class FakePieStore: PieStore, @unchecked Sendable {
    var pies: [Pie] = []

    func load() -> [Pie] {
        pies
    }

    func save(_ pies: [Pie]) {
        self.pies = pies
    }
}

final class PieUseCasesTests: XCTestCase {
    let slice1 = PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 50))
    let slice2 = PieSlice(symbol: "BTC", assetKind: .crypto, targetWeight: Percentage(value: 50))

    func makePie(id: String = "test-pie-1", name: String = "My Pie") throws -> Pie {
        try Pie(id: id, name: name, slices: [slice1, slice2], schedule: nil, createdDay: "2025-01-01")
    }

    // MARK: - LoadPies

    func test_LoadPies_emptyStore_returnsEmpty() throws {
        let store = FakePieStore()
        let loadPies = LoadPies(store: store)
        XCTAssertEqual(loadPies(), [])
    }

    func test_LoadPies_withPies_returnsAll() throws {
        let pie = try makePie()
        let store = FakePieStore()
        store.pies = [pie]
        let loadPies = LoadPies(store: store)
        XCTAssertEqual(loadPies(), [pie])
    }

    // MARK: - SavePie

    func test_SavePie_insertsNew() throws {
        let pie1 = try makePie(id: "pie-1", name: "Pie 1")
        let pie2 = try makePie(id: "pie-2", name: "Pie 2")
        let store = FakePieStore()
        store.pies = [pie1]

        let savePie = SavePie(store: store)
        let result = savePie(pie2)

        XCTAssertEqual(result.count, 2)
        XCTAssertTrue(result.contains { $0.id == "pie-1" })
        XCTAssertTrue(result.contains { $0.id == "pie-2" })
    }

    func test_SavePie_replacesExistingId() throws {
        let pie1 = try makePie(id: "pie-1", name: "Original Name")
        let pie1Updated = try makePie(id: "pie-1", name: "Updated Name")
        let store = FakePieStore()
        store.pies = [pie1]

        let savePie = SavePie(store: store)
        let result = savePie(pie1Updated)

        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].name, "Updated Name")
    }

    func test_SavePie_multipleOperations() throws {
        let pie1 = try makePie(id: "pie-1", name: "Pie 1")
        let pie2 = try makePie(id: "pie-2", name: "Pie 2")
        let pie1Updated = try makePie(id: "pie-1", name: "Pie 1 Updated")

        let store = FakePieStore()
        let savePie = SavePie(store: store)

        // Insert pie1
        var result = savePie(pie1)
        XCTAssertEqual(result.count, 1)

        // Insert pie2
        result = savePie(pie2)
        XCTAssertEqual(result.count, 2)

        // Replace pie1
        result = savePie(pie1Updated)
        XCTAssertEqual(result.count, 2)
        XCTAssertEqual(result.first(where: { $0.id == "pie-1" })?.name, "Pie 1 Updated")
    }

    // MARK: - DeletePie

    func test_DeletePie_removesById() throws {
        let pie1 = try makePie(id: "pie-1", name: "Pie 1")
        let pie2 = try makePie(id: "pie-2", name: "Pie 2")
        let store = FakePieStore()
        store.pies = [pie1, pie2]

        let deletePie = DeletePie(store: store)
        let result = deletePie(id: "pie-1")

        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].id, "pie-2")
    }

    func test_DeletePie_unknownId_noOp() throws {
        let pie1 = try makePie(id: "pie-1", name: "Pie 1")
        let store = FakePieStore()
        store.pies = [pie1]

        let deletePie = DeletePie(store: store)
        let result = deletePie(id: "unknown")

        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].id, "pie-1")
    }

    func test_DeletePie_allRemoved_returnsEmpty() throws {
        let pie1 = try makePie(id: "pie-1", name: "Pie 1")
        let store = FakePieStore()
        store.pies = [pie1]

        let deletePie = DeletePie(store: store)
        let result = deletePie(id: "pie-1")

        XCTAssertEqual(result, [])
    }
}
