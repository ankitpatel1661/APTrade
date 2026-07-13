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
    func notifyMarketStatus(opened: Bool) async { marketStatusEvents.append(opened) }
    func notifyDigest(summary: String) async {}
    func notifyEarnings(title: String, body: String) async {
        earningsNotifications.append((title, body))
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

    private func makeCoordinator(
        state: SchedulerState,
        settings: AppSettings,
        notifier: RecordingNotifier,
        fetchOwnedEarningsToday: @escaping @Sendable (String) async -> [EarningsEvent],
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
}
