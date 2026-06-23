import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

final class DetailFakeRepo: MarketDataRepository, @unchecked Sendable {
    var failHistory = false
    var historyByTf: [Timeframe: [PricePoint]] = [:]
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 10), previousClose: Money(amount: 9))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        if failHistory { throw AppError.network }
        return historyByTf[timeframe] ?? []
    }
}

@MainActor
final class AssetDetailViewModelTests: XCTestCase {
    let asset = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)

    func makeVM(_ repo: DetailFakeRepo) -> AssetDetailViewModel {
        AssetDetailViewModel(asset: asset,
                             fetchHistory: FetchHistoryUseCase(repository: repo),
                             fetchQuotes: FetchQuotesUseCase(repository: repo))
    }

    func test_load_setsQuoteAndPoints_loaded() async {
        let repo = DetailFakeRepo()
        repo.historyByTf[.oneMonth] = [PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 5))]
        let vm = makeVM(repo)
        await vm.load()
        XCTAssertEqual(vm.loadState, .loaded)
        XCTAssertEqual(vm.quote?.symbol, "AAPL")
        XCTAssertEqual(vm.points.count, 1)
    }

    func test_load_failure_setsFailed() async {
        let repo = DetailFakeRepo(); repo.failHistory = true
        let vm = makeVM(repo)
        await vm.load()
        XCTAssertEqual(vm.loadState, .failed)
    }

    func test_select_changesTimeframeAndReloads() async {
        let repo = DetailFakeRepo()
        repo.historyByTf[.oneMonth] = []
        repo.historyByTf[.oneYear] = [PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 1)),
                                      PricePoint(date: Date(timeIntervalSince1970: 1), close: Money(amount: 2))]
        let vm = makeVM(repo)
        await vm.load()
        await vm.select(.oneYear)
        XCTAssertEqual(vm.timeframe, .oneYear)
        XCTAssertEqual(vm.points.count, 2)
    }
}
