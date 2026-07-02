# KMP Increment 4 — Port history/candles/profile to the Shared Core — Design Spec

**Date:** 2026-07-02
**Status:** Approved (design)
**Author:** APTrade team

## Context

Increment 3 (merged de58627) wired the real app's `quote(for:)` through the shared Kotlin core via
`SharedCoreMarketDataRepository`, while `history`/`candles`/`profile`/`search` stayed delegated to
the Swift-native `YahooMarketDataRepository` fallback. This increment ports three of those four —
`history`, `candles`, `profile` — into Kotlin. `search` uses a different Yahoo endpoint and DTO and
is deferred to increment 4b, run right after this one through the same design→plan→execute cycle.

The source of truth is `Sources/APTradeInfrastructure/YahooMarketDataRepository.swift`,
`YahooMapper.swift`, `YahooChartDTO.swift`, and `TimeframeMapping.swift` — this increment ports
their chart-endpoint logic to Kotlin, verbatim where the logic is source-independent.

## Goal

`SharedCoreMarketDataRepository.history/candles/profile` route through the shared Kotlin core
instead of the Swift fallback. Only `search` still delegates to Swift after this increment.
Behavior (values, error mapping, window clamping) is unchanged from the app's perspective.

## Decisions (user-confirmed)

1. **Scope: chart-endpoint methods only** (`history`, `candles`, `profile`). `search` is increment 4b.
2. **Kotlin gains proper Application-layer ports + use cases**, mirroring the Swift layering,
   rather than bolting extra methods onto the concrete repository with no abstraction. This costs
   more now but avoids retrofitting a port later when Android (increment 5) needs the same
   use cases behind Compose.

## Kotlin layering changes

- **Port rename:** `QuoteRepository` (`shared/.../application/QuoteRepository.kt`) becomes
  `MarketDataRepository`:
  ```kotlin
  interface MarketDataRepository {
      suspend fun quotes(symbols: List<String>): List<Quote>
      suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint>
      suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle>
      suspend fun profile(symbol: String): Asset
  }
  ```
  `YahooMarketDataRepository` implements the broadened interface. `StubQuoteRepository` is renamed
  `StubMarketDataRepository` and implements all four methods (history/candles/profile return
  small hardcoded fixtures — the stub's only consumer is the KMP test suite and any future
  standalone harness, not the live app).
- **New use cases**, each a thin wrapper matching `FetchMarketQuotes`'s existing shape:
  ```kotlin
  class FetchHistory(private val repository: MarketDataRepository) {
      suspend fun execute(symbol: String, timeframe: Timeframe): List<PricePoint> =
          repository.history(symbol, timeframe)
  }
  class FetchCandles(private val repository: MarketDataRepository) {
      suspend fun execute(symbol: String, timeframe: Timeframe): List<Candle> =
          repository.candles(symbol, timeframe)
  }
  class FetchProfile(private val repository: MarketDataRepository) {
      suspend fun execute(symbol: String): Asset = repository.profile(symbol)
  }
  ```
  Each carries `@Throws(QuoteError::class, CancellationException::class)` for Swift bridging,
  matching `FetchMarketQuotes.execute`. `FetchMarketQuotes` itself is unchanged except that its
  constructor parameter type is now `MarketDataRepository` instead of `QuoteRepository`.

## New Kotlin domain types (additive, framework-free)

- **`Timeframe`** enum — ports `Sources/APTradeDomain/Timeframe.swift` +
  `TimeframeMapping.swift` verbatim:
  ```kotlin
  enum class Timeframe {
      OneDay, OneWeek, OneMonth, OneYear;

      val yahooRange: String get() = when (this) {
          OneDay -> "5d"; OneWeek -> "1mo"; OneMonth -> "3mo"; OneYear -> "1y"
      }
      val yahooInterval: String get() = when (this) {
          OneDay -> "5m"; OneWeek -> "60m"; OneMonth -> "1d"; OneYear -> "1d"
      }
      val windowDurationSeconds: Long get() = when (this) {
          OneDay -> 24L * 3600; OneWeek -> 7L * 24 * 3600
          OneMonth -> 30L * 24 * 3600; OneYear -> 365L * 24 * 3600
      }
  }
  ```
- **`PricePoint(epochSeconds: Long, close: Money)`** and
  **`Candle(epochSeconds: Long, open: Money, high: Money, low: Money, close: Money, volume: Double)`**.
  Dates are raw Unix epoch seconds (`Long`), matching Yahoo's own wire format — this avoids adding
  `kotlinx-datetime` as a new dependency for a single field. The Swift adapter converts via
  `Date(timeIntervalSince1970: TimeInterval(epochSeconds))`.
- **`Asset(symbol: String, name: String, kind: AssetKind)`**, **`AssetKind`** enum
  (`Stock, Etf, Crypto`).

## Kotlin infrastructure changes

- **`YahooChartDTO.kt`** — extend `Meta` with `instrumentType: String?`, `longName: String?`,
  `shortName: String?`; extend `ResultItem` with `timestamp: List<Long>?` and
  `indicators: Indicators`; add `Indicators(quote: List<QuoteBlock>?)` and
  `QuoteBlock(open: List<BigDecimal?>?, high: List<BigDecimal?>?, low: List<BigDecimal?>?,
  close: List<BigDecimal?>?, volume: List<Double?>?)` — OHLC arrays use the existing
  `BigDecimalWireSerializer` (exact decimal); volume is `Double` (not money, same treatment as
  `changePercent`).
- **`YahooQuoteMapper.kt`** gains three functions alongside the existing `quote`:
  - `history(response): List<PricePoint>` — pairs `timestamp[i]` with `quote.close[i]`, skipping
    indices where `close` is null (matches Swift's `guard let close = closes[i] else { continue }`).
  - `candles(response): List<Candle>` — same pairing; missing `open`/`high`/`low` fall back to
    `close` (and `high`/`low` further fall back to `max`/`min` of open/close), missing `volume`
    falls back to `0.0` — verbatim port of Swift's fallback logic.
  - `asset(response): Asset` — `name = longName ?: shortName ?: symbol`; `kind` from
    `instrumentType` (`"ETF"→Etf`, `"CRYPTOCURRENCY"→Crypto`, `"EQUITY"→Stock`, else infer from a
    `-USD` symbol suffix → `Crypto`, else `Stock`) — verbatim port of Swift's `kind(of:)`.
- **Window clamping** — a small Kotlin utility (e.g. top-level `clampToWindow` in the
  infrastructure package) generic over any type with an epoch-seconds accessor, anchored to the
  **newest bar** (not wall-clock now) — verbatim port of Swift's `clampToWindow`. Used by both
  `history` and `candles`.
- **`YahooMarketDataRepository.kt`** gains `history`, `candles`, `profile`, reusing the existing
  `fetchChart(symbol, range, interval)` plumbing (factor the current quote-only fetch into a
  shared private helper if not already shaped that way) and the existing error mapping
  (429→`RateLimited`, other non-2xx→`Network`, decode failure→`Network` — reusing the existing
  case rather than adding a new `QuoteError.Decoding`, since increment 2 already treats malformed
  bodies as `Network`).

## Swift side

- `SharedCoreMarketDataRepository` (`Sources/APTradeInfrastructure/`) gains three more injectable
  closures (`fetchHistory`, `fetchCandles`, `fetchProfile`), following the same pattern established
  for `fetch` (quote) in increment 3 — the public `init(fallback:)` wires the real KMP use cases;
  an internal init accepts fakes for the adapter's own unit tests.
  - `history`/`candles`/`profile` now call through to KMP instead of `fallback`.
  - Only `search` still delegates to `fallback`.
  - Type mapping: `Shared.PricePoint`/`Shared.Candle` → Swift `PricePoint`/`Candle` via
    `amountText`→`Decimal(string:)` (money) and `epochSeconds`→`Date(timeIntervalSince1970:)`
    (time) — same exact-decimal discipline as `quote`. `Shared.Asset`/`Shared.AssetKind` → Swift
    `Asset`/`AssetKind` via a small explicit switch (Kotlin enum case names are bridged as-is;
    the adapter maps them explicitly rather than relying on raw-value coincidence).
  - Error mapping reuses the existing `mapError` (no change needed — same `QuoteError` cases).

## Testing

- **Kotlin:** mapper tests for `history`/`candles`/`profile` reusing the same JSON shape as the
  existing Swift fixture `Tests/APTradeInfrastructureTests/Fixtures/aapl_chart.json` (inlined as a
  Kotlin string literal — KMP `commonTest` doesn't share Swift's file-based fixture loading), plus
  edge cases: a null `close` entry skipped, missing `open`/`high`/`low`/`volume` falling back
  correctly, and `windowDurationSeconds`-based clamping trimming older bars. MockEngine repository
  tests for `history`/`candles`/`profile` mirroring the existing `quotes` tests (429, malformed
  body). `StubMarketDataRepository` test updated for the rename.
- **Swift:** adapter tests for `history`/`candles`/`profile` (exact `PricePoint`/`Candle`/`Asset`
  mapping, each via the injectable-closure seam — no live network), and the delegation test
  narrowed to assert only `search` still calls the fallback.
- **Regression:** Kotlin `jvmTest`, Swift `swift build`/`swift test`, iOS `APTradeLite-Package`
  scheme (`ARCHS=arm64`), xcframework reassembly. The skeleton harness stays quotes-only — not
  extended in this increment (history/candles/profile aren't part of its walking-skeleton scope).

## Out of scope

`search` (increment 4b); `androidTarget`/Compose (increment 5); Windows (increment 6); adding
`kotlinx-datetime` (deferred until a real calendar/timezone need arises); a distinct
`QuoteError.Decoding` case (decode failures stay mapped to `Network`, matching increment 2/3
precedent).

## Definition of Done

1. `MarketDataRepository` port (renamed from `QuoteRepository`) has all four methods;
   `YahooMarketDataRepository` and `StubMarketDataRepository` implement it fully.
2. `FetchHistory`, `FetchCandles`, `FetchProfile` use cases exist, each `@Throws`-annotated.
3. `Timeframe`, `PricePoint`, `Candle`, `Asset`, `AssetKind` exist in `shared/commonMain/domain`,
   framework-free.
4. `./gradlew :shared:jvmTest` green with new mapper/repository/window-clamp tests.
5. `SharedCoreMarketDataRepository.history/candles/profile` route through KMP; only `search`
   delegates to the Swift fallback. New Swift adapter tests green; full `swift test` green.
6. iOS `APTradeLite-Package` scheme builds (`ARCHS=arm64`); xcframework reassembled successfully.
7. Money and OHLC values remain exact-decimal end to end (BigDecimal → `amountText` →
   `Decimal(string:)`); no value passes through `Double` except `changePercent` and `volume`.
