# Increment 4b: Port `search` to the shared Kotlin core

## Context

Increment 4 broadened the shared Kotlin `MarketDataRepository` port to cover `history`, `candles`, and `profile`, leaving `search` (asset search) as the only method still served exclusively by the Swift-native fallback (`YahooMarketDataRepository` in `APTradeInfrastructure`). This increment ports `search` onto the same Kotlin-backed path, closing out the Swift-fallback migration for `MarketDataRepository`.

After this lands, `SharedCoreMarketDataRepository`'s `fallback` parameter is no longer exercised by any method. It is kept as-is (not removed) — pruning it is out of scope for this increment.

## Goals

- `search(query:)` in `SharedCoreMarketDataRepository` calls through to the Kotlin core, matching the wiring pattern used for `quote`/`history`/`candles`/`profile`.
- Search behavior is preserved exactly as observed today: same Yahoo endpoint, same params, same name-priority rule, same set of supported asset kinds.
- Existing Swift tests (`SearchAssetsUseCaseTests`) continue to pass unchanged, since they test the use case layer, not the repository.

## Non-goals

- Removing the `fallback` parameter/property from `SharedCoreMarketDataRepository`.
- Changing `SearchAssetsUseCase`'s trim/empty-query behavior in the Swift application layer.
- Adding search to any other consumer surface (e.g. no new UI).

## Design

### Kotlin side (`shared/src/commonMain/kotlin/com/aptrade/shared/`)

1. **`application/MarketDataRepository.kt`** — add:
   ```kotlin
   suspend fun search(query: String): List<Asset>
   ```

2. **`infrastructure/YahooSearchDTO.kt`** (new) — mirrors Swift's `YahooSearchResponse`:
   ```kotlin
   @Serializable
   data class YahooSearchDTO(val quotes: List<Item>? = null) {
       @Serializable
       data class Item(
           val symbol: String? = null,
           val shortname: String? = null,
           val longname: String? = null,
           val quoteType: String? = null
       )
   }
   ```

3. **`infrastructure/YahooSearchMapper.kt`** (new) — maps DTO → `List<Asset>`:
   - Name priority: `shortname > longname > symbol`. Skip items with no usable name/symbol.
   - `quoteType.uppercase()` → `AssetKind`: `EQUITY → Stock`, `ETF → Etf`, `CRYPTOCURRENCY → Crypto`.
   - Any other `quoteType` (`INDEX`, `FUTURE`, `CURRENCY`, `OPTION`, unknown/null, ...) is **silently excluded** from the result list. This is an intentional divergence from `profile()`'s fail-loud-on-unrecognized-kind rule (established in the increment-4 review fix): search legitimately receives a mixed bag of quote types from Yahoo, and dropping unsupported ones is the expected filter behavior, not an error condition.

4. **`infrastructure/YahooMarketDataRepository.kt`** — add `search(query: String): List<Asset>`:
   - Builds a GET request to `https://query1.finance.yahoo.com/v1/finance/search` with query params `q=<query>`, `quotesCount=8`, `newsCount=0`, and the same `User-Agent: Mozilla/5.0` header used elsewhere in this file.
   - Reuses the existing Ktor client instance (same `CompositionRoot`-style singleton reuse already fixed in increment 3 — do not construct a new client per call).
   - Error handling matches the existing `fetchChart`-adjacent pattern: propagate `CancellationException` as-is; map HTTP 429 → `QuoteError.RateLimited`; map other failures → `QuoteError.Network`.
   - Delegates DTO → domain mapping to `YahooSearchMapper`.

5. **`application/FetchSearch.kt`** (new) — following the `Fetch*` naming convention already used for `FetchHistory`/`FetchCandles`/`FetchProfile`/`FetchMarketQuotes` (kept consistent within Kotlin even though Swift's equivalent use case is named `SearchAssetsUseCase`):
   ```kotlin
   class FetchSearch(private val repository: MarketDataRepository) {
       @Throws(QuoteError::class, CancellationException::class)
       suspend fun execute(query: String): List<Asset> =
           repository.search(query)
   }
   ```
   No trimming/empty-query handling here — that responsibility stays in Swift's `SearchAssetsUseCase`, which already filters before any repository call is made (mirrors how `FetchHistory` etc. don't re-validate their inputs either).

6. **`infrastructure/StubMarketDataRepository.kt`** — add a `search()` implementation returning an empty list. No existing consumer of the stub needs richer behavior.

7. **Tests** — extend `YahooMarketDataRepositoryTest.kt` with cases mirroring the existing history/candles/profile shape:
   - Successful search maps quotes to `Asset` with correct name-priority and kind mapping.
   - Items with unsupported `quoteType` are filtered out of the result list.
   - Rate-limit (429) and network failures propagate as `QuoteError.RateLimited`/`QuoteError.Network`.

### Swift adapter side (`Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift`)

1. Add a `fetchSearch: (String) async throws -> [Shared.Asset]` closure parameter, following the exact pattern already used for `fetchHistory`/`fetchCandles`/`fetchProfile`.
2. In the `convenience init(fallback:)`, instantiate `Shared.FetchSearch(repository: repository)` and wire its `execute` method into `fetchSearch`.
3. Replace the current `search(query:)` body:
   ```swift
   // before
   public func search(query: String) async throws -> [APTradeDomain.Asset] {
       try await fallback.search(query: query)
   }
   // after
   public func search(query: String) async throws -> [APTradeDomain.Asset] {
       try await fetchSearch(query).map(mapAsset)
   }
   ```
   Reuses the existing `mapAsset`/`mapAssetKind`/`mapError` functions already in this file — no new Swift-side mapping code.
4. Remove the file-header comment (currently noting that search isn't yet ported), since it no longer applies.

## Error handling

Errors from the Kotlin core (`QuoteError.RateLimited`, `QuoteError.Network`, decoding failures) bridge through the existing `mapError` function in `SharedCoreMarketDataRepository.swift`, which already handles these cases generically for the other three ported methods. No new Swift-side error type is needed.

## Testing / Verification

- Kotlin: `./gradlew :shared:test` (or platform equivalent) — new `YahooMarketDataRepositoryTest.kt` cases pass, existing suite stays green.
- Swift: existing `SearchAssetsUseCaseTests` pass unchanged (they test the use case, not the repository).
- Regenerate the xcframework, rebuild the iOS scheme with `ARCHS=arm64` (per established KMP toolchain gotcha), confirm no compile errors from the new `Shared.Asset`/`Shared.FetchSearch` symbols.
- Manual smoke check: search UI (watchlist search / command palette) still returns results for a stock, an ETF, and a crypto query.
