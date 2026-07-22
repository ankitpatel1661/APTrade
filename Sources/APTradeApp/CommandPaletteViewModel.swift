import Foundation
import APTradeApplication
import APTradeDomain

/// Where a `.navigate` palette result sends the user. Kept local to the palette so this
/// file never has to import or reach into `RootView`'s private `Tab` type.
enum PaletteDestination: Equatable {
    case home, markets, portfolio, invest
}

/// One row the command palette can show: a static navigation shortcut, or a search result.
enum PaletteResult: Identifiable, Equatable {
    case navigate(label: String, icon: String, destination: PaletteDestination)
    case asset(Asset)

    var id: String {
        switch self {
        case .navigate(let label, _, _): return "nav-\(label)"
        case .asset(let asset): return "asset-\(asset.symbol)"
        }
    }
}

/// Drives the ⌘K command palette: combines two static navigation shortcuts with a
/// debounced asset search, and tracks which row is keyboard-selected. No business logic —
/// only orchestrates the existing `SearchAssetsUseCase`.
@MainActor
@Observable
final class CommandPaletteViewModel {
    private let searchAssets: SearchAssetsUseCase
    private var searchTask: Task<Void, Never>?

    /// Computed (not cached) so the labels re-translate live when the user switches
    /// language while the palette is open.
    private static var staticResults: [PaletteResult] {
        [
            .navigate(label: tr(.homeTab), icon: "house.fill", destination: .home),
            .navigate(label: tr(.marketsTab), icon: "chart.line.uptrend.xyaxis", destination: .markets),
            .navigate(label: tr(.portfolio), icon: "chart.pie", destination: .portfolio),
            .navigate(label: tr(.investTab), icon: "basket.fill", destination: .invest)
        ]
    }

    var query: String = ""
    private(set) var results: [PaletteResult] = []
    private(set) var selectedIndex: Int = 0

    init(searchAssets: SearchAssetsUseCase) {
        self.searchAssets = searchAssets
        self.results = Self.staticResults
    }

    /// Empty query shows the static shortcuts immediately. A non-empty query debounces
    /// 250ms, then combines shortcuts whose label matches with live asset search results.
    /// Cancels any in-flight search on every keystroke. Best-effort: a failing search
    /// resolves to just the matching shortcuts, never a crash.
    func updateQuery(_ text: String) {
        query = text
        searchTask?.cancel()
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            results = Self.staticResults
            selectedIndex = 0
            return
        }
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self else { return }
            let navMatches = Self.staticResults.filter { result in
                guard case .navigate(let label, _, _) = result else { return false }
                return label.localizedCaseInsensitiveContains(trimmed)
            }
            let assets = (try? await self.searchAssets(query: trimmed)) ?? []
            guard !Task.isCancelled else { return }
            self.results = navMatches + assets.map(PaletteResult.asset)
            self.selectedIndex = 0
        }
    }

    /// Moves the keyboard selection by `delta`, clamped to the result list (no wraparound).
    /// No-op on an empty list.
    func moveSelection(_ delta: Int) {
        guard !results.isEmpty else { return }
        selectedIndex = max(0, min(results.count - 1, selectedIndex + delta))
    }

    /// The currently keyboard-selected result, or `nil` if the list is empty.
    func activateSelected() -> PaletteResult? {
        results.indices.contains(selectedIndex) ? results[selectedIndex] : nil
    }

    /// Clears all state back to the just-opened default (the static shortcuts), so the
    /// next time the palette opens it doesn't show a stale previous search.
    func reset() {
        searchTask?.cancel()
        query = ""
        results = Self.staticResults
        selectedIndex = 0
    }
}
