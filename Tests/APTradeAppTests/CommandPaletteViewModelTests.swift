import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class PaletteFakeRepo: MarketDataRepository, @unchecked Sendable {
    var searchResults: [Asset] = []
    var searchError: Error?

    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
    func search(query: String) async throws -> [Asset] {
        if let searchError { throw searchError }
        return searchResults
    }
}

@MainActor
final class CommandPaletteViewModelTests: XCTestCase {
    private func makeVM(_ repo: PaletteFakeRepo) -> CommandPaletteViewModel {
        CommandPaletteViewModel(searchAssets: SearchAssetsUseCase(repository: repo))
    }

    /// M10.1 UAT U7: the palette's static "Go to Home/Markets/Portfolio/Invest" rows are
    /// gone (redundant with the sidebar/tabs) — an untouched, just-opened palette now shows
    /// nothing until the user types.
    func test_emptyQuery_showsNoResults() {
        let vm = makeVM(PaletteFakeRepo())
        XCTAssertEqual(vm.results, [])
    }

    func test_updateQuery_includesSearchResults() async throws {
        let repo = PaletteFakeRepo()
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        repo.searchResults = [aapl]
        let vm = makeVM(repo)
        vm.updateQuery("aapl")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(vm.results, [.asset(aapl)])
    }

    func test_updateQuery_searchFailure_resolvesToEmptyResults() async throws {
        let repo = PaletteFakeRepo()
        repo.searchError = AppError.network
        let vm = makeVM(repo)
        vm.updateQuery("portfolio")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(vm.results, [])
    }

    func test_moveSelection_clampsAtBothEnds() async throws {
        let repo = PaletteFakeRepo()
        repo.searchResults = [
            Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock),
            Asset(symbol: "AMZN", name: "Amazon.com Inc.", kind: .stock),
            Asset(symbol: "AMD", name: "Advanced Micro Devices", kind: .stock)
        ]
        let vm = makeVM(repo)
        vm.updateQuery("a")
        try await Task.sleep(for: .milliseconds(300))
        // Three matching rows: indices 0...2.
        vm.moveSelection(-5)
        XCTAssertEqual(vm.selectedIndex, 0)
        vm.moveSelection(1)
        XCTAssertEqual(vm.selectedIndex, 1)
        vm.moveSelection(5)
        XCTAssertEqual(vm.selectedIndex, 2)
    }

    func test_moveSelection_onEmptyResults_doesNothing() async throws {
        let vm = makeVM(PaletteFakeRepo())
        vm.updateQuery("nomatch-xyz")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertTrue(vm.results.isEmpty)
        vm.moveSelection(1)
        XCTAssertEqual(vm.selectedIndex, 0)
    }

    func test_activateSelected_returnsSelectedResult_orNilWhenEmpty() async throws {
        let repo = PaletteFakeRepo()
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        repo.searchResults = [aapl]
        let vm = makeVM(repo)
        // No query yet — no results, nothing to activate.
        XCTAssertNil(vm.activateSelected())
        vm.updateQuery("aapl")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(vm.activateSelected(), .asset(aapl))
    }

    func test_reset_clearsQueryResultsAndSelection() async throws {
        let repo = PaletteFakeRepo()
        repo.searchResults = [Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)]
        let vm = makeVM(repo)
        vm.updateQuery("aapl")
        try await Task.sleep(for: .milliseconds(300))
        vm.moveSelection(1)
        vm.reset()
        XCTAssertEqual(vm.query, "")
        XCTAssertEqual(vm.selectedIndex, 0)
        XCTAssertEqual(vm.results, [])
    }
}
