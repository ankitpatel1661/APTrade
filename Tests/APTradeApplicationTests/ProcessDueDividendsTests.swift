import XCTest
@testable import APTradeApplication
import APTradeDomain

// MARK: - Fakes

private final class FakeDividendPortfolioStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    var saveCallCount = 0
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) {
        self.portfolio = portfolio
        saveCallCount += 1
    }
}

private final class FakeDividendStateStore: SchedulerStateStore, @unchecked Sendable {
    var state: SchedulerState
    init(_ state: SchedulerState = SchedulerState()) { self.state = state }
    func load() -> SchedulerState { state }
    func save(_ state: SchedulerState) { self.state = state }
}

private final class FakeDividendRepo: DividendEventsRepository, @unchecked Sendable {
    var events: [String: [DividendEvent]] = [:]
    var errors: [String: Error] = [:]
    func dividendEvents(for symbol: String, since: Date) async throws -> [DividendEvent] {
        if let error = errors[symbol] { throw error }
        return events[symbol] ?? []
    }
}

/// Market with a canned daily-close table indexed by `yyyy-MM-dd` exactly as
/// `ProcessDueDividends` indexes `history(for:timeframe:)`.
private final class FakeDividendMarket: MarketDataRepository, @unchecked Sendable {
    var dailyCloses: [String: [String: Money]] = [:]
    var historyError: Error?
    private let calendar = MarketCalendar()

    func quote(for symbol: String) async throws -> Quote { throw AppError.notFound }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        if let historyError { throw historyError }
        let closes = dailyCloses[symbol] ?? [:]
        return closes.compactMap { day, close in
            guard let date = PieSchedule.date(fromDay: day, calendar: calendar) else { return nil }
            return PricePoint(date: date, close: close)
        }
    }
}

final class ProcessDueDividendsTests: XCTestCase {
    private let calendar = MarketCalendar()

    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func day(_ d: String) -> Date { PieSchedule.date(fromDay: d, calendar: calendar) ?? Date() }

    private func makeSUT(
        portfolioStore: FakeDividendPortfolioStore,
        market: FakeDividendMarket,
        dividends: FakeDividendRepo,
        stateStore: FakeDividendStateStore,
        drip: Bool
    ) -> ProcessDueDividends {
        ProcessDueDividends(portfolioStore: portfolioStore, market: market, dividends: dividends,
                            stateStore: stateStore, calendar: calendar, serializer: TradeSerializer(),
                            isDripEnabled: { drip })
    }

    /// A single stock position with one buy transaction on `buyDay`.
    private func portfolio(cash: String = "100000", symbol: String, kind: AssetKind = .stock,
                           shares: String, buyDay: String) -> Portfolio {
        let asset = Asset(symbol: symbol, name: symbol, kind: kind)
        let qty = Quantity(Decimal(string: shares) ?? 0)
        let pos = Position(asset: asset, quantity: qty, averageCost: usd("10"), realizedPnL: Money(amount: 0))
        let txn = Transaction(symbol: symbol, side: .buy, quantity: qty, price: usd("10"), date: day(buyDay))
        return Portfolio(cash: usd(cash), positions: [pos], transactions: [txn])
    }

    // MARK: (a) Backfill: two past events credit as cash even with DRIP on; firstRunDay persisted.

    func test_backfillEvents_creditAsCashEvenWithDripOn_andPersistFirstRunDay() async {
        let portfolioStore = FakeDividendPortfolioStore(portfolio(symbol: "AAPL", shares: "100", buyDay: "2024-01-01"))
        let market = FakeDividendMarket()
        market.dailyCloses["AAPL"] = ["2025-02-15": usd("200"), "2025-05-15": usd("200")]
        let dividends = FakeDividendRepo()
        dividends.events["AAPL"] = [
            DividendEvent(symbol: "AAPL", exDate: day("2025-02-15"), amountPerShare: usd("0.50")),
            DividendEvent(symbol: "AAPL", exDate: day("2025-05-15"), amountPerShare: usd("0.60"))
        ]
        let stateStore = FakeDividendStateStore() // fresh -> first run
        let sut = makeSUT(portfolioStore: portfolioStore, market: market, dividends: dividends,
                          stateStore: stateStore, drip: true)

        let outcomes = await sut(now: day("2025-06-01"))

        XCTAssertEqual(outcomes, [
            .credited(symbol: "AAPL", cash: usd("50"), isBackfill: true),
            .credited(symbol: "AAPL", cash: usd("60"), isBackfill: true)
        ])
        XCTAssertEqual(stateStore.state.dividendsFirstRunDay, "2025-06-01")
        // Cash credited, no reinvestment buys.
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("100110"))
        XCTAssertEqual(portfolioStore.portfolio.transactions.filter { $0.side == .dividend }.count, 2)
        XCTAssertTrue(portfolioStore.portfolio.transactions.allSatisfy { !$0.isDrip })
    }

    // MARK: (b) Post-first-run event, DRIP on -> reinvested at that day's close, fractional exact.

    func test_postFirstRunEvent_dripOn_reinvestsAtCloseWithExactFractionalShares() async {
        let portfolioStore = FakeDividendPortfolioStore(portfolio(symbol: "MSFT", shares: "100", buyDay: "2023-06-01"))
        let market = FakeDividendMarket()
        market.dailyCloses["MSFT"] = ["2025-03-14": usd("400")]
        let dividends = FakeDividendRepo()
        dividends.events["MSFT"] = [DividendEvent(symbol: "MSFT", exDate: day("2025-03-14"), amountPerShare: usd("1.00"))]
        let stateStore = FakeDividendStateStore(SchedulerState(dividendsFirstRunDay: "2024-01-01"))
        let sut = makeSUT(portfolioStore: portfolioStore, market: market, dividends: dividends,
                          stateStore: stateStore, drip: true)

        let outcomes = await sut(now: day("2025-06-01"))

        let expectedShares = Quantity(usd("100").amount / usd("400").amount) // 0.25 exactly
        XCTAssertEqual(outcomes, [.reinvested(symbol: "MSFT", cash: usd("100"), shares: expectedShares, isBackfill: false)])
        // A .dividend cash event, plus a DRIP buy at the close.
        let dividendTxns = portfolioStore.portfolio.transactions.filter { $0.side == .dividend }
        XCTAssertEqual(dividendTxns.count, 1)
        let dripBuys = portfolioStore.portfolio.transactions.filter { $0.side == .buy && $0.isDrip }
        XCTAssertEqual(dripBuys.count, 1)
        XCTAssertEqual(dripBuys.first?.price, usd("400"))
        XCTAssertEqual(dripBuys.first?.quantity, expectedShares)
        XCTAssertEqual(portfolioStore.portfolio.position(for: "MSFT")?.quantity,
                       Quantity(Decimal(100) + expectedShares.amount))
    }

    // MARK: (c) Second run -> zero outcomes (ledger dedup).

    func test_secondRun_dedupsAlreadyCreditedEvent() async {
        let portfolioStore = FakeDividendPortfolioStore(portfolio(symbol: "T", shares: "10", buyDay: "2024-01-01"))
        let market = FakeDividendMarket()
        let dividends = FakeDividendRepo()
        dividends.events["T"] = [DividendEvent(symbol: "T", exDate: day("2025-03-15"), amountPerShare: usd("1"))]
        let stateStore = FakeDividendStateStore(SchedulerState(dividendsFirstRunDay: "2024-01-01"))
        let sut = makeSUT(portfolioStore: portfolioStore, market: market, dividends: dividends,
                          stateStore: stateStore, drip: false)

        let first = await sut(now: day("2025-06-01"))
        XCTAssertEqual(first, [.credited(symbol: "T", cash: usd("10"), isBackfill: false)])

        let second = await sut(now: day("2025-06-01"))
        XCTAssertEqual(second, [])
        // Only ONE dividend txn total; cash credited exactly once.
        XCTAssertEqual(portfolioStore.portfolio.transactions.filter { $0.side == .dividend }.count, 1)
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("100010"))
    }

    // MARK: (d) A buy on the ex-date itself earns nothing (strictly-before).

    func test_buyOnExDate_earnsNothing() async {
        // Only buy is ON the ex-date -> sharesHeld strictly-before is zero.
        let asset = Asset(symbol: "X", name: "X", kind: .stock)
        let txn = Transaction(symbol: "X", side: .buy, quantity: Quantity(10), price: usd("10"), date: day("2025-03-15"))
        let pf = Portfolio(cash: usd("100000"),
                           positions: [Position(asset: asset, quantity: Quantity(10), averageCost: usd("10"), realizedPnL: Money(amount: 0))],
                           transactions: [txn])
        let portfolioStore = FakeDividendPortfolioStore(pf)
        let market = FakeDividendMarket()
        let dividends = FakeDividendRepo()
        dividends.events["X"] = [DividendEvent(symbol: "X", exDate: day("2025-03-15"), amountPerShare: usd("1"))]
        let stateStore = FakeDividendStateStore(SchedulerState(dividendsFirstRunDay: "2024-01-01"))
        let sut = makeSUT(portfolioStore: portfolioStore, market: market, dividends: dividends,
                          stateStore: stateStore, drip: false)

        let outcomes = await sut(now: day("2025-06-01"))

        XCTAssertEqual(outcomes, [])
        XCTAssertEqual(portfolioStore.portfolio.transactions.filter { $0.side == .dividend }.count, 0)
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("100000"))
    }

    // MARK: (e) A sell before the ex-date reduces the credited quantity.

    func test_sellBeforeExDate_reducesCreditedQuantity() async {
        let asset = Asset(symbol: "Y", name: "Y", kind: .stock)
        let buy = Transaction(symbol: "Y", side: .buy, quantity: Quantity(100), price: usd("10"), date: day("2024-01-01"))
        let sell = Transaction(symbol: "Y", side: .sell, quantity: Quantity(60), price: usd("12"), date: day("2025-01-01"))
        let pf = Portfolio(cash: usd("100000"),
                           positions: [Position(asset: asset, quantity: Quantity(40), averageCost: usd("10"), realizedPnL: Money(amount: 0))],
                           transactions: [buy, sell])
        let portfolioStore = FakeDividendPortfolioStore(pf)
        let market = FakeDividendMarket()
        let dividends = FakeDividendRepo()
        dividends.events["Y"] = [DividendEvent(symbol: "Y", exDate: day("2025-03-15"), amountPerShare: usd("1"))]
        let stateStore = FakeDividendStateStore(SchedulerState(dividendsFirstRunDay: "2024-01-01"))
        let sut = makeSUT(portfolioStore: portfolioStore, market: market, dividends: dividends,
                          stateStore: stateStore, drip: false)

        let outcomes = await sut(now: day("2025-06-01"))

        // 40 shares held before ex-date x $1 = $40 (not $100).
        XCTAssertEqual(outcomes, [.credited(symbol: "Y", cash: usd("40"), isBackfill: false)])
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("100040"))
    }

    // MARK: (f) DRIP on but close missing that day -> cash fallback.

    func test_dripOn_missingClose_fallsBackToCash() async {
        let portfolioStore = FakeDividendPortfolioStore(portfolio(symbol: "Z", shares: "50", buyDay: "2023-01-01"))
        let market = FakeDividendMarket()
        market.dailyCloses["Z"] = ["2025-01-10": usd("100")] // no close on the ex-date
        let dividends = FakeDividendRepo()
        dividends.events["Z"] = [DividendEvent(symbol: "Z", exDate: day("2025-03-15"), amountPerShare: usd("2"))]
        let stateStore = FakeDividendStateStore(SchedulerState(dividendsFirstRunDay: "2024-01-01"))
        let sut = makeSUT(portfolioStore: portfolioStore, market: market, dividends: dividends,
                          stateStore: stateStore, drip: true)

        let outcomes = await sut(now: day("2025-06-01"))

        XCTAssertEqual(outcomes, [.credited(symbol: "Z", cash: usd("100"), isBackfill: false)])
        XCTAssertEqual(portfolioStore.portfolio.transactions.filter { $0.side == .buy && $0.isDrip }.count, 0)
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("100100"))
    }

    // MARK: (g) Crypto position ignored.

    func test_cryptoPosition_ignored() async {
        let portfolioStore = FakeDividendPortfolioStore(
            portfolio(symbol: "BTC-USD", kind: .crypto, shares: "5", buyDay: "2024-01-01"))
        let market = FakeDividendMarket()
        let dividends = FakeDividendRepo()
        dividends.events["BTC-USD"] = [DividendEvent(symbol: "BTC-USD", exDate: day("2025-03-15"), amountPerShare: usd("1"))]
        let stateStore = FakeDividendStateStore(SchedulerState(dividendsFirstRunDay: "2024-01-01"))
        let sut = makeSUT(portfolioStore: portfolioStore, market: market, dividends: dividends,
                          stateStore: stateStore, drip: false)

        let outcomes = await sut(now: day("2025-06-01"))

        XCTAssertEqual(outcomes, [])
        XCTAssertEqual(portfolioStore.portfolio.transactions.filter { $0.side == .dividend }.count, 0)
    }

    // MARK: (h) Repository throws for symbol A -> symbol B's event still credits.

    func test_repositoryThrowsForOneSymbol_otherSymbolStillCredits() async {
        let assetA = Asset(symbol: "A", name: "A", kind: .stock)
        let assetB = Asset(symbol: "B", name: "B", kind: .stock)
        let buyA = Transaction(symbol: "A", side: .buy, quantity: Quantity(5), price: usd("10"), date: day("2024-01-01"))
        let buyB = Transaction(symbol: "B", side: .buy, quantity: Quantity(10), price: usd("10"), date: day("2024-01-01"))
        let pf = Portfolio(cash: usd("100000"),
                           positions: [
                               Position(asset: assetA, quantity: Quantity(5), averageCost: usd("10"), realizedPnL: Money(amount: 0)),
                               Position(asset: assetB, quantity: Quantity(10), averageCost: usd("10"), realizedPnL: Money(amount: 0))
                           ],
                           transactions: [buyA, buyB])
        let portfolioStore = FakeDividendPortfolioStore(pf)
        let market = FakeDividendMarket()
        let dividends = FakeDividendRepo()
        dividends.errors["A"] = AppError.notFound
        dividends.events["B"] = [DividendEvent(symbol: "B", exDate: day("2025-03-15"), amountPerShare: usd("1"))]
        let stateStore = FakeDividendStateStore(SchedulerState(dividendsFirstRunDay: "2024-01-01"))
        let sut = makeSUT(portfolioStore: portfolioStore, market: market, dividends: dividends,
                          stateStore: stateStore, drip: false)

        let outcomes = await sut(now: day("2025-06-01"))

        XCTAssertEqual(outcomes, [.credited(symbol: "B", cash: usd("10"), isBackfill: false)])
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("100010"))
    }

    // MARK: (i) DRIP off -> plain cash credit for a post-first-run event.

    func test_dripOff_postFirstRunEvent_creditsCash() async {
        let portfolioStore = FakeDividendPortfolioStore(portfolio(symbol: "M", shares: "20", buyDay: "2023-01-01"))
        let market = FakeDividendMarket()
        market.dailyCloses["M"] = ["2025-03-15": usd("50")] // present, but must be ignored
        let dividends = FakeDividendRepo()
        dividends.events["M"] = [DividendEvent(symbol: "M", exDate: day("2025-03-15"), amountPerShare: usd("1.50"))]
        let stateStore = FakeDividendStateStore(SchedulerState(dividendsFirstRunDay: "2024-01-01"))
        let sut = makeSUT(portfolioStore: portfolioStore, market: market, dividends: dividends,
                          stateStore: stateStore, drip: false)

        let outcomes = await sut(now: day("2025-06-01"))

        XCTAssertEqual(outcomes, [.credited(symbol: "M", cash: usd("30"), isBackfill: false)])
        XCTAssertEqual(portfolioStore.portfolio.transactions.filter { $0.side == .buy && $0.isDrip }.count, 0)
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("100030"))
    }
}
