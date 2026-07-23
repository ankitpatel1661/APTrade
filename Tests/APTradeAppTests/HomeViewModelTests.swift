import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private struct Boom: Error {}

/// Scripted per-symbol quote outcomes: a symbol mapped into `failing` throws instead of
/// returning, simulating a per-symbol fetch failure without blocking the others —
/// `FetchQuotesUseCase` (production) already isolates these; this fake just lets tests
/// script which symbols fail.
private final class FakeQuoteRepo: MarketDataRepository, @unchecked Sendable {
    var quotes: [String: Quote] = [:]
    var failing: Set<String> = []
    func quote(for symbol: String) async throws -> Quote {
        if failing.contains(symbol) { throw AppError.network }
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

@MainActor
final class HomeViewModelTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func qty(_ s: String) -> Quantity { Quantity(Decimal(string: s) ?? 0) }

    private func etDate(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York")!
        return cal.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    // Monday 2026-11-23 — the same reference day CalendarViewModelTests anchors on.
    private var mondayNoonET: Date { etDate(2026, 11, 23, 12) }
    private var saturdayNoonET: Date { etDate(2026, 11, 21, 12) }

    private func quote(_ symbol: String, price: String, previousClose: String) -> Quote {
        Quote(symbol: symbol, price: usd(price), previousClose: usd(previousClose))
    }

    private func report(totalReturn: Double, curveValues: [Double]) -> PerformanceReport {
        let curve = curveValues.enumerated().map { index, value in
            EquityPoint(date: Date(timeIntervalSince1970: TimeInterval(index) * 86_400),
                       value: Money(amount: Decimal(value)))
        }
        return PerformanceReport(
            metrics: PerformanceMetrics(totalReturn: totalReturn, annualizedReturn: 0, volatility: 0,
                                        maxDrawdown: 0, sharpe: nil, beta: nil, alpha: nil),
            equityCurve: curve, benchmarkCurve: [], benchmarkSymbol: "SPY",
            concentration: 0, effectiveHoldings: 0, warnings: [], isEmpty: false)
    }

    private func row(symbol: String, rsi14: Double? = nil) -> ScreenerSnapshotRow {
        ScreenerSnapshotRow(
            symbol: symbol, name: symbol, close: 100, dayChangePercent: nil, rsi14: rsi14,
            macd: nil, macdSignal: nil, macdHistogram: nil, sma50: nil, sma200: nil, ema20: nil,
            pctVsSma50: nil, pctVsSma200: nil, bollingerPercentB: nil, bollingerBandwidth: nil,
            week52High: nil, week52Low: nil, pctTo52wHigh: nil, pctTo52wLow: nil,
            relativeVolume: nil, macdCrossedUp: false, macdCrossedDown: false, goldenCross: false, deathCross: false
        )
    }

    private func earningsEvent(symbol: String, day: String, session: EarningsSession = .afterClose) -> EarningsEvent {
        EarningsEvent(symbol: symbol, companyName: "\(symbol) Inc.", day: day, session: session,
                     epsEstimate: nil, epsActual: nil)
    }

    /// Every dependency defaults to an empty/degraded-but-harmless double so a single test
    /// only has to override the sources it actually cares about.
    private func makeVM(
        portfolio: @escaping @Sendable () async throws -> Portfolio = { .starting() },
        quoteRepo: FakeQuoteRepo = FakeQuoteRepo(),
        ownSymbols: @escaping @Sendable () async -> Set<String> = { [] },
        performanceReport: @escaping @Sendable () async throws -> PerformanceReport = { .empty },
        incomeSummary: @escaping @Sendable () async throws -> HomeIncomeSummary
            = { HomeIncomeSummary(receivedYTD: Money(amount: 0), nextDividend: nil) },
        nextEarnings: @escaping @Sendable () async throws -> EarningsEvent? = { nil },
        screenerSnapshot: @escaping @Sendable () -> ScreenerSnapshot? = { nil },
        alertStore: VMFakeAlertStore = VMFakeAlertStore(),
        now: Date = Date(timeIntervalSince1970: 1_795_453_200) // Monday 2026-11-23 noon ET
    ) -> HomeViewModel {
        HomeViewModel(
            loadPortfolio: portfolio,
            fetchQuotes: FetchQuotesUseCase(repository: quoteRepo),
            ownSymbols: ownSymbols,
            fetchPerformanceReport: performanceReport,
            loadIncomeSummary: incomeSummary,
            fetchNextEarnings: nextEarnings,
            loadScreenerSnapshot: screenerSnapshot,
            loadAlerts: LoadAlertsUseCase(store: alertStore),
            now: { now }
        )
    }

    // MARK: - Hero: totalValue / dayChange / dayChangePercent

    func test_totalValue_equalsMarketValuePlusCash() async throws {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let portfolio = try Portfolio.starting(cash: usd("1000"))
            .buying(aapl, quantity: qty("2"), at: usd("100")) // costs 200 → cash left 800
        let repo = FakeQuoteRepo()
        repo.quotes["AAPL"] = quote("AAPL", price: "150", previousClose: "140")
        let vm = makeVM(portfolio: { portfolio }, quoteRepo: repo)

        await vm.refresh()

        // holdingsValue = 150 × 2 = 300; totalValue = 300 + 800 cash = 1100.
        XCTAssertEqual(vm.totalValue, usd("1100"))
        XCTAssertEqual(vm.cash, usd("800"))
    }

    func test_dayChange_summedFromHoldingsQuoteChanges() async throws {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let msft = Asset(symbol: "MSFT", name: "Microsoft", kind: .stock)
        let built = try Portfolio.starting(cash: usd("1000")).buying(aapl, quantity: qty("2"), at: usd("100"))
        let portfolio = try built.buying(msft, quantity: qty("1"), at: usd("200"))
        let repo = FakeQuoteRepo()
        repo.quotes["AAPL"] = quote("AAPL", price: "150", previousClose: "140") // +10 × 2 = +20
        repo.quotes["MSFT"] = quote("MSFT", price: "190", previousClose: "200") // -10 × 1 = -10
        let vm = makeVM(portfolio: { portfolio }, quoteRepo: repo)

        await vm.refresh()

        XCTAssertEqual(vm.dayChange, usd("10")) // +20 + (-10)
    }

    // MARK: - Movers: top gainer / loser across holdings ∪ watchlist, deduped

    func test_movers_pickedAcrossHoldingsAndWatchlist_dedupedBySet() async {
        // AAPL is both held AND watched — must contribute one row, not two.
        let repo = FakeQuoteRepo()
        repo.quotes["AAPL"] = quote("AAPL", price: "110", previousClose: "100") // +10%
        repo.quotes["TSLA"] = quote("TSLA", price: "90", previousClose: "100")  // -10%
        repo.quotes["SPY"] = quote("SPY", price: "101", previousClose: "100")   // +1%
        let vm = makeVM(quoteRepo: repo, ownSymbols: { ["AAPL", "TSLA", "SPY"] })

        await vm.refresh()

        guard case .topGainer(let gSymbol, let gPct) = vm.feed.first(where: {
            if case .topGainer = $0 { return true }; return false
        }) else { return XCTFail("expected a topGainer row") }
        guard case .topLoser(let lSymbol, _) = vm.feed.first(where: {
            if case .topLoser = $0 { return true }; return false
        }) else { return XCTFail("expected a topLoser row") }

        XCTAssertEqual(gSymbol, "AAPL")
        XCTAssertTrue(gPct.isPositive)
        XCTAssertEqual(lSymbol, "TSLA")
        // Exactly one gainer/loser row apiece — no duplicate AAPL row from the union.
        XCTAssertEqual(vm.feed.filter { if case .topGainer = $0 { return true }; return false }.count, 1)
    }

    // MARK: - Per-source failure isolation

    func test_portfolioLoadFailure_stillShowsMarketStatus() async {
        let vm = makeVM(portfolio: { throw Boom() })

        await vm.refresh()

        XCTAssertTrue(vm.feed.contains {
            if case .marketStatus = $0 { return true }; return false
        })
        XCTAssertEqual(vm.totalValue, Money(amount: 0))
    }

    func test_incomeFailure_dropsOnlyDividendRow_marketStatusUnaffected() async {
        let vm = makeVM(incomeSummary: { throw Boom() })

        await vm.refresh()

        XCTAssertFalse(vm.feed.contains { if case .dividend = $0 { return true }; return false })
        XCTAssertTrue(vm.feed.contains { if case .marketStatus = $0 { return true }; return false })
    }

    func test_earningsFailure_dropsOnlyEarningsRow_marketStatusUnaffected() async {
        let vm = makeVM(nextEarnings: { throw Boom() })

        await vm.refresh()

        XCTAssertFalse(vm.feed.contains { if case .earnings = $0 { return true }; return false })
        XCTAssertTrue(vm.feed.contains { if case .marketStatus = $0 { return true }; return false })
    }

    // MARK: - Market open / closed

    func test_marketStatus_openDuringSession() async {
        let vm = makeVM(now: mondayNoonET)

        await vm.refresh()

        guard case .marketStatus(let isOpen, let nextTransition) = vm.feed.first else {
            return XCTFail("expected marketStatus as the first feed row")
        }
        XCTAssertTrue(isOpen)
        // Reused hours-table oracle: next transition is the same day's 4:00 PM ET close.
        XCTAssertEqual(nextTransition, etDate(2026, 11, 23, 16, 0))
    }

    func test_marketStatus_closedOnWeekend() async {
        let vm = makeVM(now: saturdayNoonET)

        await vm.refresh()

        guard case .marketStatus(let isOpen, let nextTransition) = vm.feed.first else {
            return XCTFail("expected marketStatus as the first feed row")
        }
        XCTAssertFalse(isOpen)
        // Next open is Monday 9:30 AM ET.
        XCTAssertEqual(nextTransition, etDate(2026, 11, 23, 9, 30))
    }

    // MARK: - Screener freshness

    func test_screenerRow_absentWhenSnapshotStale() async {
        let staleSnapshot = ScreenerSnapshot(
            tradingDay: "2020-01-01", scannedAt: Date(timeIntervalSince1970: 0),
            rows: [row(symbol: "AAPL", rsi14: 20)], failedSymbols: [])
        let vm = makeVM(screenerSnapshot: { staleSnapshot })

        await vm.refresh()

        XCTAssertFalse(vm.feed.contains { if case .screenerFresh = $0 { return true }; return false })
    }

    func test_screenerRow_presentWhenFresh_withNameAndMatchCount() async {
        // rsiOversold (the first/default preset) matches rows with rsi14 < 30.
        let fresh = ScreenerSnapshot(
            tradingDay: "2026-11-23", scannedAt: mondayNoonET,
            rows: [row(symbol: "AAPL", rsi14: 20), row(symbol: "MSFT", rsi14: 25), row(symbol: "SPY", rsi14: 80)],
            failedSymbols: [])
        let vm = makeVM(screenerSnapshot: { fresh }, now: mondayNoonET)

        await vm.refresh()

        guard case .screenerFresh(let name, let matches) = vm.feed.first(where: {
            if case .screenerFresh = $0 { return true }; return false
        }) else { return XCTFail("expected a screenerFresh row") }
        XCTAssertEqual(name, PresetScreen.rsiOversold.rawValue)
        XCTAssertEqual(matches, 2)
    }

    // MARK: - Dividend row

    func test_dividendRow_absentWithNoUpcoming() async {
        let vm = makeVM(incomeSummary: {
            HomeIncomeSummary(receivedYTD: Money(amount: 50), nextDividend: nil)
        })

        await vm.refresh()

        XCTAssertFalse(vm.feed.contains { if case .dividend = $0 { return true }; return false })
        XCTAssertEqual(vm.incomeYTD, Money(amount: 50))
    }

    func test_dividendRow_presentWithUpcoming() async {
        let exDate = Date(timeIntervalSince1970: 1_795_000_000)
        let vm = makeVM(incomeSummary: {
            HomeIncomeSummary(
                receivedYTD: Money(amount: 0),
                nextDividend: IncomeViewModel.UpcomingRow(id: "AAPL", symbol: "AAPL",
                                                          estimatedExDate: exDate, estimatedAmount: Money(amount: 12.40)))
        })

        await vm.refresh()

        guard case .dividend(let symbol, let amount, let date) = vm.feed.first(where: {
            if case .dividend = $0 { return true }; return false
        }) else { return XCTFail("expected a dividend row") }
        XCTAssertEqual(symbol, "AAPL")
        XCTAssertEqual(amount, Money(amount: 12.40))
        XCTAssertEqual(date, exDate)
    }

    // MARK: - Feed ordering

    func test_feedOrdering_isFixed_statusThenMoversThenEarningsThenDividendThenScreener() async {
        let repo = FakeQuoteRepo()
        repo.quotes["AAPL"] = quote("AAPL", price: "110", previousClose: "100")
        repo.quotes["TSLA"] = quote("TSLA", price: "90", previousClose: "100")
        let freshSnapshot = ScreenerSnapshot(tradingDay: "2026-11-23", scannedAt: mondayNoonET,
                                             rows: [row(symbol: "AAPL", rsi14: 20)], failedSymbols: [])
        let msftEarnings = earningsEvent(symbol: "MSFT", day: "2026-11-24")
        let vm = makeVM(
            quoteRepo: repo,
            ownSymbols: { ["AAPL", "TSLA"] },
            incomeSummary: {
                HomeIncomeSummary(receivedYTD: Money(amount: 0),
                                 nextDividend: IncomeViewModel.UpcomingRow(
                                     id: "AAPL", symbol: "AAPL",
                                     estimatedExDate: Date(timeIntervalSince1970: 1_795_000_000),
                                     estimatedAmount: Money(amount: 5)))
            },
            nextEarnings: { msftEarnings },
            screenerSnapshot: { freshSnapshot },
            now: mondayNoonET
        )

        await vm.refresh()

        let kinds: [String] = vm.feed.map { item in
            switch item {
            case .marketStatus: return "status"
            case .topGainer: return "gainer"
            case .topLoser: return "loser"
            case .earnings: return "earnings"
            case .dividend: return "dividend"
            case .screenerFresh: return "screener"
            }
        }
        XCTAssertEqual(kinds, ["status", "gainer", "loser", "earnings", "dividend", "screener"])
    }

    // MARK: - Alerts

    // DELIBERATE assertion change (parity backport): alertCount is now armed-only (matches
    // shared Kotlin `HomeFeed.refreshAlertCount` / `alertCount_countsOnlyArmedAlerts`), not a
    // count of all alerts — mirror the injected armed+triggered mix and assert armed-only.
    func test_alertCount_fromStore() async {
        let store = VMFakeAlertStore()
        store.alerts = [
            PriceAlert(symbol: "AAPL", condition: .priceAbove(usd("200"))),
            PriceAlert(symbol: "MSFT", condition: .priceBelow(usd("100")), isTriggered: true)
        ]
        let vm = makeVM(alertStore: store)

        await vm.refresh()

        XCTAssertEqual(vm.alertCount, 1) // only the armed one counts
    }

    // MARK: - Spark values: trailing ≤30 points

    func test_sparkValues_takesAtMostTrailing30Points() async {
        let values = (0..<50).map(Double.init) // 50 points: 0...49
        let perfReport = report(totalReturn: 0.12, curveValues: values)
        let vm = makeVM(performanceReport: { perfReport })

        await vm.refresh()

        XCTAssertEqual(vm.sparkValues.count, 30)
        XCTAssertEqual(vm.sparkValues, Array((20..<50).map(Double.init))) // the trailing 30
        XCTAssertEqual(vm.totalReturnPercent, 0.12)
    }

    func test_sparkValues_fewerThan30_passesThroughAllPoints() async {
        let values: [Double] = [10, 11, 12]
        let perfReport = report(totalReturn: 0, curveValues: values)
        let vm = makeVM(performanceReport: { perfReport })

        await vm.refresh()

        XCTAssertEqual(vm.sparkValues, values)
    }
}
