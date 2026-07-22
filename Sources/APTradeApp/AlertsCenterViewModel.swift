import Foundation
import APTradeApplication
import APTradeDomain

/// One symbol's alerts, grouped for the Alerts center list.
struct AlertsCenterGroup: Identifiable, Equatable {
    let symbol: String
    let alerts: [PriceAlert]
    var id: String { symbol }
}

/// Drives the Alerts center: ALL price alerts across every symbol, grouped, removable, and
/// resolvable back to a full `Asset` for tap-through — reached from Home's bell (Task 5).
///
/// Reuses the SAME load/remove paths `WatchlistViewModel` and `PriceAlertSheet` already use
/// (`LoadAlertsUseCase` / `RemovePriceAlertUseCase` over the shared `AlertStore`); this view
/// model never talks to the store directly. `loadAlerts` is typed as a throwing closure —
/// mirroring `HomeViewModel`'s `loadPortfolio` — purely so tests can inject a failing double
/// to exercise graceful degradation; the concrete use case never actually throws.
@MainActor
@Observable
final class AlertsCenterViewModel {
    private let loadAlerts: () throws -> [PriceAlert]
    private let removeAlert: (PriceAlert.ID) -> [PriceAlert]
    /// Resolves a full `Asset` (name + kind) for tap-through — the same watchlist load
    /// `WatchlistViewModel` reads, since alerts today are only ever created from a
    /// watchlist row's bell.
    private let loadWatchlist: () -> [Asset]

    private(set) var groups: [AlertsCenterGroup] = []

    /// True once loaded and there are no alerts at all, for the empty-state view.
    var isEmpty: Bool { groups.isEmpty }

    init(loadAlerts: @escaping () throws -> [PriceAlert],
         removeAlert: @escaping (PriceAlert.ID) -> [PriceAlert],
         loadWatchlist: @escaping () -> [Asset]) {
        self.loadAlerts = loadAlerts
        self.removeAlert = removeAlert
        self.loadWatchlist = loadWatchlist
        load()
    }

    /// Loads every alert and regroups by symbol. A load failure degrades gracefully to an
    /// empty list rather than crashing or leaving stale state.
    func load() {
        let alerts = (try? loadAlerts()) ?? []
        rebuild(from: alerts)
    }

    /// Removes one alert via the injected (persisting) path and updates state from its
    /// returned, already-saved list — mirrors `WatchlistViewModel.deleteAlert`.
    func remove(_ id: PriceAlert.ID) {
        rebuild(from: removeAlert(id))
    }

    /// Symbols sorted alphabetically; alerts within a symbol kept in stored order.
    private func rebuild(from alerts: [PriceAlert]) {
        let bySymbol = Dictionary(grouping: alerts, by: \.symbol)
        groups = bySymbol.keys.sorted().map { symbol in
            AlertsCenterGroup(symbol: symbol, alerts: bySymbol[symbol] ?? [])
        }
    }

    /// Full `Asset` for tap-through to `AssetDetailView`. Looked up in the watchlist; falls
    /// back to a minimal placeholder (name = symbol, kind = `.stock`) if the symbol isn't
    /// — or is no longer — on the watchlist, so navigation never fails outright.
    func asset(for symbol: String) -> Asset {
        loadWatchlist().first { $0.symbol == symbol } ?? Asset(symbol: symbol, name: symbol, kind: .stock)
    }
}
