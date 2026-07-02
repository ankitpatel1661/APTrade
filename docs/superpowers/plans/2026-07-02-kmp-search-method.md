# Increment 4b: Port `search` to the Shared Kotlin Core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move asset `search` off the Swift-only fallback repository onto the shared Kotlin `MarketDataRepository` port, matching the pattern already used for `quote`/`history`/`candles`/`profile`.

**Architecture:** Add `search` to Kotlin's `MarketDataRepository` interface, implement it in `YahooMarketDataRepository` against Yahoo's `/v1/finance/search` endpoint via a new DTO + mapper, expose it through a `FetchSearch` use case, rebuild the xcframework, then wire `SharedCoreMarketDataRepository.search(query:)` in Swift to call through the Kotlin core instead of the fallback — reusing the adapter's existing `mapAsset`/`mapError` helpers.

**Tech Stack:** Kotlin Multiplatform (Ktor client, kotlinx.serialization), Swift/XCTest, Gradle, xcodebuild.

**Spec:** `docs/superpowers/specs/2026-07-02-kmp-search-method-design.md`

## Global Constraints

- Every `./gradlew` shell needs `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`; never system Gradle.
- Every `swift test` / `swift build` / Apple-target Gradle task needs `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- iOS builds need `ARCHS=arm64` — the xcframework is Apple-Silicon-only.
- Baseline test counts before this plan: `./gradlew :shared:jvmTest` = **37 tests**; `swift test` = **205 tests**. Every task's "Expected" test count below is cumulative from this baseline.
- Unsupported Yahoo `quoteType` values (INDEX, FUTURE, CURRENCY, OPTION, unknown/null) are **silently filtered out** of search results — this is an intentional divergence from `profile()`'s fail-loud-on-unrecognized-`AssetKind` rule (search legitimately gets a mixed bag from Yahoo; dropping unsupported types is the expected filter behavior, not an error).
- The new Kotlin use case is named `FetchSearch` (matching Kotlin's existing `Fetch*` convention: `FetchHistory`, `FetchCandles`, `FetchProfile`, `FetchMarketQuotes`) even though Swift's equivalent use case is named `SearchAssetsUseCase`.
- Never guess a KMP↔Swift bridged symbol name — confirm against the generated header (`shared/build/XCFrameworks/release/Shared.xcframework/macos-arm64/Shared.framework/Headers/Shared.h`) before writing Swift code that references a new Kotlin symbol.
- Reuse the existing Ktor `HttpClient` instance for search requests — never construct a new client per call (the CompositionRoot-singleton regression fixed in increment 3).

---

### Task 1: Kotlin — Yahoo search DTO + mapper

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooSearchDTO.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooSearchMapper.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooSearchMapperTest.kt`

**Interfaces:**
- Consumes: `com.aptrade.shared.domain.Asset(symbol: String, name: String, kind: AssetKind)`, `com.aptrade.shared.domain.AssetKind` (`Stock`, `Etf`, `Crypto`).
- Produces: `YahooSearchResponse(quotes: List<Item>?)` with `Item(symbol: String?, shortname: String?, longname: String?, quoteType: String?)`; `YahooSearchMapper.assets(response: YahooSearchResponse): List<Asset>` — consumed by Task 2's `YahooMarketDataRepository.search()`.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooSearchMapperTest.kt`:

```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import kotlin.test.Test
import kotlin.test.assertEquals

class YahooSearchMapperTest {
    @Test
    fun mapsSupportedQuoteTypesWithNamePriority() {
        val response = YahooSearchResponse(
            quotes = listOf(
                YahooSearchResponse.Item(
                    symbol = "AAPL", shortname = "Apple Inc.",
                    longname = "Apple Incorporated", quoteType = "EQUITY",
                ),
                YahooSearchResponse.Item(
                    symbol = "SPY", shortname = null,
                    longname = "SPDR S&P 500", quoteType = "ETF",
                ),
                YahooSearchResponse.Item(
                    symbol = "BTC-USD", shortname = null,
                    longname = null, quoteType = "CRYPTOCURRENCY",
                ),
            ),
        )

        val assets = YahooSearchMapper.assets(response)

        assertEquals(3, assets.size)
        assertEquals(Asset("AAPL", "Apple Inc.", AssetKind.Stock), assets[0])
        assertEquals(Asset("SPY", "SPDR S&P 500", AssetKind.Etf), assets[1])
        assertEquals(Asset("BTC-USD", "BTC-USD", AssetKind.Crypto), assets[2])
    }

    @Test
    fun filtersOutUnsupportedQuoteTypes() {
        val response = YahooSearchResponse(
            quotes = listOf(
                YahooSearchResponse.Item(symbol = "^GSPC", shortname = "S&P 500", quoteType = "INDEX"),
                YahooSearchResponse.Item(symbol = "CLQ24", shortname = "Crude Oil", quoteType = "FUTURE"),
                YahooSearchResponse.Item(symbol = "EURUSD=X", shortname = "EUR/USD", quoteType = "CURRENCY"),
                YahooSearchResponse.Item(symbol = "AAPL240119C00190000", shortname = "AAPL Call", quoteType = "OPTION"),
                YahooSearchResponse.Item(symbol = "AAPL", shortname = "Apple Inc.", quoteType = "EQUITY"),
            ),
        )

        val assets = YahooSearchMapper.assets(response)

        assertEquals(listOf("AAPL"), assets.map { it.symbol })
    }

    @Test
    fun returnsEmptyListWhenQuotesIsNull() {
        assertEquals(emptyList(), YahooSearchMapper.assets(YahooSearchResponse(quotes = null)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :shared:jvmTest --console=plain --tests "com.aptrade.shared.infrastructure.YahooSearchMapperTest"
```
Expected: FAIL — compile error `unresolved reference: YahooSearchResponse` (and `YahooSearchMapper`), since neither exists yet.

- [ ] **Step 3: Create the DTO**

Create `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooSearchDTO.kt`:

```kotlin
package com.aptrade.shared.infrastructure

import kotlinx.serialization.Serializable

@Serializable
data class YahooSearchResponse(val quotes: List<Item>? = null) {
    @Serializable
    data class Item(
        val symbol: String? = null,
        val shortname: String? = null,
        val longname: String? = null,
        val quoteType: String? = null,
    )
}
```

- [ ] **Step 4: Create the mapper**

Create `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooSearchMapper.kt`:

```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind

object YahooSearchMapper {
    fun assets(response: YahooSearchResponse): List<Asset> =
        (response.quotes ?: emptyList()).mapNotNull(::asset)

    private fun asset(item: YahooSearchResponse.Item): Asset? {
        val symbol = item.symbol ?: return null
        val kind = kind(item.quoteType) ?: return null
        val name = item.shortname ?: item.longname ?: symbol
        return Asset(symbol = symbol, name = name, kind = kind)
    }

    private fun kind(type: String?): AssetKind? = when (type?.uppercase()) {
        "EQUITY" -> AssetKind.Stock
        "ETF" -> AssetKind.Etf
        "CRYPTOCURRENCY" -> AssetKind.Crypto
        else -> null // INDEX, FUTURE, CURRENCY, OPTION, … unsupported — filtered out
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run:
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :shared:jvmTest --console=plain --tests "com.aptrade.shared.infrastructure.YahooSearchMapperTest"
```
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooSearchDTO.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooSearchMapper.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooSearchMapperTest.kt
git commit -m "feat(kmp): add Yahoo search DTO and mapper to shared core"
```

---

### Task 2: Kotlin — wire `search` into `MarketDataRepository` port, `YahooMarketDataRepository`, `StubMarketDataRepository`, `FetchSearch`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/application/MarketDataRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchSearch.kt`
- Modify: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepositoryTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepositoryTest.kt`

**Interfaces:**
- Consumes: `YahooSearchMapper.assets(YahooSearchResponse): List<Asset>` and `YahooSearchResponse` from Task 1; `QuoteError.RateLimited` / `QuoteError.Network(reason: String)` from `com.aptrade.shared.application.QuoteError`.
- Produces: `MarketDataRepository.search(query: String): List<Asset>`; `FetchSearch(repository: MarketDataRepository).execute(query: String): List<Asset>` (`@Throws(QuoteError::class, CancellationException::class)`) — consumed by Task 4's Swift adapter via the generated framework.

- [ ] **Step 1: Add `search` to the port interface**

Modify `shared/src/commonMain/kotlin/com/aptrade/shared/application/MarketDataRepository.kt`:

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe

interface MarketDataRepository {
    suspend fun quotes(symbols: List<String>): List<Quote>
    suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint>
    suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle>
    suspend fun profile(symbol: String): Asset
    suspend fun search(query: String): List<Asset>
}
```

- [ ] **Step 2: Run the build to see the expected break**

Run:
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :shared:jvmTest --console=plain
```
Expected: FAIL — `class YahooMarketDataRepository is not abstract and does not implement abstract member 'search'` (and the same for `StubMarketDataRepository`). This is the expected mid-task compile break from broadening the interface.

- [ ] **Step 3: Fix `StubMarketDataRepository`**

Modify `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepository.kt` — add after the existing `profile()` override:

```kotlin
    override suspend fun search(query: String): List<Asset> = emptyList()
```

- [ ] **Step 4: Write the failing `YahooMarketDataRepository` search tests**

Modify `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepositoryTest.kt` — add these three tests at the end of the class, before the closing brace:

```kotlin
    @Test
    fun returnsMappedSearchResults() = runTest {
        val body = """
            {"quotes":[{"symbol":"AAPL","shortname":"Apple Inc.","quoteType":"EQUITY"},
            {"symbol":"^GSPC","shortname":"S&P 500","quoteType":"INDEX"}]}
        """.trimIndent()
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, body))

        val assets = repo.search("apple")

        assertEquals(listOf("AAPL"), assets.map { it.symbol })
    }

    @Test
    fun mapsSearchHttp429ToRateLimited() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.search("apple") }
        assertTrue(ex is QuoteError.RateLimited)
    }

    @Test
    fun mapsSearchMalformedBodyToNetwork() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, "not json"))
        val ex = assertFailsWith<QuoteError> { repo.search("apple") }
        assertTrue(ex is QuoteError.Network)
    }
```

- [ ] **Step 5: Run tests to verify they fail**

Run:
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :shared:jvmTest --console=plain --tests "com.aptrade.shared.infrastructure.YahooMarketDataRepositoryTest"
```
Expected: FAIL — `class YahooMarketDataRepository is not abstract and does not implement abstract member 'search'` (still, until Step 6 lands).

- [ ] **Step 6: Implement `search` in `YahooMarketDataRepository`**

Modify `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepository.kt` — add the override and the private helper. No new imports are needed: `com.aptrade.shared.domain.Asset` is already imported (line 5), and `QuoteError`/`HttpStatusCode`/`isSuccess`/`CancellationException` are already imported for `fetchChart`.

Add after the existing `profile()` override:

```kotlin
    override suspend fun search(query: String): List<Asset> =
        YahooSearchMapper.assets(fetchSearchResponse(query))

    private suspend fun fetchSearchResponse(query: String): YahooSearchResponse {
        val response = try {
            client.get("https://query1.finance.yahoo.com/v1/finance/search") {
                header("User-Agent", "Mozilla/5.0")
                url {
                    parameters.append("q", query)
                    parameters.append("quotesCount", "8")
                    parameters.append("newsCount", "0")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network(e.message ?: "request failed")
        }

        if (response.status == HttpStatusCode.TooManyRequests) throw QuoteError.RateLimited
        if (!response.status.isSuccess()) throw QuoteError.Network("HTTP ${response.status.value}")

        return try {
            response.body<YahooSearchResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network("malformed response")
        }
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run:
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :shared:jvmTest --console=plain --tests "com.aptrade.shared.infrastructure.YahooMarketDataRepositoryTest"
```
Expected: PASS — all tests in this class pass, including the 3 new ones.

- [ ] **Step 8: Create the `FetchSearch` use case**

Create `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchSearch.kt`:

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import kotlin.coroutines.cancellation.CancellationException

class FetchSearch(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(query: String): List<Asset> = repository.search(query)
}
```

- [ ] **Step 9: Add the `StubMarketDataRepository` search test**

Modify `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepositoryTest.kt` — add after `returnsProfileWithGivenSymbol`:

```kotlin
    @Test
    fun returnsEmptySearchResults() = runTest {
        val assets = repo.search("AAPL")
        assertEquals(0, assets.size)
    }
```

- [ ] **Step 10: Run the full shared test suite**

Run:
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :shared:jvmTest --console=plain
```
Expected: PASS — **44 tests**, 0 failures (37 baseline + 3 `YahooSearchMapperTest` from Task 1 + 3 `YahooMarketDataRepositoryTest` search cases + 1 `StubMarketDataRepositoryTest` search case).

- [ ] **Step 11: Commit**

```bash
git add shared/src/commonMain/kotlin/com/aptrade/shared/application/MarketDataRepository.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchSearch.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepository.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepository.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepositoryTest.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepositoryTest.kt
git commit -m "feat(kmp): implement search on MarketDataRepository port and FetchSearch use case"
```

---

### Task 3: Reassemble `Shared.xcframework` and confirm the generated header

**Files:**
- None modified — this task rebuilds `shared/build/XCFrameworks/release/Shared.xcframework` and inspects the generated Objective-C header.

**Interfaces:**
- Consumes: Task 2's `FetchSearch` class.
- Produces: confirmed exact Swift-bridged signature for `Shared.FetchSearch` — consumed by Task 4.

- [ ] **Step 1: Compile for macOS arm64**

Run:
```bash
cd "Trading app"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:compileKotlinMacosArm64 --console=plain
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Assemble the release xcframework**

Run:
```bash
cd "Trading app"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:assembleSharedReleaseXCFramework --console=plain
```
Expected: `BUILD SUCCESSFUL`; `shared/build/XCFrameworks/release/Shared.xcframework` contains `Info.plist` plus `ios-arm64`, `ios-arm64-simulator`, `macos-arm64` slices.

- [ ] **Step 3: Confirm the bridged `FetchSearch` signature**

Run:
```bash
cd "Trading app"
grep -B2 -A6 "@interface SharedFetchSearch" shared/build/XCFrameworks/release/Shared.xcframework/macos-arm64/Shared.framework/Headers/Shared.h
```
Expected: an `@interface SharedFetchSearch` block with an `initWithRepository:` initializer and an `executeQuery:completionHandler:` (or equivalent async-bridged) method. **Record the exact method name shown here** — Task 4 must call exactly this, not a guessed name. If the generated signature differs from `execute(query:)` used elsewhere in this plan for `FetchHistory`/`FetchCandles`/`FetchProfile`, update Task 4's code to match what the header actually shows.

- [ ] **Step 4: Commit (if xcframework output is tracked)**

Check whether `shared/build/` or a copied framework destination is tracked in git:
```bash
cd "Trading app" && git status --short shared/build/ Sources/ 2>/dev/null
```
Expected: `shared/build/` is untracked (gitignored build output) — no commit needed for this task. If the project instead vendors the xcframework into a tracked location (check `.gitignore` for `shared/build/`), copy it there and commit that copy following the existing project convention from increment 4's Task 4.

---

### Task 4: Swift adapter — wire `search` through the shared core

**Files:**
- Modify: `Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift`
- Modify: `Tests/APTradeInfrastructureTests/SharedCoreMarketDataRepositoryTests.swift`

**Interfaces:**
- Consumes: `Shared.FetchSearch(repository:).execute(query:)` (exact name confirmed in Task 3 Step 3); `Shared.Asset.symbol/.name/.kind`; existing `SharedCoreMarketDataRepository.mapAsset(_:)` and `mapError(_:)` static functions (already defined, unchanged).
- Produces: `SharedCoreMarketDataRepository.search(query:) async throws -> [APTradeDomain.Asset]` now backed by the Kotlin core instead of `fallback`.

Before writing code, confirm the exact `Shared.FetchSearch` method name recorded in Task 3 Step 3. The code below assumes `execute(query:)` bridges directly as an `async throws` Swift method, matching the existing convention for `Shared.FetchProfile.execute(symbol:)` used elsewhere in this file. If the header showed a different name, use that name instead in every step below.

- [ ] **Step 1: Write the failing Swift tests**

Modify `Tests/APTradeInfrastructureTests/SharedCoreMarketDataRepositoryTests.swift`:

Remove the `noOpRepo` helper (lines 16–23) and the `testDelegatesSearchToFallback` test (lines 92–98) entirely — `search` will no longer delegate to `fallback`, so this test would become false. Replace both with:

```swift
    func testSearchForSuccessReturnsMappedAssets() async throws {
        let kmp = [Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)]
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { query in
                XCTAssertEqual(query, "apple")
                return kmp
            })

        let assets = try await repo.search(query: "apple")
        XCTAssertEqual(assets.count, 1)
        XCTAssertEqual(assets[0].symbol, "AAPL")
        XCTAssertEqual(assets[0].name, "Apple Inc.")
        XCTAssertEqual(assets[0].kind, .stock)
    }

    func testSearchForKotlinErrorMapsToAppError() async {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { _ in
                throw NSError(
                    domain: "KotlinException", code: 0,
                    userInfo: ["KotlinException": Shared.QuoteError.RateLimited.shared])
            })

        do {
            _ = try await repo.search(query: "apple")
            XCTFail("Expected AppError.rateLimited")
        } catch {
            XCTAssertEqual(error as? AppError, .rateLimited)
        }
    }
```

Every other test function in this file that constructs `SharedCoreMarketDataRepository(fallback:fetch:fetchHistory:fetchCandles:fetchProfile:)` directly (`testQuoteForSuccessReturnsMappedQuote`, `testQuoteForEmptyResultThrowsNotFound`, `testQuoteForKotlinErrorMapsToAppError`, `testHistoryForSuccessReturnsMappedPoints`, `testCandlesForKotlinErrorMapsToAppError`, `testProfileForSuccessReturnsMappedAsset`) needs a trailing `fetchSearch: { _ in [] }` argument added to its call, since the initializer parameter will become non-optional in Step 3. Add it to each of those 6 call sites now.

- [ ] **Step 2: Run tests to verify they fail to compile**

Run:
```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd "Trading app" && swift test --filter APTradeInfrastructureTests 2>&1 | tail -30
```
Expected: FAIL — compile error, e.g. `argument 'fetchSearch' not found` or `extra argument 'fetchSearch' in call` (initializer doesn't have the parameter yet) or `missing argument for parameter 'fetchSearch'` depending on edit order. Either way, a compile failure confirming the test file is ahead of the production code.

- [ ] **Step 3: Wire `fetchSearch` into `SharedCoreMarketDataRepository`**

Modify `Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift`.

Replace the file-header doc comment (lines 9–10):
```swift
/// Serves `quote`/`history`/`candles`/`profile`/`search` from the shared Kotlin core.
```

Add a `fetchSearch` stored property after `fetchProfile` (line 17):
```swift
    private let fetchSearch: @Sendable (String) async throws -> [Shared.Asset]
```

Replace the `convenience init` (lines 20–32):
```swift
    public convenience init(fallback: APTradeApplication.MarketDataRepository) {
        let repository = Shared.YahooMarketDataRepository()
        let quotesUseCase = Shared.FetchMarketQuotes(repository: repository)
        let historyUseCase = Shared.FetchHistory(repository: repository)
        let candlesUseCase = Shared.FetchCandles(repository: repository)
        let profileUseCase = Shared.FetchProfile(repository: repository)
        let searchUseCase = Shared.FetchSearch(repository: repository)
        self.init(
            fallback: fallback,
            fetch: { try await quotesUseCase.execute(symbols: $0) },
            fetchHistory: { try await historyUseCase.execute(symbol: $0, timeframe: $1) },
            fetchCandles: { try await candlesUseCase.execute(symbol: $0, timeframe: $1) },
            fetchProfile: { try await profileUseCase.execute(symbol: $0) },
            fetchSearch: { try await searchUseCase.execute(query: $0) })
    }
```

Replace the designated `init` (lines 34–46):
```swift
    init(
        fallback: APTradeApplication.MarketDataRepository,
        fetch: @escaping @Sendable ([String]) async throws -> [Shared.Quote],
        fetchHistory: @escaping @Sendable (String, Shared.Timeframe) async throws -> [Shared.PricePoint],
        fetchCandles: @escaping @Sendable (String, Shared.Timeframe) async throws -> [Shared.Candle],
        fetchProfile: @escaping @Sendable (String) async throws -> Shared.Asset,
        fetchSearch: @escaping @Sendable (String) async throws -> [Shared.Asset]
    ) {
        self.fetch = fetch
        self.fetchHistory = fetchHistory
        self.fetchCandles = fetchCandles
        self.fetchProfile = fetchProfile
        self.fetchSearch = fetchSearch
        self.fallback = fallback
    }
```

Replace the `search(query:)` method (lines 85–87):
```swift
    public func search(query: String) async throws -> [APTradeDomain.Asset] {
        do {
            return try await fetchSearch(query).map(Self.mapAsset)
        } catch {
            throw Self.mapError(error)
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd "Trading app" && swift test --filter APTradeInfrastructureTests 2>&1 | tail -30
```
Expected: PASS — all tests in `APTradeInfrastructureTests` pass, including the 2 new search tests.

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift \
        Tests/APTradeInfrastructureTests/SharedCoreMarketDataRepositoryTests.swift
git commit -m "feat(kmp): route search through the shared Kotlin core"
```

---

### Task 5: Regression — full test suites, iOS build, manual smoke check

**Files:**
- None modified — verification only.

**Interfaces:**
- Consumes: Task 4's completed adapter.

- [ ] **Step 1: Run the full Kotlin test suite**

Run:
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :shared:jvmTest --console=plain
```
Expected: PASS — **44 tests**, 0 failures.

- [ ] **Step 2: Run the full Swift test suite**

Run:
```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd "Trading app" && swift test 2>&1 | grep -iE "executed [0-9]+ test"
```
Expected: PASS — the top-level "All tests" run reports **206 tests**, 0 failures (205 baseline − 1 removed `testDelegatesSearchToFallback` + 2 new search tests).

- [ ] **Step 3: Build the iOS scheme**

Run:
```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd "Trading app" && xcodebuild -scheme APTradeLite-Package -destination 'generic/platform=iOS Simulator' ARCHS=arm64 build -quiet
```
Expected: exits with no output and status 0 (a clean `-quiet` build). If it fails, re-check that `shared/build/XCFrameworks/release/Shared.xcframework` from Task 3 is the one referenced by the Swift package/project.

- [ ] **Step 4: Manual smoke check**

Computer-use tooling cannot target this app's dev build (per project memory), so this step is manual:

Launch the macOS app (`swift run APTradeApp` or via Xcode) and use the watchlist search / command palette search to query:
1. A stock (e.g. "AAPL") — expect it to appear in results.
2. An ETF (e.g. "SPY") — expect it to appear in results.
3. A crypto asset (e.g. "BTC") — expect it to appear in results.

Confirm results still render with correct names and kinds, and that no crash or empty-result regression appears compared to pre-increment behavior.

- [ ] **Step 5: Confirm no leftover fallback-only search path**

Run:
```bash
cd "Trading app" && grep -n "fallback.search" Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift
```
Expected: no output — `search` no longer calls `fallback.search`.
