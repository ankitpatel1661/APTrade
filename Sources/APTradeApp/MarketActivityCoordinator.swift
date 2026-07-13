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
        self.calendar = calendar
        self.interval = interval
        self.now = now
    }

    /// Ticks immediately, then every `interval` until the surrounding task is cancelled
    /// (SwiftUI cancels it when the hosting view disappears).
    func run() async {
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
