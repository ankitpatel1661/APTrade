# APTrade Lite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native macOS SwiftUI watchlist app that shows live prices and daily change for stocks, ETFs, and crypto, with a per-symbol price chart over multiple timeframes.

**Architecture:** Miniature Clean Architecture in a Swift Package — Domain (pure value types) ← Application (use cases + repository protocols) ← Infrastructure (Yahoo Finance repo, cache, UserDefaults store) ← Presentation (SwiftUI + MVVM). Dependencies point inward; adapters are injected at the app composition root.

**Tech Stack:** Swift 6, Swift Package Manager, SwiftUI, Swift Charts, `URLSession`, `Codable`, XCTest.

## Global Constraints

- Swift tools version `6.0`; macOS platform floor `.v14` (Swift Charts + `@Observable`).
- Money is **never** a `Double`/`Float` — use `Decimal` everywhere on the price path.
- No force-unwraps (`!`) and no force-try (`try!`) in non-test code.
- Domain layer imports only `Foundation`. Application imports only Domain + `Foundation`. Infrastructure may import `Foundation`/networking. Presentation imports SwiftUI + Application + Domain.
- Data source: `https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range={range}&interval={interval}`, sent with header `User-Agent: Mozilla/5.0`. No API key.
- Seeded default watchlist: `AAPL`, `SPY`, `BTC-USD`, `ETH-USD`.
- Tests never hit the network; infrastructure mapping is tested against a checked-in JSON fixture.

---

### Task 0: Scaffold the Swift package and git repo

**Files:**
- Create: `Package.swift`
- Create: `Sources/APTradeDomain/Placeholder.swift`
- Create: `Sources/APTradeApplication/Placeholder.swift`
- Create: `Sources/APTradeInfrastructure/Placeholder.swift`
- Create: `Sources/APTradeApp/Placeholder.swift`
- Create: `Tests/APTradeDomainTests/SmokeTests.swift`
- Create: `.gitignore`

**Interfaces:**
- Consumes: nothing.
- Produces: package targets `APTradeDomain`, `APTradeApplication`, `APTradeInfrastructure`, `APTradeApp` (executable), and matching test targets.

- [ ] **Step 1: Create `Package.swift`**

```swift
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "APTradeLite",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "APTradeDomain"),
        .target(name: "APTradeApplication", dependencies: ["APTradeDomain"]),
        .target(name: "APTradeInfrastructure", dependencies: ["APTradeApplication", "APTradeDomain"]),
        .executableTarget(
            name: "APTradeApp",
            dependencies: ["APTradeInfrastructure", "APTradeApplication", "APTradeDomain"]
        ),
        .testTarget(name: "APTradeDomainTests", dependencies: ["APTradeDomain"]),
        .testTarget(name: "APTradeApplicationTests", dependencies: ["APTradeApplication", "APTradeDomain"]),
        .testTarget(
            name: "APTradeInfrastructureTests",
            dependencies: ["APTradeInfrastructure", "APTradeApplication", "APTradeDomain"],
            resources: [.process("Fixtures")]
        ),
    ]
)
```

- [ ] **Step 2: Create placeholder source files** so each target compiles

In each of the four `Sources/*/Placeholder.swift` files:

```swift
// Intentionally empty. Replaced by real types in later tasks.
```

- [ ] **Step 3: Write a smoke test**

`Tests/APTradeDomainTests/SmokeTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class SmokeTests: XCTestCase {
    func test_packageCompilesAndTestsRun() {
        XCTAssertTrue(true)
    }
}
```

- [ ] **Step 4: Create `.gitignore`**

```
.build/
.DS_Store
*.xcodeproj
.swiftpm/
```

- [ ] **Step 5: Build and test**

Run: `swift build && swift test`
Expected: build succeeds; `SmokeTests` passes.

- [ ] **Step 6: Init git and commit**

```bash
git init
git add Package.swift Sources Tests .gitignore
git commit -m "chore: scaffold APTrade Lite swift package"
```

---

### Task 1: Money and Percentage value objects

**Files:**
- Create: `Sources/APTradeDomain/Money.swift`
- Create: `Sources/APTradeDomain/Percentage.swift`
- Test: `Tests/APTradeDomainTests/MoneyTests.swift`
- Delete: `Sources/APTradeDomain/Placeholder.swift`

**Interfaces:**
- Produces:
  - `struct Money: Equatable, Hashable { let amount: Decimal; let currencyCode: String; init(amount: Decimal, currencyCode: String = "USD") }`
    - `static func - (lhs: Money, rhs: Money) -> Money` (same currency; precondition on mismatch)
    - `var formatted: String` (e.g. `$294.30`)
  - `struct Percentage: Equatable, Hashable { let value: Decimal /* e.g. -1.25 means -1.25% */ }`
    - `var isPositive: Bool` / `var isNegative: Bool`
    - `var formatted: String` (e.g. `+1.25%` / `-1.25%`)

- [ ] **Step 1: Write failing tests**

`Tests/APTradeDomainTests/MoneyTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class MoneyTests: XCTestCase {
    func test_subtraction_sameCurrency() {
        let a = Money(amount: 100)
        let b = Money(amount: 30)
        XCTAssertEqual(a - b, Money(amount: 70))
    }

    func test_money_formatted_usd() {
        XCTAssertEqual(Money(amount: Decimal(string: "294.3")!).formatted, "$294.30")
    }

    func test_percentage_sign_and_format() {
        XCTAssertTrue(Percentage(value: Decimal(string: "1.25")!).isPositive)
        XCTAssertEqual(Percentage(value: Decimal(string: "1.25")!).formatted, "+1.25%")
        XCTAssertEqual(Percentage(value: Decimal(string: "-1.25")!).formatted, "-1.25%")
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter MoneyTests`
Expected: FAIL — `Money`/`Percentage` undefined.

- [ ] **Step 3: Implement `Money`**

`Sources/APTradeDomain/Money.swift`:

```swift
import Foundation

public struct Money: Equatable, Hashable, Sendable {
    public let amount: Decimal
    public let currencyCode: String

    public init(amount: Decimal, currencyCode: String = "USD") {
        self.amount = amount
        self.currencyCode = currencyCode
    }

    public static func - (lhs: Money, rhs: Money) -> Money {
        precondition(lhs.currencyCode == rhs.currencyCode, "currency mismatch")
        return Money(amount: lhs.amount - rhs.amount, currencyCode: lhs.currencyCode)
    }

    public var formatted: String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = currencyCode
        f.maximumFractionDigits = 2
        return f.string(from: amount as NSDecimalNumber) ?? "\(amount)"
    }
}
```

- [ ] **Step 4: Implement `Percentage`**

`Sources/APTradeDomain/Percentage.swift`:

```swift
import Foundation

public struct Percentage: Equatable, Hashable, Sendable {
    public let value: Decimal

    public init(value: Decimal) { self.value = value }

    public var isPositive: Bool { value > 0 }
    public var isNegative: Bool { value < 0 }

    public var formatted: String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        let body = f.string(from: value as NSDecimalNumber) ?? "\(value)"
        let sign = value > 0 ? "+" : ""
        return "\(sign)\(body)%"
    }
}
```

- [ ] **Step 5: Delete the placeholder, run tests**

```bash
rm Sources/APTradeDomain/Placeholder.swift
swift test --filter MoneyTests
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeDomain Tests/APTradeDomainTests/MoneyTests.swift
git commit -m "feat(domain): Money and Percentage value objects"
```

---

### Task 2: Domain entities — Asset, Quote, PricePoint, Timeframe

**Files:**
- Create: `Sources/APTradeDomain/Asset.swift`
- Create: `Sources/APTradeDomain/Quote.swift`
- Create: `Sources/APTradeDomain/PricePoint.swift`
- Create: `Sources/APTradeDomain/Timeframe.swift`
- Test: `Tests/APTradeDomainTests/QuoteTests.swift`

**Interfaces:**
- Consumes: `Money`, `Percentage` (Task 1).
- Produces:
  - `enum AssetKind: String, Codable, Sendable { case stock, etf, crypto }`
  - `struct Asset: Equatable, Hashable, Codable, Sendable { let symbol: String; let name: String; let kind: AssetKind }`
  - `struct Quote: Equatable, Sendable { let symbol: String; let price: Money; let previousClose: Money; var change: Money; var changePercent: Percentage }`
  - `struct PricePoint: Equatable, Sendable { let date: Date; let close: Money }`
  - `enum Timeframe: String, CaseIterable, Sendable { case oneDay, oneWeek, oneMonth, oneYear; var displayName: String }`

- [ ] **Step 1: Write failing tests**

`Tests/APTradeDomainTests/QuoteTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class QuoteTests: XCTestCase {
    func test_change_isPriceMinusPreviousClose() {
        let q = Quote(symbol: "AAPL",
                      price: Money(amount: Decimal(string: "294.30")!),
                      previousClose: Money(amount: Decimal(string: "296.42")!))
        XCTAssertEqual(q.change, Money(amount: Decimal(string: "-2.12")!))
    }

    func test_changePercent_isRelativeToPreviousClose() {
        let q = Quote(symbol: "X",
                      price: Money(amount: 110),
                      previousClose: Money(amount: 100))
        XCTAssertEqual(q.changePercent, Percentage(value: 10))
    }

    func test_changePercent_zeroPreviousClose_isZero() {
        let q = Quote(symbol: "X",
                      price: Money(amount: 110),
                      previousClose: Money(amount: 0))
        XCTAssertEqual(q.changePercent, Percentage(value: 0))
    }

    func test_timeframe_allCases_haveDisplayNames() {
        XCTAssertEqual(Timeframe.oneDay.displayName, "1D")
        XCTAssertEqual(Timeframe.allCases.count, 4)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter QuoteTests`
Expected: FAIL — types undefined.

- [ ] **Step 3: Implement `Asset` and `Timeframe`**

`Sources/APTradeDomain/Asset.swift`:

```swift
public enum AssetKind: String, Codable, Sendable {
    case stock, etf, crypto
}

public struct Asset: Equatable, Hashable, Codable, Sendable {
    public let symbol: String
    public let name: String
    public let kind: AssetKind

    public init(symbol: String, name: String, kind: AssetKind) {
        self.symbol = symbol
        self.name = name
        self.kind = kind
    }
}
```

`Sources/APTradeDomain/Timeframe.swift`:

```swift
public enum Timeframe: String, CaseIterable, Sendable {
    case oneDay, oneWeek, oneMonth, oneYear

    public var displayName: String {
        switch self {
        case .oneDay: return "1D"
        case .oneWeek: return "1W"
        case .oneMonth: return "1M"
        case .oneYear: return "1Y"
        }
    }
}
```

- [ ] **Step 4: Implement `PricePoint` and `Quote`**

`Sources/APTradeDomain/PricePoint.swift`:

```swift
import Foundation

public struct PricePoint: Equatable, Sendable {
    public let date: Date
    public let close: Money

    public init(date: Date, close: Money) {
        self.date = date
        self.close = close
    }
}
```

`Sources/APTradeDomain/Quote.swift`:

```swift
import Foundation

public struct Quote: Equatable, Sendable {
    public let symbol: String
    public let price: Money
    public let previousClose: Money

    public init(symbol: String, price: Money, previousClose: Money) {
        self.symbol = symbol
        self.price = price
        self.previousClose = previousClose
    }

    public var change: Money { price - previousClose }

    public var changePercent: Percentage {
        guard previousClose.amount != 0 else { return Percentage(value: 0) }
        let ratio = (price.amount - previousClose.amount) / previousClose.amount * 100
        return Percentage(value: ratio)
    }
}
```

- [ ] **Step 5: Run tests**

Run: `swift test --filter QuoteTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeDomain Tests/APTradeDomainTests/QuoteTests.swift
git commit -m "feat(domain): Asset, Quote, PricePoint, Timeframe entities"
```

---

### Task 3: Application — errors, ports, and watchlist use cases

**Files:**
- Create: `Sources/APTradeApplication/AppError.swift`
- Create: `Sources/APTradeApplication/Ports.swift`
- Create: `Sources/APTradeApplication/WatchlistUseCases.swift`
- Test: `Tests/APTradeApplicationTests/WatchlistUseCasesTests.swift`
- Delete: `Sources/APTradeApplication/Placeholder.swift`

**Interfaces:**
- Consumes: `Asset`, `Quote`, `PricePoint`, `Timeframe` (Domain).
- Produces:
  - `enum AppError: Error, Equatable { case network, notFound, decoding, rateLimited }`
  - `protocol MarketDataRepository: Sendable { func quote(for symbol: String) async throws -> Quote; func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] }`
  - `protocol WatchlistStore: Sendable { func load() -> [Asset]; func save(_ assets: [Asset]) }`
  - `struct AddToWatchlistUseCase { init(store:); func callAsFunction(_ asset: Asset) -> [Asset] }`
  - `struct RemoveFromWatchlistUseCase { init(store:); func callAsFunction(symbol: String) -> [Asset] }`
  - `struct LoadWatchlistUseCase { init(store:); func callAsFunction() -> [Asset] }`

- [ ] **Step 1: Write failing tests with an in-memory fake store**

`Tests/APTradeApplicationTests/WatchlistUseCasesTests.swift`:

```swift
import XCTest
@testable import APTradeApplication
import APTradeDomain

final class FakeStore: WatchlistStore, @unchecked Sendable {
    var assets: [Asset]
    init(_ assets: [Asset] = []) { self.assets = assets }
    func load() -> [Asset] { assets }
    func save(_ assets: [Asset]) { self.assets = assets }
}

final class WatchlistUseCasesTests: XCTestCase {
    let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
    let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)

    func test_add_persistsAndReturnsList() {
        let store = FakeStore()
        let result = AddToWatchlistUseCase(store: store)(aapl)
        XCTAssertEqual(result, [aapl])
        XCTAssertEqual(store.load(), [aapl])
    }

    func test_add_isIdempotentBySymbol() {
        let store = FakeStore([aapl])
        let result = AddToWatchlistUseCase(store: store)(aapl)
        XCTAssertEqual(result, [aapl])
    }

    func test_remove_bySymbol() {
        let store = FakeStore([aapl, btc])
        let result = RemoveFromWatchlistUseCase(store: store)(symbol: "AAPL")
        XCTAssertEqual(result, [btc])
    }

    func test_load_returnsStored() {
        let store = FakeStore([btc])
        XCTAssertEqual(LoadWatchlistUseCase(store: store)(), [btc])
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter WatchlistUseCasesTests`
Expected: FAIL — types undefined.

- [ ] **Step 3: Implement `AppError` and `Ports`**

`Sources/APTradeApplication/AppError.swift`:

```swift
public enum AppError: Error, Equatable, Sendable {
    case network
    case notFound
    case decoding
    case rateLimited
}
```

`Sources/APTradeApplication/Ports.swift`:

```swift
import APTradeDomain

public protocol MarketDataRepository: Sendable {
    func quote(for symbol: String) async throws -> Quote
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint]
}

public protocol WatchlistStore: Sendable {
    func load() -> [Asset]
    func save(_ assets: [Asset])
}
```

- [ ] **Step 4: Implement the watchlist use cases**

`Sources/APTradeApplication/WatchlistUseCases.swift`:

```swift
import APTradeDomain

public struct AddToWatchlistUseCase {
    private let store: WatchlistStore
    public init(store: WatchlistStore) { self.store = store }

    @discardableResult
    public func callAsFunction(_ asset: Asset) -> [Asset] {
        var assets = store.load()
        guard !assets.contains(where: { $0.symbol == asset.symbol }) else { return assets }
        assets.append(asset)
        store.save(assets)
        return assets
    }
}

public struct RemoveFromWatchlistUseCase {
    private let store: WatchlistStore
    public init(store: WatchlistStore) { self.store = store }

    @discardableResult
    public func callAsFunction(symbol: String) -> [Asset] {
        var assets = store.load()
        assets.removeAll { $0.symbol == symbol }
        store.save(assets)
        return assets
    }
}

public struct LoadWatchlistUseCase {
    private let store: WatchlistStore
    public init(store: WatchlistStore) { self.store = store }
    public func callAsFunction() -> [Asset] { store.load() }
}
```

- [ ] **Step 5: Delete placeholder, run tests**

```bash
rm Sources/APTradeApplication/Placeholder.swift
swift test --filter WatchlistUseCasesTests
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeApplication Tests/APTradeApplicationTests/WatchlistUseCasesTests.swift
git commit -m "feat(application): errors, ports, watchlist use cases"
```

---

### Task 4: Application — quote, history, and search use cases

**Files:**
- Create: `Sources/APTradeApplication/MarketUseCases.swift`
- Test: `Tests/APTradeApplicationTests/MarketUseCasesTests.swift`

**Interfaces:**
- Consumes: `MarketDataRepository`, `AppError` (Task 3), `Quote`, `PricePoint`, `Timeframe`, `Asset`, `AssetKind`.
- Produces:
  - `struct FetchQuotesUseCase { init(repository:); func callAsFunction(symbols: [String]) async -> [String: Result<Quote, AppError>] }`
  - `struct FetchHistoryUseCase { init(repository:); func callAsFunction(symbol: String, timeframe: Timeframe) async throws -> [PricePoint] }`
  - `struct SearchSymbolUseCase { init(repository:); func callAsFunction(query: String) async throws -> Asset }`
    - normalizes query (uppercased, trimmed); infers `AssetKind` (`-USD` suffix → `.crypto`, else `.stock`); throws `AppError.notFound` if no quote.

- [ ] **Step 1: Write failing tests with a fake repository**

`Tests/APTradeApplicationTests/MarketUseCasesTests.swift`:

```swift
import XCTest
@testable import APTradeApplication
import APTradeDomain

final class FakeRepo: MarketDataRepository, @unchecked Sendable {
    var quotes: [String: Quote] = [:]
    var failSymbols: Set<String> = []

    func quote(for symbol: String) async throws -> Quote {
        if failSymbols.contains(symbol) { throw AppError.network }
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        guard quotes[symbol] != nil else { throw AppError.notFound }
        return [PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 1))]
    }
}

final class MarketUseCasesTests: XCTestCase {
    func makeRepo() -> FakeRepo {
        let r = FakeRepo()
        r.quotes["AAPL"] = Quote(symbol: "AAPL", price: Money(amount: 10), previousClose: Money(amount: 9))
        return r
    }

    func test_fetchQuotes_returnsPerSymbolResults() async {
        let repo = makeRepo()
        repo.failSymbols = ["BAD"]
        let out = await FetchQuotesUseCase(repository: repo)(symbols: ["AAPL", "BAD"])
        XCTAssertEqual(try? out["AAPL"]?.get().symbol, "AAPL")
        if case .failure(let e) = out["BAD"] { XCTAssertEqual(e, .network) } else { XCTFail() }
    }

    func test_fetchHistory_returnsPoints() async throws {
        let points = try await FetchHistoryUseCase(repository: makeRepo())(symbol: "AAPL", timeframe: .oneMonth)
        XCTAssertEqual(points.count, 1)
    }

    func test_search_inferaCryptoKind_andNormalizes() async throws {
        let repo = makeRepo()
        repo.quotes["BTC-USD"] = Quote(symbol: "BTC-USD", price: Money(amount: 1), previousClose: Money(amount: 1))
        let asset = try await SearchSymbolUseCase(repository: repo)(query: " btc-usd ")
        XCTAssertEqual(asset.symbol, "BTC-USD")
        XCTAssertEqual(asset.kind, .crypto)
    }

    func test_search_unknownSymbol_throwsNotFound() async {
        do {
            _ = try await SearchSymbolUseCase(repository: makeRepo())(query: "NOPE")
            XCTFail("expected throw")
        } catch { XCTAssertEqual(error as? AppError, .notFound) }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter MarketUseCasesTests`
Expected: FAIL — use cases undefined.

- [ ] **Step 3: Implement the use cases**

`Sources/APTradeApplication/MarketUseCases.swift`:

```swift
import APTradeDomain

public struct FetchQuotesUseCase {
    private let repository: MarketDataRepository
    public init(repository: MarketDataRepository) { self.repository = repository }

    public func callAsFunction(symbols: [String]) async -> [String: Result<Quote, AppError>] {
        await withTaskGroup(of: (String, Result<Quote, AppError>).self) { group in
            for symbol in symbols {
                group.addTask {
                    do {
                        let q = try await repository.quote(for: symbol)
                        return (symbol, .success(q))
                    } catch {
                        return (symbol, .failure((error as? AppError) ?? .network))
                    }
                }
            }
            var out: [String: Result<Quote, AppError>] = [:]
            for await (symbol, result) in group { out[symbol] = result }
            return out
        }
    }
}

public struct FetchHistoryUseCase {
    private let repository: MarketDataRepository
    public init(repository: MarketDataRepository) { self.repository = repository }

    public func callAsFunction(symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        try await repository.history(for: symbol, timeframe: timeframe)
    }
}

public struct SearchSymbolUseCase {
    private let repository: MarketDataRepository
    public init(repository: MarketDataRepository) { self.repository = repository }

    public func callAsFunction(query: String) async throws -> Asset {
        let symbol = query.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !symbol.isEmpty else { throw AppError.notFound }
        _ = try await repository.quote(for: symbol)   // validates existence
        let kind: AssetKind = symbol.hasSuffix("-USD") ? .crypto : .stock
        return Asset(symbol: symbol, name: symbol, kind: kind)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `swift test --filter MarketUseCasesTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeApplication/MarketUseCases.swift Tests/APTradeApplicationTests/MarketUseCasesTests.swift
git commit -m "feat(application): fetch quotes/history and search use cases"
```

---

### Task 5: Infrastructure — Yahoo DTOs and mapping (offline, fixture-tested)

**Files:**
- Create: `Sources/APTradeInfrastructure/YahooChartDTO.swift`
- Create: `Sources/APTradeInfrastructure/YahooMapper.swift`
- Create: `Tests/APTradeInfrastructureTests/Fixtures/aapl_chart.json`
- Test: `Tests/APTradeInfrastructureTests/YahooMapperTests.swift`
- Delete: `Sources/APTradeInfrastructure/Placeholder.swift`

**Interfaces:**
- Consumes: `Quote`, `PricePoint`, `Money` (Domain), `AppError` (Application).
- Produces:
  - `struct YahooChartResponse: Decodable` mirroring `chart.result[0]` (`meta`, `timestamp`, `indicators.quote[0].close`).
  - `enum YahooMapper { static func quote(from data: Data) throws -> Quote; static func history(from data: Data) throws -> [PricePoint] }`
    - throws `AppError.decoding` on malformed JSON; skips null closes in history.

- [ ] **Step 1: Create the JSON fixture**

`Tests/APTradeInfrastructureTests/Fixtures/aapl_chart.json` (trimmed but structurally real — note one `null` close to exercise skipping):

```json
{
  "chart": {
    "result": [
      {
        "meta": {
          "symbol": "AAPL",
          "currency": "USD",
          "instrumentType": "EQUITY",
          "regularMarketPrice": 294.3,
          "chartPreviousClose": 296.42,
          "longName": "Apple Inc.",
          "shortName": "Apple Inc."
        },
        "timestamp": [1782000000, 1782086400, 1782172800],
        "indicators": {
          "quote": [
            { "close": [299.24, null, 298.01] }
          ]
        }
      }
    ],
    "error": null
  }
}
```

- [ ] **Step 2: Write failing tests**

`Tests/APTradeInfrastructureTests/YahooMapperTests.swift`:

```swift
import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class YahooMapperTests: XCTestCase {
    func fixture() throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "aapl_chart", withExtension: "json"))
        return try Data(contentsOf: url)
    }

    func test_quote_mapsPriceAndPreviousClose() throws {
        let q = try YahooMapper.quote(from: fixture())
        XCTAssertEqual(q.symbol, "AAPL")
        XCTAssertEqual(q.price, Money(amount: Decimal(string: "294.3")!))
        XCTAssertEqual(q.previousClose, Money(amount: Decimal(string: "296.42")!))
    }

    func test_history_skipsNullCloses() throws {
        let points = try YahooMapper.history(from: fixture())
        XCTAssertEqual(points.count, 2)            // middle null dropped
        XCTAssertEqual(points.first?.close, Money(amount: Decimal(string: "299.24")!))
    }

    func test_malformedJson_throwsDecoding() {
        XCTAssertThrowsError(try YahooMapper.quote(from: Data("{}".utf8))) { error in
            XCTAssertEqual(error as? AppError, .decoding)
        }
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `swift test --filter YahooMapperTests`
Expected: FAIL — mapper/DTO undefined.

- [ ] **Step 4: Implement DTOs**

`Sources/APTradeInfrastructure/YahooChartDTO.swift`:

```swift
import Foundation

struct YahooChartResponse: Decodable {
    let chart: Chart

    struct Chart: Decodable {
        let result: [ResultItem]?
        let error: YahooError?
    }
    struct YahooError: Decodable { let code: String?; let description: String? }

    struct ResultItem: Decodable {
        let meta: Meta
        let timestamp: [Int]?
        let indicators: Indicators
    }
    struct Meta: Decodable {
        let symbol: String
        let currency: String?
        let instrumentType: String?
        let regularMarketPrice: Double?
        let chartPreviousClose: Double?
        let longName: String?
        let shortName: String?
    }
    struct Indicators: Decodable { let quote: [QuoteBlock] }
    struct QuoteBlock: Decodable { let close: [Double?]? }
}
```

- [ ] **Step 5: Implement the mapper**

Decimals are built from strings (via `Double` → `String`) to avoid binary float artifacts in `Money`.

`Sources/APTradeInfrastructure/YahooMapper.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

enum YahooMapper {
    private static func decimal(_ value: Double) -> Decimal {
        Decimal(string: String(value)) ?? Decimal(value)
    }

    private static func firstResult(from data: Data) throws -> YahooChartResponse.ResultItem {
        let decoded: YahooChartResponse
        do { decoded = try JSONDecoder().decode(YahooChartResponse.self, from: data) }
        catch { throw AppError.decoding }
        guard let item = decoded.chart.result?.first else { throw AppError.notFound }
        return item
    }

    static func quote(from data: Data) throws -> Quote {
        let item = try firstResult(from: data)
        guard let price = item.meta.regularMarketPrice,
              let prev = item.meta.chartPreviousClose else { throw AppError.decoding }
        let currency = item.meta.currency ?? "USD"
        return Quote(
            symbol: item.meta.symbol,
            price: Money(amount: decimal(price), currencyCode: currency),
            previousClose: Money(amount: decimal(prev), currencyCode: currency)
        )
    }

    static func history(from data: Data) throws -> [PricePoint] {
        let item = try firstResult(from: data)
        guard let stamps = item.timestamp,
              let closes = item.indicators.quote.first?.close else { return [] }
        let currency = item.meta.currency ?? "USD"
        var points: [PricePoint] = []
        for (i, stamp) in stamps.enumerated() where i < closes.count {
            guard let close = closes[i] else { continue }
            points.append(PricePoint(
                date: Date(timeIntervalSince1970: TimeInterval(stamp)),
                close: Money(amount: decimal(close), currencyCode: currency)
            ))
        }
        return points
    }
}
```

- [ ] **Step 6: Delete placeholder, run tests**

```bash
rm Sources/APTradeInfrastructure/Placeholder.swift
swift test --filter YahooMapperTests
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeInfrastructure Tests/APTradeInfrastructureTests
git commit -m "feat(infra): Yahoo chart DTOs and offline mapper"
```

---

### Task 6: Infrastructure — Yahoo repository and quote cache

**Files:**
- Create: `Sources/APTradeInfrastructure/TimeframeMapping.swift`
- Create: `Sources/APTradeInfrastructure/YahooMarketDataRepository.swift`
- Create: `Sources/APTradeInfrastructure/CachingMarketDataRepository.swift`
- Test: `Tests/APTradeInfrastructureTests/TimeframeMappingTests.swift`
- Test: `Tests/APTradeInfrastructureTests/CachingRepositoryTests.swift`

**Interfaces:**
- Consumes: `MarketDataRepository` protocol, `YahooMapper`, `Timeframe`, `Quote`, `PricePoint`.
- Produces:
  - `extension Timeframe { var yahooRange: String; var yahooInterval: String }`
  - `final class YahooMarketDataRepository: MarketDataRepository` — `init(session: URLSession = .shared)`; builds the URL, sets `User-Agent`, maps `URLError`/HTTP status to `AppError` (`429 → .rateLimited`, other non-2xx → `.network`).
  - `final class CachingMarketDataRepository: MarketDataRepository` — `init(wrapping: MarketDataRepository, ttl: TimeInterval = 15, now: @escaping () -> Date = Date.init)`; caches quotes by symbol within TTL; history is passed through.

- [ ] **Step 1: Write failing tests for timeframe mapping**

`Tests/APTradeInfrastructureTests/TimeframeMappingTests.swift`:

```swift
import XCTest
@testable import APTradeInfrastructure
import APTradeDomain

final class TimeframeMappingTests: XCTestCase {
    func test_mappings() {
        XCTAssertEqual(Timeframe.oneDay.yahooRange, "1d")
        XCTAssertEqual(Timeframe.oneDay.yahooInterval, "5m")
        XCTAssertEqual(Timeframe.oneWeek.yahooRange, "5d")
        XCTAssertEqual(Timeframe.oneMonth.yahooInterval, "1d")
        XCTAssertEqual(Timeframe.oneYear.yahooRange, "1y")
    }
}
```

- [ ] **Step 2: Write failing tests for the cache (with a counting fake)**

`Tests/APTradeInfrastructureTests/CachingRepositoryTests.swift`:

```swift
import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class CountingRepo: MarketDataRepository, @unchecked Sendable {
    private(set) var quoteCalls = 0
    func quote(for symbol: String) async throws -> Quote {
        quoteCalls += 1
        return Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

final class CachingRepositoryTests: XCTestCase {
    func test_secondCallWithinTTL_isCached() async throws {
        let inner = CountingRepo()
        let cache = CachingMarketDataRepository(wrapping: inner, ttl: 100, now: { Date(timeIntervalSince1970: 0) })
        _ = try await cache.quote(for: "AAPL")
        _ = try await cache.quote(for: "AAPL")
        XCTAssertEqual(inner.quoteCalls, 1)
    }

    func test_callAfterTTL_refetches() async throws {
        let inner = CountingRepo()
        var t = Date(timeIntervalSince1970: 0)
        let cache = CachingMarketDataRepository(wrapping: inner, ttl: 10, now: { t })
        _ = try await cache.quote(for: "AAPL")
        t = Date(timeIntervalSince1970: 20)
        _ = try await cache.quote(for: "AAPL")
        XCTAssertEqual(inner.quoteCalls, 2)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `swift test --filter "TimeframeMappingTests|CachingRepositoryTests"`
Expected: FAIL — types undefined.

- [ ] **Step 4: Implement timeframe mapping**

`Sources/APTradeInfrastructure/TimeframeMapping.swift`:

```swift
import APTradeDomain

extension Timeframe {
    public var yahooRange: String {
        switch self {
        case .oneDay: return "1d"
        case .oneWeek: return "5d"
        case .oneMonth: return "1mo"
        case .oneYear: return "1y"
        }
    }
    public var yahooInterval: String {
        switch self {
        case .oneDay: return "5m"
        case .oneWeek: return "30m"
        case .oneMonth: return "1d"
        case .oneYear: return "1d"
        }
    }
}
```

- [ ] **Step 5: Implement the Yahoo repository**

`Sources/APTradeInfrastructure/YahooMarketDataRepository.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

public final class YahooMarketDataRepository: MarketDataRepository {
    private let session: URLSession
    private let base = "https://query1.finance.yahoo.com/v8/finance/chart/"

    public init(session: URLSession = .shared) { self.session = session }

    private func fetch(symbol: String, range: String, interval: String) async throws -> Data {
        guard let encoded = symbol.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              var components = URLComponents(string: base + encoded) else { throw AppError.network }
        components.queryItems = [
            URLQueryItem(name: "range", value: range),
            URLQueryItem(name: "interval", value: interval),
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
            return data
        } catch let error as AppError {
            throw error
        } catch {
            throw AppError.network
        }
    }

    public func quote(for symbol: String) async throws -> Quote {
        let data = try await fetch(symbol: symbol, range: "1d", interval: "1d")
        return try YahooMapper.quote(from: data)
    }

    public func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        let data = try await fetch(symbol: symbol, range: timeframe.yahooRange, interval: timeframe.yahooInterval)
        return try YahooMapper.history(from: data)
    }
}
```

- [ ] **Step 6: Implement the caching decorator**

`Sources/APTradeInfrastructure/CachingMarketDataRepository.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

public final class CachingMarketDataRepository: MarketDataRepository, @unchecked Sendable {
    private struct Entry { let quote: Quote; let at: Date }
    private let inner: MarketDataRepository
    private let ttl: TimeInterval
    private let now: () -> Date
    private var cache: [String: Entry] = [:]
    private let lock = NSLock()

    public init(wrapping inner: MarketDataRepository, ttl: TimeInterval = 15, now: @escaping () -> Date = Date.init) {
        self.inner = inner
        self.ttl = ttl
        self.now = now
    }

    public func quote(for symbol: String) async throws -> Quote {
        lock.lock()
        if let entry = cache[symbol], now().timeIntervalSince(entry.at) < ttl {
            lock.unlock()
            return entry.quote
        }
        lock.unlock()
        let fresh = try await inner.quote(for: symbol)
        lock.lock(); cache[symbol] = Entry(quote: fresh, at: now()); lock.unlock()
        return fresh
    }

    public func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        try await inner.history(for: symbol, timeframe: timeframe)
    }
}
```

- [ ] **Step 7: Run tests**

Run: `swift test --filter "TimeframeMappingTests|CachingRepositoryTests"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add Sources/APTradeInfrastructure Tests/APTradeInfrastructureTests/TimeframeMappingTests.swift Tests/APTradeInfrastructureTests/CachingRepositoryTests.swift
git commit -m "feat(infra): Yahoo repository, timeframe mapping, quote cache"
```

---

### Task 7: Infrastructure — UserDefaults watchlist store

**Files:**
- Create: `Sources/APTradeInfrastructure/UserDefaultsWatchlistStore.swift`
- Test: `Tests/APTradeInfrastructureTests/UserDefaultsWatchlistStoreTests.swift`

**Interfaces:**
- Consumes: `WatchlistStore` protocol, `Asset`.
- Produces:
  - `final class UserDefaultsWatchlistStore: WatchlistStore` — `init(defaults: UserDefaults = .standard, key: String = "watchlist", seed: [Asset] = [])`; persists `[Asset]` as JSON; returns `seed` (and persists it) when nothing is stored yet.

- [ ] **Step 1: Write failing tests against an isolated suite**

`Tests/APTradeInfrastructureTests/UserDefaultsWatchlistStoreTests.swift`:

```swift
import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class UserDefaultsWatchlistStoreTests: XCTestCase {
    func makeDefaults() -> UserDefaults {
        let suite = "test.\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }
    let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)

    func test_emptyStore_returnsSeed_andPersistsIt() {
        let defaults = makeDefaults()
        let store = UserDefaultsWatchlistStore(defaults: defaults, seed: [aapl])
        XCTAssertEqual(store.load(), [aapl])
        // A second store with no seed still sees the persisted value.
        let store2 = UserDefaultsWatchlistStore(defaults: defaults, seed: [])
        XCTAssertEqual(store2.load(), [aapl])
    }

    func test_saveThenLoad_roundTrips() {
        let defaults = makeDefaults()
        let store = UserDefaultsWatchlistStore(defaults: defaults)
        store.save([aapl])
        XCTAssertEqual(store.load(), [aapl])
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter UserDefaultsWatchlistStoreTests`
Expected: FAIL — type undefined.

- [ ] **Step 3: Implement the store**

`Sources/APTradeInfrastructure/UserDefaultsWatchlistStore.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

public final class UserDefaultsWatchlistStore: WatchlistStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String
    private let seed: [Asset]

    public init(defaults: UserDefaults = .standard, key: String = "watchlist", seed: [Asset] = []) {
        self.defaults = defaults
        self.key = key
        self.seed = seed
    }

    public func load() -> [Asset] {
        guard let data = defaults.data(forKey: key),
              let assets = try? JSONDecoder().decode([Asset].self, from: data) else {
            if !seed.isEmpty { save(seed) }
            return seed
        }
        return assets
    }

    public func save(_ assets: [Asset]) {
        guard let data = try? JSONEncoder().encode(assets) else { return }
        defaults.set(data, forKey: key)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `swift test --filter UserDefaultsWatchlistStoreTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeInfrastructure/UserDefaultsWatchlistStore.swift Tests/APTradeInfrastructureTests/UserDefaultsWatchlistStoreTests.swift
git commit -m "feat(infra): UserDefaults watchlist store"
```

---

### Task 8: Presentation — WatchlistViewModel

**Files:**
- Create: `Sources/APTradeApp/RowState.swift`
- Create: `Sources/APTradeApp/WatchlistViewModel.swift`
- Test: `Tests/APTradeApplicationTests/WatchlistViewModelTests.swift` (lives in an existing test target that can import the app's logic types)

> **Note:** view models live in `APTradeApp`. To unit-test them, add a test target dependency. In `Package.swift`, add a test target:
> ```swift
> .testTarget(name: "APTradeAppTests", dependencies: ["APTradeApp", "APTradeApplication", "APTradeDomain"]),
> ```
> and put the test below in `Tests/APTradeAppTests/WatchlistViewModelTests.swift`. (Executable targets are importable by `@testable import` in SPM.)

**Interfaces:**
- Consumes: `LoadWatchlistUseCase`, `AddToWatchlistUseCase`, `RemoveFromWatchlistUseCase`, `FetchQuotesUseCase`, `SearchSymbolUseCase`, `Asset`, `Quote`, `AppError`.
- Produces:
  - `struct RowState: Identifiable, Equatable { let asset: Asset; var quote: Quote?; var failed: Bool; var id: String { asset.symbol } }`
  - `@MainActor @Observable final class WatchlistViewModel` with:
    - `init(load:add:remove:fetchQuotes:search:)` (use-case dependencies injected)
    - `private(set) var rows: [RowState]`
    - `var isRefreshing: Bool`
    - `var addError: String?`
    - `func onAppear() async` — loads watchlist into rows, then `refresh()`
    - `func refresh() async` — fetches quotes, updates each row's `quote`/`failed`
    - `func add(query: String) async` — search → add → reload rows → refresh; sets `addError` on failure
    - `func remove(symbol: String)` — remove → reload rows

- [ ] **Step 1: Add the app test target to `Package.swift`** (per the note above), then run `swift build` to confirm it resolves.

Run: `swift build`
Expected: success.

- [ ] **Step 2: Write failing tests with fakes**

`Tests/APTradeAppTests/WatchlistViewModelTests.swift`:

```swift
import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

final class VMFakeStore: WatchlistStore, @unchecked Sendable {
    var assets: [Asset]
    init(_ a: [Asset]) { assets = a }
    func load() -> [Asset] { assets }
    func save(_ a: [Asset]) { assets = a }
}

final class VMFakeRepo: MarketDataRepository, @unchecked Sendable {
    var quotes: [String: Quote] = [:]
    var bad: Set<String> = []
    func quote(for symbol: String) async throws -> Quote {
        if bad.contains(symbol) { throw AppError.network }
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

@MainActor
final class WatchlistViewModelTests: XCTestCase {
    func makeVM(store: VMFakeStore, repo: VMFakeRepo) -> WatchlistViewModel {
        WatchlistViewModel(
            load: LoadWatchlistUseCase(store: store),
            add: AddToWatchlistUseCase(store: store),
            remove: RemoveFromWatchlistUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            search: SearchSymbolUseCase(repository: repo)
        )
    }

    func test_onAppear_loadsRowsAndQuotes() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let store = VMFakeStore([aapl])
        let repo = VMFakeRepo()
        repo.quotes["AAPL"] = Quote(symbol: "AAPL", price: Money(amount: 10), previousClose: Money(amount: 9))
        let vm = makeVM(store: store, repo: repo)
        await vm.onAppear()
        XCTAssertEqual(vm.rows.count, 1)
        XCTAssertEqual(vm.rows.first?.quote?.symbol, "AAPL")
        XCTAssertEqual(vm.rows.first?.failed, false)
    }

    func test_refresh_marksFailedRows() async {
        let bad = Asset(symbol: "BAD", name: "BAD", kind: .stock)
        let store = VMFakeStore([bad])
        let repo = VMFakeRepo(); repo.bad = ["BAD"]
        let vm = makeVM(store: store, repo: repo)
        await vm.onAppear()
        XCTAssertEqual(vm.rows.first?.failed, true)
        XCTAssertNil(vm.rows.first?.quote)
    }

    func test_add_unknownSymbol_setsAddError() async {
        let store = VMFakeStore([])
        let vm = makeVM(store: store, repo: VMFakeRepo())
        await vm.add(query: "NOPE")
        XCTAssertNotNil(vm.addError)
        XCTAssertTrue(vm.rows.isEmpty)
    }

    func test_remove_dropsRow() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let store = VMFakeStore([aapl])
        let repo = VMFakeRepo()
        repo.quotes["AAPL"] = Quote(symbol: "AAPL", price: Money(amount: 10), previousClose: Money(amount: 9))
        let vm = makeVM(store: store, repo: repo)
        await vm.onAppear()
        vm.remove(symbol: "AAPL")
        XCTAssertTrue(vm.rows.isEmpty)
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `swift test --filter WatchlistViewModelTests`
Expected: FAIL — types undefined.

- [ ] **Step 4: Implement `RowState`**

`Sources/APTradeApp/RowState.swift`:

```swift
import APTradeDomain

struct RowState: Identifiable, Equatable {
    let asset: Asset
    var quote: Quote?
    var failed: Bool = false
    var id: String { asset.symbol }
}
```

- [ ] **Step 5: Implement `WatchlistViewModel`**

`Sources/APTradeApp/WatchlistViewModel.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class WatchlistViewModel {
    private let load: LoadWatchlistUseCase
    private let add: AddToWatchlistUseCase
    private let remove: RemoveFromWatchlistUseCase
    private let fetchQuotes: FetchQuotesUseCase
    private let search: SearchSymbolUseCase

    private(set) var rows: [RowState] = []
    var isRefreshing = false
    var addError: String?

    init(load: LoadWatchlistUseCase,
         add: AddToWatchlistUseCase,
         remove: RemoveFromWatchlistUseCase,
         fetchQuotes: FetchQuotesUseCase,
         search: SearchSymbolUseCase) {
        self.load = load
        self.add = add
        self.remove = remove
        self.fetchQuotes = fetchQuotes
        self.search = search
    }

    private func reloadRows() {
        let existing = Dictionary(uniqueKeysWithValues: rows.map { ($0.asset.symbol, $0.quote) })
        rows = load().map { RowState(asset: $0, quote: existing[$0.symbol] ?? nil) }
    }

    func onAppear() async {
        reloadRows()
        await refresh()
    }

    func refresh() async {
        guard !rows.isEmpty else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        let results = await fetchQuotes(symbols: rows.map { $0.asset.symbol })
        rows = rows.map { row in
            var copy = row
            switch results[row.asset.symbol] {
            case .success(let q): copy.quote = q; copy.failed = false
            case .failure: copy.failed = true
            case .none: break
            }
            return copy
        }
    }

    func add(query: String) async {
        addError = nil
        do {
            let asset = try await search(query: query)
            _ = add(asset)
            reloadRows()
            await refresh()
        } catch {
            addError = "Couldn't find \"\(query.trimmingCharacters(in: .whitespaces))\""
        }
    }

    func remove(symbol: String) {
        _ = remove(symbol: symbol)
        reloadRows()
    }
}
```

- [ ] **Step 6: Run tests**

Run: `swift test --filter WatchlistViewModelTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add Package.swift Sources/APTradeApp/RowState.swift Sources/APTradeApp/WatchlistViewModel.swift Tests/APTradeAppTests/WatchlistViewModelTests.swift
git commit -m "feat(app): watchlist view model with row state"
```

---

### Task 9: Presentation — AssetDetailViewModel

**Files:**
- Create: `Sources/APTradeApp/AssetDetailViewModel.swift`
- Test: `Tests/APTradeAppTests/AssetDetailViewModelTests.swift`

**Interfaces:**
- Consumes: `FetchHistoryUseCase`, `FetchQuotesUseCase`, `Asset`, `Quote`, `PricePoint`, `Timeframe`.
- Produces:
  - `@MainActor @Observable final class AssetDetailViewModel` with:
    - `init(asset:fetchHistory:fetchQuotes:)`
    - `let asset: Asset`
    - `private(set) var quote: Quote?`
    - `private(set) var points: [PricePoint]`
    - `var timeframe: Timeframe` (default `.oneMonth`)
    - `private(set) var loadState: LoadState` where `enum LoadState: Equatable { case idle, loading, loaded, failed }`
    - `func load() async` — fetches quote + history for current `timeframe`; sets `loadState`
    - `func select(_ timeframe: Timeframe) async` — updates timeframe and reloads history

- [ ] **Step 1: Write failing tests**

`Tests/APTradeAppTests/AssetDetailViewModelTests.swift`:

```swift
import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

final class DetailFakeRepo: MarketDataRepository, @unchecked Sendable {
    var failHistory = false
    var historyByTf: [Timeframe: [PricePoint]] = [:]
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 10), previousClose: Money(amount: 9))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        if failHistory { throw AppError.network }
        return historyByTf[timeframe] ?? []
    }
}

@MainActor
final class AssetDetailViewModelTests: XCTestCase {
    let asset = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)

    func makeVM(_ repo: DetailFakeRepo) -> AssetDetailViewModel {
        AssetDetailViewModel(asset: asset,
                             fetchHistory: FetchHistoryUseCase(repository: repo),
                             fetchQuotes: FetchQuotesUseCase(repository: repo))
    }

    func test_load_setsQuoteAndPoints_loaded() async {
        let repo = DetailFakeRepo()
        repo.historyByTf[.oneMonth] = [PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 5))]
        let vm = makeVM(repo)
        await vm.load()
        XCTAssertEqual(vm.loadState, .loaded)
        XCTAssertEqual(vm.quote?.symbol, "AAPL")
        XCTAssertEqual(vm.points.count, 1)
    }

    func test_load_failure_setsFailed() async {
        let repo = DetailFakeRepo(); repo.failHistory = true
        let vm = makeVM(repo)
        await vm.load()
        XCTAssertEqual(vm.loadState, .failed)
    }

    func test_select_changesTimeframeAndReloads() async {
        let repo = DetailFakeRepo()
        repo.historyByTf[.oneMonth] = []
        repo.historyByTf[.oneYear] = [PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 1)),
                                      PricePoint(date: Date(timeIntervalSince1970: 1), close: Money(amount: 2))]
        let vm = makeVM(repo)
        await vm.load()
        await vm.select(.oneYear)
        XCTAssertEqual(vm.timeframe, .oneYear)
        XCTAssertEqual(vm.points.count, 2)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `swift test --filter AssetDetailViewModelTests`
Expected: FAIL — type undefined.

- [ ] **Step 3: Implement `AssetDetailViewModel`**

`Sources/APTradeApp/AssetDetailViewModel.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class AssetDetailViewModel {
    enum LoadState: Equatable { case idle, loading, loaded, failed }

    let asset: Asset
    private let fetchHistory: FetchHistoryUseCase
    private let fetchQuotes: FetchQuotesUseCase

    private(set) var quote: Quote?
    private(set) var points: [PricePoint] = []
    var timeframe: Timeframe = .oneMonth
    private(set) var loadState: LoadState = .idle

    init(asset: Asset, fetchHistory: FetchHistoryUseCase, fetchQuotes: FetchQuotesUseCase) {
        self.asset = asset
        self.fetchHistory = fetchHistory
        self.fetchQuotes = fetchQuotes
    }

    func load() async {
        loadState = .loading
        let quotes = await fetchQuotes(symbols: [asset.symbol])
        if case .success(let q) = quotes[asset.symbol] { quote = q }
        do {
            points = try await fetchHistory(symbol: asset.symbol, timeframe: timeframe)
            loadState = .loaded
        } catch {
            loadState = .failed
        }
    }

    func select(_ timeframe: Timeframe) async {
        self.timeframe = timeframe
        loadState = .loading
        do {
            points = try await fetchHistory(symbol: asset.symbol, timeframe: timeframe)
            loadState = .loaded
        } catch {
            loadState = .failed
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `swift test --filter AssetDetailViewModelTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeApp/AssetDetailViewModel.swift Tests/APTradeAppTests/AssetDetailViewModelTests.swift
git commit -m "feat(app): asset detail view model"
```

---

### Task 10: Presentation — SwiftUI views and composition root

**Files:**
- Create: `Sources/APTradeApp/Theme.swift`
- Create: `Sources/APTradeApp/WatchlistView.swift`
- Create: `Sources/APTradeApp/AssetDetailView.swift`
- Create: `Sources/APTradeApp/CompositionRoot.swift`
- Create: `Sources/APTradeApp/APTradeApp.swift`
- Delete: `Sources/APTradeApp/Placeholder.swift`

**Interfaces:**
- Consumes: `WatchlistViewModel`, `AssetDetailViewModel`, `RowState`, all use cases, `YahooMarketDataRepository`, `CachingMarketDataRepository`, `UserDefaultsWatchlistStore`, `Timeframe`, `Asset`.
- Produces: the `@main` app. This task's deliverable is verified by **build + manual launch**, not unit tests (SwiftUI views are not unit-tested in this slice).

- [ ] **Step 1: Implement a small theme helper**

`Sources/APTradeApp/Theme.swift`:

```swift
import SwiftUI
import APTradeDomain

enum Theme {
    static func changeColor(_ percent: Percentage?) -> Color {
        guard let percent else { return .secondary }
        if percent.isPositive { return .green }
        if percent.isNegative { return .red }
        return .secondary
    }
}
```

- [ ] **Step 2: Implement the composition root**

`Sources/APTradeApp/CompositionRoot.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeInfrastructure
import APTradeDomain

@MainActor
enum CompositionRoot {
    static let seed: [Asset] = [
        Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock),
        Asset(symbol: "SPY", name: "SPDR S&P 500 ETF", kind: .etf),
        Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto),
        Asset(symbol: "ETH-USD", name: "Ethereum", kind: .crypto),
    ]

    static func makeRepository() -> MarketDataRepository {
        CachingMarketDataRepository(wrapping: YahooMarketDataRepository())
    }

    static func makeStore() -> WatchlistStore {
        UserDefaultsWatchlistStore(seed: seed)
    }

    static func makeWatchlistViewModel() -> WatchlistViewModel {
        let repo = makeRepository()
        let store = makeStore()
        return WatchlistViewModel(
            load: LoadWatchlistUseCase(store: store),
            add: AddToWatchlistUseCase(store: store),
            remove: RemoveFromWatchlistUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            search: SearchSymbolUseCase(repository: repo)
        )
    }

    static func makeDetailViewModel(for asset: Asset) -> AssetDetailViewModel {
        let repo = makeRepository()
        return AssetDetailViewModel(
            asset: asset,
            fetchHistory: FetchHistoryUseCase(repository: repo),
            fetchQuotes: FetchQuotesUseCase(repository: repo)
        )
    }
}
```

- [ ] **Step 3: Implement the watchlist view**

`Sources/APTradeApp/WatchlistView.swift`:

```swift
import SwiftUI
import APTradeDomain

struct WatchlistView: View {
    @State private var viewModel = CompositionRoot.makeWatchlistViewModel()
    @State private var newSymbol = ""

    var body: some View {
        NavigationStack {
            List {
                ForEach(viewModel.rows) { row in
                    NavigationLink(value: row.asset) {
                        WatchlistRow(row: row)
                    }
                }
                .onDelete { indexSet in
                    for index in indexSet {
                        viewModel.remove(symbol: viewModel.rows[index].asset.symbol)
                    }
                }
            }
            .navigationTitle("Watchlist")
            .navigationDestination(for: Asset.self) { asset in
                AssetDetailView(asset: asset)
            }
            .safeAreaInset(edge: .bottom) { addBar }
            .task { await viewModel.onAppear() }
            .refreshable { await viewModel.refresh() }
        }
        .frame(minWidth: 420, minHeight: 520)
        .preferredColorScheme(.dark)
    }

    private var addBar: some View {
        VStack(spacing: 4) {
            if let error = viewModel.addError {
                Text(error).font(.caption).foregroundStyle(.red)
            }
            HStack {
                TextField("Add symbol (e.g. NVDA, SOL-USD)", text: $newSymbol)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { submit() }
                Button("Add") { submit() }
                    .disabled(newSymbol.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding()
        .background(.thinMaterial)
    }

    private func submit() {
        let query = newSymbol
        newSymbol = ""
        Task { await viewModel.add(query: query) }
    }
}

private struct WatchlistRow: View {
    let row: RowState

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(row.asset.symbol).font(.headline)
                Text(row.asset.name).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let quote = row.quote {
                VStack(alignment: .trailing) {
                    Text(quote.price.formatted).font(.headline.monospacedDigit())
                    Text(quote.changePercent.formatted)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Theme.changeColor(quote.changePercent))
                }
            } else if row.failed {
                Text("—").foregroundStyle(.secondary)
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .padding(.vertical, 4)
    }
}
```

- [ ] **Step 4: Implement the detail view with a Swift Charts line chart**

`Sources/APTradeApp/AssetDetailView.swift`:

```swift
import SwiftUI
import Charts
import APTradeDomain

struct AssetDetailView: View {
    @State private var viewModel: AssetDetailViewModel

    init(asset: Asset) {
        _viewModel = State(initialValue: CompositionRoot.makeDetailViewModel(for: asset))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            chart
            timeframePicker
            Spacer()
        }
        .padding()
        .navigationTitle(viewModel.asset.symbol)
        .task { await viewModel.load() }
        .frame(minWidth: 480, minHeight: 420)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(viewModel.asset.name).font(.title2).bold()
            if let quote = viewModel.quote {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(quote.price.formatted).font(.largeTitle.monospacedDigit())
                    Text(quote.changePercent.formatted)
                        .font(.title3.monospacedDigit())
                        .foregroundStyle(Theme.changeColor(quote.changePercent))
                }
            }
        }
    }

    @ViewBuilder
    private var chart: some View {
        switch viewModel.loadState {
        case .loading, .idle:
            ProgressView().frame(maxWidth: .infinity, minHeight: 220)
        case .failed:
            ContentUnavailableView("Couldn't load chart", systemImage: "chart.line.downtrend.xyaxis")
                .frame(minHeight: 220)
        case .loaded:
            Chart(viewModel.points, id: \.date) { point in
                LineMark(
                    x: .value("Date", point.date),
                    y: .value("Price", (point.close.amount as NSDecimalNumber).doubleValue)
                )
                .interpolationMethod(.catmullRom)
            }
            .frame(minHeight: 220)
        }
    }

    private var timeframePicker: some View {
        Picker("Timeframe", selection: Binding(
            get: { viewModel.timeframe },
            set: { tf in Task { await viewModel.select(tf) } }
        )) {
            ForEach(Timeframe.allCases, id: \.self) { tf in
                Text(tf.displayName).tag(tf)
            }
        }
        .pickerStyle(.segmented)
    }
}
```

- [ ] **Step 5: Implement the app entry point**

`Sources/APTradeApp/APTradeApp.swift`:

```swift
import SwiftUI

@main
struct APTradeApp: App {
    var body: some Scene {
        WindowGroup("APTrade Lite") {
            WatchlistView()
        }
    }
}
```

- [ ] **Step 6: Delete placeholder, build, and run the full suite**

```bash
rm Sources/APTradeApp/Placeholder.swift
swift build
swift test
```
Expected: build succeeds; all tests pass.

- [ ] **Step 7: Manual launch (verification)**

Run: `swift run APTradeApp`
Expected: a dark window titled "APTrade Lite" opens showing AAPL, SPY, BTC-USD, ETH-USD with live prices and colored % change; adding `NVDA` adds a row; clicking a row opens the chart; switching timeframes reloads it; relaunching preserves the watchlist.

> If the SPM executable does not present a normal app window/menu bar on this macOS version, create a thin Xcode macOS App target that depends on the package and hosts `WatchlistView()` — the package logic is unchanged.

- [ ] **Step 8: Commit**

```bash
git add Sources/APTradeApp
git commit -m "feat(app): SwiftUI watchlist + detail views and composition root"
```

---

## Self-Review

**Spec coverage:**
- Watchlist (add/remove/live price/daily %) → Tasks 3, 8, 10. ✅
- Detail price chart + timeframes (1D/1W/1M/1Y) → Tasks 2, 6, 9, 10. ✅
- Single Yahoo data source, no key → Tasks 5, 6. ✅
- Local persistence → Task 7. ✅
- Miniature Clean Architecture across 4 layers → Tasks 1–10 mapped to layers. ✅
- Money never a float; no force-unwraps → enforced in code (Decimal, guard/optional handling). ✅
- Seeded watchlist AAPL/SPY/BTC-USD/ETH-USD → Task 10 composition root. ✅
- Tests never hit network; fixture-based mapping → Task 5. ✅

**Placeholder scan:** No TBD/TODO/"handle edge cases" — all steps contain concrete code and commands. ✅

**Type consistency:** `MarketDataRepository`, `WatchlistStore`, use-case `callAsFunction` signatures, `RowState`, `LoadState`, and view-model initializers are referenced identically across tasks 3→4→6→7→8→9→10. ✅
