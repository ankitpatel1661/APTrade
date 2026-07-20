import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class FakeSchedulerStateStore: SchedulerStateStore, @unchecked Sendable {
    private var state: SchedulerState
    private(set) var saveCount = 0
    init(_ state: SchedulerState = SchedulerState()) { self.state = state }
    func load() -> SchedulerState { state }
    func save(_ state: SchedulerState) {
        self.state = state
        saveCount += 1
    }
}

private final class FakeSettingsStore: SettingsStore, @unchecked Sendable {
    private var settings: AppSettings
    init(_ settings: AppSettings) { self.settings = settings }
    func load() -> AppSettings { settings }
    func save(_ settings: AppSettings) { self.settings = settings }
}

private final class FakeWatchlistStore: WatchlistStore, @unchecked Sendable {
    func load() -> [Asset] { [] }
    func save(_ assets: [Asset]) {}
}

/// Never actually exercised by the tests below (no test enables `newsDigest`), but the
/// coordinator's digest path still calls it every tick.
private final class FakeMarketDataRepository: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote { throw AppError.network }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

private final class RecordingNotifier: MarketEventNotifier, @unchecked Sendable {
    private(set) var marketStatusEvents: [Bool] = []
    private(set) var earningsNotifications: [(title: String, body: String)] = []
    private(set) var pieContributionNotifications: [(title: String, body: String)] = []
    private(set) var dividendNotifications: [(title: String, body: String)] = []
    func notifyMarketStatus(opened: Bool) async { marketStatusEvents.append(opened) }
    func notifyDigest(summary: String) async {}
    func notifyEarnings(title: String, body: String) async {
        earningsNotifications.append((title, body))
    }
    func notifyPieContribution(title: String, body: String) async {
        pieContributionNotifications.append((title, body))
    }
    func notifyDividend(title: String, body: String) async {
        dividendNotifications.append((title, body))
    }
}

/// Box for a value written from inside a `@Sendable` closure and read back afterward —
/// a plain captured `var` isn't allowed in a `@Sendable` closure.
private final class Box<Value>: @unchecked Sendable {
    var value: Value?
}

/// Always throws — used to prove a failing earnings fetch degrades to `[]` at the
/// closure boundary (the same `(try? await ... ) ?? []` pattern
/// `CompositionRoot.makeMarketActivityCoordinator` uses) rather than propagating.
private final class ThrowingEarningsCalendarRepository: EarningsCalendarRepository, @unchecked Sendable {
    func earnings(fromDay: String, toDay: String) async throws -> [EarningsEvent] {
        throw AppError.network
    }
}

@MainActor
final class MarketActivityCoordinatorTests: XCTestCase {
    private let marketCalendar = MarketCalendar()

    // Monday 2025-06-25, 10:00 ET — same reference instant as
    // MarketActivityPlannerTests, a known "market open" moment.
    private func et(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York")!
        return cal.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
    }

    private func event(symbol: String, session: EarningsSession, day: String) -> EarningsEvent {
        EarningsEvent(symbol: symbol, companyName: "\(symbol) Inc.", day: day,
                      session: session, epsEstimate: nil, epsActual: nil)
    }

    /// Minimal valid Pie for notification-content assertions — slices/schedule details
    /// don't matter to the coordinator, only `name` (read by the notification body).
    private func pie(name: String) -> Pie {
        try! Pie(
            name: name,
            slices: [PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 100))],
            schedule: nil,
            createdDay: "2025-06-01"
        )
    }

    private func makeCoordinator(
        state: SchedulerState,
        settings: AppSettings,
        notifier: RecordingNotifier,
        fetchOwnedEarningsToday: @escaping @Sendable (String) async -> [EarningsEvent] = { _ in [] },
        executeDueContributions: @escaping @Sendable (Date) async -> [(pie: Pie, outcomes: [ContributionOutcome])] = { _ in [] },
        processDueDividends: @escaping @Sendable (Date) async -> [DividendOutcome] = { _ in [] },
        now: @escaping () -> Date
    ) -> MarketActivityCoordinator {
        MarketActivityCoordinator(
            planner: MarketActivityPlanner(calendar: marketCalendar),
            stateStore: FakeSchedulerStateStore(state),
            loadSettings: LoadSettingsUseCase(store: FakeSettingsStore(settings)),
            notifier: notifier,
            loadWatchlist: LoadWatchlistUseCase(store: FakeWatchlistStore()),
            fetchQuotes: FetchQuotesUseCase(repository: FakeMarketDataRepository()),
            fetchOwnedEarningsToday: fetchOwnedEarningsToday,
            executeDueContributions: executeDueContributions,
            processDueDividends: processDueDividends,
            calendar: marketCalendar,
            now: now
        )
    }

    func test_earningsCheckDue_notifiesOncePerOwnedEvent() async {
        let now = et(2025, 6, 25, 10, 0) // open
        let events = [
            event(symbol: "AAPL", session: .afterClose, day: "2025-06-25"),
            event(symbol: "MSFT", session: .beforeOpen, day: "2025-06-25"),
        ]
        let requestedDay = Box<String>()
        let notifier = RecordingNotifier()
        let coordinator = makeCoordinator(
            state: SchedulerState(lastStatus: .open),
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: true),
            notifier: notifier,
            fetchOwnedEarningsToday: { day in
                requestedDay.value = day
                return events
            },
            now: { now }
        )

        await coordinator.tick()

        XCTAssertEqual(requestedDay.value, marketCalendar.tradingDay(of: now))
        XCTAssertEqual(notifier.earningsNotifications.count, 2)
        let expectedTitle = tr(.earningsTodayTitle)
        for (recorded, source) in zip(notifier.earningsNotifications, events) {
            XCTAssertEqual(recorded.title, expectedTitle)
            let expectedBody = String(format: tr(.earningsTodayBodyFmt), source.symbol, sessionLabel(source.session))
            XCTAssertEqual(recorded.body, expectedBody)
        }
    }

    func test_earningsCheckDue_withNoOwnedEvents_notifiesNothing() async {
        let now = et(2025, 6, 25, 10, 0)
        let notifier = RecordingNotifier()
        let coordinator = makeCoordinator(
            state: SchedulerState(lastStatus: .open),
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: true),
            notifier: notifier,
            fetchOwnedEarningsToday: { _ in [] },
            now: { now }
        )

        await coordinator.tick()

        XCTAssertTrue(notifier.earningsNotifications.isEmpty)
    }

    /// Regression guard mirroring the Kotlin twin's
    /// `earningsFetchFailureDropsOnlyEarningsAndTheLoopSurvives`: a failing earnings
    /// fetch must drop only this tick's earnings notifications — the same tick's other
    /// due events (here: the closed->open transition) still fire.
    func test_earningsFetchFailure_dropsOnlyEarnings_tickSurvives() async {
        let now = et(2025, 6, 25, 10, 0) // open
        let notifier = RecordingNotifier()
        let throwingRepo = ThrowingEarningsCalendarRepository()
        let fetchEarnings = FetchEarningsCalendarUseCase(repository: throwingRepo) { [] }
        let coordinator = makeCoordinator(
            state: SchedulerState(lastStatus: .closed), // seeds a marketOpened transition too
            settings: AppSettings(marketOpenClose: true, newsDigest: false, earningsReports: true),
            notifier: notifier,
            fetchOwnedEarningsToday: { day in
                // Same degrade-to-`[]` boundary CompositionRoot builds — this closure
                // type cannot throw, so any failure (including CancellationError) must
                // be swallowed right here rather than propagate and kill `tick()`.
                (try? await fetchEarnings.ownedToday(day: day)) ?? []
            },
            now: { now }
        )

        await coordinator.tick()

        XCTAssertTrue(notifier.earningsNotifications.isEmpty, "failure -> no earnings notification")
        XCTAssertEqual(notifier.marketStatusEvents, [true], "loop survived: transition still delivered")
    }

    func test_contributionCheckDue_notifiesExecutedAndSkipped() async {
        let now = et(2025, 6, 25, 10, 0) // open
        let notifier = RecordingNotifier()
        let executedPie = pie(name: "Core Growth")
        let skippedPie = pie(name: "Dividend Income")
        let portfolio = Portfolio(cash: Money(amount: 500))
        let coordinator = makeCoordinator(
            state: SchedulerState(lastStatus: .open), // no lastContributionDay yet -> due today
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: false, pieContributions: true),
            notifier: notifier,
            executeDueContributions: { _ in
                [
                    (pie: executedPie, outcomes: [.executed(portfolio, executedPie)]),
                    (pie: skippedPie, outcomes: [.skippedInsufficientCash(skippedPie)]),
                ]
            },
            now: { now }
        )

        await coordinator.tick()

        XCTAssertEqual(notifier.pieContributionNotifications.count, 2)
        XCTAssertEqual(notifier.pieContributionNotifications[0].title, tr(.notifPieExecutedTitle))
        XCTAssertEqual(notifier.pieContributionNotifications[0].body,
                       String(format: tr(.notifPieExecutedBody), "Core Growth"))
        XCTAssertEqual(notifier.pieContributionNotifications[1].title, tr(.notifPieSkippedTitle))
        XCTAssertEqual(notifier.pieContributionNotifications[1].body,
                       String(format: tr(.notifPieSkippedBody), "Dividend Income"))
    }

    func test_contributionCheckDue_disabledBySettings_notifiesNothing() async {
        let now = et(2025, 6, 25, 10, 0) // open
        let notifier = RecordingNotifier()
        let calledCount = Box<Int>()
        calledCount.value = 0
        let unexpectedPie = pie(name: "Core Growth")
        let coordinator = makeCoordinator(
            state: SchedulerState(lastStatus: .open),
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: false, pieContributions: false),
            notifier: notifier,
            executeDueContributions: { _ in
                calledCount.value = (calledCount.value ?? 0) + 1
                return [(pie: unexpectedPie, outcomes: [])]
            },
            now: { now }
        )

        await coordinator.tick()

        XCTAssertTrue(notifier.pieContributionNotifications.isEmpty)
        XCTAssertEqual(calledCount.value, 0, "the planner never emits contributionCheckDue when the toggle is off")
    }

    /// `run()`'s launch catch-up executes once before entering the tick loop, and does
    /// so independent of market status — a Pie's backlog should settle immediately on
    /// launch rather than waiting for the next market-open tick (see the coordinator's
    /// `run()` doc comment).
    func test_run_executesLaunchCatchUp_beforeFirstTick_regardlessOfMarketStatus() async {
        let now = et(2025, 6, 25, 3, 0) // market closed at this hour
        let notifier = RecordingNotifier()
        let executedPie = pie(name: "Launch Catch-Up")
        let portfolio = Portfolio(cash: Money(amount: 500))
        let coordinator = makeCoordinator(
            state: SchedulerState(), // fresh install: no seeded status yet
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: false, pieContributions: true),
            notifier: notifier,
            executeDueContributions: { _ in
                [(pie: executedPie, outcomes: [.executed(portfolio, executedPie)])]
            },
            now: { now }
        )

        let task = Task { await coordinator.run() }
        // Give the launch catch-up (an async call with no delay) a chance to complete
        // before cancelling — the tick loop itself sleeps for `interval` (default 60s)
        // after its first iteration, so cancelling promptly does not race the catch-up.
        try? await Task.sleep(for: .milliseconds(50))
        task.cancel()
        _ = await task.value

        XCTAssertEqual(notifier.pieContributionNotifications.count, 1)
        XCTAssertEqual(notifier.pieContributionNotifications[0].body,
                       String(format: tr(.notifPieExecutedBody), "Launch Catch-Up"))
    }

    // MARK: - dividendCheckDue

    func test_dividendCheckDue_notifiesPerOutcome_cashAndReinvested() async {
        let now = et(2025, 6, 25, 10, 0) // open
        let notifier = RecordingNotifier()
        let cashOutcome = DividendOutcome.credited(symbol: "AAPL", cash: Money(amount: 12.34), isBackfill: false)
        let dripOutcome = DividendOutcome.reinvested(symbol: "MSFT", cash: Money(amount: 5.67),
                                                     shares: Quantity(0.1), isBackfill: false)
        let coordinator = makeCoordinator(
            state: SchedulerState(lastStatus: .open), // no lastDividendCheckDay yet -> due today
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: false,
                                  pieContributions: false, dividendNotifications: true),
            notifier: notifier,
            processDueDividends: { _ in [cashOutcome, dripOutcome] },
            now: { now }
        )

        await coordinator.tick()

        XCTAssertEqual(notifier.dividendNotifications.count, 2)
        XCTAssertEqual(notifier.dividendNotifications[0].title, tr(.notifDividendTitle))
        XCTAssertEqual(notifier.dividendNotifications[0].body,
                       String(format: tr(.notifDividendCashBodyFmt), "AAPL", Money(amount: 12.34).formatted))
        XCTAssertEqual(notifier.dividendNotifications[1].title, tr(.notifDividendTitle))
        XCTAssertEqual(notifier.dividendNotifications[1].body,
                       String(format: tr(.notifDividendDripBodyFmt), "MSFT", Money(amount: 5.67).formatted))
        // Zero backfill outcomes among these two -> no third, summary notification.
        XCTAssertEqual(notifier.dividendNotifications.count, 2)
    }

    /// Crediting is never gated by settings — only the notification is. The closure must
    /// still be invoked (so cash is credited/DRIP reinvested) even when
    /// `dividendNotifications` is off; only the resulting notifications are suppressed.
    func test_dividendCheckDue_notificationsDisabled_closureStillInvoked_zeroNotifications() async {
        let now = et(2025, 6, 25, 10, 0) // open
        let notifier = RecordingNotifier()
        let calledCount = Box<Int>()
        calledCount.value = 0
        let coordinator = makeCoordinator(
            state: SchedulerState(lastStatus: .open),
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: false,
                                  pieContributions: false, dividendNotifications: false),
            notifier: notifier,
            processDueDividends: { _ in
                calledCount.value = (calledCount.value ?? 0) + 1
                return [.credited(symbol: "AAPL", cash: Money(amount: 12.34), isBackfill: false)]
            },
            now: { now }
        )

        await coordinator.tick()

        XCTAssertTrue(notifier.dividendNotifications.isEmpty)
        XCTAssertEqual(calledCount.value, 1, "crediting is ungated — the closure still ran")
    }

    /// Mirrors `test_run_executesLaunchCatchUp_beforeFirstTick_regardlessOfMarketStatus`:
    /// the dividend catch-up runs once at launch even when `pieContributions` is off —
    /// dividends are never settings-gated at the crediting level.
    func test_run_executesDividendCatchUp_atLaunch_evenWhenPieContributionsDisabled() async {
        let now = et(2025, 6, 25, 3, 0) // market closed at this hour
        let notifier = RecordingNotifier()
        let coordinator = makeCoordinator(
            state: SchedulerState(), // fresh install: no seeded status yet
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: false,
                                  pieContributions: false, dividendNotifications: true),
            notifier: notifier,
            processDueDividends: { _ in [.credited(symbol: "AAPL", cash: Money(amount: 9.99), isBackfill: false)] },
            now: { now }
        )

        let task = Task { await coordinator.run() }
        try? await Task.sleep(for: .milliseconds(50))
        task.cancel()
        _ = await task.value

        XCTAssertEqual(notifier.dividendNotifications.count, 1)
        XCTAssertEqual(notifier.dividendNotifications[0].body,
                       String(format: tr(.notifDividendCashBodyFmt), "AAPL", Money(amount: 9.99).formatted))
    }

    /// A first-run backfill can credit an established portfolio's entire dividend
    /// history in one pass (20-100+ events). Notifying once per outcome there would fire
    /// a notification burst at launch — so backfill outcomes must collapse into ONE
    /// summary, while any live (non-backfill) outcome in the same batch still notifies
    /// individually, exactly as before.
    func test_dividendCheckDue_mixedBackfillAndLive_collapsesBackfillIntoOneSummary() async {
        let now = et(2025, 6, 25, 10, 0) // open
        let notifier = RecordingNotifier()
        let backfill1 = DividendOutcome.credited(symbol: "AAPL", cash: Money(amount: 10), isBackfill: true)
        let backfill2 = DividendOutcome.credited(symbol: "AAPL", cash: Money(amount: 20), isBackfill: true)
        let backfill3 = DividendOutcome.reinvested(symbol: "MSFT", cash: Money(amount: 30),
                                                    shares: Quantity(0.5), isBackfill: true)
        let live = DividendOutcome.credited(symbol: "NVDA", cash: Money(amount: 5), isBackfill: false)
        let coordinator = makeCoordinator(
            state: SchedulerState(lastStatus: .open),
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: false,
                                  pieContributions: false, dividendNotifications: true),
            notifier: notifier,
            processDueDividends: { _ in [backfill1, backfill2, backfill3, live] },
            now: { now }
        )

        await coordinator.tick()

        // Exactly 2 notifications: 1 per-outcome (the live one) + 1 backfill summary.
        XCTAssertEqual(notifier.dividendNotifications.count, 2)
        XCTAssertEqual(notifier.dividendNotifications[0].title, tr(.notifDividendTitle))
        XCTAssertEqual(notifier.dividendNotifications[0].body,
                       String(format: tr(.notifDividendCashBodyFmt), "NVDA", Money(amount: 5).formatted))
        XCTAssertEqual(notifier.dividendNotifications[1].title, tr(.notifDividendTitle))
        let totalBackfillCash = Money(amount: 10) + Money(amount: 20) + Money(amount: 30)
        XCTAssertEqual(notifier.dividendNotifications[1].body,
                       String(format: tr(.notifDividendBackfillBodyFmt), "3", totalBackfillCash.formatted))
    }

    /// An all-backfill batch (the classic first-run-on-an-established-portfolio case)
    /// notifies exactly once, regardless of how many events were credited.
    func test_dividendCheckDue_allBackfillOutcomes_notifiesExactlyOneSummary() async {
        let now = et(2025, 6, 25, 10, 0) // open
        let notifier = RecordingNotifier()
        let backfill1 = DividendOutcome.credited(symbol: "AAPL", cash: Money(amount: 1), isBackfill: true)
        let backfill2 = DividendOutcome.credited(symbol: "AAPL", cash: Money(amount: 2), isBackfill: true)
        let coordinator = makeCoordinator(
            state: SchedulerState(lastStatus: .open),
            settings: AppSettings(marketOpenClose: false, newsDigest: false, earningsReports: false,
                                  pieContributions: false, dividendNotifications: true),
            notifier: notifier,
            processDueDividends: { _ in [backfill1, backfill2] },
            now: { now }
        )

        await coordinator.tick()

        XCTAssertEqual(notifier.dividendNotifications.count, 1)
        XCTAssertEqual(notifier.dividendNotifications[0].title, tr(.notifDividendTitle))
        XCTAssertEqual(notifier.dividendNotifications[0].body,
                       String(format: tr(.notifDividendBackfillBodyFmt), "2", Money(amount: 3).formatted))
    }
}
