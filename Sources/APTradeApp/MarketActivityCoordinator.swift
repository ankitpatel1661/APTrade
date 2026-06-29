import Foundation
import APTradeApplication
import APTradeDomain

/// Drives time-based notifications (market open/close and the daily digest). A light
/// loop polls the pure `MarketActivityPlanner` on a fixed cadence, persists its state,
/// and dispatches whatever the planner says is due. All policy lives in the planner;
/// this type only supplies the clock, persistence, and digest content.
@MainActor
final class MarketActivityCoordinator {
    private let planner: MarketActivityPlanner
    private let stateStore: SchedulerStateStore
    private let loadSettings: LoadSettingsUseCase
    private let notifier: MarketEventNotifier
    private let loadWatchlist: LoadWatchlistUseCase
    private let fetchQuotes: FetchQuotesUseCase
    private let interval: Duration

    init(planner: MarketActivityPlanner,
         stateStore: SchedulerStateStore,
         loadSettings: LoadSettingsUseCase,
         notifier: MarketEventNotifier,
         loadWatchlist: LoadWatchlistUseCase,
         fetchQuotes: FetchQuotesUseCase,
         interval: Duration = .seconds(60)) {
        self.planner = planner
        self.stateStore = stateStore
        self.loadSettings = loadSettings
        self.notifier = notifier
        self.loadWatchlist = loadWatchlist
        self.fetchQuotes = fetchQuotes
        self.interval = interval
    }

    /// Ticks immediately, then every `interval` until the surrounding task is cancelled
    /// (SwiftUI cancels it when the hosting view disappears).
    func run() async {
        while !Task.isCancelled {
            await tick()
            try? await Task.sleep(for: interval)
        }
    }

    private func tick() async {
        let (events, newState) = planner.plan(now: Date(), state: stateStore.load(), settings: loadSettings())
        stateStore.save(newState)
        for event in events {
            switch event {
            case .marketOpened: await notifier.notifyMarketStatus(opened: true)
            case .marketClosed: await notifier.notifyMarketStatus(opened: false)
            case .digestDue: await notifier.notifyDigest(summary: await digestSummary())
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
