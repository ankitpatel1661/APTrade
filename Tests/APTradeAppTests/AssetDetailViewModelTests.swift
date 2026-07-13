import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

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

/// Records nothing beyond the fixed events/error it was built with — mirrors
/// `CalendarViewModelTests`' fake of the same shape (kept local since that one is
/// `private` to its own file).
private final class DetailFakeEarningsCalendarRepository: EarningsCalendarRepository, @unchecked Sendable {
    private let events: [EarningsEvent]
    private let error: (any Error)?

    init(events: [EarningsEvent] = [], error: (any Error)? = nil) {
        self.events = events
        self.error = error
    }

    func earnings(fromDay: String, toDay: String) async throws -> [EarningsEvent] {
        if let error { throw error }
        return events
    }
}

@MainActor
final class AssetDetailViewModelTests: XCTestCase {
    let asset = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)

    func makeVM(_ repo: DetailFakeRepo, fetchEarnings: FetchEarningsCalendarUseCase? = nil) -> AssetDetailViewModel {
        let store = MemoryStore(Portfolio(cash: Money(amount: 10_000)))
        return AssetDetailViewModel(asset: asset,
                             fetchCandles: FetchCandlesUseCase(repository: repo),
                             fetchQuotes: FetchQuotesUseCase(repository: repo),
                             fetchPortfolio: FetchPortfolioUseCase(store: store),
                             fetchEarnings: fetchEarnings)
    }

    func test_load_setsQuoteAndPoints_loaded() async {
        let repo = DetailFakeRepo()
        // Detail opens on the live intraday timeframe (1D) by default.
        repo.historyByTf[.oneDay] = [PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 5))]
        let vm = makeVM(repo)
        await vm.load()
        XCTAssertEqual(vm.loadState, .loaded)
        XCTAssertEqual(vm.quote?.symbol, "AAPL")
        XCTAssertEqual(vm.timeframe, .oneDay)
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

    func test_load_withOwnedEvent_populatesNextEarnings() async {
        let event = EarningsEvent(symbol: "AAPL", companyName: "Apple Inc.", day: "2026-08-01",
                                   session: .afterClose, epsEstimate: 1.5, epsActual: nil)
        let earningsRepo = DetailFakeEarningsCalendarRepository(events: [event])
        let fetchEarnings = FetchEarningsCalendarUseCase(repository: earningsRepo) { ["AAPL"] }
        let vm = makeVM(DetailFakeRepo(), fetchEarnings: fetchEarnings)
        await vm.load()
        XCTAssertEqual(vm.nextEarnings, event)
    }

    func test_load_withNilFetchEarnings_leavesNextEarningsNil() async {
        let vm = makeVM(DetailFakeRepo(), fetchEarnings: nil)
        await vm.load()
        XCTAssertNil(vm.nextEarnings)
    }
}
