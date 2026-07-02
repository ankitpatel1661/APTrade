# KMP Increment 2 — Ktor Yahoo Networking — Design Spec

**Date:** 2026-07-02
**Status:** Approved (design); amended during execution
**Author:** APTrade team

## Amendment (2026-07-02, during execution)

The original scope decision "HTTP engine: Ktor **CIO** in `commonMain` for all
targets" proved **wrong at runtime**: Ktor's CIO engine compiles for
Kotlin/Native but cannot perform TLS on Apple native, so the live macOS harness
crashed with *"TLS sessions are not supported on Native platform."* Yahoo is
HTTPS-only. Per user decision, the engine is changed to **per-platform via
`expect`/`actual`**: `ktor-client-darwin` on Apple, `ktor-client-cio` on JVM;
`commonMain` keeps only `ktor-client-core` and the engine-agnostic config. A
second finding is also fixed: `FetchMarketQuotes.execute` gains
`@Throws(QuoteError::class)` so a thrown `QuoteError` bridges to Swift as a
catchable error instead of terminating the process. Unit tests (MockEngine) are
unaffected. See plan Tasks 7–8.

## Context

Increment 1 (merged, commit 84b89dc) stood up the KMP `shared/` module with a
one-slice walking skeleton: `Money`, `Quote`, a `QuoteRepository` port, a
`FetchMarketQuotes` suspend use case, and a `StubQuoteRepository` returning
hardcoded quotes — proven end-to-end through `Shared.xcframework` and a Swift
harness. See `docs/superpowers/specs/2026-07-01-kmp-walking-skeleton-design.md`.

Increment 2 replaces the stub data path with **real market data over the
network**, behind the *same* `QuoteRepository` port. This validates the
port/adapter seam: no consumer (use case, harness) changes, only a new
implementation slots in.

The source of truth for the API contract is the existing Swift
`Sources/APTradeInfrastructure/YahooMarketDataRepository.swift`, which this
increment ports (quote path only).

## Goal

Add a `YahooMarketDataRepository` (Kotlin, Ktor) implementing
`QuoteRepository.quotes(symbols)` by fetching live quotes from Yahoo Finance,
with deterministic unit tests (no live network) and a harness that fetches
live at run time.

## Scope decisions (locked)

1. **HTTP engine:** Ktor **CIO** in `commonMain` for all current targets
   (jvm + iosArm64 + iosSimulatorArm64 + macosArm64). The repository takes an
   **injected `HttpClient`** so tests swap in `MockEngine` and the production
   engine can change later without touching the repo.
2. **Harness:** points at the live `YahooMarketDataRepository`; `swift run`
   fetches real quotes. `StubQuoteRepository` stays for offline/test use.
3. **Quote path only.** No history/candles/search/profile.

## API contract (from the Swift original)

- Endpoint: `GET https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=1d&interval=1d`
- Header: `User-Agent: Mozilla/5.0` (Yahoo returns 401/429 without it).
- Response: `chart.result[0].meta` carries `symbol`, `currency`,
  `regularMarketPrice`, `chartPreviousClose`.
- HTTP 429 → rate-limited.

## Dependencies (pinned for Kotlin 2.1.0)

- Gradle plugin: `kotlin("plugin.serialization") version "2.1.0"`
- commonMain: Ktor **3.0.3** (`ktor-client-core`, `ktor-client-cio`,
  `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`);
  `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`
- commonTest: `io.ktor:ktor-client-mock:3.0.3`

## Components / files

```
shared/src/commonMain/kotlin/com/aptrade/shared/
├── application/
│   └── QuoteError.kt              NEW  sealed class QuoteError : Exception()
│                                       — RateLimited / NotFound / Network
└── infrastructure/
    ├── YahooChartDTO.kt           NEW  @Serializable DTOs mirroring the chart JSON
    ├── YahooQuoteMapper.kt        NEW  DTO -> Quote (price Money + changePercent)
    ├── YahooHttpClient.kt         NEW  defaultYahooHttpClient(): HttpClient (CIO + JSON + UA)
    └── YahooMarketDataRepository.kt NEW implements QuoteRepository (injected HttpClient)

shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/
    └── YahooMarketDataRepositoryTest.kt   NEW  MockEngine tests

skeletonHarness/Sources/SkeletonHarness/main.swift   MODIFY  stub -> live Yahoo repo
```

`StubQuoteRepository` and the `QuoteRepository` port are unchanged.

## Data flow

`FetchMarketQuotes.execute(symbols)` → `YahooMarketDataRepository.quotes(symbols)`:

1. Fetch all symbols **concurrently**: `symbols.map { async { fetchOne(it) } }.awaitAll()`.
2. `fetchOne(symbol)`: GET the chart endpoint with the UA header → deserialize
   `chart.result[0].meta` → map to `Quote`.
3. `awaitAll` is **fail-fast** for this increment: the first symbol that errors
   fails the whole call (matches the Swift throwing behavior). Per-symbol partial
   results are a later concern.

## Money exactness (honors "never Double for money")

Yahoo returns prices as JSON numbers. Deserializing to `Double` then converting
loses precision. Instead, `regularMarketPrice` and `chartPreviousClose` use a
custom serializer that reads the raw JSON number's **string text** and parses it
with `BigDecimal.parseString(...)`. Thus `price = Money(BigDecimal, currency ?? "USD")`
is exact from the wire.

`changePercent = ((price − prevClose) / prevClose × 100).toDouble()` — a
percentage, not a money amount, so `Double` is acceptable (consistent with
`Quote.changePercent` from increment 1).

## Error handling

`sealed class QuoteError : Exception()` in the application layer:

- HTTP 429 → `QuoteError.RateLimited`
- other non-2xx, connection failure, malformed/empty body → `QuoteError.Network`
- `chart.result` empty/absent (unknown symbol) → `QuoteError.NotFound`

`FetchMarketQuotes` propagates these unchanged. The Swift harness catches them in
its existing `do/catch` and prints a readable message.

## Testing (MockEngine — no live network)

`YahooMarketDataRepositoryTest` injects `HttpClient(MockEngine)` returning canned
Yahoo JSON:

1. **Success** (2 symbols) → asserts correct symbol, exact `Money` price, and the
   sign of `changePercent`.
2. **HTTP 429** → asserts `QuoteError.RateLimited` is thrown.
3. **Malformed body** → asserts `QuoteError.Network` is thrown.

## Harness (live)

`main.swift` swaps `StubQuoteRepository()` for
`YahooMarketDataRepository(client: defaultYahooHttpClient())`, requesting
`["AAPL", "MSFT", "BTC-USD"]` (Yahoo's crypto symbol is `BTC-USD`, not `BTC`).

## Definition of Done

1. `./gradlew :shared:jvmTest` — prior 7 tests + 3 new MockEngine tests green.
2. `./gradlew :shared:assembleSharedReleaseXCFramework` succeeds.
3. `cd skeletonHarness && swift run` — prints live AAPL/MSFT/BTC-USD quotes
   (agent captures stdout; requires network at run time).
4. Existing Apple `swift build` still green; `Package.swift`/`Sources`/`Tests`
   unmodified.

## Out of scope (YAGNI)

history / candles / search / profile; caching or in-flight coalescing; per-symbol
partial results; retry/backoff; androidTarget; wiring into the real app's
`CompositionRoot`.

## Follow-on (not designed here)

3. Wire the real Apple `CompositionRoot` to the shared use case via a SwiftPM
   binaryTarget. 4. Port remaining Domain/Application/Infrastructure. 5.
   androidTarget + Compose app. 6. Windows front end.
