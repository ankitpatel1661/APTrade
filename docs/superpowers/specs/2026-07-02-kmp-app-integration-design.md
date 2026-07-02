# KMP Increment 3 — Real App on the Shared Kotlin Core (Quote Path) — Design Spec

**Date:** 2026-07-02
**Status:** Approved (design)
**Author:** APTrade team

## Context

Increment 1 (merged 84b89dc) stood up the KMP `shared/` module and proved it through a
standalone console harness. Increment 2 (merged 6963b9d) replaced the stub with a live Ktor
`YahooMarketDataRepository` (Darwin engine on Apple, CIO on JVM; exact-decimal money;
`@Throws` Swift bridging), proven live from the harness.

The real APTrade app still runs entirely on its Swift-native data path. This increment wires
the **actual app** — via `CompositionRoot` — to the shared Kotlin use case, for the **quote
path only**. It is the first time `Package.swift` and `Sources/` change for KMP.

## Goal

`CompositionRoot.makeRepository()` returns a repository whose `quote(for:)` is served by the
shared Kotlin core (`FetchMarketQuotes` → Kotlin `YahooMarketDataRepository` → Ktor Darwin),
while `history`/`candles`/`profile`/`search` continue on the Swift-native Yahoo path. App
behavior is otherwise unchanged: same caching, same error surface, same UI.

## Decisions (user-confirmed)

1. **Scope: quote path only.** The other four `MarketDataRepository` methods stay Swift-native
   until increment 4 ports them.
2. **Build coupling: accept + helper script.** The root `Package.swift` gains a `binaryTarget`
   pointing at the git-ignored `shared/build/XCFrameworks/release/Shared.xcframework`. A
   `scripts/build-shared.sh` (sets `JAVA_HOME`, `DEVELOPER_DIR`; runs the Gradle assemble)
   plus a README note cover the one-time step per clone / per shared-code change. Committing
   the binary and keeping the app decoupled were both rejected.

## Chosen approach: Infrastructure decorator

Rejected alternatives: swapping at the use-case level (Swift `FetchQuotesUseCase` returns
per-symbol `Result`s — partial failure — while the KMP use case is fail-fast; swapping would
change app behavior and touch every ViewModel) and using the KMP repository as the port
directly (four of five methods aren't ported yet).

### Components

**1. Shared module (Kotlin) — `Quote` gains `previousClose`**

- `Quote(symbol, price: Money, previousClose: Money, changePercent: Double)` — additive;
  `changePercent` stays (the harness and future Android UI use it).
- `YahooQuoteMapper`: `previousClose = chartPreviousClose?.let { Money(it, currency) } ?:
  Money(price, currency)` — when Yahoo omits previous close, it defaults to `price`
  (change = 0), matching today's `changePercent = 0.0` behavior. The DTO already parses
  `chartPreviousClose` through `BigDecimalWireSerializer`, so the value is exact.
- Update the stub repository and any tests constructing `Quote`; add mapper tests for
  previousClose present/absent.

**2. Root `Package.swift` — binaryTarget**

```swift
.binaryTarget(name: "Shared", path: "shared/build/XCFrameworks/release/Shared.xcframework"),
```

Only `APTradeInfrastructure` adds `"Shared"` to its dependencies. `APTradeDomain` and
`APTradeApplication` stay framework-free — the dependency rule holds (the KMP framework is
outermost infrastructure).

**3. Adapter — `Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift`**

```swift
public final class SharedCoreMarketDataRepository: MarketDataRepository, @unchecked Sendable
```

- Holds an immutable KMP handle (`FetchMarketQuotes` over the Kotlin
  `YahooMarketDataRepository()`), created at init. `@unchecked Sendable` is justified:
  the stored properties are `let`, and Kotlin/Native objects are safely shareable under
  the current KMP memory model. Also holds the injected Swift `fallback: MarketDataRepository`.
- `quote(for:)`: calls the shared use case with `[symbol]`, takes the first result;
  empty result maps to `AppError.notFound`.
- **Type mapping (exact, no Double):** KMP `Money` → Swift `Money` via
  `Decimal(string: amount.toStringExpanded())`; failure to parse → `AppError.decoding`.
  Swift `Quote(symbol:price:previousClose:)` — the app derives change/changePercent itself.
- **Error mapping:** the KMP `@Throws` bridge surfaces Kotlin exceptions as `NSError`.
  Map `QuoteError.RateLimited` → `AppError.rateLimited`, `QuoteError.NotFound` →
  `AppError.notFound`, `QuoteError.Network` and anything unrecognized → `AppError.network`.
  `CancellationError` re-thrown untouched.
- `history`/`candles`/`profile`/`search`: one-line delegation to `fallback`.

**4. CompositionRoot**

```swift
static func makeRepository() -> MarketDataRepository {
    CachingMarketDataRepository(
        wrapping: SharedCoreMarketDataRepository(fallback: YahooMarketDataRepository()))
}
```

The caching actor stays outermost: TTL and request-coalescing behavior are untouched, and the
Kotlin path gets the same protection the Swift path had.

**5. Build orchestration — `scripts/build-shared.sh` + README**

Script: exports `JAVA_HOME` (JDK 17 explicit path) and prefixes `DEVELOPER_DIR` (Xcode.app),
runs `./gradlew :shared:assembleSharedReleaseXCFramework`, prints the artifact path. README
gains a short "building the shared Kotlin core" section: run once per clone or whenever
`shared/` changes, before `swift build` / opening the Xcode project.

### Data flow (quote)

ViewModel → `FetchQuotesUseCase` (Swift, per-symbol task group, unchanged) →
`CachingMarketDataRepository` (unchanged) → `SharedCoreMarketDataRepository.quote(for:)` →
KMP `FetchMarketQuotes.execute(symbols: [symbol])` → Kotlin `YahooMarketDataRepository`
(Ktor Darwin, exact-decimal parse) → KMP `Quote` → adapter maps to Swift `Quote` → up the
same chain. Errors: `QuoteError` → NSError bridge → `AppError` in the adapter.

## Error handling

- All adapter throws are `AppError` (the app's existing error surface); no NSError or Kotlin
  types leak past infrastructure.
- Per-symbol failure isolation is preserved because the adapter is called per symbol by the
  existing Swift use case — the KMP use case's fail-fast concurrency is never exercised with
  more than one symbol from the app path.

## Testing

- **Kotlin:** mapper tests for `previousClose` (present → exact value; absent → equals price);
  existing suite stays green (`./gradlew :shared:jvmTest`).
- **Swift:** new `APTradeInfrastructureTests` for the adapter. The adapter's KMP→Swift
  mapping lives in `internal` static functions so tests hit them directly (no Kotlin-protocol
  stubbing through the ObjC bridge): `Money`/`Quote` mapping exactness (construct KMP `Quote`
  directly; the framework exports its initializer) and error mapping (each `QuoteError` case →
  expected `AppError`). Fallback delegation (history/candles/profile/search hit the fallback,
  never the KMP path) is tested with a Swift fake `MarketDataRepository`.
- **Regression:** full root `swift test` (macOS) and the iOS package scheme
  (`APTradeLite-Package`) build; skeleton harness still runs.
- **Live proof:** launch the macOS app — watchlist/dashboard quotes now arrive through the
  Kotlin core. Visual verification is the user's (dev-build UI can't be driven by tooling).

## Out of scope

`history`/`candles`/`profile`/`search` porting (increment 4); `androidTarget` + Compose
(increment 5); Windows (increment 6); retiring the skeleton harness (kept as a cheap
smoke-test rig); SKIE or other interop sugar; closing the KMP `HttpClient` (tracked debt,
picked up when the adapter's lifecycle becomes DI-managed).

## Definition of Done

1. Kotlin `Quote` carries exact `previousClose`; `./gradlew :shared:jvmTest` green.
2. Root `Package.swift` has the `Shared` binaryTarget; only Infrastructure depends on it.
3. Adapter maps types exactly and errors completely; new Swift tests green; full
   `swift test` green (macOS) after `scripts/build-shared.sh`.
4. `CompositionRoot.makeRepository()` routes quotes through the shared core; no ViewModel,
   Application, or Domain source changes.
5. iOS package scheme still builds.
6. macOS app runs with live quotes served by the Kotlin core (user-verified visually).
