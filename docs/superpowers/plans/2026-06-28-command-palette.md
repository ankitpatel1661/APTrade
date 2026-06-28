# Command Palette (⌘K) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a ⌘K command palette to APTrade: fuzzy-search any asset and jump to its detail view, plus two static navigation shortcuts ("Go to Watchlist", "Go to Portfolio").

**Architecture:** Pure presentation feature — no Domain or Application changes. A new `CommandPaletteViewModel` combines two static nav results with a debounced call to the existing `SearchAssetsUseCase`. A new `CommandPaletteView` renders the overlay (dimmed backdrop + top-anchored floating panel) and handles keyboard navigation via `.onKeyPress`. `RootView` owns the open/closed state and an isolated `.sheet`-presented `AssetDetailView` for asset selections — no changes to `WatchlistView`/`PortfolioView`.

**Tech Stack:** Swift, SwiftUI (`.onKeyPress`, macOS 14+ — already this project's deployment target), XCTest.

## Global Constraints

- No new Domain or Application code — this feature only orchestrates the existing `SearchAssetsUseCase` (`Sources/APTradeApplication/MarketUseCases.swift:57`).
- No force-unwraps. No business logic in views.
- `swift test` requires `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` or XCTest is missing. `swift build` needs no override.
- Selecting an asset must **not** add it to the watchlist (that's the existing, different `addSuggestion` flow) — it only navigates.
- `WatchlistView.swift` and `PortfolioView.swift` are **not modified** by this plan — the palette's asset navigation is fully isolated via its own `.sheet`.
- Debounce: 250ms, matching `WatchlistViewModel.updateQuery`'s existing convention (`Sources/APTradeApp/WatchlistViewModel.swift`).
- Test style: per-file private fakes (`final class XFakeRepo: MarketDataRepository, @unchecked Sendable`), `@MainActor` on view-model test classes — matches `Tests/APTradeAppTests/WatchlistViewModelTests.swift`.

---

### Task 1: PaletteResult, PaletteDestination, CommandPaletteViewModel

**Files:**
- Create: `Sources/APTradeApp/CommandPaletteViewModel.swift`
- Test: `Tests/APTradeAppTests/CommandPaletteViewModelTests.swift`

**Interfaces:**
- Consumes: `SearchAssetsUseCase(repository: MarketDataRepository)` — existing, `Sources/APTradeApplication/MarketUseCases.swift:57`, `callAsFunction(query: String) async throws -> [Asset]`. `Asset { symbol: String, name: String, kind: AssetKind }`, `AssetKind { .stock, .etf, .crypto }` — existing, `Sources/APTradeDomain/Asset.swift`.
- Produces:
  - `enum PaletteDestination: Equatable { case watchlist, portfolio }`
  - `enum PaletteResult: Identifiable, Equatable { case navigate(label: String, icon: String, destination: PaletteDestination); case asset(Asset) }`, `var id: String`
  - `@MainActor @Observable final class CommandPaletteViewModel`: `init(searchAssets: SearchAssetsUseCase)`; `var query: String`; `private(set) var results: [PaletteResult]`; `private(set) var selectedIndex: Int`; `func updateQuery(_ text: String)`; `func moveSelection(_ delta: Int)`; `func activateSelected() -> PaletteResult?`; `func reset()`

- [ ] **Step 1: Write the failing test**

Create `Tests/APTradeAppTests/CommandPaletteViewModelTests.swift`:

```swift
import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class PaletteFakeRepo: MarketDataRepository, @unchecked Sendable {
    var searchResults: [Asset] = []
    var searchError: Error?

    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
    func search(query: String) async throws -> [Asset] {
        if let searchError { throw searchError }
        return searchResults
    }
}

private let goWatchlist = PaletteResult.navigate(label: "Go to Watchlist", icon: "list.bullet", destination: .watchlist)
private let goPortfolio = PaletteResult.navigate(label: "Go to Portfolio", icon: "chart.pie", destination: .portfolio)

@MainActor
final class CommandPaletteViewModelTests: XCTestCase {
    private func makeVM(_ repo: PaletteFakeRepo) -> CommandPaletteViewModel {
        CommandPaletteViewModel(searchAssets: SearchAssetsUseCase(repository: repo))
    }

    func test_emptyQuery_showsStaticNavResults() {
        let vm = makeVM(PaletteFakeRepo())
        XCTAssertEqual(vm.results, [goWatchlist, goPortfolio])
    }

    func test_updateQuery_matchingNavLabel_includesIt() async throws {
        let vm = makeVM(PaletteFakeRepo())
        vm.updateQuery("watch")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(vm.results, [goWatchlist])
    }

    func test_updateQuery_includesSearchResults() async throws {
        let repo = PaletteFakeRepo()
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        repo.searchResults = [aapl]
        let vm = makeVM(repo)
        vm.updateQuery("aapl")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(vm.results, [.asset(aapl)])
    }

    func test_updateQuery_searchFailure_resolvesToNavMatchesOnly() async throws {
        let repo = PaletteFakeRepo()
        repo.searchError = AppError.network
        let vm = makeVM(repo)
        vm.updateQuery("portfolio")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(vm.results, [goPortfolio])
    }

    func test_moveSelection_clampsAtBothEnds() {
        let vm = makeVM(PaletteFakeRepo())
        // Static results give two rows: indices 0...1.
        vm.moveSelection(-5)
        XCTAssertEqual(vm.selectedIndex, 0)
        vm.moveSelection(1)
        XCTAssertEqual(vm.selectedIndex, 1)
        vm.moveSelection(5)
        XCTAssertEqual(vm.selectedIndex, 1)
    }

    func test_moveSelection_onEmptyResults_doesNothing() async throws {
        let vm = makeVM(PaletteFakeRepo())
        vm.updateQuery("nomatch-xyz")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertTrue(vm.results.isEmpty)
        vm.moveSelection(1)
        XCTAssertEqual(vm.selectedIndex, 0)
    }

    func test_activateSelected_returnsSelectedResult_orNilWhenEmpty() async throws {
        let vm = makeVM(PaletteFakeRepo())
        XCTAssertEqual(vm.activateSelected(), goWatchlist)
        vm.updateQuery("nomatch-xyz")
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertNil(vm.activateSelected())
    }

    func test_reset_clearsQueryResultsAndSelection() async throws {
        let repo = PaletteFakeRepo()
        repo.searchResults = [Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)]
        let vm = makeVM(repo)
        vm.updateQuery("aapl")
        try await Task.sleep(for: .milliseconds(300))
        vm.moveSelection(1)
        vm.reset()
        XCTAssertEqual(vm.query, "")
        XCTAssertEqual(vm.selectedIndex, 0)
        XCTAssertEqual(vm.results, [goWatchlist, goPortfolio])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter CommandPaletteViewModelTests`
Expected: FAIL — `cannot find 'CommandPaletteViewModel' in scope`.

- [ ] **Step 3: Write minimal implementation**

Create `Sources/APTradeApp/CommandPaletteViewModel.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

/// Where a `.navigate` palette result sends the user. Kept local to the palette so this
/// file never has to import or reach into `RootView`'s private `Tab` type.
enum PaletteDestination: Equatable {
    case watchlist, portfolio
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

    private static let staticResults: [PaletteResult] = [
        .navigate(label: "Go to Watchlist", icon: "list.bullet", destination: .watchlist),
        .navigate(label: "Go to Portfolio", icon: "chart.pie", destination: .portfolio)
    ]

    var query: String = ""
    private(set) var results: [PaletteResult] = CommandPaletteViewModel.staticResults
    private(set) var selectedIndex: Int = 0

    init(searchAssets: SearchAssetsUseCase) {
        self.searchAssets = searchAssets
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter CommandPaletteViewModelTests`
Expected: PASS (8 tests).

- [ ] **Step 5: Run the full suite to confirm no regressions**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: all tests pass (142 prior + 8 new = 150).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeApp/CommandPaletteViewModel.swift Tests/APTradeAppTests/CommandPaletteViewModelTests.swift
git commit -m "feat(palette): CommandPaletteViewModel — static shortcuts + debounced asset search"
```

---

### Task 2: CommandPaletteView (overlay UI)

A self-contained SwiftUI component: dimmed backdrop, top-anchored floating panel, search field, keyboard-navigable result list. No logic of its own beyond rendering `viewModel` and forwarding interaction to the two closures it's given — not independently unit-testable, verified by a clean build.

**Files:**
- Create: `Sources/APTradeApp/CommandPaletteView.swift`

**Interfaces:**
- Consumes: `CommandPaletteViewModel`, `PaletteResult`, `PaletteDestination` (Task 1). `Theme.surface`, `Theme.surfaceHi`, `Theme.hairline`, `Theme.gold`, `Theme.textPrimary`, `Theme.textSecondary` — existing, `Sources/APTradeApp/Theme.swift`. `AssetKind` — existing, `Sources/APTradeDomain/Asset.swift`.
- Produces: `struct CommandPaletteView: View { init(viewModel: CommandPaletteViewModel, onSelect: @escaping (PaletteResult) -> Void, onClose: @escaping () -> Void) }` — consumed by Task 3's `RootView` wiring.

- [ ] **Step 1: Create the view**

Create `Sources/APTradeApp/CommandPaletteView.swift`:

```swift
import SwiftUI
import APTradeDomain

/// The ⌘K command palette: a top-anchored floating panel over a dimmed backdrop, with a
/// search field and a keyboard-navigable result list. Presentation-only — all matching and
/// selection state live in `viewModel`; this view only renders it and forwards interaction.
struct CommandPaletteView: View {
    @Bindable var viewModel: CommandPaletteViewModel
    @FocusState private var fieldFocused: Bool
    let onSelect: (PaletteResult) -> Void
    let onClose: () -> Void

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .onTapGesture { onClose() }

            panel
                .padding(.top, 80)
        }
        .onAppear { fieldFocused = true }
    }

    private var panel: some View {
        VStack(spacing: 0) {
            searchField
            if !viewModel.results.isEmpty {
                Divider().overlay(Theme.hairline)
                resultList
            } else {
                Text("No matches")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.vertical, 16)
            }
        }
        .frame(width: 520)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
        .shadow(color: .black.opacity(0.35), radius: 24, y: 12)
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Theme.textSecondary)
            TextField("Search assets or jump to a tab…", text: $viewModel.query)
                .textFieldStyle(.plain)
                .font(.system(size: 16))
                .foregroundStyle(Theme.textPrimary)
                .focused($fieldFocused)
                .onChange(of: viewModel.query) { _, text in viewModel.updateQuery(text) }
                .onKeyPress(.upArrow) { viewModel.moveSelection(-1); return .handled }
                .onKeyPress(.downArrow) { viewModel.moveSelection(1); return .handled }
                .onKeyPress(.return) {
                    if let selected = viewModel.activateSelected() { onSelect(selected) }
                    return .handled
                }
                .onKeyPress(.escape) { onClose(); return .handled }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    private var resultList: some View {
        ScrollView {
            VStack(spacing: 2) {
                ForEach(Array(viewModel.results.enumerated()), id: \.element.id) { index, result in
                    resultRow(result, isSelected: index == viewModel.selectedIndex)
                        .onTapGesture { onSelect(result) }
                }
            }
            .padding(8)
        }
        .frame(maxHeight: 320)
    }

    @ViewBuilder
    private func resultRow(_ result: PaletteResult, isSelected: Bool) -> some View {
        HStack(spacing: 12) {
            switch result {
            case .navigate(let label, let icon, _):
                Image(systemName: icon)
                    .foregroundStyle(Theme.gold)
                    .frame(width: 20)
                Text(label)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
            case .asset(let asset):
                VStack(alignment: .leading, spacing: 2) {
                    Text(asset.name)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                    Text(asset.symbol)
                        .font(.system(size: 11, weight: .medium).monospacedDigit())
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Text(kindLabel(asset.kind))
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isSelected ? Theme.surfaceHi : .clear, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .contentShape(Rectangle())
    }

    private func kindLabel(_ kind: AssetKind) -> String {
        switch kind {
        case .stock: return "STOCK"
        case .etf: return "ETF"
        case .crypto: return "CRYPTO"
        }
    }
}
```

> Note: if `.onKeyPress(.upArrow) { ... }` (the zero-named-parameter closure form) reports an ambiguity or type error when compiled, change each to the explicit single-parameter form, e.g. `.onKeyPress(.upArrow) { _ in viewModel.moveSelection(-1); return .handled }` — same behavior, just naming the unused `KeyPress` parameter explicitly.

- [ ] **Step 2: Build to verify it compiles**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build`
Expected: builds with no errors. (`CommandPaletteView` has no call site yet — Task 3 adds one — so an "unused" warning is not expected since it's a public-enough `struct` referenced by nothing yet; this is fine, Swift does not warn on unused internal types.)

- [ ] **Step 3: Commit**

```bash
git add Sources/APTradeApp/CommandPaletteView.swift
git commit -m "feat(palette): CommandPaletteView — dimmed backdrop, search field, keyboard-navigable results"
```

---

### Task 3: Wire into CompositionRoot + RootView

Adds the DI factory, the ⌘K shortcut, the visible header button, the overlay rendering, and the isolated `.sheet`-presented asset navigation. Verified by build + manual click-through (no new automated tests — this is UI wiring with no new logic, consistent with how the previous feature's final UI task was verified).

**Files:**
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (add `makeCommandPaletteViewModel()`)
- Modify: `Sources/APTradeApp/RootView.swift:1-94` (add state, shortcut, header button, overlay, sheet, and two handler functions)

**Interfaces:**
- Consumes: `CommandPaletteViewModel`, `CommandPaletteView`, `PaletteResult`, `PaletteDestination` (Tasks 1–2); existing `CompositionRoot.makeRepository()`, `SearchAssetsUseCase`; existing `RootView.Tab`, `Theme.gold`, `Theme.surface`.
- Produces: `CompositionRoot.makeCommandPaletteViewModel() -> CommandPaletteViewModel`.

- [ ] **Step 1: Add the DI factory**

In `Sources/APTradeApp/CompositionRoot.swift`, add alongside the other `make...ViewModel` factories (after `makePerformanceViewModel()`):

```swift
static func makeCommandPaletteViewModel() -> CommandPaletteViewModel {
    CommandPaletteViewModel(searchAssets: SearchAssetsUseCase(repository: makeRepository()))
}
```

- [ ] **Step 2: Add state to RootView**

In `Sources/APTradeApp/RootView.swift`, add alongside the other `@State` declarations near the top of `RootView` (after `@State private var exportError: String?`):

```swift
@State private var showPalette = false
@State private var paletteVM = CompositionRoot.makeCommandPaletteViewModel()
@State private var paletteAsset: Asset?
```

- [ ] **Step 3: Add the always-live ⌘K shortcut**

In `Sources/APTradeApp/RootView.swift`, the outer `ZStack` currently opens like this (lines 28–30):

```swift
            ZStack(alignment: .trailing) {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 0) {
```

Insert a hidden shortcut button between `Theme.background.ignoresSafeArea()` and the `VStack` — placing it here (a direct sibling of the `VStack`, not inside it) keeps it live even when the account panel's `.allowsHitTesting(!showAccountPanel)` (applied to the `VStack`) would otherwise block it:

```swift
            ZStack(alignment: .trailing) {
                Theme.background.ignoresSafeArea()
                Button("") { showPalette = true }
                    .keyboardShortcut("k", modifiers: .command)
                    .frame(width: 0, height: 0)
                    .opacity(0)
                VStack(spacing: 0) {
```

- [ ] **Step 4: Add the visible header button**

In `Sources/APTradeApp/RootView.swift`, the header currently reads (lines 39–42):

```swift
                        HStack(spacing: 10) {
                            themeToggleButton
                            accountMenuButton
                        }
```

Add `paletteButton` before `themeToggleButton`:

```swift
                        HStack(spacing: 10) {
                            paletteButton
                            themeToggleButton
                            accountMenuButton
                        }
```

Then add the `paletteButton` computed property near `themeToggleButton`'s definition (find `private var themeToggleButton: some View` in the file and add this immediately before it):

```swift
    private var paletteButton: some View {
        Button {
            showPalette = true
        } label: {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.gold)
                .frame(width: 28, height: 28)
                .background(Theme.surface, in: Circle())
                .overlay(Circle().stroke(Theme.gold.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

```

- [ ] **Step 5: Render the palette overlay**

In `Sources/APTradeApp/RootView.swift`, the account-panel overlay currently closes like this (lines 57–71):

```swift
                if showAccountPanel {
                    Color.black.opacity(0.45)
                        .ignoresSafeArea()
                        .transition(.opacity)
                        .onTapGesture { close() }

                    accountPanel
                        .frame(width: max(geo.size.width * 0.25, 260))
                        .frame(maxHeight: .infinity)
                        .background(Theme.surface)
                        .overlay(Rectangle().frame(width: 1).foregroundStyle(Theme.hairline), alignment: .leading)
                        .ignoresSafeArea()
                        .transition(.move(edge: .trailing))
                }
            }
```

Add the palette overlay as a new sibling block right after the `if showAccountPanel { ... }` block closes, still inside the outer `ZStack`:

```swift
                if showAccountPanel {
                    Color.black.opacity(0.45)
                        .ignoresSafeArea()
                        .transition(.opacity)
                        .onTapGesture { close() }

                    accountPanel
                        .frame(width: max(geo.size.width * 0.25, 260))
                        .frame(maxHeight: .infinity)
                        .background(Theme.surface)
                        .overlay(Rectangle().frame(width: 1).foregroundStyle(Theme.hairline), alignment: .leading)
                        .ignoresSafeArea()
                        .transition(.move(edge: .trailing))
                }

                if showPalette {
                    CommandPaletteView(
                        viewModel: paletteVM,
                        onSelect: { handlePaletteSelection($0) },
                        onClose: { closePalette() }
                    )
                }
            }
```

- [ ] **Step 6: Add the selection/close handlers**

In `Sources/APTradeApp/RootView.swift`, find the existing `private func close() { ... }` function and add these two new functions immediately after it:

```swift
    private func handlePaletteSelection(_ result: PaletteResult) {
        switch result {
        case .navigate(_, _, let destination):
            switch destination {
            case .watchlist: tab = .watchlist
            case .portfolio: tab = .portfolio
            }
        case .asset(let asset):
            paletteAsset = asset
        }
        closePalette()
    }

    private func closePalette() {
        showPalette = false
        paletteVM.reset()
    }
```

- [ ] **Step 7: Present the asset detail sheet**

In `Sources/APTradeApp/RootView.swift`, the body's modifier chain currently ends like this (lines 86–94):

```swift
        .alert("Export Failed", isPresented: Binding(
            get: { exportError != nil },
            set: { if !$0 { exportError = nil } }
        )) {
            Button("OK", role: .cancel) { exportError = nil }
        } message: {
            Text(exportError ?? "")
        }
    }
```

Add a new `.sheet(item:)` modifier right after the `.alert(...)` block, still before the closing `}` of `body`:

```swift
        .alert("Export Failed", isPresented: Binding(
            get: { exportError != nil },
            set: { if !$0 { exportError = nil } }
        )) {
            Button("OK", role: .cancel) { exportError = nil }
        } message: {
            Text(exportError ?? "")
        }
        .sheet(item: $paletteAsset) { asset in
            NavigationStack {
                AssetDetailView(asset: asset)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") { paletteAsset = nil }
                        }
                    }
            }
        }
    }
```

- [ ] **Step 8: Build and run the full test suite**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build`
Expected: builds with no errors.

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: full suite passes, same 150 tests as after Task 1 (this task adds no new tests).

- [ ] **Step 9: Manual UI verification**

Launch the app (`"$(swift build --show-bin-path)/APTradeApp"`). Verify:
- Pressing ⌘K from either the Watchlist or Portfolio tab opens the palette.
- Clicking the new search-icon button in the header (next to the theme toggle) also opens it.
- With an empty query, the palette shows "Go to Watchlist" and "Go to Portfolio".
- Typing a partial match of either label (e.g. "watch") filters the list to just that shortcut.
- Typing a ticker or company name (e.g. "AAPL" or "Apple") shows matching asset results after a brief pause.
- ↑/↓ arrow keys move the highlighted row; Return activates the highlighted row; clicking a row activates it directly.
- Selecting a nav shortcut switches to the corresponding tab and closes the palette.
- Selecting an asset result closes the palette and opens that asset's detail view in a sheet, with a working "Done" button; the underlying tab is unchanged.
- Escape, and clicking the dimmed backdrop, both close the palette without selecting anything.
- Re-opening the palette after a previous search shows the fresh empty-query shortcuts, not the stale previous search.

- [ ] **Step 10: Commit**

```bash
git add Sources/APTradeApp/CompositionRoot.swift Sources/APTradeApp/RootView.swift
git commit -m "feat(palette): wire ⌘K, header button, and isolated asset-detail sheet into RootView"
```

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task |
|---|---|
| Fuzzy-search assets via existing `SearchAssetsUseCase` | Task 1 |
| Selecting an asset navigates, no watchlist side effect | Task 1 (no `add`/`addSuggestion` call) + Task 3 (`.sheet`, not the watchlist's add flow) |
| Two static nav shortcuts | Task 1 (`staticResults`) |
| ⌘K invocation | Task 3 Step 3 |
| Visible header button | Task 3 Step 4 |
| Empty query → static shortcuts; typing → fuzzy results | Task 1 (`updateQuery`) |
| ↑/↓/Return/Escape keyboard interaction | Task 2 |
| Isolated modal navigation (no `WatchlistView`/`PortfolioView` changes) | Task 3 Step 7 — confirmed neither file appears in any task's file list |
| Stale-results-while-debouncing avoided (no flicker) | Task 1 (`results` only overwritten once the debounced fetch resolves) |
| Reset on close | Task 1 (`reset()`) + Task 3 (`closePalette()` calls it) |
| Tests at the logic-bearing layer | Task 1 ships 8 tests; Tasks 2–3 are presentation glue, verified by build + manual click-through |

No gaps.

**2. Placeholder scan:** No TBD/TODO/"handle edge cases" — every code step is complete. The one "Note:" callout (Task 2) flags a possible compiler-overload ambiguity with a concrete fallback fix, not missing content.

**3. Type consistency:** `PaletteResult`, `PaletteDestination`, and `CommandPaletteViewModel`'s method signatures (`updateQuery(_:)`, `moveSelection(_:)`, `activateSelected()`, `reset()`) match exactly across the task that defines them (Task 1) and the tasks that consume them (Task 2's view, Task 3's `handlePaletteSelection`). `CompositionRoot.makeCommandPaletteViewModel()` return type matches `CommandPaletteViewModel`'s real initializer.

## Out-of-scope (tracked in spec, not built here)

Action commands (Buy/Sell/Export/Reset/Theme toggle), recent/frecency-ranked results.
