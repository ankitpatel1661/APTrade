import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class MemoryPortfolioStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

private final class FakeMarketDataRepository: MarketDataRepository, @unchecked Sendable {
    var quotes: [String: Quote] = [:]
    func quote(for symbol: String) async throws -> Quote {
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

/// Returns fixed events per symbol; a symbol mapped to `nil` throws (simulates a
/// per-symbol event-fetch failure without blocking other symbols).
private final class FakeDividendEventsRepository: DividendEventsRepository, @unchecked Sendable {
    var eventsBySymbol: [String: [DividendEvent]?] = [:]
    private(set) var requestedSymbols: [String] = []

    func dividendEvents(for symbol: String, since: Date) async throws -> [DividendEvent] {
        requestedSymbols.append(symbol)
        guard let entry = eventsBySymbol[symbol] else { return [] }
        guard let events = entry else { throw AppError.network }
        return events
    }
}

@MainActor
final class IncomeViewModelTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func qty(_ s: String) -> Quantity { Quantity(Decimal(string: s) ?? 0) }

    /// 2026-07-20 12:00:00 UTC — a fixed "now" so calendar-year and month-bucket math
    /// is deterministic across runs.
    private let fixedNow: Date = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: DateComponents(year: 2026, month: 7, day: 20, hour: 12))!
    }()

    private func utc(_ year: Int, _ month: Int, _ day: Int) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: DateComponents(year: year, month: month, day: day, hour: 12))!
    }

    private func makeVM(portfolio: Portfolio, quotes: [String: Quote] = [:],
                       events: [String: [DividendEvent]?] = [:],
                       now: Date? = nil) -> (IncomeViewModel, FakeDividendEventsRepository) {
        let store = MemoryPortfolioStore(portfolio)
        let market = FakeMarketDataRepository()
        market.quotes = quotes
        let eventsRepo = FakeDividendEventsRepository()
        eventsRepo.eventsBySymbol = events
        let fixed = now ?? fixedNow
        let vm = IncomeViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: market),
            dividendEventsRepository: eventsRepo,
            now: { fixed }
        )
        return (vm, eventsRepo)
    }

    // MARK: - (a) cards computed from ledger + events fixture (exact Money math)

    func test_cards_computedFromLedgerAndEvents_exactMoneyMath() async throws {
        var portfolio = Portfolio.starting(cash: usd("10000"))
        // 100 shares of KO bought at cost basis $60/share.
        portfolio = try portfolio.buying(Asset(symbol: "KO", name: "Coca-Cola", kind: .stock),
                                         quantity: qty("100"), at: usd("60"), on: utc(2025, 1, 5))
        // Two dividend payouts this year: $0.48/share and $0.51/share on 100 shares.
        portfolio = try portfolio.receivingDividend("KO", amountPerShare: usd("0.48"),
                                                     shares: qty("100"), on: utc(2026, 2, 14))
        portfolio = try portfolio.receivingDividend("KO", amountPerShare: usd("0.51"),
                                                     shares: qty("100"), on: utc(2026, 5, 14))
        // A dividend from LAST year must not count toward receivedYTD.
        portfolio = try portfolio.receivingDividend("KO", amountPerShare: usd("0.46"),
                                                     shares: qty("100"), on: utc(2025, 11, 14))

        let events = [
            DividendEvent(symbol: "KO", exDate: utc(2025, 11, 14), amountPerShare: usd("0.46")),
            DividendEvent(symbol: "KO", exDate: utc(2026, 2, 14), amountPerShare: usd("0.48")),
            DividendEvent(symbol: "KO", exDate: utc(2026, 5, 14), amountPerShare: usd("0.51"))
        ]
        let quote = Quote(symbol: "KO", price: usd("65"), previousClose: usd("64"))
        let (vm, _) = makeVM(portfolio: portfolio, quotes: ["KO": quote], events: ["KO": events])

        await vm.load()

        // receivedYTD = (0.48 + 0.51) × 100 = $99.00 — the 2025 payout is excluded.
        XCTAssertEqual(vm.cards?.receivedYTD, usd("99"))

        // trailingAnnualPerShare (asOf 2026-07-20, 365d window): all three events fall
        // within the trailing year → 0.46 + 0.48 + 0.51 = 1.45/share × 100 shares = $145.
        XCTAssertEqual(vm.cards?.projectedAnnual, usd("145"))

        // marketValue = 100 × $65 = $6,500 → yield = 145 / 6500.
        let expectedYield = ((Decimal(string: "145")! / Decimal(string: "6500")!) as NSDecimalNumber).doubleValue
        XCTAssertEqual(vm.cards?.portfolioYield ?? -1, expectedYield, accuracy: 0.0001)

        // costBasis = 100 × $60 = $6,000 → yieldOnCost = 145 / 6000.
        let expectedYoC = ((Decimal(string: "145")! / Decimal(string: "6000")!) as NSDecimalNumber).doubleValue
        XCTAssertEqual(vm.cards?.yieldOnCost ?? -1, expectedYoC, accuracy: 0.0001)
    }

    // MARK: - (b) history pairs the DRIP badge correctly

    func test_history_pairsDripBadgeCorrectly() async throws {
        var portfolio = Portfolio.starting(cash: usd("10000"))
        portfolio = try portfolio.buying(Asset(symbol: "KO", name: "Coca-Cola", kind: .stock),
                                         quantity: qty("100"), at: usd("60"), on: utc(2025, 1, 5))
        // Dividend #1 (2026-02-14) is immediately reinvested — a DRIP buy same symbol,
        // same trading day.
        portfolio = try portfolio.receivingDividend("KO", amountPerShare: usd("0.48"),
                                                     shares: qty("100"), on: utc(2026, 2, 14))
        portfolio = try portfolio.buying(Asset(symbol: "KO", name: "Coca-Cola", kind: .stock),
                                         quantity: qty("0.7"), at: usd("68.5"),
                                         on: utc(2026, 2, 14), isDrip: true)
        // Dividend #2 (2026-05-14) is taken as cash — no matching DRIP buy.
        portfolio = try portfolio.receivingDividend("KO", amountPerShare: usd("0.51"),
                                                     shares: qty("100.7"), on: utc(2026, 5, 14))

        let (vm, _) = makeVM(portfolio: portfolio, quotes: [:], events: [:])
        await vm.load()

        XCTAssertEqual(vm.history.count, 2)
        // Newest first.
        XCTAssertEqual(vm.history[0].date, utc(2026, 5, 14))
        XCTAssertFalse(vm.history[0].wasReinvested)
        XCTAssertEqual(vm.history[1].date, utc(2026, 2, 14))
        XCTAssertTrue(vm.history[1].wasReinvested)
    }

    // MARK: - (c) months: 12 buckets, projected bars flagged

    func test_months_twelveBucketsPlusProjectedFlagged() async throws {
        var portfolio = Portfolio.starting(cash: usd("10000"))
        portfolio = try portfolio.buying(Asset(symbol: "KO", name: "Coca-Cola", kind: .stock),
                                         quantity: qty("100"), at: usd("60"), on: utc(2025, 1, 5))
        portfolio = try portfolio.receivingDividend("KO", amountPerShare: usd("0.48"),
                                                     shares: qty("100"), on: utc(2026, 2, 14))
        portfolio = try portfolio.receivingDividend("KO", amountPerShare: usd("0.51"),
                                                     shares: qty("100"), on: utc(2026, 5, 14))

        // Quarterly cadence (91-day gaps) → nextProjected lands ~2026-08-13, bucket "2026-08".
        let events = [
            DividendEvent(symbol: "KO", exDate: utc(2025, 11, 14), amountPerShare: usd("0.46")),
            DividendEvent(symbol: "KO", exDate: utc(2026, 2, 14), amountPerShare: usd("0.48")),
            DividendEvent(symbol: "KO", exDate: utc(2026, 5, 14), amountPerShare: usd("0.51"))
        ]
        let (vm, _) = makeVM(portfolio: portfolio, quotes: [:], events: ["KO": events])
        await vm.load()

        let receivedBars = vm.months.filter { !$0.isProjected }
        XCTAssertEqual(receivedBars.count, 12)
        XCTAssertEqual(receivedBars.first?.id, "2025-08")
        XCTAssertEqual(receivedBars.last?.id, "2026-07")
        XCTAssertEqual(receivedBars.first { $0.id == "2026-02" }?.amount, usd("48"))
        XCTAssertEqual(receivedBars.first { $0.id == "2026-05" }?.amount, usd("51"))

        let projectedBars = vm.months.filter { $0.isProjected }
        XCTAssertFalse(projectedBars.isEmpty)
        XCTAssertTrue(projectedBars.allSatisfy { $0.id > "2026-07" })
        XCTAssertLessThanOrEqual(projectedBars.count, 3)
        // last.exDate (2026-05-14) + 91d ≈ 2026-08-13 → 100 shares × $0.51 = $51.
        XCTAssertEqual(projectedBars.first?.id, "2026-08")
        XCTAssertEqual(projectedBars.first?.amount, usd("51"))
    }

    // MARK: - (d) upcoming sorted by estimatedExDate

    func test_upcoming_sortedByEstimatedExDate() async throws {
        var portfolio = Portfolio.starting(cash: usd("20000"))
        portfolio = try portfolio.buying(Asset(symbol: "KO", name: "Coca-Cola", kind: .stock),
                                         quantity: qty("100"), at: usd("60"), on: utc(2025, 1, 5))
        portfolio = try portfolio.buying(Asset(symbol: "JNJ", name: "Johnson & Johnson", kind: .stock),
                                         quantity: qty("50"), at: usd("150"), on: utc(2025, 1, 5))

        // KO: quarterly, next projected ≈ 2026-08-13.
        let koEvents = [
            DividendEvent(symbol: "KO", exDate: utc(2025, 11, 14), amountPerShare: usd("0.46")),
            DividendEvent(symbol: "KO", exDate: utc(2026, 2, 14), amountPerShare: usd("0.48")),
            DividendEvent(symbol: "KO", exDate: utc(2026, 5, 14), amountPerShare: usd("0.51"))
        ]
        // JNJ: quarterly, next projected ≈ 2026-08-01 (earlier than KO's).
        let jnjEvents = [
            DividendEvent(symbol: "JNJ", exDate: utc(2025, 11, 2), amountPerShare: usd("1.19")),
            DividendEvent(symbol: "JNJ", exDate: utc(2026, 2, 1), amountPerShare: usd("1.24")),
            DividendEvent(symbol: "JNJ", exDate: utc(2026, 5, 2), amountPerShare: usd("1.24"))
        ]
        let (vm, _) = makeVM(portfolio: portfolio, quotes: [:], events: ["KO": koEvents, "JNJ": jnjEvents])
        await vm.load()

        XCTAssertEqual(vm.upcoming.count, 2)
        XCTAssertEqual(vm.upcoming[0].symbol, "JNJ")
        XCTAssertEqual(vm.upcoming[1].symbol, "KO")
        XCTAssertLessThan(vm.upcoming[0].estimatedExDate, vm.upcoming[1].estimatedExDate)
        XCTAssertEqual(vm.upcoming[0].estimatedAmount, usd("62"))    // 1.24 × 50
        XCTAssertEqual(vm.upcoming[1].estimatedAmount, usd("51"))    // 0.51 × 100
    }

    // MARK: - Regression: a stale nextProjected (landing in the past) must not surface

    /// `DividendMath.nextProjected` is just `lastEvent.exDate + cadenceInterval` — it has
    /// no awareness of "now". A symbol whose last recorded event is old enough that its
    /// projected next payout has already elapsed (e.g. an annual payer last seen ~700
    /// days ago) must NOT show up in `upcoming` as a future-dated row. A symbol with a
    /// genuinely future projection must still appear.
    func test_upcoming_excludesStaleProjection_pastAsOf() async throws {
        var portfolio = Portfolio.starting(cash: usd("20000"))
        portfolio = try portfolio.buying(Asset(symbol: "OLD", name: "Old Annual Payer", kind: .stock),
                                         quantity: qty("10"), at: usd("50"), on: utc(2022, 1, 5))
        portfolio = try portfolio.buying(Asset(symbol: "KO", name: "Coca-Cola", kind: .stock),
                                         quantity: qty("100"), at: usd("60"), on: utc(2025, 1, 5))

        // OLD: annual cadence, last event 2024-08-01 → nextProjected ≈ 2025-08-01,
        // which is BEFORE fixedNow (2026-07-20) — a stale, already-elapsed projection.
        let oldEvents = [
            DividendEvent(symbol: "OLD", exDate: utc(2023, 7, 28), amountPerShare: usd("2.00")),
            DividendEvent(symbol: "OLD", exDate: utc(2024, 8, 1), amountPerShare: usd("2.10"))
        ]
        // KO: quarterly, last event 2026-05-14 → nextProjected ≈ 2026-08-13, genuinely
        // in the future relative to fixedNow.
        let koEvents = [
            DividendEvent(symbol: "KO", exDate: utc(2025, 11, 14), amountPerShare: usd("0.46")),
            DividendEvent(symbol: "KO", exDate: utc(2026, 2, 14), amountPerShare: usd("0.48")),
            DividendEvent(symbol: "KO", exDate: utc(2026, 5, 14), amountPerShare: usd("0.51"))
        ]
        let (vm, _) = makeVM(portfolio: portfolio, quotes: [:], events: ["OLD": oldEvents, "KO": koEvents])
        await vm.load()

        XCTAssertFalse(vm.upcoming.contains { $0.symbol == "OLD" },
                       "a projection dated before 'now' must not appear as an upcoming payout")
        XCTAssertTrue(vm.upcoming.contains { $0.symbol == "KO" })
        XCTAssertEqual(vm.upcoming.count, 1)
    }

    // MARK: - (e) event-fetch failure degrades upcoming only

    func test_eventFetchFailure_upcomingEmpty_historyAndReceivedYTDStillPopulate() async throws {
        var portfolio = Portfolio.starting(cash: usd("10000"))
        portfolio = try portfolio.buying(Asset(symbol: "KO", name: "Coca-Cola", kind: .stock),
                                         quantity: qty("100"), at: usd("60"), on: utc(2025, 1, 5))
        portfolio = try portfolio.receivingDividend("KO", amountPerShare: usd("0.48"),
                                                     shares: qty("100"), on: utc(2026, 2, 14))

        // The events repo throws for KO — the only held symbol.
        let (vm, eventsRepo) = makeVM(portfolio: portfolio, quotes: [:], events: ["KO": nil])
        await vm.load()

        XCTAssertTrue(vm.upcoming.isEmpty)
        XCTAssertEqual(vm.history.count, 1)
        XCTAssertEqual(vm.cards?.receivedYTD, usd("48"))
        // projectedAnnual degrades to zero for the failed symbol — never blocks the rest.
        XCTAssertEqual(vm.cards?.projectedAnnual, usd("0"))
        XCTAssertEqual(eventsRepo.requestedSymbols, ["KO"])
    }
}
