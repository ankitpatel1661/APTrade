# Paper-Trading Portfolio & Symbol Autocomplete — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a debounced symbol-autocomplete dropdown to the add bar, and a simulated paper-trading portfolio (Buy/Sell with virtual cash, holdings, average cost, realized/unrealized P&L) reachable from a new top-level Watchlist / Portfolio switch.

**Architecture:** Strict Clean Architecture (Domain → Application → Infrastructure → Presentation), one SwiftPM target per layer. All money/quantity/trade math lives in the pure `APTradeDomain` layer. Networking (Yahoo search) lives in Infrastructure behind the `MarketDataRepository` port; persistence behind a new `PortfolioStore` port. Presentation is SwiftUI + thin `@Observable` view models wired in `CompositionRoot`.

**Tech Stack:** Swift 6, SwiftUI, Charts, async/await, actors, UserDefaults persistence, Yahoo Finance JSON endpoints.

## Global Constraints

- Swift 6 language mode; macOS 14+ (`.macOS(.v14)`).
- Clean Architecture dependency direction only: Domain knows nothing of other layers; Application depends on Domain; Infrastructure depends on Application + Domain; Presentation depends on all three. No framework imports in Domain.
- Dependency injection only — wire everything in `Sources/APTradeApp/CompositionRoot.swift`. No singletons, no force-unwraps, no business logic in views.
- **Color rule:** gold (`Theme.goldGradient` / `Theme.gold`) owns brand + interactive accents (toggle, Buy/Sell, confirm, Max). Every gain/loss/P&L figure stays green/red via `Theme.changeColor(_:)` or `Theme.up`/`Theme.down`. Price-direction color is data, never branding.
- All `Money` is USD (`currencyCode: "USD"`).
- Starting paper-trading cash: **$100,000**.
- Build: `swift build`. Test: `DEVELOPER_DIR=/Applications/Xcode.app swift test` (XCTest requires a full Xcode, not Command Line Tools).
- The existing 42 tests must stay green; build must be warning-clean.
- Numerics use `.monospacedDigit()`; money uses `SuperscriptPrice`; percentage changes use `ChangePill`. Reuse `DesignKit` components — never hard-code hex or rebuild a primitive.

---

## File Structure

**Feature 1 — Autocomplete**
- Modify `Sources/APTradeApplication/Ports.swift` — add `search(query:)` to `MarketDataRepository` (+ default impl).
- Modify `Sources/APTradeApplication/MarketUseCases.swift` — add `SearchAssetsUseCase`.
- Create `Sources/APTradeInfrastructure/YahooSearchDTO.swift` — search response DTO + `YahooSearchMapper`.
- Modify `Sources/APTradeInfrastructure/YahooMarketDataRepository.swift` — implement `search`.
- Modify `Sources/APTradeInfrastructure/CachingMarketDataRepository.swift` — short-TTL search cache + forward.
- Modify `Sources/APTradeApp/WatchlistViewModel.swift` — debounced suggestions.
- Modify `Sources/APTradeApp/WatchlistView.swift` — suggestions dropdown UI.
- Create `Tests/APTradeInfrastructureTests/YahooSearchMapperTests.swift` + fixture `Tests/APTradeInfrastructureTests/Fixtures/search.json`.

**Feature 2 — Portfolio**
- Create `Sources/APTradeDomain/Quantity.swift`.
- Create `Sources/APTradeDomain/Trade.swift` — `TradeSide`, `Transaction`, `TradeError`.
- Create `Sources/APTradeDomain/Position.swift`.
- Create `Sources/APTradeDomain/Portfolio.swift` — `Portfolio`, `PortfolioValuation`.
- Modify `Sources/APTradeDomain/Money.swift` — add `+` operator.
- Modify `Sources/APTradeApplication/Ports.swift` — add `PortfolioStore` port.
- Create `Sources/APTradeApplication/PortfolioUseCases.swift` — Buy/Sell/Fetch/Reset.
- Create `Sources/APTradeInfrastructure/UserDefaultsPortfolioStore.swift`.
- Modify `Sources/APTradeApp/CompositionRoot.swift` — wire store + use cases + new VMs.
- Create `Sources/APTradeApp/PortfolioViewModel.swift`.
- Create `Sources/APTradeApp/TradeViewModel.swift`.
- Create `Sources/APTradeApp/RootView.swift` — Watchlist / Portfolio switch.
- Create `Sources/APTradeApp/PortfolioView.swift`.
- Create `Sources/APTradeApp/TradeSheet.swift`.
- Modify `Sources/APTradeApp/AssetDetailView.swift` — Buy/Sell buttons + position panel.
- Modify `Sources/APTradeApp/AssetDetailViewModel.swift` — expose held position.
- Modify `Sources/APTradeApp/APTradeApp.swift` — root is `RootView`.
- Create domain/application tests as listed per task.

---

## Task 1: Yahoo search DTO + mapper (Infrastructure)

**Files:**
- Create: `Sources/APTradeInfrastructure/YahooSearchDTO.swift`
- Create: `Tests/APTradeInfrastructureTests/YahooSearchMapperTests.swift`
- Create: `Tests/APTradeInfrastructureTests/Fixtures/search.json`

**Interfaces:**
- Consumes: `Asset`, `AssetKind` (`APTradeDomain`); `AppError` (`APTradeApplication`).
- Produces: `enum YahooSearchMapper { static func assets(from data: Data) throws -> [Asset] }`; internal `YahooSearchResponse`.

- [ ] **Step 1: Create the fixture JSON**

`Tests/APTradeInfrastructureTests/Fixtures/search.json`:

```json
{
  "quotes": [
    { "symbol": "AAPL", "shortname": "Apple Inc.", "longname": "Apple Inc.", "quoteType": "EQUITY" },
    { "symbol": "AMD", "shortname": "Advanced Micro Devices, Inc.", "quoteType": "EQUITY" },
    { "symbol": "ARKK", "shortname": "ARK Innovation ETF", "quoteType": "ETF" },
    { "symbol": "AVAX-USD", "shortname": "Avalanche USD", "quoteType": "CRYPTOCURRENCY" },
    { "symbol": "^GSPC", "shortname": "S&P 500", "quoteType": "INDEX" },
    { "symbol": "ES=F", "shortname": "E-Mini S&P 500", "quoteType": "FUTURE" },
    { "symbol": "BADTYPE", "quoteType": "EQUITY" }
  ]
}
```

- [ ] **Step 2: Write the failing test**

`Tests/APTradeInfrastructureTests/YahooSearchMapperTests.swift`:

```swift
import XCTest
@testable import APTradeInfrastructure
import APTradeDomain

final class YahooSearchMapperTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json"))
        return try Data(contentsOf: url)
    }

    func test_mapsSupportedKindsAndDropsUnsupported() throws {
        let assets = try YahooSearchMapper.assets(from: fixture("search"))
        // INDEX and FUTURE dropped; BADTYPE kept (EQUITY) with symbol as name fallback.
        XCTAssertEqual(assets.map(\.symbol), ["AAPL", "AMD", "ARKK", "AVAX-USD", "BADTYPE"])
        XCTAssertEqual(assets.first?.kind, .stock)
        XCTAssertEqual(assets.first { $0.symbol == "ARKK" }?.kind, .etf)
        XCTAssertEqual(assets.first { $0.symbol == "AVAX-USD" }?.kind, .crypto)
        XCTAssertEqual(assets.first { $0.symbol == "BADTYPE" }?.name, "BADTYPE")
    }

    func test_throwsDecodingOnGarbage() {
        XCTAssertThrowsError(try YahooSearchMapper.assets(from: Data("nope".utf8)))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter YahooSearchMapperTests`
Expected: FAIL — `YahooSearchMapper` / `YahooSearchResponse` undefined.

- [ ] **Step 4: Implement the DTO + mapper**

`Sources/APTradeInfrastructure/YahooSearchDTO.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

struct YahooSearchResponse: Decodable {
    let quotes: [Item]?

    struct Item: Decodable {
        let symbol: String?
        let shortname: String?
        let longname: String?
        let quoteType: String?
    }
}

enum YahooSearchMapper {
    static func assets(from data: Data) throws -> [Asset] {
        let decoded: YahooSearchResponse
        do { decoded = try JSONDecoder().decode(YahooSearchResponse.self, from: data) }
        catch { throw AppError.decoding }
        return (decoded.quotes ?? []).compactMap(asset(from:))
    }

    private static func asset(from item: YahooSearchResponse.Item) -> Asset? {
        guard let symbol = item.symbol, let kind = kind(from: item.quoteType) else { return nil }
        let name = item.shortname ?? item.longname ?? symbol
        return Asset(symbol: symbol, name: name, kind: kind)
    }

    private static func kind(from type: String?) -> AssetKind? {
        switch type?.uppercased() {
        case "EQUITY": return .stock
        case "ETF": return .etf
        case "CRYPTOCURRENCY": return .crypto
        default: return nil   // INDEX, FUTURE, CURRENCY, OPTION, … unsupported
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter YahooSearchMapperTests`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeInfrastructure/YahooSearchDTO.swift Tests/APTradeInfrastructureTests/YahooSearchMapperTests.swift Tests/APTradeInfrastructureTests/Fixtures/search.json
git commit -m "feat(infra): Yahoo search DTO + mapper for symbol autocomplete"
```

---

## Task 2: `search` port + Yahoo impl + caching passthrough

**Files:**
- Modify: `Sources/APTradeApplication/Ports.swift`
- Modify: `Sources/APTradeInfrastructure/YahooMarketDataRepository.swift`
- Modify: `Sources/APTradeInfrastructure/CachingMarketDataRepository.swift`
- Modify: `Tests/APTradeInfrastructureTests/CachingRepositoryTests.swift`

**Interfaces:**
- Consumes: `YahooSearchMapper.assets(from:)` (Task 1).
- Produces: `MarketDataRepository.search(query:) async throws -> [Asset]` (default impl returns `[]`); `CachingMarketDataRepository` caches search by normalized query for its TTL.

- [ ] **Step 1: Add the port method + default**

In `Sources/APTradeApplication/Ports.swift`, add to the `MarketDataRepository` protocol (after `profile`):

```swift
    /// Returns ranked asset matches for a free-text query (autocomplete).
    func search(query: String) async throws -> [Asset]
```

And in the existing `extension MarketDataRepository`, add a default so non-search adapters/mocks keep compiling:

```swift
    /// Default: no search capability. Concrete repositories override this.
    func search(query: String) async throws -> [Asset] { [] }
```

- [ ] **Step 2: Write the failing caching test**

Add to `Tests/APTradeInfrastructureTests/CachingRepositoryTests.swift` (inside the existing test class). The file already defines a stub repository — extend it with a `search` that counts calls. If the existing stub is named differently, adapt the names; here we assume a `StubRepository` with a `quoteCallCount`. Add a search counter and test:

```swift
    func test_search_isCachedWithinTTL() async throws {
        let stub = StubRepository()
        stub.searchResults = [Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)]
        let now = DateBox()
        let sut = CachingMarketDataRepository(wrapping: stub, ttl: 15, now: { now.value })

        _ = try await sut.search(query: "aapl")
        _ = try await sut.search(query: "aapl")   // same normalized query, within TTL

        XCTAssertEqual(stub.searchCallCount, 1)
    }
```

If the existing test file does not already have a `StubRepository`/`DateBox`, reuse whatever stub + clock the existing `test_quote_isCached` test uses (read the file first) and add to that stub:

```swift
    var searchResults: [Asset] = []
    private(set) var searchCallCount = 0
    func search(query: String) async throws -> [Asset] {
        searchCallCount += 1
        return searchResults
    }
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter CachingRepositoryTests`
Expected: FAIL — `searchCallCount` is 2 (no caching yet) or `search` unresolved on caching repo.

- [ ] **Step 4: Implement Yahoo `search`**

In `Sources/APTradeInfrastructure/YahooMarketDataRepository.swift`, add a search base and method. Add the property next to `base`:

```swift
    private let searchBase = "https://query1.finance.yahoo.com/v1/finance/search"
```

Add the method (after `profile`):

```swift
    public func search(query: String) async throws -> [Asset] {
        guard var components = URLComponents(string: searchBase) else { throw AppError.network }
        components.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "quotesCount", value: "8"),
            URLQueryItem(name: "newsCount", value: "0"),
        ]
        guard let url = components.url else { throw AppError.network }
        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await session.data(for: request)
            if let http = response as? HTTPURLResponse {
                if http.statusCode == 429 { throw AppError.rateLimited }
                guard (200..<300).contains(http.statusCode) else { throw AppError.network }
            }
            return try YahooSearchMapper.assets(from: data)
        } catch let error as AppError {
            throw error
        } catch {
            throw AppError.network
        }
    }
```

- [ ] **Step 5: Implement caching search**

In `Sources/APTradeInfrastructure/CachingMarketDataRepository.swift`, add a search cache. Add a stored property near `cache`:

```swift
    private struct SearchEntry { let results: [Asset]; let at: Date }
    private var searchCache: [String: SearchEntry] = [:]
```

Add the method (after `profile`):

```swift
    public func search(query: String) async throws -> [Asset] {
        let key = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let entry = searchCache[key], now().timeIntervalSince(entry.at) < ttl {
            return entry.results
        }
        let results = try await inner.search(query: query)
        searchCache[key] = SearchEntry(results: results, at: now())
        return results
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter CachingRepositoryTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApplication/Ports.swift Sources/APTradeInfrastructure/YahooMarketDataRepository.swift Sources/APTradeInfrastructure/CachingMarketDataRepository.swift Tests/APTradeInfrastructureTests/CachingRepositoryTests.swift
git commit -m "feat(infra): search port, Yahoo search endpoint, cached search"
```

---

## Task 3: `SearchAssetsUseCase` (Application)

**Files:**
- Modify: `Sources/APTradeApplication/MarketUseCases.swift`
- Create: `Tests/APTradeApplicationTests/SearchAssetsUseCaseTests.swift`

**Interfaces:**
- Consumes: `MarketDataRepository.search(query:)` (Task 2).
- Produces: `SearchAssetsUseCase` with `callAsFunction(query:) async throws -> [Asset]`.

- [ ] **Step 1: Write the failing test**

`Tests/APTradeApplicationTests/SearchAssetsUseCaseTests.swift`:

```swift
import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class SearchRepo: MarketDataRepository, @unchecked Sendable {
    var results: [Asset] = []
    private(set) var lastQuery: String?
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
    func search(query: String) async throws -> [Asset] { lastQuery = query; return results }
}

final class SearchAssetsUseCaseTests: XCTestCase {
    func test_emptyQuery_returnsEmptyWithoutHittingRepo() async throws {
        let repo = SearchRepo()
        let sut = SearchAssetsUseCase(repository: repo)
        let out = try await sut(query: "   ")
        XCTAssertTrue(out.isEmpty)
        XCTAssertNil(repo.lastQuery)
    }

    func test_trimsAndForwardsQuery() async throws {
        let repo = SearchRepo()
        repo.results = [Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)]
        let sut = SearchAssetsUseCase(repository: repo)
        let out = try await sut(query: "  aapl ")
        XCTAssertEqual(repo.lastQuery, "aapl")
        XCTAssertEqual(out.map(\.symbol), ["AAPL"])
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter SearchAssetsUseCaseTests`
Expected: FAIL — `SearchAssetsUseCase` undefined.

- [ ] **Step 3: Implement the use case**

Append to `Sources/APTradeApplication/MarketUseCases.swift`:

```swift
public struct SearchAssetsUseCase: Sendable {
    private let repository: MarketDataRepository
    public init(repository: MarketDataRepository) { self.repository = repository }

    public func callAsFunction(query: String) async throws -> [Asset] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        return try await repository.search(query: trimmed)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter SearchAssetsUseCaseTests`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeApplication/MarketUseCases.swift Tests/APTradeApplicationTests/SearchAssetsUseCaseTests.swift
git commit -m "feat(app): SearchAssetsUseCase"
```

---

## Task 4: Autocomplete in WatchlistViewModel + dropdown UI (Presentation)

**Files:**
- Modify: `Sources/APTradeApp/WatchlistViewModel.swift`
- Modify: `Sources/APTradeApp/WatchlistView.swift`
- Modify: `Sources/APTradeApp/CompositionRoot.swift`

**Interfaces:**
- Consumes: `SearchAssetsUseCase` (Task 3), existing `AddToWatchlistUseCase`.
- Produces: `WatchlistViewModel.suggestions: [Asset]`, `updateQuery(_:)`, `addSuggestion(_:) async`, `clearSuggestions()`; an added `search` init parameter.

> View changes verify via `swift build` + manual run (SwiftUI views are not unit-tested here). View-model wiring is covered by the compile + the existing `WatchlistViewModelTests`.

- [ ] **Step 1: Add suggestion state to the view model**

In `Sources/APTradeApp/WatchlistViewModel.swift`, add a stored use case and state. Add to the property block:

```swift
    private let searchAssets: SearchAssetsUseCase
    private(set) var suggestions: [Asset] = []
    private var searchTask: Task<Void, Never>?
```

Add `searchAssets` to the initializer signature and assignment. The new `init` parameter list becomes (append after `search`):

```swift
    init(load: LoadWatchlistUseCase,
         add: AddToWatchlistUseCase,
         remove: RemoveFromWatchlistUseCase,
         fetchQuotes: FetchQuotesUseCase,
         fetchHistory: FetchHistoryUseCase,
         search: SearchSymbolUseCase,
         searchAssets: SearchAssetsUseCase) {
        self.load = load
        self.add = add
        self.remove = remove
        self.fetchQuotes = fetchQuotes
        self.fetchHistory = fetchHistory
        self.search = search
        self.searchAssets = searchAssets
    }
```

- [ ] **Step 2: Add the debounced search methods**

Add these methods to `WatchlistViewModel`:

```swift
    /// Debounced autocomplete. Cancels any in-flight search and, after a 250ms pause,
    /// fetches ranked matches for the current query. Best-effort: errors clear results.
    func updateQuery(_ text: String) {
        searchTask?.cancel()
        let query = text.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { suggestions = []; return }
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self else { return }
            let results = (try? await self.searchAssets(query: query)) ?? []
            if !Task.isCancelled { self.suggestions = results }
        }
    }

    /// Adds a chosen suggestion to the watchlist and clears the dropdown.
    func addSuggestion(_ asset: Asset) async {
        clearSuggestions()
        _ = add(asset)
        reloadRows()
        selectedKind = asset.kind
        await refresh()
        await loadSparklines()
    }

    func clearSuggestions() {
        searchTask?.cancel()
        suggestions = []
    }
```

- [ ] **Step 3: Wire the new dependency in CompositionRoot**

In `Sources/APTradeApp/CompositionRoot.swift`, inside `makeWatchlistViewModel()`, add the `searchAssets:` argument:

```swift
        return WatchlistViewModel(
            load: LoadWatchlistUseCase(store: store),
            add: AddToWatchlistUseCase(store: store),
            remove: RemoveFromWatchlistUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            fetchHistory: FetchHistoryUseCase(repository: repo),
            search: SearchSymbolUseCase(repository: repo),
            searchAssets: SearchAssetsUseCase(repository: repo)
        )
```

- [ ] **Step 4: Add the dropdown UI to the add bar**

In `Sources/APTradeApp/WatchlistView.swift`, replace the `addBar` computed property with a version that shows suggestions above the field and drives `updateQuery`:

```swift
    private var addBar: some View {
        VStack(spacing: 8) {
            if !viewModel.suggestions.isEmpty {
                suggestionList
            }
            if let error = viewModel.addError {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.down)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(Theme.textTertiary)
                TextField("Search symbol or name — Apple, VOO, SOL", text: $newSymbol)
                    .textFieldStyle(.plain)
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textPrimary)
                    .onChange(of: newSymbol) { _, text in viewModel.updateQuery(text) }
                    .onSubmit(submit)
                if !newSymbol.trimmingCharacters(in: .whitespaces).isEmpty {
                    Button("Add", action: submit)
                        .buttonStyle(.plain)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Theme.bgBottom)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                        .background(Theme.goldGradient, in: Capsule())
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(Theme.surface, in: Capsule())
            .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
        .background(Theme.bgBottom)
    }

    private var suggestionList: some View {
        VStack(spacing: 0) {
            ForEach(viewModel.suggestions, id: \.symbol) { asset in
                Button {
                    newSymbol = ""
                    Task { await viewModel.addSuggestion(asset) }
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(asset.name)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Theme.textPrimary)
                                .lineLimit(1)
                            Text(asset.symbol)
                                .font(.system(size: 11, weight: .medium).monospacedDigit())
                                .foregroundStyle(Theme.textSecondary)
                        }
                        Spacer()
                        Text(kindChipLabel(asset.kind))
                            .font(.system(size: 10, weight: .bold))
                            .tracking(0.6)
                            .foregroundStyle(Theme.textSecondary)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Theme.surfaceHi, in: Capsule())
                    }
                    .contentShape(Rectangle())
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                }
                .buttonStyle(.plain)
                if asset.symbol != viewModel.suggestions.last?.symbol {
                    Divider().overlay(Theme.hairline)
                }
            }
        }
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private func kindChipLabel(_ kind: AssetKind) -> String {
        switch kind {
        case .stock: return "STOCK"
        case .etf: return "ETF"
        case .crypto: return "CRYPTO"
        }
    }
```

Also update `submit()` to clear suggestions:

```swift
    private func submit() {
        let query = newSymbol
        newSymbol = ""
        viewModel.clearSuggestions()
        Task { await viewModel.add(query: query) }
    }
```

- [ ] **Step 5: Build and verify**

Run: `swift build`
Expected: builds clean.
Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter WatchlistViewModelTests`
Expected: existing watchlist VM tests still PASS.

- [ ] **Step 6: Manual smoke (optional but recommended)**

Run: `"$(swift build --show-bin-path)/APTradeApp"` — type "app" in the add bar and confirm a suggestion dropdown appears; click one and confirm it's added and the toggle jumps to its category.

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/WatchlistViewModel.swift Sources/APTradeApp/WatchlistView.swift Sources/APTradeApp/CompositionRoot.swift
git commit -m "feat(app): debounced symbol autocomplete dropdown"
```

---

## Task 5: `Quantity` value object (Domain)

**Files:**
- Create: `Sources/APTradeDomain/Quantity.swift`
- Create: `Tests/APTradeDomainTests/QuantityTests.swift`

**Interfaces:**
- Produces: `struct Quantity { let amount: Decimal; init(_:); var isZero: Bool; var formatted: String }` — non-negative invariant.

- [ ] **Step 1: Write the failing test**

`Tests/APTradeDomainTests/QuantityTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class QuantityTests: XCTestCase {
    func test_clampsNegativeToZero() {
        XCTAssertEqual(Quantity(Decimal(-3)).amount, 0)
        XCTAssertTrue(Quantity(Decimal(-3)).isZero)
    }

    func test_keepsFractionalValue() {
        XCTAssertEqual(Quantity(Decimal(string: "0.05")!).amount, Decimal(string: "0.05"))
    }

    func test_formatted_trimsTrailingZeros() {
        XCTAssertEqual(Quantity(Decimal(string: "2.50")!).formatted, "2.5")
        XCTAssertEqual(Quantity(Decimal(3)).formatted, "3")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter QuantityTests`
Expected: FAIL — `Quantity` undefined.

- [ ] **Step 3: Implement `Quantity`**

`Sources/APTradeDomain/Quantity.swift`:

```swift
import Foundation

/// A non-negative amount of an asset held or traded. Supports fractional units
/// (e.g. 0.05 BTC). Negative inputs are clamped to zero to preserve the invariant.
public struct Quantity: Equatable, Hashable, Codable, Sendable {
    public let amount: Decimal

    public init(_ amount: Decimal) {
        self.amount = Swift.max(0, amount)
    }

    public var isZero: Bool { amount == 0 }

    /// Up to 8 fraction digits, trailing zeros trimmed (so "2.50" → "2.5", "3.0" → "3").
    public var formatted: String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .decimal
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 8
        return f.string(from: amount as NSDecimalNumber) ?? "\(amount)"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter QuantityTests`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeDomain/Quantity.swift Tests/APTradeDomainTests/QuantityTests.swift
git commit -m "feat(domain): Quantity value object"
```

---

## Task 6: Trade types — `TradeSide`, `Transaction`, `TradeError`, `Position` (Domain)

**Files:**
- Create: `Sources/APTradeDomain/Trade.swift`
- Create: `Sources/APTradeDomain/Position.swift`
- Create: `Tests/APTradeDomainTests/PositionTests.swift`

**Interfaces:**
- Consumes: `Asset`, `Money`, `Quantity`.
- Produces:
  - `enum TradeSide: String, Codable, Sendable { case buy, sell }`
  - `enum TradeError: Error, Equatable, Sendable { case insufficientFunds, insufficientShares, invalidQuantity }`
  - `struct Transaction: Identifiable, Equatable, Codable, Sendable` (`id, symbol, side, quantity, price, date`)
  - `struct Position: Equatable, Codable, Sendable` (`asset, quantity, averageCost, realizedPnL`) with `marketValue(at:)`, `unrealizedPnL(at:)`.

- [ ] **Step 1: Write the failing test**

`Tests/APTradeDomainTests/PositionTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class PositionTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s)!) }

    func test_marketValue() {
        let pos = Position(
            asset: Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock),
            quantity: Quantity(Decimal(string: "2")!),
            averageCost: usd("100"),
            realizedPnL: usd("0")
        )
        XCTAssertEqual(pos.marketValue(at: usd("150")), usd("300"))
    }

    func test_unrealizedPnL() {
        let pos = Position(
            asset: Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock),
            quantity: Quantity(Decimal(string: "2")!),
            averageCost: usd("100"),
            realizedPnL: usd("0")
        )
        XCTAssertEqual(pos.unrealizedPnL(at: usd("150")), usd("100"))   // (150-100)*2
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter PositionTests`
Expected: FAIL — `Position` undefined.

- [ ] **Step 3: Implement the trade types**

`Sources/APTradeDomain/Trade.swift`:

```swift
import Foundation

public enum TradeSide: String, Codable, Sendable {
    case buy, sell
}

public enum TradeError: Error, Equatable, Sendable {
    case insufficientFunds
    case insufficientShares
    case invalidQuantity
}

public struct Transaction: Identifiable, Equatable, Codable, Sendable {
    public let id: UUID
    public let symbol: String
    public let side: TradeSide
    public let quantity: Quantity
    public let price: Money
    public let date: Date

    public init(id: UUID = UUID(), symbol: String, side: TradeSide,
                quantity: Quantity, price: Money, date: Date) {
        self.id = id
        self.symbol = symbol
        self.side = side
        self.quantity = quantity
        self.price = price
        self.date = date
    }
}
```

- [ ] **Step 4: Implement `Position`**

`Sources/APTradeDomain/Position.swift`:

```swift
import Foundation

public struct Position: Equatable, Codable, Sendable {
    public let asset: Asset
    public let quantity: Quantity
    public let averageCost: Money
    public let realizedPnL: Money

    public init(asset: Asset, quantity: Quantity, averageCost: Money, realizedPnL: Money) {
        self.asset = asset
        self.quantity = quantity
        self.averageCost = averageCost
        self.realizedPnL = realizedPnL
    }

    public func marketValue(at price: Money) -> Money {
        Money(amount: price.amount * quantity.amount, currencyCode: price.currencyCode)
    }

    public func unrealizedPnL(at price: Money) -> Money {
        Money(amount: (price.amount - averageCost.amount) * quantity.amount,
              currencyCode: price.currencyCode)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter PositionTests`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeDomain/Trade.swift Sources/APTradeDomain/Position.swift Tests/APTradeDomainTests/PositionTests.swift
git commit -m "feat(domain): TradeSide, Transaction, TradeError, Position"
```

---

## Task 7: `Portfolio` + `PortfolioValuation` + `Money.+` (Domain)

**Files:**
- Modify: `Sources/APTradeDomain/Money.swift`
- Create: `Sources/APTradeDomain/Portfolio.swift`
- Modify: `Tests/APTradeDomainTests/MoneyTests.swift`
- Create: `Tests/APTradeDomainTests/PortfolioTests.swift`

**Interfaces:**
- Consumes: `Money`, `Quantity`, `Position`, `Transaction`, `TradeSide`, `TradeError`, `Asset`, `Quote`.
- Produces:
  - `Money.+(_:_:)`.
  - `struct PortfolioValuation: Equatable, Sendable` (`cash, holdingsValue, totalValue, unrealizedPnL, dayChange`).
  - `struct Portfolio: Equatable, Codable, Sendable` with `static func starting(cash:) -> Portfolio`, `position(for:) -> Position?`, `buying(_:quantity:at:on:) throws -> Portfolio`, `selling(_:quantity:at:on:) throws -> Portfolio`, `valuation(quotes:) -> PortfolioValuation`.

- [ ] **Step 1: Add `Money.+` with a test**

In `Sources/APTradeDomain/Money.swift`, add after the `-` operator:

```swift
    public static func + (lhs: Money, rhs: Money) -> Money {
        precondition(lhs.currencyCode == rhs.currencyCode, "currency mismatch")
        return Money(amount: lhs.amount + rhs.amount, currencyCode: lhs.currencyCode)
    }
```

Add to `Tests/APTradeDomainTests/MoneyTests.swift` (inside the class):

```swift
    func test_addition() {
        XCTAssertEqual(Money(amount: 3) + Money(amount: 4), Money(amount: 7))
    }
```

- [ ] **Step 2: Write the failing Portfolio test**

`Tests/APTradeDomainTests/PortfolioTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class PortfolioTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s)!) }
    private func qty(_ s: String) -> Quantity { Quantity(Decimal(string: s)!) }
    private let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
    private let date = Date(timeIntervalSince1970: 0)

    func test_buy_debitsCashAndCreatesPosition() throws {
        let p = try Portfolio.starting().buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
        XCTAssertEqual(p.cash, usd("99800"))   // 100_000 - 200
        XCTAssertEqual(p.position(for: "AAPL")?.quantity, qty("2"))
        XCTAssertEqual(p.position(for: "AAPL")?.averageCost, usd("100"))
        XCTAssertEqual(p.transactions.count, 1)
    }

    func test_secondBuy_averagesCost() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
            .buying(aapl, quantity: qty("2"), at: usd("200"), on: date)
        XCTAssertEqual(p.position(for: "AAPL")?.quantity, qty("4"))
        XCTAssertEqual(p.position(for: "AAPL")?.averageCost, usd("150"))   // (200+400)/4
    }

    func test_buy_insufficientFunds_throws() {
        XCTAssertThrowsError(
            try Portfolio.starting().buying(aapl, quantity: qty("2"), at: usd("100000"), on: date)
        ) { XCTAssertEqual($0 as? TradeError, .insufficientFunds) }
    }

    func test_buy_zeroQuantity_throws() {
        XCTAssertThrowsError(
            try Portfolio.starting().buying(aapl, quantity: qty("0"), at: usd("100"), on: date)
        ) { XCTAssertEqual($0 as? TradeError, .invalidQuantity) }
    }

    func test_sell_creditsCashAccruesRealizedPnL() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty("4"), at: usd("100"), on: date)
            .selling("AAPL", quantity: qty("2"), at: usd("150"), on: date)
        XCTAssertEqual(p.position(for: "AAPL")?.quantity, qty("2"))
        XCTAssertEqual(p.position(for: "AAPL")?.realizedPnL, usd("100"))   // (150-100)*2
        XCTAssertEqual(p.cash, usd("99900"))   // 100000 - 400 + 300
    }

    func test_sellAll_removesPosition() throws {
        let p = try Portfolio.starting()
            .buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
            .selling("AAPL", quantity: qty("2"), at: usd("120"), on: date)
        XCTAssertNil(p.position(for: "AAPL"))
    }

    func test_sell_insufficientShares_throws() {
        XCTAssertThrowsError(
            try Portfolio.starting().selling("AAPL", quantity: qty("1"), at: usd("100"), on: date)
        ) { XCTAssertEqual($0 as? TradeError, .insufficientShares) }
    }

    func test_valuation_totalsCashPlusHoldings() throws {
        let p = try Portfolio.starting().buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
        let quote = Quote(symbol: "AAPL", price: usd("150"), previousClose: usd("140"))
        let v = p.valuation(quotes: ["AAPL": quote])
        XCTAssertEqual(v.holdingsValue, usd("300"))                 // 150*2
        XCTAssertEqual(v.totalValue, usd("100100"))                // 99800 cash + 300
        XCTAssertEqual(v.unrealizedPnL, usd("100"))                // (150-100)*2
        XCTAssertEqual(v.dayChange, usd("20"))                     // (150-140)*2
    }

    func test_valuation_missingQuote_fallsBackToCostBasis() throws {
        let p = try Portfolio.starting().buying(aapl, quantity: qty("2"), at: usd("100"), on: date)
        let v = p.valuation(quotes: [:])
        XCTAssertEqual(v.holdingsValue, usd("200"))   // cost basis 100*2
        XCTAssertEqual(v.unrealizedPnL, usd("0"))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter PortfolioTests`
Expected: FAIL — `Portfolio` undefined.

- [ ] **Step 4: Implement `Portfolio` + `PortfolioValuation`**

`Sources/APTradeDomain/Portfolio.swift`:

```swift
import Foundation

/// A derived snapshot of a portfolio valued against a set of current quotes. Pure.
public struct PortfolioValuation: Equatable, Sendable {
    public let cash: Money
    public let holdingsValue: Money
    public let totalValue: Money
    public let unrealizedPnL: Money
    public let dayChange: Money

    public init(cash: Money, holdingsValue: Money, totalValue: Money,
                unrealizedPnL: Money, dayChange: Money) {
        self.cash = cash
        self.holdingsValue = holdingsValue
        self.totalValue = totalValue
        self.unrealizedPnL = unrealizedPnL
        self.dayChange = dayChange
    }
}

/// A simulated (paper-trading) portfolio: virtual cash plus average-cost positions and
/// a transaction log. All transitions are pure and return a new `Portfolio`.
public struct Portfolio: Equatable, Codable, Sendable {
    public let cash: Money
    public let positions: [Position]
    public let transactions: [Transaction]

    public init(cash: Money, positions: [Position] = [], transactions: [Transaction] = []) {
        self.cash = cash
        self.positions = positions
        self.transactions = transactions
    }

    /// The starting paper portfolio: $100,000 cash, no holdings.
    public static func starting(cash: Money = Money(amount: 100_000)) -> Portfolio {
        Portfolio(cash: cash)
    }

    public func position(for symbol: String) -> Position? {
        positions.first { $0.asset.symbol == symbol }
    }

    public func buying(_ asset: Asset, quantity: Quantity, at price: Money,
                       on date: Date = Date()) throws -> Portfolio {
        guard !quantity.isZero else { throw TradeError.invalidQuantity }
        let cost = price.amount * quantity.amount
        guard cash.amount >= cost else { throw TradeError.insufficientFunds }

        var updated = positions
        if let index = positions.firstIndex(where: { $0.asset.symbol == asset.symbol }) {
            let old = positions[index]
            let newQty = old.quantity.amount + quantity.amount
            let newAvg = (old.averageCost.amount * old.quantity.amount + cost) / newQty
            updated[index] = Position(
                asset: old.asset,
                quantity: Quantity(newQty),
                averageCost: Money(amount: newAvg, currencyCode: price.currencyCode),
                realizedPnL: old.realizedPnL
            )
        } else {
            updated.append(Position(
                asset: asset,
                quantity: quantity,
                averageCost: price,
                realizedPnL: Money(amount: 0, currencyCode: price.currencyCode)
            ))
        }

        let txn = Transaction(symbol: asset.symbol, side: .buy,
                              quantity: quantity, price: price, date: date)
        return Portfolio(
            cash: Money(amount: cash.amount - cost, currencyCode: cash.currencyCode),
            positions: updated,
            transactions: transactions + [txn]
        )
    }

    public func selling(_ symbol: String, quantity: Quantity, at price: Money,
                        on date: Date = Date()) throws -> Portfolio {
        guard !quantity.isZero else { throw TradeError.invalidQuantity }
        guard let index = positions.firstIndex(where: { $0.asset.symbol == symbol }),
              positions[index].quantity.amount >= quantity.amount else {
            throw TradeError.insufficientShares
        }

        let old = positions[index]
        let proceeds = price.amount * quantity.amount
        let realizedDelta = (price.amount - old.averageCost.amount) * quantity.amount
        let newQty = old.quantity.amount - quantity.amount

        var updated = positions
        if newQty == 0 {
            updated.remove(at: index)
        } else {
            updated[index] = Position(
                asset: old.asset,
                quantity: Quantity(newQty),
                averageCost: old.averageCost,
                realizedPnL: Money(amount: old.realizedPnL.amount + realizedDelta,
                                   currencyCode: old.realizedPnL.currencyCode)
            )
        }

        let txn = Transaction(symbol: symbol, side: .sell,
                              quantity: quantity, price: price, date: date)
        return Portfolio(
            cash: Money(amount: cash.amount + proceeds, currencyCode: cash.currencyCode),
            positions: updated,
            transactions: transactions + [txn]
        )
    }

    /// Values every position against `quotes` (falling back to cost basis when a quote
    /// is missing) and rolls them into account totals. Pure.
    public func valuation(quotes: [String: Quote]) -> PortfolioValuation {
        var holdings = Decimal(0)
        var unrealized = Decimal(0)
        var day = Decimal(0)
        for position in positions {
            let q = position.quantity.amount
            if let quote = quotes[position.asset.symbol] {
                holdings += quote.price.amount * q
                unrealized += (quote.price.amount - position.averageCost.amount) * q
                day += quote.change.amount * q
            } else {
                holdings += position.averageCost.amount * q   // cost-basis fallback
            }
        }
        let code = cash.currencyCode
        return PortfolioValuation(
            cash: cash,
            holdingsValue: Money(amount: holdings, currencyCode: code),
            totalValue: Money(amount: cash.amount + holdings, currencyCode: code),
            unrealizedPnL: Money(amount: unrealized, currencyCode: code),
            dayChange: Money(amount: day, currencyCode: code)
        )
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter PortfolioTests`
Expected: PASS (9 tests).
Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter MoneyTests`
Expected: PASS (includes the new `test_addition`).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeDomain/Money.swift Sources/APTradeDomain/Portfolio.swift Tests/APTradeDomainTests/MoneyTests.swift Tests/APTradeDomainTests/PortfolioTests.swift
git commit -m "feat(domain): Portfolio buy/sell/valuation with average cost + Money addition"
```

---

## Task 8: `PortfolioStore` port + portfolio use cases (Application)

**Files:**
- Modify: `Sources/APTradeApplication/Ports.swift`
- Create: `Sources/APTradeApplication/PortfolioUseCases.swift`
- Create: `Tests/APTradeApplicationTests/PortfolioUseCasesTests.swift`

**Interfaces:**
- Consumes: `Portfolio`, `Quantity`, `Asset`, `TradeError`, `MarketDataRepository`.
- Produces:
  - `protocol PortfolioStore: Sendable { func load() -> Portfolio; func save(_ portfolio: Portfolio) }`
  - `FetchPortfolioUseCase() -> Portfolio`
  - `BuyAssetUseCase(asset:quantity:) async throws -> Portfolio`
  - `SellAssetUseCase(symbol:quantity:) async throws -> Portfolio`
  - `ResetPortfolioUseCase() -> Portfolio`

- [ ] **Step 1: Add the port**

Append to `Sources/APTradeApplication/Ports.swift`:

```swift
public protocol PortfolioStore: Sendable {
    func load() -> Portfolio
    func save(_ portfolio: Portfolio)
}
```

- [ ] **Step 2: Write the failing tests**

`Tests/APTradeApplicationTests/PortfolioUseCasesTests.swift`:

```swift
import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class PriceRepo: MarketDataRepository, @unchecked Sendable {
    var price: Money
    init(price: Money) { self.price = price }
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: price, previousClose: price)
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

final class PortfolioUseCasesTests: XCTestCase {
    private let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s)!) }

    func test_buy_persistsUpdatedPortfolio() async throws {
        let store = MemoryStore(.starting())
        let sut = BuyAssetUseCase(repository: PriceRepo(price: usd("100")), store: store)
        let result = try await sut(asset: aapl, quantity: Quantity(Decimal(2)))
        XCTAssertEqual(result.position(for: "AAPL")?.quantity, Quantity(Decimal(2)))
        XCTAssertEqual(store.portfolio.cash, usd("99800"))
    }

    func test_buy_propagatesTradeError() async {
        let store = MemoryStore(.starting())
        let sut = BuyAssetUseCase(repository: PriceRepo(price: usd("100000")), store: store)
        do {
            _ = try await sut(asset: aapl, quantity: Quantity(Decimal(2)))
            XCTFail("expected throw")
        } catch {
            XCTAssertEqual(error as? TradeError, .insufficientFunds)
        }
    }

    func test_sell_persistsUpdatedPortfolio() async throws {
        let start = try Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(2)), at: usd("100"))
        let store = MemoryStore(start)
        let sut = SellAssetUseCase(repository: PriceRepo(price: usd("150")), store: store)
        let result = try await sut(symbol: "AAPL", quantity: Quantity(Decimal(2)))
        XCTAssertNil(result.position(for: "AAPL"))
    }

    func test_reset_restoresStartingCash() {
        let store = MemoryStore(.starting(cash: usd("5")))
        let result = ResetPortfolioUseCase(store: store)()
        XCTAssertEqual(result.cash, usd("100000"))
        XCTAssertEqual(store.portfolio.cash, usd("100000"))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter PortfolioUseCasesTests`
Expected: FAIL — use cases undefined.

- [ ] **Step 4: Implement the use cases**

`Sources/APTradeApplication/PortfolioUseCases.swift`:

```swift
import APTradeDomain

public struct FetchPortfolioUseCase: Sendable {
    private let store: PortfolioStore
    public init(store: PortfolioStore) { self.store = store }
    public func callAsFunction() -> Portfolio { store.load() }
}

public struct BuyAssetUseCase: Sendable {
    private let repository: MarketDataRepository
    private let store: PortfolioStore
    public init(repository: MarketDataRepository, store: PortfolioStore) {
        self.repository = repository
        self.store = store
    }
    public func callAsFunction(asset: Asset, quantity: Quantity) async throws -> Portfolio {
        let quote = try await repository.quote(for: asset.symbol)
        let updated = try store.load().buying(asset, quantity: quantity, at: quote.price)
        store.save(updated)
        return updated
    }
}

public struct SellAssetUseCase: Sendable {
    private let repository: MarketDataRepository
    private let store: PortfolioStore
    public init(repository: MarketDataRepository, store: PortfolioStore) {
        self.repository = repository
        self.store = store
    }
    public func callAsFunction(symbol: String, quantity: Quantity) async throws -> Portfolio {
        let quote = try await repository.quote(for: symbol)
        let updated = try store.load().selling(symbol, quantity: quantity, at: quote.price)
        store.save(updated)
        return updated
    }
}

public struct ResetPortfolioUseCase: Sendable {
    private let store: PortfolioStore
    public init(store: PortfolioStore) { self.store = store }
    public func callAsFunction() -> Portfolio {
        let fresh = Portfolio.starting()
        store.save(fresh)
        return fresh
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter PortfolioUseCasesTests`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeApplication/Ports.swift Sources/APTradeApplication/PortfolioUseCases.swift Tests/APTradeApplicationTests/PortfolioUseCasesTests.swift
git commit -m "feat(app): PortfolioStore port + buy/sell/fetch/reset use cases"
```

---

## Task 9: `UserDefaultsPortfolioStore` (Infrastructure)

**Files:**
- Create: `Sources/APTradeInfrastructure/UserDefaultsPortfolioStore.swift`
- Create: `Tests/APTradeInfrastructureTests/UserDefaultsPortfolioStoreTests.swift`

**Interfaces:**
- Consumes: `PortfolioStore` (Task 8), `Portfolio`.
- Produces: `UserDefaultsPortfolioStore(defaults:key:)` conforming to `PortfolioStore`; seeds `Portfolio.starting()` on first load.

- [ ] **Step 1: Write the failing test**

`Tests/APTradeInfrastructureTests/UserDefaultsPortfolioStoreTests.swift`:

```swift
import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class UserDefaultsPortfolioStoreTests: XCTestCase {
    private func makeDefaults() -> UserDefaults {
        let d = UserDefaults(suiteName: "portfolio.test.\(UUID().uuidString)")!
        return d
    }

    func test_firstLoad_seedsStartingPortfolio() {
        let store = UserDefaultsPortfolioStore(defaults: makeDefaults(), key: "pf")
        XCTAssertEqual(store.load().cash, Money(amount: 100_000))
        XCTAssertTrue(store.load().positions.isEmpty)
    }

    func test_saveThenLoad_roundTrips() {
        let store = UserDefaultsPortfolioStore(defaults: makeDefaults(), key: "pf")
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let updated = try! store.load().buying(aapl, quantity: Quantity(Decimal(1)), at: Money(amount: 100))
        store.save(updated)
        XCTAssertEqual(store.load().position(for: "AAPL")?.quantity, Quantity(Decimal(1)))
        XCTAssertEqual(store.load().cash, Money(amount: 99_900))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter UserDefaultsPortfolioStoreTests`
Expected: FAIL — `UserDefaultsPortfolioStore` undefined.

- [ ] **Step 3: Implement the store**

`Sources/APTradeInfrastructure/UserDefaultsPortfolioStore.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

public final class UserDefaultsPortfolioStore: PortfolioStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "portfolio") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> Portfolio {
        guard let data = defaults.data(forKey: key),
              let portfolio = try? JSONDecoder().decode(Portfolio.self, from: data) else {
            let seed = Portfolio.starting()
            save(seed)
            return seed
        }
        return portfolio
    }

    public func save(_ portfolio: Portfolio) {
        guard let data = try? JSONEncoder().encode(portfolio) else { return }
        defaults.set(data, forKey: key)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter UserDefaultsPortfolioStoreTests`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeInfrastructure/UserDefaultsPortfolioStore.swift Tests/APTradeInfrastructureTests/UserDefaultsPortfolioStoreTests.swift
git commit -m "feat(infra): UserDefaultsPortfolioStore"
```

---

## Task 10: `PortfolioViewModel` + composition wiring (Presentation)

**Files:**
- Create: `Sources/APTradeApp/PortfolioViewModel.swift`
- Modify: `Sources/APTradeApp/CompositionRoot.swift`
- Create: `Tests/APTradeAppTests/PortfolioViewModelTests.swift`

**Interfaces:**
- Consumes: `FetchPortfolioUseCase`, `FetchQuotesUseCase`, `ResetPortfolioUseCase`, `Portfolio`, `PortfolioValuation`, `Position`.
- Produces: `PortfolioViewModel` (`portfolio`, `valuation`, `holdings`, `isLive`, `isRefreshing`, `reload()`, `onAppear() async`, `refresh(showIndicator:) async`, `runLiveUpdates() async`, `reset()`); a shared `PortfolioStore` in `CompositionRoot`; `CompositionRoot.makePortfolioViewModel()`.

- [ ] **Step 1: Write the failing test**

`Tests/APTradeAppTests/PortfolioViewModelTests.swift`:

```swift
import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

private final class FixedRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 150), previousClose: Money(amount: 140))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

@MainActor
final class PortfolioViewModelTests: XCTestCase {
    func test_onAppear_loadsHoldingsAndValues() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let start = try! Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(2)), at: Money(amount: 100))
        let store = MemoryStore(start)
        let repo = FixedRepo()
        let vm = PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            resetPortfolio: ResetPortfolioUseCase(store: store)
        )
        await vm.onAppear()
        XCTAssertEqual(vm.holdings.count, 1)
        XCTAssertEqual(vm.valuation.holdingsValue, Money(amount: 300))   // 150*2
    }

    func test_reset_clearsHoldings() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let start = try! Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(1)), at: Money(amount: 100))
        let store = MemoryStore(start)
        let vm = PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: FixedRepo()),
            resetPortfolio: ResetPortfolioUseCase(store: store)
        )
        await vm.onAppear()
        vm.reset()
        XCTAssertTrue(vm.holdings.isEmpty)
        XCTAssertEqual(vm.portfolio.cash, Money(amount: 100_000))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter PortfolioViewModelTests`
Expected: FAIL — `PortfolioViewModel` undefined.

- [ ] **Step 3: Implement the view model**

`Sources/APTradeApp/PortfolioViewModel.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class PortfolioViewModel {
    private let fetchPortfolio: FetchPortfolioUseCase
    private let fetchQuotes: FetchQuotesUseCase
    private let resetPortfolio: ResetPortfolioUseCase

    private(set) var portfolio: Portfolio
    private(set) var quotes: [String: Quote] = [:]
    private(set) var isLive = false
    var isRefreshing = false

    init(fetchPortfolio: FetchPortfolioUseCase,
         fetchQuotes: FetchQuotesUseCase,
         resetPortfolio: ResetPortfolioUseCase) {
        self.fetchPortfolio = fetchPortfolio
        self.fetchQuotes = fetchQuotes
        self.resetPortfolio = resetPortfolio
        self.portfolio = fetchPortfolio()
    }

    /// Holdings ordered by current market value, largest first.
    var holdings: [Position] {
        portfolio.positions.sorted { lhs, rhs in
            let lv = lhs.marketValue(at: quotes[lhs.asset.symbol]?.price ?? lhs.averageCost).amount
            let rv = rhs.marketValue(at: quotes[rhs.asset.symbol]?.price ?? rhs.averageCost).amount
            return lv > rv
        }
    }

    var valuation: PortfolioValuation { portfolio.valuation(quotes: quotes) }

    func quote(for symbol: String) -> Quote? { quotes[symbol] }

    /// Re-reads the persisted portfolio (e.g. after a trade made elsewhere).
    func reload() { portfolio = fetchPortfolio() }

    func onAppear() async {
        reload()
        await refresh()
    }

    func refresh(showIndicator: Bool = true) async {
        let symbols = portfolio.positions.map { $0.asset.symbol }
        guard !symbols.isEmpty else { return }
        if showIndicator { isRefreshing = true }
        defer { if showIndicator { isRefreshing = false } }
        let results = await fetchQuotes(symbols: symbols)
        var fresh = quotes
        for (symbol, result) in results {
            if case .success(let q) = result { fresh[symbol] = q }
        }
        quotes = fresh
    }

    /// Polls held quotes on the standard 15s cadence until cancelled on disappear.
    func runLiveUpdates() async {
        guard !portfolio.positions.isEmpty else { return }
        isLive = true
        defer { isLive = false }
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(15))
            if Task.isCancelled { break }
            await refresh(showIndicator: false)
        }
    }

    func reset() {
        portfolio = resetPortfolio()
        quotes = [:]
    }
}
```

- [ ] **Step 4: Wire the shared store + factory in CompositionRoot**

In `Sources/APTradeApp/CompositionRoot.swift`, add a shared portfolio store and a factory. Add after `makeStore()`:

```swift
    /// A single shared portfolio store so the Portfolio view and trade sheets read and
    /// write the same persisted state.
    static let portfolioStore: PortfolioStore = UserDefaultsPortfolioStore()

    static func makePortfolioViewModel() -> PortfolioViewModel {
        let repo = makeRepository()
        return PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            resetPortfolio: ResetPortfolioUseCase(store: portfolioStore)
        )
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter PortfolioViewModelTests`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeApp/PortfolioViewModel.swift Sources/APTradeApp/CompositionRoot.swift Tests/APTradeAppTests/PortfolioViewModelTests.swift
git commit -m "feat(app): PortfolioViewModel + composition wiring"
```

---

## Task 11: `TradeViewModel` (Presentation)

**Files:**
- Create: `Sources/APTradeApp/TradeViewModel.swift`
- Modify: `Sources/APTradeApp/CompositionRoot.swift`
- Create: `Tests/APTradeAppTests/TradeViewModelTests.swift`

**Interfaces:**
- Consumes: `BuyAssetUseCase`, `SellAssetUseCase`, `FetchPortfolioUseCase`, `FetchQuotesUseCase`, `Asset`, `Quantity`, `TradeSide`, `TradeError`.
- Produces: `TradeViewModel` (`side`, `quantityText`, `quote`, `sharesOwned`, `estimatedAmount`, `availableCash`, `canSubmit`, `errorMessage`, `didComplete`, `load() async`, `setMax()`, `submit() async`); `CompositionRoot.makeTradeViewModel(for:)`.

- [ ] **Step 1: Write the failing test**

`Tests/APTradeAppTests/TradeViewModelTests.swift`:

```swift
import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

private final class FixedRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 200), previousClose: Money(amount: 200))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

@MainActor
final class TradeViewModelTests: XCTestCase {
    private let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)

    private func make(_ store: MemoryStore) -> TradeViewModel {
        let repo = FixedRepo()
        return TradeViewModel(
            asset: aapl,
            buy: BuyAssetUseCase(repository: repo, store: store),
            sell: SellAssetUseCase(repository: repo, store: store),
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo)
        )
    }

    func test_buy_succeedsAndCompletes() async {
        let store = MemoryStore(.starting())
        let vm = make(store)
        await vm.load()
        vm.side = .buy
        vm.quantityText = "2"
        await vm.submit()
        XCTAssertTrue(vm.didComplete)
        XCTAssertNil(vm.errorMessage)
        XCTAssertEqual(store.portfolio.position(for: "AAPL")?.quantity, Quantity(Decimal(2)))
    }

    func test_buy_insufficientFunds_setsError() async {
        let store = MemoryStore(.starting(cash: Money(amount: 10)))
        let vm = make(store)
        await vm.load()
        vm.side = .buy
        vm.quantityText = "2"   // 2 * 200 = 400 > 10
        await vm.submit()
        XCTAssertFalse(vm.didComplete)
        XCTAssertEqual(vm.errorMessage, "Not enough cash for this order.")
    }

    func test_setMax_onSell_usesSharesOwned() async {
        let start = try! Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(3)), at: Money(amount: 100))
        let store = MemoryStore(start)
        let vm = make(store)
        await vm.load()
        vm.side = .sell
        vm.setMax()
        XCTAssertEqual(vm.quantityText, "3")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter TradeViewModelTests`
Expected: FAIL — `TradeViewModel` undefined.

- [ ] **Step 3: Implement the view model**

`Sources/APTradeApp/TradeViewModel.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class TradeViewModel {
    let asset: Asset
    private let buy: BuyAssetUseCase
    private let sell: SellAssetUseCase
    private let fetchPortfolio: FetchPortfolioUseCase
    private let fetchQuotes: FetchQuotesUseCase

    var side: TradeSide = .buy
    var quantityText: String = ""
    private(set) var quote: Quote?
    private(set) var portfolio: Portfolio
    private(set) var errorMessage: String?
    private(set) var isSubmitting = false
    private(set) var didComplete = false

    init(asset: Asset,
         buy: BuyAssetUseCase,
         sell: SellAssetUseCase,
         fetchPortfolio: FetchPortfolioUseCase,
         fetchQuotes: FetchQuotesUseCase) {
        self.asset = asset
        self.buy = buy
        self.sell = sell
        self.fetchPortfolio = fetchPortfolio
        self.fetchQuotes = fetchQuotes
        self.portfolio = fetchPortfolio()
    }

    var quantity: Quantity { Quantity(Decimal(string: quantityText) ?? 0) }
    var availableCash: Money { portfolio.cash }
    var sharesOwned: Quantity { portfolio.position(for: asset.symbol)?.quantity ?? Quantity(0) }

    /// Estimated cost (buy) or proceeds (sell) at the current quote.
    var estimatedAmount: Money? {
        guard let price = quote?.price else { return nil }
        return Money(amount: price.amount * quantity.amount, currencyCode: price.currencyCode)
    }

    var canSubmit: Bool {
        guard !isSubmitting, !quantity.isZero, quote != nil else { return false }
        switch side {
        case .buy:
            guard let cost = estimatedAmount?.amount else { return false }
            return cost <= availableCash.amount
        case .sell:
            return quantity.amount <= sharesOwned.amount
        }
    }

    func load() async {
        portfolio = fetchPortfolio()
        let quotes = await fetchQuotes(symbols: [asset.symbol])
        if case .success(let q) = quotes[asset.symbol] { quote = q }
    }

    /// Fills the quantity field with the maximum: shares owned (sell), or the largest
    /// whole-and-fractional amount affordable at the current price (buy).
    func setMax() {
        switch side {
        case .sell:
            quantityText = sharesOwned.isZero ? "" : sharesOwned.formatted
        case .buy:
            guard let price = quote?.price, price.amount > 0 else { return }
            let maxQty = availableCash.amount / price.amount
            quantityText = Quantity(maxQty).formatted
        }
    }

    func submit() async {
        guard canSubmit else { return }
        errorMessage = nil
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            switch side {
            case .buy:
                portfolio = try await buy(asset: asset, quantity: quantity)
            case .sell:
                portfolio = try await sell(symbol: asset.symbol, quantity: quantity)
            }
            didComplete = true
        } catch let error as TradeError {
            errorMessage = message(for: error)
        } catch {
            errorMessage = "Couldn't place the order. Try again."
        }
    }

    private func message(for error: TradeError) -> String {
        switch error {
        case .insufficientFunds: return "Not enough cash for this order."
        case .insufficientShares: return "You don't own that many shares."
        case .invalidQuantity: return "Enter a quantity greater than zero."
        }
    }
}
```

- [ ] **Step 4: Add the factory to CompositionRoot**

In `Sources/APTradeApp/CompositionRoot.swift`, add after `makePortfolioViewModel()`:

```swift
    static func makeTradeViewModel(for asset: Asset) -> TradeViewModel {
        let repo = makeRepository()
        return TradeViewModel(
            asset: asset,
            buy: BuyAssetUseCase(repository: repo, store: portfolioStore),
            sell: SellAssetUseCase(repository: repo, store: portfolioStore),
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
            fetchQuotes: FetchQuotesUseCase(repository: repo)
        )
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter TradeViewModelTests`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeApp/TradeViewModel.swift Sources/APTradeApp/CompositionRoot.swift Tests/APTradeAppTests/TradeViewModelTests.swift
git commit -m "feat(app): TradeViewModel for buy/sell orders"
```

---

## Task 12: `TradeSheet` + Buy/Sell on AssetDetailView (Presentation)

**Files:**
- Create: `Sources/APTradeApp/TradeSheet.swift`
- Modify: `Sources/APTradeApp/AssetDetailViewModel.swift`
- Modify: `Sources/APTradeApp/AssetDetailView.swift`

**Interfaces:**
- Consumes: `TradeViewModel` + `CompositionRoot.makeTradeViewModel(for:)`; `FetchPortfolioUseCase`; existing `AssetDetailViewModel`.
- Produces: `TradeSheet` view; `AssetDetailViewModel.position` (held position, if any) + `reloadPosition()`.

> Views verify via `swift build` + manual run. Logic is already covered by `TradeViewModelTests`.

- [ ] **Step 1: Expose the held position on the detail view model**

In `Sources/APTradeApp/AssetDetailViewModel.swift`, add a fetch-portfolio dependency and a `position` property. Add to the property block:

```swift
    private let fetchPortfolio: FetchPortfolioUseCase
    private(set) var position: Position?
```

Update the initializer to accept and store it (append the parameter):

```swift
    init(asset: Asset,
         fetchHistory: FetchHistoryUseCase,
         fetchQuotes: FetchQuotesUseCase,
         fetchPortfolio: FetchPortfolioUseCase) {
        self.asset = asset
        self.fetchHistory = fetchHistory
        self.fetchQuotes = fetchQuotes
        self.fetchPortfolio = fetchPortfolio
    }
```

Add a reload method and call it from `load()`:

```swift
    /// Re-reads whether this asset is currently held (after a trade or on appear).
    func reloadPosition() {
        position = fetchPortfolio().position(for: asset.symbol)
    }
```

In `load()`, add `reloadPosition()` as the first line:

```swift
    func load() async {
        reloadPosition()
        loadState = .loading
        // …existing body unchanged…
    }
```

- [ ] **Step 2: Update the detail factory in CompositionRoot**

In `Sources/APTradeApp/CompositionRoot.swift`, update `makeDetailViewModel(for:)` to pass the portfolio fetch use case:

```swift
    static func makeDetailViewModel(for asset: Asset) -> AssetDetailViewModel {
        let repo = makeRepository()
        return AssetDetailViewModel(
            asset: asset,
            fetchHistory: FetchHistoryUseCase(repository: repo),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore)
        )
    }
```

- [ ] **Step 3: Create the TradeSheet**

`Sources/APTradeApp/TradeSheet.swift`:

```swift
import SwiftUI
import APTradeDomain

struct TradeSheet: View {
    @State private var viewModel: TradeViewModel
    @Environment(\.dismiss) private var dismiss
    let onComplete: () -> Void

    init(asset: Asset, side: TradeSide, onComplete: @escaping () -> Void) {
        let vm = CompositionRoot.makeTradeViewModel(for: asset)
        vm.side = side
        _viewModel = State(initialValue: vm)
        self.onComplete = onComplete
    }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 22) {
                header
                sideToggle
                quantityField
                estimateRow
                if let error = viewModel.errorMessage {
                    Text(error)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.down)
                }
                Spacer()
                actions
            }
            .padding(24)
        }
        .frame(width: 420, height: 460)
        .task { await viewModel.load() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(viewModel.asset.symbol.uppercased())
                .font(.system(size: 11, weight: .bold))
                .tracking(2.0)
                .foregroundStyle(Theme.gold)
            Text(viewModel.asset.name)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            if let quote = viewModel.quote {
                HStack(spacing: 6) {
                    Text("Market price")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                    Text(quote.price.formatted)
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .foregroundStyle(Theme.textPrimary)
                }
            }
            Text("Simulated · paper trading")
                .font(.system(size: 10, weight: .semibold))
                .tracking(0.6)
                .foregroundStyle(Theme.textTertiary)
        }
    }

    private var sideToggle: some View {
        Picker("", selection: $viewModel.side) {
            Text("Buy").tag(TradeSide.buy)
            Text("Sell").tag(TradeSide.sell)
        }
        .pickerStyle(.segmented)
        .onChange(of: viewModel.side) { _, _ in viewModel.quantityText = "" }
    }

    private var quantityField: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("QUANTITY")
                    .font(.system(size: 10, weight: .bold)).tracking(1.0)
                    .foregroundStyle(Theme.textTertiary)
                Spacer()
                Button("Max") { viewModel.setMax() }
                    .buttonStyle(.plain)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Theme.gold)
            }
            TextField("0", text: $viewModel.quantityText)
                .textFieldStyle(.plain)
                .font(.system(size: 28, weight: .semibold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
            Text(secondaryLabel)
                .font(.system(size: 11).monospacedDigit())
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private var secondaryLabel: String {
        switch viewModel.side {
        case .buy: return "Available cash \(viewModel.availableCash.formatted)"
        case .sell: return "Shares owned \(viewModel.sharesOwned.formatted)"
        }
    }

    private var estimateRow: some View {
        HStack {
            Text(viewModel.side == .buy ? "Estimated cost" : "Estimated proceeds")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
            Spacer()
            Text(viewModel.estimatedAmount?.formatted ?? "—")
                .font(.system(size: 15, weight: .semibold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
        }
    }

    private var actions: some View {
        HStack(spacing: 12) {
            Button("Cancel") { dismiss() }
                .buttonStyle(.plain)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Theme.surface, in: Capsule())
                .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))

            Button(viewModel.side == .buy ? "Buy" : "Sell") {
                Task {
                    await viewModel.submit()
                    if viewModel.didComplete { onComplete(); dismiss() }
                }
            }
            .buttonStyle(.plain)
            .font(.system(size: 14, weight: .bold))
            .foregroundStyle(viewModel.canSubmit ? Theme.bgBottom : Theme.textTertiary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(
                Group {
                    if viewModel.canSubmit { Theme.goldGradient }
                    else { Theme.surface }
                },
                in: Capsule()
            )
            .disabled(!viewModel.canSubmit)
        }
    }
}
```

- [ ] **Step 4: Add Buy/Sell buttons + position panel to AssetDetailView**

In `Sources/APTradeApp/AssetDetailView.swift`, add sheet state at the top of the struct:

```swift
    @State private var tradeSide: TradeSide?
```

Insert a `tradeButtons` view and a `positionPanel`, and place them in the body. Change the body's `VStack` contents to include them (add `tradeButtons` right after `header`, and `positionPanel` after `keyStats`):

```swift
                VStack(alignment: .leading, spacing: 28) {
                    header
                    tradeButtons
                    chart
                    TimeframeBar(selection: viewModel.timeframe) { tf in
                        Task { await viewModel.select(tf) }
                    }
                    keyStats
                    positionPanel
                }
```

Add the sheet modifier after `.task { … }`:

```swift
        .sheet(item: $tradeSide) { side in
            TradeSheet(asset: viewModel.asset, side: side) {
                viewModel.reloadPosition()
            }
        }
```

Add the two view builders:

```swift
    private var tradeButtons: some View {
        HStack(spacing: 12) {
            tradeButton(title: "Buy", side: .buy, filled: true)
            tradeButton(title: "Sell", side: .sell, filled: false)
        }
    }

    private func tradeButton(title: String, side: TradeSide, filled: Bool) -> some View {
        Button { tradeSide = side } label: {
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(filled ? Theme.bgBottom : Theme.gold)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    Group { if filled { Theme.goldGradient } else { Color.clear } },
                    in: Capsule()
                )
                .overlay(Capsule().stroke(Theme.gold.opacity(filled ? 0 : 0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(side == .sell && (viewModel.position == nil))
    }

    @ViewBuilder
    private var positionPanel: some View {
        if let position = viewModel.position, let quote = viewModel.quote {
            VStack(alignment: .leading, spacing: 16) {
                Text("YOUR POSITION")
                    .font(.system(size: 11, weight: .bold)).tracking(1.8)
                    .foregroundStyle(Theme.textSecondary)
                let columns = [GridItem(.flexible(), spacing: 24), GridItem(.flexible(), spacing: 24)]
                LazyVGrid(columns: columns, alignment: .leading, spacing: 18) {
                    StatTile(label: "Shares", value: position.quantity.formatted)
                    StatTile(label: "Average cost", value: position.averageCost.formatted)
                    StatTile(label: "Market value", value: position.marketValue(at: quote.price).formatted)
                    StatTile(label: "Unrealized P&L",
                             value: signed(position.unrealizedPnL(at: quote.price)),
                             valueColor: pnlColor(position.unrealizedPnL(at: quote.price)))
                }
            }
            .padding(20)
            .background(Theme.surface.opacity(0.5), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
        }
    }

    /// Green for a gain, red for a loss — P&L direction is data, not branding.
    private func pnlColor(_ money: Money) -> Color {
        if money.amount > 0 { return Theme.up }
        if money.amount < 0 { return Theme.down }
        return Theme.textPrimary
    }
```

Note: `TradeSide` must be `Identifiable` for `.sheet(item:)`. Add this conformance once — append to `Sources/APTradeApp/TradeSheet.swift`. Use `@retroactive` because `TradeSide` and `Identifiable` are both from other modules (without it, Swift 6 emits a retroactive-conformance warning, and the build must stay warning-clean):

```swift
extension TradeSide: @retroactive Identifiable {
    public var id: String { rawValue }
}
```

- [ ] **Step 5: Build and verify**

Run: `swift build`
Expected: builds clean.
Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test`
Expected: all tests PASS (existing 42 + new tasks' tests).

- [ ] **Step 6: Manual smoke (recommended)**

Run: `"$(swift build --show-bin-path)/APTradeApp"` — open a stock's detail, tap Buy, enter a fractional quantity, confirm, and verify the Your Position panel appears. Tap Sell and verify it works only when holding.

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/TradeSheet.swift Sources/APTradeApp/AssetDetailView.swift Sources/APTradeApp/AssetDetailViewModel.swift Sources/APTradeApp/CompositionRoot.swift
git commit -m "feat(app): trade sheet with Buy/Sell + position panel on detail view"
```

---

## Task 13: Root Watchlist / Portfolio switch + Portfolio view (Presentation)

**Files:**
- Create: `Sources/APTradeApp/RootView.swift`
- Create: `Sources/APTradeApp/PortfolioView.swift`
- Modify: `Sources/APTradeApp/APTradeApp.swift`

**Interfaces:**
- Consumes: `WatchlistView`, `PortfolioViewModel` + `CompositionRoot.makePortfolioViewModel()`, `AssetDetailView`, `DesignKit` components.
- Produces: `RootView` (top-level segmented switch), `PortfolioView`.

> Views verify via `swift build` + manual run.

- [ ] **Step 1: Create the Portfolio view**

`Sources/APTradeApp/PortfolioView.swift`:

```swift
import SwiftUI
import APTradeDomain

struct PortfolioView: View {
    @State private var viewModel = CompositionRoot.makePortfolioViewModel()
    @State private var selectedAsset: Asset?

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    summary
                    Divider().overlay(Theme.hairline)
                    content
                }
            }
            .navigationDestination(item: $selectedAsset) { asset in
                AssetDetailView(asset: asset)
            }
            .toolbar(.hidden, for: .windowToolbar)
            .task {
                await viewModel.onAppear()
                await viewModel.runLiveUpdates()
            }
            .refreshable { await viewModel.refresh() }
        }
        .frame(minWidth: 560, minHeight: 640)
        .preferredColorScheme(.dark)
        .onAppear { viewModel.reload() }
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("TOTAL VALUE")
                        .font(.system(size: 11, weight: .bold)).tracking(1.8)
                        .foregroundStyle(Theme.textSecondary)
                    SuperscriptPrice(money: viewModel.valuation.totalValue, size: 40, weight: .semibold)
                }
                Spacer()
                if viewModel.isRefreshing {
                    ProgressView().controlSize(.small)
                } else if viewModel.isLive {
                    LiveBadge()
                }
            }
            HStack(spacing: 22) {
                metric(label: "Day P&L", money: viewModel.valuation.dayChange, colored: true)
                metric(label: "Unrealized P&L", money: viewModel.valuation.unrealizedPnL, colored: true)
                metric(label: "Cash", money: viewModel.valuation.cash, colored: false)
            }
            Text("Simulated · paper trading")
                .font(.system(size: 10, weight: .semibold)).tracking(0.6)
                .foregroundStyle(Theme.textTertiary)
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 18)
    }

    private func metric(label: String, money: Money, colored: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .semibold)).tracking(1.0)
                .foregroundStyle(Theme.textTertiary)
            Text(signed(money, showsSign: colored))
                .font(.system(size: 16, weight: .semibold).monospacedDigit())
                .foregroundStyle(colored ? pnlColor(money) : Theme.textPrimary)
        }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.holdings.isEmpty {
            emptyState
        } else {
            List {
                ForEach(viewModel.holdings, id: \.asset.symbol) { position in
                    HoldingRow(position: position, quote: viewModel.quote(for: position.asset.symbol))
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                        .contentShape(Rectangle())
                        .onTapGesture { selectedAsset = position.asset }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "chart.pie")
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(Theme.textTertiary)
            Text("No holdings yet")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text("Open an asset and tap Buy to start a simulated position.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }

    private func pnlColor(_ money: Money) -> Color {
        if money.amount > 0 { return Theme.up }
        if money.amount < 0 { return Theme.down }
        return Theme.textPrimary
    }

    private func signed(_ money: Money, showsSign: Bool) -> String {
        guard showsSign, money.amount > 0 else { return money.formatted }
        return "+" + money.formatted
    }
}

private struct HoldingRow: View {
    let position: Position
    let quote: Quote?

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(position.asset.name)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                Text("\(position.quantity.formatted) @ \(position.averageCost.formatted)")
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
            Spacer(minLength: 12)
            VStack(alignment: .trailing, spacing: 5) {
                if let quote {
                    SuperscriptPrice(money: position.marketValue(at: quote.price), size: 18, weight: .semibold)
                    Text(signed(position.unrealizedPnL(at: quote.price)))
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .foregroundStyle(pnlColor(position.unrealizedPnL(at: quote.price)))
                } else {
                    SuperscriptPrice(money: position.marketValue(at: position.averageCost), size: 18, weight: .semibold)
                }
            }
        }
        .padding(.vertical, 13)
        .padding(.horizontal, 10)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.hairline).frame(height: 1).padding(.horizontal, 10)
        }
    }

    private func pnlColor(_ money: Money) -> Color {
        if money.amount > 0 { return Theme.up }
        if money.amount < 0 { return Theme.down }
        return Theme.textPrimary
    }

    private func signed(_ money: Money) -> String {
        let sign = money.amount > 0 ? "+" : ""
        return sign + money.formatted
    }
}
```

- [ ] **Step 2: Create RootView with the top-level switch**

`Sources/APTradeApp/RootView.swift`:

```swift
import SwiftUI

struct RootView: View {
    enum Tab: String, CaseIterable { case watchlist = "Watchlist", portfolio = "Portfolio" }
    @State private var tab: Tab = .watchlist
    @Namespace private var pill

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(spacing: 0) {
                switcher
                    .padding(.horizontal, 24)
                    .padding(.top, 14)
                    .padding(.bottom, 8)
                Group {
                    switch tab {
                    case .watchlist: WatchlistView()
                    case .portfolio: PortfolioView()
                    }
                }
            }
        }
        .frame(minWidth: 560, minHeight: 680)
        .preferredColorScheme(.dark)
    }

    private var switcher: some View {
        HStack(spacing: 4) {
            ForEach(Tab.allCases, id: \.self) { item in
                let selected = tab == item
                Button {
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) { tab = item }
                } label: {
                    Text(item.rawValue)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 8)
                        .background {
                            if selected {
                                Capsule().fill(Theme.surfaceHi)
                                    .overlay(Capsule().stroke(Theme.gold.opacity(0.40), lineWidth: 1))
                                    .matchedGeometryEffect(id: "tab", in: pill)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .background(Theme.surface, in: Capsule())
        .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
        .frame(maxWidth: .infinity, alignment: .center)
    }
}
```

- [ ] **Step 3: Point the app at RootView**

In `Sources/APTradeApp/APTradeApp.swift`, change the window content from `WatchlistView()` to `RootView()`:

```swift
        WindowGroup("APTrade Lite") {
            RootView()
        }
```

- [ ] **Step 4: Build and verify**

Run: `swift build`
Expected: builds clean.
Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test`
Expected: all tests PASS.

- [ ] **Step 5: Manual smoke (recommended)**

Run: `"$(swift build --show-bin-path)/APTradeApp"` — confirm the top switch toggles Watchlist/Portfolio; buy something from a detail view; switch to Portfolio and confirm the holding, totals, and live P&L appear.

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeApp/RootView.swift Sources/APTradeApp/PortfolioView.swift Sources/APTradeApp/APTradeApp.swift
git commit -m "feat(app): top-level Watchlist/Portfolio switch + Portfolio view"
```

---

## Task 14: Reset action + final full verification

**Files:**
- Modify: `Sources/APTradeApp/PortfolioView.swift`

**Interfaces:**
- Consumes: `PortfolioViewModel.reset()`.

- [ ] **Step 1: Add a Reset overflow menu to the Portfolio summary**

In `Sources/APTradeApp/PortfolioView.swift`, add reset-confirmation state to the struct:

```swift
    @State private var showResetConfirm = false
```

In the `summary`'s top `HStack`, add a menu button before/after the live badge area. Replace the `Spacer()` + badge block with:

```swift
                Spacer()
                HStack(spacing: 10) {
                    if viewModel.isRefreshing {
                        ProgressView().controlSize(.small)
                    } else if viewModel.isLive {
                        LiveBadge()
                    }
                    Menu {
                        Button("Reset portfolio", systemImage: "arrow.counterclockwise", role: .destructive) {
                            showResetConfirm = true
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .font(.system(size: 16))
                            .foregroundStyle(Theme.textSecondary)
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }
```

Add a confirmation dialog modifier on the `NavigationStack` (next to `.refreshable`):

```swift
            .confirmationDialog("Reset portfolio to $100,000 cash and clear all holdings?",
                                isPresented: $showResetConfirm, titleVisibility: .visible) {
                Button("Reset", role: .destructive) { viewModel.reset() }
                Button("Cancel", role: .cancel) {}
            }
```

- [ ] **Step 2: Build and run the full suite**

Run: `swift build`
Expected: builds clean, no warnings.
Run: `DEVELOPER_DIR=/Applications/Xcode.app swift test`
Expected: ALL tests pass (existing 42 + the new Quantity/Position/Portfolio/PortfolioUseCases/SearchAssetsUseCase/YahooSearchMapper/UserDefaultsPortfolioStore/PortfolioViewModel/TradeViewModel tests).

- [ ] **Step 3: Commit**

```bash
git add Sources/APTradeApp/PortfolioView.swift
git commit -m "feat(app): reset portfolio action"
```

---

## Self-Review Notes (resolved during planning)

- **Spec coverage:** autocomplete (Tasks 1–4), Quantity (5), Position/Transaction/TradeError (6), Portfolio + valuation (7), PortfolioStore + use cases (8), UserDefaults store (9), PortfolioViewModel (10), TradeViewModel (11), TradeSheet + detail buttons/position (12), RootView + PortfolioView (13), reset (14). All spec sections map to a task.
- **Money currency:** every constructed `Money` uses the default USD code; arithmetic preserves `currencyCode`.
- **Type consistency:** `Quantity(_:)`, `Portfolio.buying/selling/valuation`, `PortfolioValuation(cash/holdingsValue/totalValue/unrealizedPnL/dayChange)`, and the use-case signatures are referenced identically across producing and consuming tasks.
- **Default `search` impl** on the port keeps existing test mocks (which don't implement `search`) compiling.
- **`MemoryStore`/`FixedRepo`** test doubles are defined privately per test file to avoid cross-target sharing.

## Out of scope (YAGNI)

Limit/stop orders, transaction-history UI, multiple portfolios, tax lots/FIFO, multi-currency, Supabase persistence, real brokerage integration.
