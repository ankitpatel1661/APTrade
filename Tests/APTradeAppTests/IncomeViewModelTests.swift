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

private final class InMemoryGoalStore: GoalStore, @unchecked Sendable {
    private var goals: [PortfolioGoal]
    init(_ goals: [PortfolioGoal] = []) { self.goals = goals }
    func load() -> [PortfolioGoal] { goals }
    func save(_ goals: [PortfolioGoal]) { self.goals = goals }
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
                       goalStore: GoalStore = InMemoryGoalStore(),
                       dripEnabled: Bool = false,
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
            loadGoals: LoadGoalsUseCase(store: goalStore),
            saveGoal: SaveGoalUseCase(store: goalStore),
            removeGoal: RemoveGoalUseCase(store: goalStore),
            isDripEnabled: { dripEnabled },
            now: { fixed }
        )
        return (vm, eventsRepo)
    }

    /// Builds a VM over a portfolio holding `holdings` (symbol, share-quantity pairs, each
    /// bought at a $50 cost basis on 2020-01-05) with the given dividend-event fixtures.
    /// Adapts the plan's `makeSUT(holdings:events:)` shape onto this file's existing
    /// `makeVM` construction helper.
    private func makeSUT(holdings: [(String, String)], events: [String: [DividendEvent]],
                        quotes: [String: Quote] = [:], dripEnabled: Bool = false,
                        goalStore: GoalStore = InMemoryGoalStore(),
                        now: Date? = nil) -> IncomeViewModel {
        var portfolio = Portfolio.starting(cash: usd("1000000"))
        for (symbol, shares) in holdings {
            portfolio = try! portfolio.buying(Asset(symbol: symbol, name: symbol, kind: .stock),
                                              quantity: qty(shares), at: usd("50"), on: utc(2020, 1, 5))
        }
        let (vm, _) = makeVM(portfolio: portfolio, quotes: quotes,
                            events: events.mapValues { $0 as [DividendEvent]? },
                            goalStore: goalStore, dripEnabled: dripEnabled, now: now)
        return vm
    }

    private func quarterlyHistory(_ symbol: String, _ amount: String) -> [DividendEvent] {
        // Recent enough to fall inside `trailingAnnualPerShare`'s 365-day window relative
        // to `fixedNow` (2026-07-20) — a forecast/goal test needs a nonzero year-1 rate,
        // unlike a cadence-only test which would tolerate any historical dates.
        [utc(2025, 8, 14), utc(2025, 11, 14), utc(2026, 2, 14), utc(2026, 5, 14)].map {
            DividendEvent(symbol: symbol, exDate: $0, amountPerShare: Money(amount: Decimal(string: amount) ?? 0))
        }
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

    // MARK: - (f) calendar built from projected schedule

    @MainActor
    func test_load_buildsCalendarMonthsFromProjectedSchedule() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        XCTAssertFalse(vm.calendarMonths.isEmpty)
        XCTAssertTrue(vm.calendarMonths.allSatisfy { !$0.rows.isEmpty })
        let totals = vm.calendarMonths.map(\.total.amount)
        XCTAssertTrue(totals.allSatisfy { $0 > 0 })
    }

    // MARK: - (g) forecast horizon

    @MainActor
    func test_load_defaultHorizonIsTenYears() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        XCTAssertEqual(vm.horizon, .ten)
        XCTAssertEqual(vm.forecast.count, 10)
    }

    @MainActor
    func test_changingHorizon_rebuildsForecastLength() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        vm.horizon = .thirty
        XCTAssertEqual(vm.forecast.count, 30)
    }

    // MARK: - (h) income goal set/remove

    @MainActor
    func test_setIncomeGoal_persistsAndProjects() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        vm.setIncomeGoal(Money(amount: 5_000))
        XCTAssertEqual(vm.incomeGoal?.target, Money(amount: 5_000))
        XCTAssertNotNil(vm.incomeGoalProjection)
    }

    @MainActor
    func test_removeIncomeGoal_clearsGoalAndProjection() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        vm.setIncomeGoal(Money(amount: 5_000))
        vm.removeIncomeGoal()
        XCTAssertNil(vm.incomeGoal)
        XCTAssertNil(vm.incomeGoalProjection)
    }

    // MARK: - (i) degenerate: empty ledger

    @MainActor
    func test_load_emptyLedger_hasNoCalendarOrForecast() async {
        let vm = makeSUT(holdings: [], events: [:])
        await vm.load()
        XCTAssertTrue(vm.calendarMonths.isEmpty)
        XCTAssertTrue(vm.forecast.allSatisfy { $0.income.amount == 0 })
        // `forecast` is never `[]` here (one zero-`Money` entry per requested year), so
        // the section view can't gate its chart on `!forecast.isEmpty` — this is the flag
        // it must read instead (M11.1 Task 12 review finding).
        XCTAssertFalse(vm.hasForecastIncome)
    }

    /// The positive counterpart to the empty-ledger case above: a real dividend payer
    /// must flip `hasForecastIncome` to `true`.
    @MainActor
    func test_hasForecastIncome_trueWhenForecastHasIncome() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        XCTAssertTrue(vm.hasForecastIncome)
    }

    // MARK: - (j) gap coverage: goal persistence survives a fresh VM (SaveGoalUseCase wiring)

    /// The brief's own goal tests only check state on the SAME `vm` instance, which would
    /// still pass even if `setIncomeGoal` forgot to call `saveGoal` at all (the in-memory
    /// `@Published` write alone would satisfy them). Reload through a second VM sharing the
    /// same `GoalStore` to prove the goal was actually persisted, not just held in memory.
    @MainActor
    func test_setIncomeGoal_persistsToStore_survivesFreshViewModel() async {
        let goalStore = InMemoryGoalStore()
        let vm1 = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")],
                          goalStore: goalStore)
        await vm1.load()
        vm1.setIncomeGoal(Money(amount: 5_000))

        let vm2 = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")],
                          goalStore: goalStore)
        await vm2.load()
        XCTAssertEqual(vm2.incomeGoal?.target, Money(amount: 5_000))
    }

    // MARK: - (k) gap coverage: current for the income goal matches forecast's yearOffset == 1

    /// Carried constraint 2: the income goal's `current` must be measured the same way as
    /// `forecast`'s `yearOffset == 1` entry, or the progress bar and the forecast chart
    /// beside it would disagree. Verified by using a target exactly equal to what year 1's
    /// income should be — this must read as `.reached`, not `.notOnTrack`/`.years`, which
    /// would only happen if `current` came from a differently defined measure (e.g. a
    /// growth-adjusted or yield-based figure instead of the flat trailing-12mo rate).
    @MainActor
    func test_incomeGoalProjection_currentMatchesForecastYearOneIncome() async {
        let events = quarterlyHistory("AAA", "0.50")   // trailing 365d: 4 × 0.50 = 2.00/share
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": events])
        await vm.load()

        // year 1 income = 100 shares × 2.00/share = 200, exactly.
        XCTAssertEqual(vm.forecast.first { $0.yearOffset == 1 }?.income, Money(amount: 200))

        vm.setIncomeGoal(Money(amount: 200))
        XCTAssertEqual(vm.incomeGoalProjection, .reached,
                       "current must equal the trailing-12mo rate (year 1), matching the forecast chart")
    }

    // MARK: - (l) gap coverage: carried constraint 1 — pricesBySymbol must reach incomeForecast

    /// Carried constraint 1: dropping `pricesBySymbol` silently reverts DRIP reinvestment
    /// to the cost-basis fallback, overstating a long-horizon forecast for any holding that
    /// has appreciated. This holding was bought at $50 and now quotes at $100 — reinvesting
    /// at the real (higher) price buys fewer shares each year than reinvesting at the
    /// (lower) cost basis, so a correct year-30 forecast must be STRICTLY LOWER than the
    /// cost-basis-fallback figure `DividendMath.incomeForecast` produces when
    /// `pricesBySymbol` is omitted entirely. If the view model forgot to pass
    /// `pricesBySymbol`, the two values below would be equal, and this assertion would fail.
    @MainActor
    func test_forecast_dripReinvestsAtQuotedPrice_notCostBasis() async throws {
        var portfolio = Portfolio.starting(cash: usd("10000"))
        portfolio = try portfolio.buying(Asset(symbol: "AAA", name: "AAA Corp", kind: .stock),
                                         quantity: qty("100"), at: usd("50"), on: utc(2020, 1, 5))
        let events = quarterlyHistory("AAA", "0.50")
        let quote = Quote(symbol: "AAA", price: usd("100"), previousClose: usd("99"))

        let (vm, _) = makeVM(portfolio: portfolio, quotes: ["AAA": quote],
                            events: ["AAA": events], dripEnabled: true)
        await vm.load()
        vm.horizon = .thirty

        // `pricesBySymbol: [:]` explicitly (rather than omitted) builds the cost-basis
        // reference forecast on purpose — `incomeForecast` falls back to `averageCost`
        // for any symbol missing a quote, so an empty price map reproduces exactly what
        // this test needs to compare against.
        let costBasisFallback = DividendMath.incomeForecast(positions: portfolio.positions,
                                                            pricesBySymbol: [:],
                                                            eventsBySymbol: ["AAA": events],
                                                            years: 30, dripEnabled: true, asOf: fixedNow)

        let realPriceYear30 = vm.forecast.last?.income.amount ?? 0
        let fallbackYear30 = costBasisFallback.last?.income.amount ?? 0
        XCTAssertLessThan(realPriceYear30, fallbackYear30)
    }

    // MARK: - (m) whole-branch review fix 2: DRIP toggle rebuilds the forecast

    /// Before the fix, `rebuildForecast()` was only reachable from `load()` and
    /// `horizon`'s `didSet` — flipping the persisted DRIP setting (which `isDripEnabled`
    /// reads live) left `forecast` stuck at its pre-toggle values until the user happened
    /// to tap a horizon pill. `dripDidChange()` is the seam `IncomeSection` wires to
    /// `.onChange` on the DRIP binding; this test calls it directly (no SwiftUI needed) to
    /// verify it actually rebuilds — this method did not exist at all before the fix, so
    /// this test could not even compile against the pre-fix `IncomeViewModel`.
    @MainActor
    func test_dripDidChange_rebuildsForecast_afterLiveToggle() async throws {
        final class DripFlag: @unchecked Sendable { var enabled = false }
        let flag = DripFlag()

        var portfolio = Portfolio.starting(cash: usd("10000"))
        portfolio = try portfolio.buying(Asset(symbol: "AAA", name: "AAA Corp", kind: .stock),
                                         quantity: qty("100"), at: usd("50"), on: utc(2020, 1, 5))
        let events = quarterlyHistory("AAA", "0.50")
        let quote = Quote(symbol: "AAA", price: usd("100"), previousClose: usd("99"))
        let store = MemoryPortfolioStore(portfolio)
        let market = FakeMarketDataRepository()
        market.quotes = ["AAA": quote]
        let eventsRepo = FakeDividendEventsRepository()
        eventsRepo.eventsBySymbol = ["AAA": events]

        let vm = IncomeViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: market),
            dividendEventsRepository: eventsRepo,
            isDripEnabled: { flag.enabled },
            now: { self.fixedNow })
        await vm.load()
        let beforeToggle = vm.forecast.last?.income.amount ?? 0

        flag.enabled = true
        vm.dripDidChange()
        let afterToggle = vm.forecast.last?.income.amount ?? 0

        XCTAssertGreaterThan(afterToggle, beforeToggle,
                             "enabling DRIP and calling dripDidChange() must reinvest into the forecast, not leave it at the pre-toggle cash figure")
    }
}
