import Foundation
import APTradeApplication
import APTradeDomain

/// Drives time-based notifications (market open/close, the daily digest, and the
/// once-per-trading-day earnings check). A light loop polls the pure
/// `MarketActivityPlanner` on a fixed cadence, persists its state, and dispatches
/// whatever the planner says is due. All policy lives in the planner; this type only
/// supplies the clock, persistence, and digest/earnings content.
@MainActor
final class MarketActivityCoordinator {
    private let planner: MarketActivityPlanner
    private let stateStore: SchedulerStateStore
    private let loadSettings: LoadSettingsUseCase
    private let notifier: MarketEventNotifier
    private let loadWatchlist: LoadWatchlistUseCase
    private let fetchQuotes: FetchQuotesUseCase
    // Non-throwing by design: a failure fetching today's owned earnings must never kill
    // this tick (let alone the `run()` loop), so the catch lives at construction time in
    // whoever builds this closure (see `CompositionRoot.makeMarketActivityCoordinator`)
    // rather than here — the type system guarantees `tick()` can't propagate it.
    private let fetchOwnedEarningsToday: @Sendable (String) async -> [EarningsEvent]
    // Non-throwing by design, exactly like `fetchOwnedEarningsToday` above:
    // `ExecuteDueContributions.callAsFunction` already degrades a failing Pie to an
    // empty outcomes list internally rather than throwing (see its doc comment), so
    // there is nothing for this closure boundary to catch — it exists purely so tests
    // can inject a fake without standing up real stores/market data.
    private let executeDueContributions: @Sendable (Date) async -> [(pie: Pie, outcomes: [ContributionOutcome])]
    // Non-throwing by design, exactly like `executeDueContributions` above:
    // `ProcessDueDividends.callAsFunction` already degrades any per-symbol failure
    // internally rather than throwing (see its doc comment), so there is nothing for
    // this closure boundary to catch — it exists purely so tests can inject a fake
    // without standing up real stores/market data.
    private let processDueDividends: @Sendable (Date) async -> [DividendOutcome]
    private let calendar: MarketCalendar
    private let interval: Duration
    private let now: () -> Date

    init(planner: MarketActivityPlanner,
         stateStore: SchedulerStateStore,
         loadSettings: LoadSettingsUseCase,
         notifier: MarketEventNotifier,
         loadWatchlist: LoadWatchlistUseCase,
         fetchQuotes: FetchQuotesUseCase,
         fetchOwnedEarningsToday: @escaping @Sendable (String) async -> [EarningsEvent],
         executeDueContributions: @escaping @Sendable (Date) async -> [(pie: Pie, outcomes: [ContributionOutcome])],
         processDueDividends: @escaping @Sendable (Date) async -> [DividendOutcome],
         calendar: MarketCalendar = MarketCalendar(),
         interval: Duration = .seconds(60),
         now: @escaping () -> Date = Date.init) {
        self.planner = planner
        self.stateStore = stateStore
        self.loadSettings = loadSettings
        self.notifier = notifier
        self.loadWatchlist = loadWatchlist
        self.fetchQuotes = fetchQuotes
        self.fetchOwnedEarningsToday = fetchOwnedEarningsToday
        self.executeDueContributions = executeDueContributions
        self.processDueDividends = processDueDividends
        self.calendar = calendar
        self.interval = interval
        self.now = now
    }

    /// Runs the launch catch-up once (so a Pie schedule that accrued due days while the
    /// app was closed settles immediately, rather than waiting for the next market-open
    /// tick — see `notifyContributionsDue`), then ticks immediately and every `interval`
    /// until the surrounding task is cancelled (SwiftUI cancels it when the hosting view
    /// disappears).
    func run() async {
        if loadSettings().pieContributions {
            await notifyContributionsDue()
        }
        // Dividend crediting is never settings-gated (see `notifyDividendsDue`'s doc
        // comment) — the catch-up always runs at launch, unlike the Pie catch-up above.
        await notifyDividendsDue()
        while !Task.isCancelled {
            await tick()
            try? await Task.sleep(for: interval)
        }
    }

    /// Internal (not `private`) so tests can drive a single tick deterministically via
    /// the injected `now`/`fetchOwnedEarningsToday` seams, rather than racing `run()`'s
    /// real sleep loop.
    func tick() async {
        let (events, newState) = planner.plan(now: now(), state: stateStore.load(), settings: loadSettings())
        stateStore.save(newState)
        for event in events {
            switch event {
            case .marketOpened: await notifier.notifyMarketStatus(opened: true)
            case .marketClosed: await notifier.notifyMarketStatus(opened: false)
            case .digestDue: await notifier.notifyDigest(summary: await digestSummary())
            case .earningsCheckDue: await notifyEarningsDue()
            case .contributionCheckDue: await notifyContributionsDue()
            case .dividendCheckDue: await notifyDividendsDue()
            }
        }
    }

    /// Notifies once per owned event reporting today. `sessionLabel(.unknown)` is ""
    /// (no L10n key for it); every language's `earningsTodayBodyFmt` ends with "· %2$@",
    /// so an empty label would leave a dangling "… · " — trim the orphaned separator in
    /// that one case only, never when a real session label is present. Mirrors the
    /// Kotlin twin's `Main.kt` `notifyEarnings` handling exactly.
    private func notifyEarningsDue() async {
        let day = calendar.tradingDay(of: now())
        let events = await fetchOwnedEarningsToday(day)
        for event in events {
            let label = sessionLabel(event.session)
            let body = String(format: tr(.earningsTodayBodyFmt), event.symbol, label)
            await notifier.notifyEarnings(
                title: tr(.earningsTodayTitle),
                body: label.isEmpty ? trimmingTrailingSeparator(body) : body
            )
        }
    }

    /// Runs due-contribution catch-up and notifies once per outcome (executed or
    /// skipped for insufficient cash), one Pie at a time. Each outcome carries its own
    /// Pie snapshot — used directly (rather than the outer per-Pie result's `pie`) so
    /// the name in the notification always matches the state the outcome actually
    /// describes. Formats the body itself, mirroring `notifyEarningsDue`'s sanctioned
    /// in-coordinator formatting.
    private func notifyContributionsDue() async {
        for (_, outcomes) in await executeDueContributions(now()) {
            for outcome in outcomes {
                switch outcome {
                case .executed(_, let pie):
                    await notifier.notifyPieContribution(
                        title: tr(.notifPieExecutedTitle),
                        body: String(format: tr(.notifPieExecutedBody), pie.name)
                    )
                case .skippedInsufficientCash(let pie):
                    await notifier.notifyPieContribution(
                        title: tr(.notifPieSkippedTitle),
                        body: String(format: tr(.notifPieSkippedBody), pie.name)
                    )
                }
            }
        }
    }

    /// Runs the dividend-crediting engine and notifies once per outcome, IF the user has
    /// `dividendNotifications` enabled. Crediting itself is ALWAYS on — this closure runs
    /// unconditionally (see `run()`'s launch catch-up and `tick()`'s `.dividendCheckDue`
    /// case) because a dividend payout is bookkeeping truth (cash owed to the user), not
    /// an optional notification; only the notification below is settings-gated.
    private func notifyDividendsDue() async {
        let outcomes = await processDueDividends(now())
        guard loadSettings().dividendNotifications else { return }
        for outcome in outcomes {
            switch outcome {
            case .credited(let symbol, let cash):
                await notifier.notifyDividend(
                    title: tr(.notifDividendTitle),
                    body: String(format: tr(.notifDividendCashBodyFmt), symbol, cash.formatted)
                )
            case .reinvested(let symbol, let cash, _):
                await notifier.notifyDividend(
                    title: tr(.notifDividendTitle),
                    body: String(format: tr(.notifDividendDripBodyFmt), symbol, cash.formatted)
                )
            }
        }
    }

    /// Builds the digest body from the watchlist's biggest movers today.
    private func digestSummary() async -> String {
        let symbols = loadWatchlist().map { $0.symbol }
        guard !symbols.isEmpty else { return tr(.digestNoSymbols) }
        let results = await fetchQuotes(symbols: symbols)
        let quotes = results.values.compactMap { result -> Quote? in
            if case .success(let q) = result { return q }
            return nil
        }
        guard !quotes.isEmpty else { return tr(.digestUpdating) }

        let movers = quotes
            .sorted { abs($0.changePercent.value) > abs($1.changePercent.value) }
            .prefix(3)
            .map { "\($0.symbol) \($0.changePercent.formatted)" }
            .joined(separator: ", ")
        return String(format: tr(.digestMoversFormat), movers)
    }
}

/// Trims trailing spaces and middle-dot separators only — never leading — so a formatted
/// string ending in "… · " (an empty session label) collapses to "…" while a real label
/// (e.g. "… · After close") is left untouched. Swift twin of the Kotlin
/// `body.trimEnd(' ', '·')` call in `Main.kt`.
private func trimmingTrailingSeparator(_ text: String) -> String {
    var result = Substring(text)
    while let last = result.last, last == " " || last == "·" {
        result.removeLast()
    }
    return String(result)
}
