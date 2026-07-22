import Foundation
import APTradeApplication
import APTradeDomain

/// One row the command palette can show — a search result. (Prior to M10.1 UAT U7 this
/// also carried `.navigate` rows for Home/Markets/Portfolio/Invest; removed as redundant
/// with the sidebar/tabs. Kept as a single-case enum, rather than collapsing to a bare
/// `Asset`, so `CommandPaletteView`'s rendering switch and `RootView.handlePaletteSelection`
/// need no structural changes beyond dropping the `.navigate` arm.)
enum PaletteResult: Identifiable, Equatable {
    case asset(Asset)

    var id: String {
        switch self {
        case .asset(let asset): return "asset-\(asset.symbol)"
        }
    }
}

/// Drives the ⌘K command palette: a debounced asset search, tracking which row is
/// keyboard-selected. No business logic — only orchestrates the existing
/// `SearchAssetsUseCase`. Symbol-search only (M10.1 UAT U7) — the palette no longer offers
/// static "Go to Home/Markets/Portfolio/Invest" navigation shortcuts, which duplicated the
/// sidebar (macOS) / tab bar (iOS).
@MainActor
@Observable
final class CommandPaletteViewModel {
    private let searchAssets: SearchAssetsUseCase
    private var searchTask: Task<Void, Never>?

    var query: String = ""
    private(set) var results: [PaletteResult] = []
    private(set) var selectedIndex: Int = 0

    init(searchAssets: SearchAssetsUseCase) {
        self.searchAssets = searchAssets
    }

    /// Empty query shows no results — there are no static shortcuts to fall back to
    /// anymore. A non-empty query debounces 250ms, then resolves to live asset search
    /// results. Cancels any in-flight search on every keystroke. Best-effort: a failing
    /// search resolves to no results, never a crash.
    func updateQuery(_ text: String) {
        query = text
        searchTask?.cancel()
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            results = []
            selectedIndex = 0
            return
        }
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self else { return }
            let assets = (try? await self.searchAssets(query: trimmed)) ?? []
            guard !Task.isCancelled else { return }
            self.results = assets.map(PaletteResult.asset)
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
        results = []
        selectedIndex = 0
    }
}
