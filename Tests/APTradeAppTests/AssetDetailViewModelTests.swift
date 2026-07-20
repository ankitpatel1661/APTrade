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

/// Spy fake for `DividendEventsRepository` — records call count/args and returns a
/// fixed events list or throws a fixed error, so tests can both assert on outcomes
/// (nil vs. computed `DividendInfo`) and on whether the repository was ever invoked
/// (the crypto-skip case must never call it).
private final class SpyDividendEventsRepository: DividendEventsRepository, @unchecked Sendable {
    var eventsToReturn: [DividendEvent] = []
    var error: (any Error)?
    private(set) var callCount = 0
    private(set) var lastSymbol: String?
    private(set) var lastSince: Date?

    func dividendEvents(for symbol: String, since: Date) async throws -> [DividendEvent] {
        callCount += 1
        lastSymbol = symbol
        lastSince = since
        if let error { throw error }
        return eventsToReturn
    }
}

@MainActor
final class AssetDetailViewModelTests: XCTestCase {
    let asset = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)

    func makeVM(_ repo: DetailFakeRepo, fetchEarnings: FetchEarningsCalendarUseCase? = nil,
                dividendEventsRepository: DividendEventsRepository = SpyDividendEventsRepository(),
                asset: Asset? = nil, now: @escaping () -> Date = Date.init) -> AssetDetailViewModel {
        let store = MemoryStore(Portfolio(cash: Money(amount: 10_000)))
        return AssetDetailViewModel(asset: asset ?? self.asset,
                             fetchCandles: FetchCandlesUseCase(repository: repo),
                             fetchQuotes: FetchQuotesUseCase(repository: repo),
                             fetchPortfolio: FetchPortfolioUseCase(store: store),
                             fetchEarnings: fetchEarnings,
                             dividendEventsRepository: dividendEventsRepository,
                             now: now)
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

    // MARK: - Dividend info

    /// (a) A payer with events in the trailing window gets a computed `DividendInfo`:
    /// rate = trailing 365d per-share sum, yield = rate / current quote price.
    func test_load_payerWithEvents_computesDividendInfo() async throws {
        let repo = DetailFakeRepo()
        repo.historyByTf[.oneDay] = [PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 10))]
        let now = Date(timeIntervalSince1970: 1_700_000_000) // fixed "today" for the events below
        let dividendRepo = SpyDividendEventsRepository()
        dividendRepo.eventsToReturn = [
            DividendEvent(symbol: "AAPL", exDate: now.addingTimeInterval(-90 * 86_400), amountPerShare: Money(amount: 0.24)),
            DividendEvent(symbol: "AAPL", exDate: now.addingTimeInterval(-180 * 86_400), amountPerShare: Money(amount: 0.23)),
            DividendEvent(symbol: "AAPL", exDate: now.addingTimeInterval(-270 * 86_400), amountPerShare: Money(amount: 0.23)),
            DividendEvent(symbol: "AAPL", exDate: now.addingTimeInterval(-360 * 86_400), amountPerShare: Money(amount: 0.22)),
        ]
        let vm = makeVM(repo, dividendEventsRepository: dividendRepo, now: { now })
        await vm.load()

        // DetailFakeRepo always quotes price = 10.
        let expectedRate = DividendMath.trailingAnnualPerShare(events: dividendRepo.eventsToReturn, asOf: now)
        let info = try XCTUnwrap(vm.dividendInfo)
        XCTAssertEqual(info.trailingAnnualRate, expectedRate)
        XCTAssertEqual(info.yieldFraction, (expectedRate.amount / 10 as NSDecimalNumber).doubleValue, accuracy: 0.0001)
        XCTAssertEqual(info.recentAmounts, dividendRepo.eventsToReturn.sorted { $0.exDate < $1.exDate }.map(\.amountPerShare))
        XCTAssertEqual(dividendRepo.callCount, 1)
    }

    /// (b) Crypto assets never fetch dividend events — `dividendInfo` degrades to nil
    /// without the repository ever being called.
    func test_load_cryptoAsset_dividendInfoNilWithoutFetching() async {
        let repo = DetailFakeRepo()
        let cryptoAsset = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)
        let dividendRepo = SpyDividendEventsRepository()
        let vm = makeVM(repo, dividendEventsRepository: dividendRepo, asset: cryptoAsset)
        await vm.load()
        XCTAssertNil(vm.dividendInfo)
        XCTAssertEqual(dividendRepo.callCount, 0)
    }

    /// (c) Zero dividend events in the trailing-2-year window → nil (non-payer, section hidden).
    func test_load_noEvents_dividendInfoNil() async {
        let repo = DetailFakeRepo()
        let dividendRepo = SpyDividendEventsRepository()
        dividendRepo.eventsToReturn = []
        let vm = makeVM(repo, dividendEventsRepository: dividendRepo)
        await vm.load()
        XCTAssertNil(vm.dividendInfo)
    }

    /// (d) A dividend-events fetch failure degrades to nil — never an error state — and
    /// leaves the rest of the detail load (quote/candles) unaffected.
    func test_load_dividendFetchFailure_dividendInfoNilOtherStateUnaffected() async {
        let repo = DetailFakeRepo()
        repo.historyByTf[.oneDay] = [PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 5))]
        let dividendRepo = SpyDividendEventsRepository()
        dividendRepo.error = AppError.network
        let vm = makeVM(repo, dividendEventsRepository: dividendRepo)
        await vm.load()
        XCTAssertNil(vm.dividendInfo)
        XCTAssertEqual(vm.loadState, .loaded)
        XCTAssertEqual(vm.quote?.symbol, "AAPL")
    }
}
