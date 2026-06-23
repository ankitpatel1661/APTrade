import XCTest
@testable import APTradeApplication
import APTradeDomain

final class FakeStore: WatchlistStore, @unchecked Sendable {
    var assets: [Asset]
    init(_ assets: [Asset] = []) { self.assets = assets }
    func load() -> [Asset] { assets }
    func save(_ assets: [Asset]) { self.assets = assets }
}

final class WatchlistUseCasesTests: XCTestCase {
    let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
    let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)

    func test_add_persistsAndReturnsList() {
        let store = FakeStore()
        let result = AddToWatchlistUseCase(store: store)(aapl)
        XCTAssertEqual(result, [aapl])
        XCTAssertEqual(store.load(), [aapl])
    }

    func test_add_isIdempotentBySymbol() {
        let store = FakeStore([aapl])
        let result = AddToWatchlistUseCase(store: store)(aapl)
        XCTAssertEqual(result, [aapl])
    }

    func test_remove_bySymbol() {
        let store = FakeStore([aapl, btc])
        let result = RemoveFromWatchlistUseCase(store: store)(symbol: "AAPL")
        XCTAssertEqual(result, [btc])
    }

    func test_load_returnsStored() {
        let store = FakeStore([btc])
        XCTAssertEqual(LoadWatchlistUseCase(store: store)(), [btc])
    }
}
