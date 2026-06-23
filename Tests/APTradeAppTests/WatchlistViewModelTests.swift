import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

final class VMFakeStore: WatchlistStore, @unchecked Sendable {
    var assets: [Asset]
    init(_ a: [Asset]) { assets = a }
    func load() -> [Asset] { assets }
    func save(_ a: [Asset]) { assets = a }
}

final class VMFakeRepo: MarketDataRepository, @unchecked Sendable {
    var quotes: [String: Quote] = [:]
    var bad: Set<String> = []
    func quote(for symbol: String) async throws -> Quote {
        if bad.contains(symbol) { throw AppError.network }
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

@MainActor
final class WatchlistViewModelTests: XCTestCase {
    func makeVM(store: VMFakeStore, repo: VMFakeRepo) -> WatchlistViewModel {
        WatchlistViewModel(
            load: LoadWatchlistUseCase(store: store),
            add: AddToWatchlistUseCase(store: store),
            remove: RemoveFromWatchlistUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            search: SearchSymbolUseCase(repository: repo)
        )
    }

    func test_onAppear_loadsRowsAndQuotes() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let store = VMFakeStore([aapl])
        let repo = VMFakeRepo()
        repo.quotes["AAPL"] = Quote(symbol: "AAPL", price: Money(amount: 10), previousClose: Money(amount: 9))
        let vm = makeVM(store: store, repo: repo)
        await vm.onAppear()
        XCTAssertEqual(vm.rows.count, 1)
        XCTAssertEqual(vm.rows.first?.quote?.symbol, "AAPL")
        XCTAssertEqual(vm.rows.first?.failed, false)
    }

    func test_refresh_marksFailedRows() async {
        let bad = Asset(symbol: "BAD", name: "BAD", kind: .stock)
        let store = VMFakeStore([bad])
        let repo = VMFakeRepo(); repo.bad = ["BAD"]
        let vm = makeVM(store: store, repo: repo)
        await vm.onAppear()
        XCTAssertEqual(vm.rows.first?.failed, true)
        XCTAssertNil(vm.rows.first?.quote)
    }

    func test_add_unknownSymbol_setsAddError() async {
        let store = VMFakeStore([])
        let vm = makeVM(store: store, repo: VMFakeRepo())
        await vm.add(query: "NOPE")
        XCTAssertNotNil(vm.addError)
        XCTAssertTrue(vm.rows.isEmpty)
    }

    func test_remove_dropsRow() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let store = VMFakeStore([aapl])
        let repo = VMFakeRepo()
        repo.quotes["AAPL"] = Quote(symbol: "AAPL", price: Money(amount: 10), previousClose: Money(amount: 9))
        let vm = makeVM(store: store, repo: repo)
        await vm.onAppear()
        vm.remove(symbol: "AAPL")
        XCTAssertTrue(vm.rows.isEmpty)
    }
}
