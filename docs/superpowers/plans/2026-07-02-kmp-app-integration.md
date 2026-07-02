# KMP Increment 3 — Real App on the Shared Core (Quote Path) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `CompositionRoot.makeRepository()` serves `quote(for:)` through the shared Kotlin core (KMP `FetchMarketQuotes` → Kotlin `YahooMarketDataRepository` → Ktor Darwin) while history/candles/profile/search stay Swift-native.

**Architecture:** The shared Kotlin `Quote` gains an exact `previousClose` (the Swift domain `Quote` requires it). The root `Package.swift` gains a `Shared` binaryTarget consumed only by `APTradeInfrastructure`, where a new `SharedCoreMarketDataRepository` decorator implements the frozen `MarketDataRepository` port: quote via KMP, everything else delegated to the injected Swift Yahoo repository. The caching actor stays outermost.

**Tech Stack:** Kotlin Multiplatform 2.1.0 / Ktor 3.0.3 / bignum BigDecimal (shared); Swift 6 SwiftPM, no new Swift dependencies.

**Spec:** `docs/superpowers/specs/2026-07-02-kmp-app-integration-design.md`

## Global Constraints

- Every `./gradlew` shell needs `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`; never system Gradle.
- Apple-target Gradle tasks and all `swift build`/`swift test`/`swift run` need the `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` prefix.
- Money is exact-decimal end to end: KMP BigDecimal → decimal string → Swift `Decimal(string:)`. Money never passes through `Double`; only `changePercent` is a `Double`.
- The KMP `QuoteRepository` port, Kotlin `YahooMarketDataRepository`, and `StubQuoteRepository`'s role are frozen; `APTradeDomain` and `APTradeApplication` sources are untouched this increment.
- All adapter throws are `AppError` (`.network`, `.notFound`, `.decoding`, `.rateLimited`) — no Kotlin/NSError types leak past infrastructure.
- KMP framework types collide with Swift domain names (`Quote`, `Money`, `YahooMarketDataRepository`): in files importing both, qualify KMP types as `Shared.X` and Swift domain types as `APTradeDomain.X`.

---

### Task 1: Shared Kotlin `Quote.previousClose` + `Money.amountText`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Quote.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Money.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapper.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/StubQuoteRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/QuoteTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/MoneyTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchMarketQuotesTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapperTest.kt`

**Interfaces:**
- Consumes: existing `Quote`, `Money`, `YahooQuoteMapper`, `YahooChartResponse.Meta.chartPreviousClose` (already parsed exactly).
- Produces: `Quote(symbol: String, price: Money, previousClose: Money, changePercent: Double)` and `Money.amountText: String` (plain decimal string, e.g. `"229.35"`) — Task 3's Swift adapter relies on both, exported through the framework.

- [ ] **Step 1: Update/add failing tests**

Replace the whole of `shared/src/commonTest/kotlin/com/aptrade/shared/domain/QuoteTest.kt` with:

```kotlin
package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class QuoteTest {
    @Test
    fun holdsSymbolPricePreviousCloseAndChange() {
        val quote = Quote(
            symbol = "AAPL",
            price = Money.usd("229.35"),
            previousClose = Money.usd("227.45"),
            changePercent = 0.84,
        )
        assertEquals("AAPL", quote.symbol)
        assertEquals(Money.usd("229.35"), quote.price)
        assertEquals(Money.usd("227.45"), quote.previousClose)
        assertEquals(0.84, quote.changePercent)
    }
}
```

Add to `shared/src/commonTest/kotlin/com/aptrade/shared/domain/MoneyTest.kt` (inside the existing test class; keep everything already there):

```kotlin
    @Test
    fun amountTextIsPlainDecimalString() {
        assertEquals("229.35", Money.usd("229.35").amountText)
    }
```

In `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchMarketQuotesTest.kt`, change the `expected` line to:

```kotlin
        val expected = listOf(Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.2))
```

Add two tests to `YahooQuoteMapperTest` (inside the existing class; keep existing tests):

```kotlin
    @Test
    fun mapsPreviousCloseExactly() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "regularMarketPrice":229.35,"chartPreviousClose":227.45}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val quote = YahooQuoteMapper.quote(parsed)

        assertEquals(Money(BigDecimal.parseString("227.45"), "USD"), quote.previousClose)
    }

    @Test
    fun missingPreviousCloseDefaultsToPrice() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "regularMarketPrice":229.35}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val quote = YahooQuoteMapper.quote(parsed)

        assertEquals(quote.price, quote.previousClose)
        assertEquals(0.0, quote.changePercent)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest --console=plain
```
Expected: compilation FAILS (`previousClose`/`amountText` unresolved) — that is the failing state.

- [ ] **Step 3: Implement**

Replace `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Quote.kt` with:

```kotlin
package com.aptrade.shared.domain

data class Quote(
    val symbol: String,
    val price: Money,
    val previousClose: Money,
    val changePercent: Double,
)
```

In `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Money.kt`, add inside `data class Money` (next to `formatted`):

```kotlin
    /**
     * Plain decimal string of the exact amount (e.g. "229.35") — the lossless
     * cross-language bridge format (Swift reads it via Decimal(string:)).
     */
    val amountText: String
        get() = amount.toStringExpanded()
```

In `YahooQuoteMapper.kt`, replace the `return` statement with:

```kotlin
        val previousClose = prev?.let { Money(it, currency) } ?: Money(price, currency)
        return Quote(
            symbol = meta.symbol,
            price = Money(price, currency),
            previousClose = previousClose,
            changePercent = changePercent,
        )
```

In `StubQuoteRepository.kt`, replace the three `Quote(...)` lines with:

```kotlin
        Quote("AAPL", Money.usd("229.35"), Money.usd("227.44"), 0.84),
        Quote("MSFT", Money.usd("430.16"), Money.usd("431.97"), -0.42),
        Quote("BTC", Money.usd("61234.00"), Money.usd("59944.98"), 2.15),
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :shared:jvmTest --console=plain
```
Expected: BUILD SUCCESSFUL, 15 tests (12 prior + amountText + 2 mapper), 0 failures.

- [ ] **Step 5: Commit**

```bash
git add shared/src
git commit -m "feat(kmp): Quote carries exact previousClose; Money.amountText bridge accessor"
```

---

### Task 2: `scripts/build-shared.sh` + README section + fresh artifact

**Files:**
- Create: `scripts/build-shared.sh`
- Modify: `README.md` (add a "Building the shared Kotlin core" subsection near the existing build/run instructions)

**Interfaces:**
- Consumes: the Gradle `:shared:assembleSharedReleaseXCFramework` task.
- Produces: `shared/build/XCFrameworks/release/Shared.xcframework` containing Task 1's new `Quote` API — Task 3's `Package.swift` binaryTarget points at this path.

- [ ] **Step 1: Create `scripts/build-shared.sh`**

```bash
#!/bin/sh
# Builds Shared.xcframework (the KMP core) for the Swift app to link.
# Run once per clone and after any change under shared/, before `swift build`.
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
./gradlew :shared:assembleSharedReleaseXCFramework --console=plain
echo "OK: shared/build/XCFrameworks/release/Shared.xcframework"
```

Then: `chmod +x scripts/build-shared.sh`

- [ ] **Step 2: Add the README section**

Insert after the existing build/run instructions in `README.md`:

```markdown
### Building the shared Kotlin core

Parts of the app (live quotes) are served by a Kotlin Multiplatform core in `shared/`,
linked as `Shared.xcframework`. The framework is a build artifact (not committed), so run

    ./scripts/build-shared.sh

once per clone — and again after any change under `shared/` — before `swift build` or
opening the Xcode project. Requires JDK 17 and full Xcode (the script points at the
Homebrew OpenJDK 17 and `/Applications/Xcode.app` by default; override via `JAVA_HOME`
/ `DEVELOPER_DIR`).
```

- [ ] **Step 3: Run the script (produces the artifact Task 3 needs)**

```bash
./scripts/build-shared.sh
ls shared/build/XCFrameworks/release/Shared.xcframework
```
Expected: `OK: …` line; framework lists `Info.plist`, `ios-arm64`, `ios-arm64-simulator`, `macos-arm64`. Takes ~5 minutes; do not abort.

- [ ] **Step 4: Commit**

```bash
git add scripts/build-shared.sh README.md
git commit -m "build(kmp): add build-shared.sh and README section for the Shared.xcframework step"
```

---

### Task 3: binaryTarget + `SharedCoreMarketDataRepository` adapter

**Files:**
- Modify: `Package.swift`
- Create: `Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift`
- Test: `Tests/APTradeInfrastructureTests/SharedCoreMarketDataRepositoryTests.swift`

**Interfaces:**
- Consumes: `Shared.FetchMarketQuotes(repository:)` / `execute(symbols:) async throws -> [Shared.Quote]`, `Shared.Quote.symbol/.price/.previousClose`, `Shared.Money.amountText/.currencyCode`, `Shared.Money.companion.usd(value:)`, KMP error classes (`QuoteErrorRateLimited`, `QuoteErrorNotFound`, `QuoteErrorNetwork` — Kotlin nested sealed cases flatten in ObjC export), Swift `MarketDataRepository` port, `AppError`.
- Produces: `public final class SharedCoreMarketDataRepository: MarketDataRepository` with `public init(fallback: MarketDataRepository)` — Task 4's `CompositionRoot` constructs it. Internal statics `mapQuote`, `mapMoney`, `mapError` (tested directly).

- [ ] **Step 1: Wire the binaryTarget in `Package.swift`**

Add to `targets:` (before `.target(name: "APTradeDomain")`):

```swift
        .binaryTarget(
            name: "Shared",
            path: "shared/build/XCFrameworks/release/Shared.xcframework"
        ),
```

Change the `APTradeInfrastructure` target and its test target dependencies to:

```swift
        .target(name: "APTradeInfrastructure", dependencies: ["APTradeApplication", "APTradeDomain", "Shared"]),
```

```swift
        .testTarget(
            name: "APTradeInfrastructureTests",
            dependencies: ["APTradeInfrastructure", "APTradeApplication", "APTradeDomain", "Shared"],
            resources: [.process("Fixtures")]
        ),
```

`APTradeDomain`, `APTradeApplication`, `APTradeApp`, `APTradeMac` are NOT changed.

- [ ] **Step 2: Write the failing tests**

Create `Tests/APTradeInfrastructureTests/SharedCoreMarketDataRepositoryTests.swift`:

```swift
import Foundation
import XCTest
import APTradeApplication
import APTradeDomain
import Shared
@testable import APTradeInfrastructure

// NOTE: KMP `Quote`/`Money` collide with APTradeDomain's — Shared.X vs APTradeDomain.X.
// Kotlin companion functions surface as `.companion`; Kotlin `object`s as `.shared`.
final class SharedCoreMarketDataRepositoryTests: XCTestCase {
    private func kmpMoney(_ text: String) -> Shared.Money {
        Shared.Money.companion.usd(value: text)
    }

    func testMapsMoneyExactly() throws {
        let mapped = try SharedCoreMarketDataRepository.mapMoney(kmpMoney("229.35"))
        XCTAssertEqual(mapped, APTradeDomain.Money(amount: Decimal(string: "229.35")!, currencyCode: "USD"))
    }

    func testMapsQuoteWithPreviousClose() throws {
        let kmp = Shared.Quote(
            symbol: "AAPL",
            price: kmpMoney("229.35"),
            previousClose: kmpMoney("227.45"),
            changePercent: 0.84)
        let mapped = try SharedCoreMarketDataRepository.mapQuote(kmp)
        XCTAssertEqual(mapped.symbol, "AAPL")
        XCTAssertEqual(mapped.price.amount, Decimal(string: "229.35")!)
        XCTAssertEqual(mapped.previousClose.amount, Decimal(string: "227.45")!)
    }

    func testMapsKotlinErrorsToAppError() {
        func nsError(_ kotlin: Any) -> Error {
            NSError(domain: "KotlinException", code: 0, userInfo: ["KotlinException": kotlin])
        }
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(QuoteErrorRateLimited.shared)), .rateLimited)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(QuoteErrorNotFound.shared)), .notFound)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(QuoteErrorNetwork(reason: "boom"))), .network)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(URLError(.timedOut)), .network)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(AppError.notFound), .notFound)
    }

    func testDelegatesNonQuoteCallsToFallback() async throws {
        let fallback = RecordingRepository()
        let repo = SharedCoreMarketDataRepository(fallback: fallback)
        _ = try await repo.history(for: "AAPL", timeframe: .oneDay)
        _ = try await repo.candles(for: "AAPL", timeframe: .oneDay)
        _ = try await repo.profile(for: "AAPL")
        _ = try await repo.search(query: "app")
        let calls = await fallback.calls
        XCTAssertEqual(calls, ["history", "candles", "profile", "search"])
    }
}

private actor CallLog {
    var calls: [String] = []
    func record(_ name: String) { calls.append(name) }
}

private final class RecordingRepository: MarketDataRepository, @unchecked Sendable {
    private let log = CallLog()
    var calls: [String] { get async { await log.calls } }

    func quote(for symbol: String) async throws -> APTradeDomain.Quote {
        await log.record("quote")
        throw AppError.notFound
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        await log.record("history"); return []
    }
    func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        await log.record("candles"); return []
    }
    func profile(for symbol: String) async throws -> Asset {
        await log.record("profile"); return Asset(symbol: symbol, name: symbol, kind: .stock)
    }
    func search(query: String) async throws -> [Asset] {
        await log.record("search"); return []
    }
}
```

If `Timeframe`'s one-day case is named differently, use the actual first case (check `Sources/APTradeDomain/Timeframe.swift`) — the specific timeframe is irrelevant to the delegation assertion. If the flattened Kotlin error class names differ, check the generated header (`find shared/build -name "Shared.h" | head -1`, grep `QuoteError`) and use those names in both test and implementation.

- [ ] **Step 3: Run tests to verify they fail**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter SharedCoreMarketDataRepositoryTests
```
Expected: compilation FAILS (`SharedCoreMarketDataRepository` not defined).

- [ ] **Step 4: Implement the adapter**

Create `Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain
import Shared

/// Serves `quote(for:)` from the shared Kotlin core (KMP FetchMarketQuotes → Ktor);
/// the four not-yet-ported calls delegate to the Swift-native fallback repository.
/// @unchecked Sendable: all stored properties are immutable, and Kotlin/Native
/// objects are safely shareable across threads under the current KMP memory model.
public final class SharedCoreMarketDataRepository: MarketDataRepository, @unchecked Sendable {
    private let fetchQuotes: Shared.FetchMarketQuotes
    private let fallback: MarketDataRepository

    public init(fallback: MarketDataRepository) {
        self.fetchQuotes = Shared.FetchMarketQuotes(repository: Shared.YahooMarketDataRepository())
        self.fallback = fallback
    }

    public func quote(for symbol: String) async throws -> APTradeDomain.Quote {
        do {
            let quotes = try await fetchQuotes.execute(symbols: [symbol])
            guard let first = quotes.first else { throw AppError.notFound }
            return try Self.mapQuote(first)
        } catch {
            throw Self.mapError(error)
        }
    }

    public func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        try await fallback.history(for: symbol, timeframe: timeframe)
    }

    public func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        try await fallback.candles(for: symbol, timeframe: timeframe)
    }

    public func profile(for symbol: String) async throws -> Asset {
        try await fallback.profile(for: symbol)
    }

    public func search(query: String) async throws -> [Asset] {
        try await fallback.search(query: query)
    }

    // MARK: - Mapping (internal statics so tests hit them directly)

    static func mapQuote(_ quote: Shared.Quote) throws -> APTradeDomain.Quote {
        APTradeDomain.Quote(
            symbol: quote.symbol,
            price: try mapMoney(quote.price),
            previousClose: try mapMoney(quote.previousClose))
    }

    static func mapMoney(_ money: Shared.Money) throws -> APTradeDomain.Money {
        guard let amount = Decimal(string: money.amountText) else { throw AppError.decoding }
        return APTradeDomain.Money(amount: amount, currencyCode: money.currencyCode)
    }

    /// Kotlin exceptions cross the @Throws bridge as NSError carrying the original
    /// exception under the "KotlinException" userInfo key.
    static func mapError(_ error: Error) -> AppError {
        if let app = error as? AppError { return app }
        switch (error as NSError).userInfo["KotlinException"] {
        case is QuoteErrorRateLimited: return .rateLimited
        case is QuoteErrorNotFound: return .notFound
        default: return .network
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter SharedCoreMarketDataRepositoryTests
```
Expected: all new tests PASS.

- [ ] **Step 6: Full macOS suite**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test
```
Expected: entire suite green (nothing outside Infrastructure changed).

- [ ] **Step 7: Commit**

```bash
git add Package.swift Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift Tests/APTradeInfrastructureTests/SharedCoreMarketDataRepositoryTests.swift
git commit -m "feat(kmp): SharedCoreMarketDataRepository routes quotes through the shared Kotlin core"
```

---

### Task 4: CompositionRoot swap + full regression

**Files:**
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (only `makeRepository()`)

**Interfaces:**
- Consumes: Task 3's `SharedCoreMarketDataRepository(fallback:)`.
- Produces: the running app fetching quotes via the Kotlin core; no other composition changes.

- [ ] **Step 1: Swap the repository composition**

In `Sources/APTradeApp/CompositionRoot.swift`, replace `makeRepository()` with:

```swift
    static func makeRepository() -> MarketDataRepository {
        // Quotes come from the shared Kotlin core; the remaining calls stay on the
        // Swift-native Yahoo path until later increments port them.
        CachingMarketDataRepository(
            wrapping: SharedCoreMarketDataRepository(fallback: YahooMarketDataRepository()))
    }
```

- [ ] **Step 2: Full macOS build + tests**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test
```
Expected: build complete; full suite green.

- [ ] **Step 3: iOS package scheme still builds**

The generated `APTrade.xcodeproj` shadows the package — park it first:

```bash
mv APTrade.xcodeproj APTrade.xcodeproj.parked
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -scheme APTradeLite-Package -destination 'generic/platform=iOS Simulator' build -quiet
mv APTrade.xcodeproj.parked APTrade.xcodeproj
```
Expected: `** BUILD SUCCEEDED **`. Restore the project even if the build fails.

- [ ] **Step 4: Harness regression (shared module still consumable standalone)**

```bash
cd skeletonHarness
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift run
cd ..
```
Expected: three live quote rows, no crash (network required; a caught `error:` line is acceptable offline).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeApp/CompositionRoot.swift
git commit -m "feat(kmp): app quotes now served by the shared Kotlin core"
```

---

## Definition of Done (whole plan)

1. `./gradlew :shared:jvmTest` — 15 tests, 0 failures; KMP `Quote` carries exact `previousClose`.
2. Root `Package.swift` has the `Shared` binaryTarget; only `APTradeInfrastructure` (+ its tests) depends on it; Domain/Application untouched.
3. Adapter maps types exactly (decimal-string bridge) and errors completely; new Swift tests green; full `swift test` green after `./scripts/build-shared.sh`.
4. `CompositionRoot.makeRepository()` routes quotes through the shared core; no ViewModel/Application/Domain source changes.
5. iOS `APTradeLite-Package` scheme builds.
6. macOS app runs with quotes served by the Kotlin core (user verifies visually — dev-build UI can't be driven by tooling).

## Notes for the implementer

- ObjC-export naming can differ from the plan's guesses (`QuoteErrorRateLimited`, `.shared`, `.companion`): the generated header inside the xcframework (`Shared.framework/Headers/Shared.h`) is the source of truth. Adjust names in BOTH test and implementation, and note the deviation in your report.
- Never route money through `Double`. The bridge is `Money.amountText` (string) → `Decimal(string:)`.
- Do not modify `Sources/APTradeDomain`, `Sources/APTradeApplication`, any ViewModel, the KMP port, or the Kotlin `YahooMarketDataRepository`.
- `swift build`/`swift test` fail with "missing binaryTarget artifact" if the xcframework hasn't been assembled — run `./scripts/build-shared.sh` first (Task 2 produces it).
