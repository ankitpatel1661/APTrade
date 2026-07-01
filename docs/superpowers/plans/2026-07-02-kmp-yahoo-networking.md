# KMP Increment 2 — Ktor Yahoo Networking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `YahooMarketDataRepository` (Kotlin + Ktor) that implements the existing `QuoteRepository` port by fetching live quotes from Yahoo Finance, with deterministic MockEngine unit tests and a Swift harness that fetches live.

**Architecture:** A new infrastructure implementation slots behind the unchanged `QuoteRepository` port. It fetches each symbol concurrently from Yahoo's chart endpoint via an injected Ktor `HttpClient` (CIO engine, ContentNegotiation + kotlinx.serialization). Prices are decoded from the raw JSON number text into `BigDecimal` (never `Double`) to keep `Money` exact. Failures map to a small `QuoteError` sealed type.

**Tech Stack:** Kotlin Multiplatform 2.1.0, Ktor 3.0.3 (client-core, client-cio, content-negotiation, serialization-kotlinx-json, client-mock), kotlinx-serialization-json 1.7.3, kotlinx-coroutines 1.9.0, ionspin bignum 0.3.10.

## Global Constraints

- Branch: `kmp-yahoo-networking` (already created).
- JDK 17 for all Gradle: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"` before every `./gradlew` (the `/usr/libexec/java_home -v 17` shim points at the wrong JDK here).
- Pinned versions: Kotlin 2.1.0; Ktor **3.0.3**; kotlinx-serialization-json **1.7.3**. Do not bump.
- HTTP engine: Ktor **CIO** in `commonMain` (covers jvm + iOS + macOS). No per-platform engine, no `androidTarget`.
- Repository takes an **injected `HttpClient`** (internal constructor) so tests use `MockEngine`. Production uses a public no-arg constructor that builds the default CIO client.
- Money is exact-decimal only: prices decode from the raw JSON number's string text into ionspin `BigDecimal`. **Never** parse a money amount through `Double`. `changePercent` is a `Double` (a percentage, not money).
- Endpoint: `GET https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=1d&interval=1d`, header `User-Agent: Mozilla/5.0`.
- Do NOT modify the `QuoteRepository` port or `StubQuoteRepository`.
- Do NOT modify root `Package.swift`, `Sources/APTrade*`, or `Tests/APTrade*`.
- Namespace `com.aptrade.shared` (`.application`, `.infrastructure`).
- TDD: test first, seen to fail, then implement.

---

### Task 1: Add Ktor + serialization dependencies

**Files:**
- Modify: `build.gradle.kts` (root — add serialization plugin to the plugins block)
- Modify: `shared/build.gradle.kts` (apply serialization plugin; add deps)

**Interfaces:**
- Consumes: existing KMP scaffold.
- Produces: Ktor 3.0.3 + kotlinx-serialization available in `commonMain`; `ktor-client-mock` in `commonTest`.

- [ ] **Step 1: Add the serialization plugin to the root plugins block**

In `build.gradle.kts`, replace the plugins block with:
```kotlin
plugins {
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
}
```

- [ ] **Step 2: Apply the plugin and add dependencies in `shared/build.gradle.kts`**

Change the `plugins` block to:
```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}
```
Then replace the `sourceSets { ... }` block's dependency sections with:
```kotlin
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("com.ionspin.kotlin:bignum:0.3.10")
            implementation("io.ktor:ktor-client-core:3.0.3")
            implementation("io.ktor:ktor-client-cio:3.0.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation("io.ktor:ktor-client-mock:3.0.3")
        }
```

- [ ] **Step 3: Verify dependencies resolve and existing tests still pass**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest --console=plain
```
Expected: BUILD SUCCESSFUL, the existing 7 tests still pass (no new tests yet).

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts shared/build.gradle.kts
git commit -m "build(kmp): add Ktor 3.0.3 + kotlinx-serialization deps"
```

---

### Task 2: QuoteError + Yahoo DTO + mapper (parse/map, no HTTP)

Everything needed to turn a Yahoo chart JSON string into a `Quote`, tested without any network.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/QuoteError.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooChartDTO.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapper.kt`
- Create: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapperTest.kt`

**Interfaces:**
- Consumes: `com.aptrade.shared.domain.Money`, `com.aptrade.shared.domain.Quote`.
- Produces:
  - `sealed class QuoteError : Exception` with `object RateLimited`, `object NotFound`, `data class Network(val reason: String)`.
  - `YahooChartResponse` (@Serializable) + `val yahooJson: Json`.
  - `object YahooQuoteMapper { fun quote(response: YahooChartResponse): Quote }` (throws `QuoteError.NotFound` when no result/price).

- [ ] **Step 1: Write the failing mapper test**

`shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapperTest.kt`
```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Money
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YahooQuoteMapperTest {
    @Test
    fun mapsMetaToQuoteWithExactPrice() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "regularMarketPrice":229.35,"chartPreviousClose":227.45}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val quote = YahooQuoteMapper.quote(parsed)

        assertEquals("AAPL", quote.symbol)
        assertEquals(Money(BigDecimal.parseString("229.35"), "USD"), quote.price)
        assertTrue(quote.changePercent > 0.0) // 229.35 > 227.45
    }

    @Test
    fun emptyResultThrowsNotFound() {
        val parsed = yahooJson.decodeFromString(
            YahooChartResponse.serializer(),
            """{"chart":{"result":[]}}""",
        )
        val ex = assertFailsWith<QuoteError> { YahooQuoteMapper.quote(parsed) }
        assertTrue(ex is QuoteError.NotFound)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest --console=plain
```
Expected: FAIL — unresolved references (`QuoteError`, `yahooJson`, `YahooChartResponse`, `YahooQuoteMapper`).

- [ ] **Step 3: Create `QuoteError.kt`**

`shared/src/commonMain/kotlin/com/aptrade/shared/application/QuoteError.kt`
```kotlin
package com.aptrade.shared.application

sealed class QuoteError(message: String) : Exception(message) {
    object RateLimited : QuoteError("Rate limited by data provider")
    object NotFound : QuoteError("Quote not found")
    data class Network(val reason: String) : QuoteError("Network error: $reason")
}
```

- [ ] **Step 4: Create `YahooChartDTO.kt`** (DTOs + wire serializer + JSON config)

`shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooChartDTO.kt`
```kotlin
package com.aptrade.shared.infrastructure

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/** Reads a JSON number's raw text into an exact BigDecimal (no Double round-trip). */
object BigDecimalWireSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content
        return BigDecimal.parseString(text)
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toStringExpanded())
    }
}

val yahooJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
data class YahooChartResponse(val chart: Chart) {
    @Serializable
    data class Chart(val result: List<ResultItem>? = null)

    @Serializable
    data class ResultItem(val meta: Meta)

    @Serializable
    data class Meta(
        val symbol: String,
        val currency: String? = null,
        @Serializable(with = BigDecimalWireSerializer::class)
        val regularMarketPrice: BigDecimal? = null,
        @Serializable(with = BigDecimalWireSerializer::class)
        val chartPreviousClose: BigDecimal? = null,
    )
}
```

- [ ] **Step 5: Create `YahooQuoteMapper.kt`**

`shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapper.kt`
```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Quote

object YahooQuoteMapper {
    fun quote(response: YahooChartResponse): Quote {
        val meta = response.chart.result?.firstOrNull()?.meta ?: throw QuoteError.NotFound
        val price = meta.regularMarketPrice ?: throw QuoteError.NotFound
        val currency = meta.currency ?: "USD"
        val prev = meta.chartPreviousClose
        // Percentage is a Double, not a money amount — computing it via doubleValue is fine
        // and avoids BigDecimal non-terminating-division pitfalls. Price stays exact.
        val changePercent = if (prev != null && prev.doubleValue(false) != 0.0) {
            (price.doubleValue(false) - prev.doubleValue(false)) / prev.doubleValue(false) * 100.0
        } else {
            0.0
        }
        return Quote(symbol = meta.symbol, price = Money(price, currency), changePercent = changePercent)
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

```bash
./gradlew :shared:jvmTest --console=plain
```
Expected: PASS. Whole suite now 9 tests (7 prior + 2 new). If the `mapsMetaToQuoteWithExactPrice` Money assertion fails on scale, verify the wire text is `"229.35"` — do not switch to Double; adjust only if ionspin scale differs and record it.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/aptrade/shared/application/QuoteError.kt shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooChartDTO.kt shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapper.kt shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapperTest.kt
git commit -m "feat(kmp): add Yahoo chart DTO, exact-decimal wire serializer, and quote mapper"
```

---

### Task 3: Ktor client + YahooMarketDataRepository (MockEngine tests)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooHttpClient.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepositoryTest.kt`

**Interfaces:**
- Consumes: `QuoteRepository` (port), `Quote`, `Money`, `QuoteError`, `YahooChartResponse`, `yahooJson`, `YahooQuoteMapper`.
- Produces:
  - `fun HttpClientConfig<*>.installYahoo()` and `fun defaultYahooHttpClient(): HttpClient`.
  - `class YahooMarketDataRepository internal constructor(client: HttpClient) : QuoteRepository` with a public no-arg `constructor()`; implements `suspend fun quotes(symbols: List<String>): List<Quote>` (concurrent, fail-fast). Public no-arg ctor is what the Swift harness calls.

- [ ] **Step 1: Write the failing repository tests**

`shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepositoryTest.kt`
```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Money
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class YahooMarketDataRepositoryTest {

    private fun clientReturning(status: HttpStatusCode, body: String): HttpClient =
        HttpClient(MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { installYahoo() }

    @Test
    fun returnsExactQuoteOnSuccess() = runTest {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "regularMarketPrice":229.35,"chartPreviousClose":227.45}}]}}
        """.trimIndent()
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, body))

        val quotes = repo.quotes(listOf("AAPL"))

        assertEquals(1, quotes.size)
        assertEquals("AAPL", quotes[0].symbol)
        assertEquals(Money(BigDecimal.parseString("229.35"), "USD"), quotes[0].price)
        assertTrue(quotes[0].changePercent > 0.0)
    }

    @Test
    fun mapsHttp429ToRateLimited() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.quotes(listOf("AAPL")) }
        assertTrue(ex is QuoteError.RateLimited)
    }

    @Test
    fun mapsMalformedBodyToNetwork() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, "not json"))
        val ex = assertFailsWith<QuoteError> { repo.quotes(listOf("AAPL")) }
        assertTrue(ex is QuoteError.Network)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest --console=plain
```
Expected: FAIL — unresolved references (`installYahoo`, `YahooMarketDataRepository`).

- [ ] **Step 3: Create `YahooHttpClient.kt`**

`shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooHttpClient.kt`
```kotlin
package com.aptrade.shared.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/** Shared client config: JSON content negotiation + non-throwing status handling. */
fun HttpClientConfig<*>.installYahoo() {
    install(ContentNegotiation) { json(yahooJson) }
    expectSuccess = false
}

fun defaultYahooHttpClient(): HttpClient = HttpClient(CIO) { installYahoo() }
```

- [ ] **Step 4: Create `YahooMarketDataRepository.kt`**

`shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepository.kt`
```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.QuoteRepository
import com.aptrade.shared.domain.Quote
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class YahooMarketDataRepository internal constructor(
    private val client: HttpClient,
) : QuoteRepository {

    // Production / Swift-harness entry point: builds the default CIO client.
    constructor() : this(defaultYahooHttpClient())

    override suspend fun quotes(symbols: List<String>): List<Quote> = coroutineScope {
        symbols.map { symbol -> async { fetchOne(symbol) } }.awaitAll()
    }

    private suspend fun fetchOne(symbol: String): Quote {
        val response = try {
            client.get("https://query1.finance.yahoo.com/v8/finance/chart/$symbol") {
                header("User-Agent", "Mozilla/5.0")
                url {
                    parameters.append("range", "1d")
                    parameters.append("interval", "1d")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network(e.message ?: "request failed")
        }

        if (response.status == HttpStatusCode.TooManyRequests) throw QuoteError.RateLimited
        if (!response.status.isSuccess()) throw QuoteError.Network("HTTP ${response.status.value}")

        val parsed = try {
            response.body<YahooChartResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network("malformed response")
        }
        return YahooQuoteMapper.quote(parsed) // may throw QuoteError.NotFound
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :shared:jvmTest --console=plain
```
Expected: PASS. Whole suite now 12 tests (9 + 3 new).

- [ ] **Step 6: Confirm the whole suite count**

```bash
./gradlew :shared:jvmTest --info --console=plain 2>&1 | grep -Ei "tests completed|tests failed" | tail -3
```
Expected: `12 tests completed, 0 failed`.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooHttpClient.kt shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepository.kt shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepositoryTest.kt
git commit -m "feat(kmp): add Ktor YahooMarketDataRepository behind QuoteRepository port"
```

---

### Task 4: Reassemble `Shared.xcframework` with networking deps

**Files:** None (rebuild only; artifact is git-ignored).

**Interfaces:**
- Consumes: all `commonMain` code including the new Ktor repository.
- Produces: `shared/build/XCFrameworks/release/Shared.xcframework` exporting `YahooMarketDataRepository` (public no-arg init) to Swift.

- [ ] **Step 1: Assemble the release XCFramework**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:assembleSharedReleaseXCFramework --console=plain
```
Expected: BUILD SUCCESSFUL. First run after adding Ktor may download additional Kotlin/Native klibs (CIO for native) — be patient, do not abort.

- [ ] **Step 2: Verify the framework and slices exist**

```bash
ls shared/build/XCFrameworks/release/Shared.xcframework
ls shared/build/XCFrameworks/release/Shared.xcframework/macos-arm64/
```
Expected: `Info.plist` + `ios-arm64`, `ios-arm64-simulator`, `macos-arm64`; the macos-arm64 slice contains `Shared.framework`.

- [ ] **Step 3: No commit** (git-ignored artifact).

---

### Task 5: Point the Swift harness at live Yahoo

**Files:**
- Modify: `skeletonHarness/Sources/SkeletonHarness/main.swift`

**Interfaces:**
- Consumes: `Shared.xcframework` — `YahooMarketDataRepository()` (public no-arg init), `FetchMarketQuotes(repository:)`, `execute(symbols:)`, `Quote.symbol/.price/.changePercent`, `Money.formatted`.
- Produces: a harness that fetches live quotes at run time.

- [ ] **Step 1: Replace the repository and symbols in `main.swift`**

Replace the entire contents of `skeletonHarness/Sources/SkeletonHarness/main.swift` with:
```swift
import Shared

@main
struct SkeletonHarness {
    static func main() async {
        let repository = YahooMarketDataRepository()
        let useCase = FetchMarketQuotes(repository: repository)
        do {
            let quotes = try await useCase.execute(symbols: ["AAPL", "MSFT", "BTC-USD"])
            print("APTrade KMP — live quotes from Yahoo via shared Kotlin core:")
            for quote in quotes {
                let arrow = quote.changePercent >= 0 ? "▲" : "▼"
                print("  \(quote.symbol)\t\(quote.price.formatted)\t\(arrow) \(quote.changePercent)%")
            }
        } catch {
            print("error: \(error)")
        }
    }
}
```
(This also resolves tracked Minor M2: the previous `for case let quote as Quote` cast is replaced with a plain `for quote in quotes`, since `List<Quote>` bridges as a typed `[Quote]`.)

- [ ] **Step 2: Build and run the harness (live network)**

```bash
cd skeletonHarness
swift run
cd ..
```
Expected: prints the header and three rows for AAPL, MSFT, BTC-USD with real prices and ▲/▼ arrows. This requires network access. If the environment has no network, the build must still succeed and the program will print an `error:` line — in that case report DONE_WITH_CONCERNS noting the network limitation and that the code path is otherwise proven by Task 3's MockEngine tests. Capture the exact stdout either way.

- [ ] **Step 3: Commit**

```bash
git add skeletonHarness/Sources/SkeletonHarness/main.swift
git commit -m "feat(kmp): harness fetches live quotes from Yahoo"
```

---

### Task 6: Regression — existing Apple build still green

**Files:** None (verification only).

**Interfaces:**
- Consumes: untouched root `Package.swift`.
- Produces: evidence the networking increment did not regress the Apple build.

- [ ] **Step 1: Build the existing Swift package**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build
```
Expected: Build complete, no errors.

- [ ] **Step 2: Confirm protected paths unmodified across the branch**

```bash
git status --porcelain -- Package.swift Sources Tests
git diff --stat "$(git merge-base main HEAD)"..HEAD -- Package.swift Sources Tests
```
Expected: both empty.

- [ ] **Step 3: No commit** (nothing changed).

---

## Definition of Done (whole plan)

1. `./gradlew :shared:jvmTest` — 12 tests, 0 failures (7 prior + 5 new).
2. `./gradlew :shared:assembleSharedReleaseXCFramework` — produces `Shared.xcframework`.
3. `cd skeletonHarness && swift run` — prints live AAPL/MSFT/BTC-USD quotes (network required; MockEngine tests cover the logic offline).
4. Root `swift build` green; `Package.swift`/`Sources`/`Tests` unmodified; `QuoteRepository` port and `StubQuoteRepository` unchanged.

## Notes for the implementer

- Keep `JAVA_HOME` on JDK 17 in every `./gradlew` shell.
- Do not route money through `Double`: prices come off the wire as JSON numbers and are read as text into `BigDecimal` via `BigDecimalWireSerializer`. Only `changePercent` (a percentage) is a `Double`.
- The `QuoteRepository` port is frozen — `YahooMarketDataRepository` is a drop-in second implementation; `StubQuoteRepository` stays as-is.
- Yahoo needs the `User-Agent: Mozilla/5.0` header or it returns 401/429.
