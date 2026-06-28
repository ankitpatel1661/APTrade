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

private let goWatchlist = PaletteResult.navigate(label: "Go to Watchlist", icon: "list.bullet", destination: .watchlist)
private let goPortfolio = PaletteResult.navigate(label: "Go to Portfolio", icon: "chart.pie", destination: .portfolio)

@MainActor
final class CommandPaletteViewModelTests: XCTestCase {
    private func makeVM(_ repo: PaletteFakeRepo) -> CommandPaletteViewModel {
        CommandPaletteViewModel(searchAssets: SearchAssetsUseCase(repository: repo))
    }

    func test_emptyQuery_showsStaticNavResults() {
        let vm = makeVM(PaletteFakeRepo())
        XCTAssertEqual(vm.results, [goWatchlist, goPortfolio])
    }

    func test_updateQuery_matchingNavLabel_includesIt() async throws {
        let vm = makeVM(PaletteFakeRepo())
        vm.updateQuery("watch")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(vm.results, [goWatchlist])
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

    func test_updateQuery_searchFailure_resolvesToNavMatchesOnly() async throws {
        let repo = PaletteFakeRepo()
        repo.searchError = AppError.network
        let vm = makeVM(repo)
        vm.updateQuery("portfolio")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(vm.results, [goPortfolio])
    }

    func test_moveSelection_clampsAtBothEnds() {
        let vm = makeVM(PaletteFakeRepo())
        // Static results give two rows: indices 0...1.
        vm.moveSelection(-5)
        XCTAssertEqual(vm.selectedIndex, 0)
        vm.moveSelection(1)
        XCTAssertEqual(vm.selectedIndex, 1)
        vm.moveSelection(5)
        XCTAssertEqual(vm.selectedIndex, 1)
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
        let vm = makeVM(PaletteFakeRepo())
        XCTAssertEqual(vm.activateSelected(), goWatchlist)
        vm.updateQuery("nomatch-xyz")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertNil(vm.activateSelected())
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
        XCTAssertEqual(vm.results, [goWatchlist, goPortfolio])
    }
}
