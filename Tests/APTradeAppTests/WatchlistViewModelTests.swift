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
    var histories: [String: [PricePoint]] = [:]
    func quote(for symbol: String) async throws -> Quote {
        if bad.contains(symbol) { throw AppError.network }
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        histories[symbol] ?? []
    }
}

@MainActor
final class WatchlistViewModelTests: XCTestCase {
    func makeVM(store: VMFakeStore, repo: VMFakeRepo) -> WatchlistViewModel {
        WatchlistViewModel(
            load: LoadWatchlistUseCase(store: store),
            add: AddToWatchlistUseCase(store: store),
            remove: RemoveFromWatchlistUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            fetchHistory: FetchHistoryUseCase(repository: repo),
            search: SearchSymbolUseCase(repository: repo),
            searchAssets: SearchAssetsUseCase(repository: repo)
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

    func test_sections_groupByKind_inFixedOrder_skippingEmpties() async {
        let store = VMFakeStore([
            Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto),
            Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock),
            Asset(symbol: "SPY", name: "SPDR S&P 500 ETF", kind: .etf),
            Asset(symbol: "MSFT", name: "Microsoft", kind: .stock),
        ])
        let vm = makeVM(store: store, repo: VMFakeRepo())
        await vm.onAppear()
        let sections = vm.sections
        XCTAssertEqual(sections.map { $0.kind }, [.stock, .etf, .crypto])
        XCTAssertEqual(sections[0].rows.map { $0.asset.symbol }, ["AAPL", "MSFT"])
        XCTAssertEqual(sections[1].rows.map { $0.asset.symbol }, ["SPY"])
        XCTAssertEqual(sections[2].rows.map { $0.asset.symbol }, ["BTC-USD"])
        XCTAssertEqual(sections.map { $0.title }, ["Stocks", "ETFs", "Crypto"])
    }

    func test_onAppear_loadsSparklineFromHistory() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let store = VMFakeStore([aapl])
        let repo = VMFakeRepo()
        repo.quotes["AAPL"] = Quote(symbol: "AAPL", price: Money(amount: 10), previousClose: Money(amount: 9))
        repo.histories["AAPL"] = [
            PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 9)),
            PricePoint(date: Date(timeIntervalSince1970: 300), close: Money(amount: 10)),
        ]
        let vm = makeVM(store: store, repo: repo)
        await vm.onAppear()
        XCTAssertEqual(vm.rows.first?.spark, [9, 10])
    }

    func test_sections_omitsKindsWithNoRows() async {
        let store = VMFakeStore([Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)])
        let vm = makeVM(store: store, repo: VMFakeRepo())
        await vm.onAppear()
        XCTAssertEqual(vm.sections.map { $0.kind }, [.stock])
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
