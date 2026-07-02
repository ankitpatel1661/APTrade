# KMP Increment 4 — Chart Methods on the Shared Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `SharedCoreMarketDataRepository.history/candles/profile` route through the shared Kotlin core instead of the Swift fallback; only `search` still delegates to Swift afterward.

**Architecture:** Kotlin gains `Timeframe`/`PricePoint`/`Candle`/`Asset`/`AssetKind` domain types, chart-endpoint DTO/mapper additions, a window-clamp utility, a broadened `MarketDataRepository` port (renamed from `QuoteRepository`) with three new use cases, and real `history`/`candles`/`profile` implementations on `YahooMarketDataRepository`. The Swift adapter then wires three new injectable closures into `SharedCoreMarketDataRepository`, mapping KMP types to Swift domain types with the same exact-decimal discipline established for `quote`.

**Tech Stack:** Kotlin Multiplatform 2.1.0 / Ktor 3.0.3 / kotlinx-serialization 1.7.3 / bignum BigDecimal (shared); Swift 6 SwiftPM, no new dependencies.

**Spec:** `docs/superpowers/specs/2026-07-02-kmp-chart-methods-design.md`

## Global Constraints

- Every `./gradlew` shell needs `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`; never system Gradle.
- Apple-target Gradle tasks and all `swift build`/`swift test` need `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- Money and OHLC prices are exact-decimal end to end: KMP `BigDecimal` → `Money.amountText` (plain decimal string) → Swift `Decimal(string:)`. Never route a price through `Double`. Only `changePercent` and `volume` are `Double`.
- Dates cross the boundary as raw Unix epoch seconds (`Long` in Kotlin) — no `kotlinx-datetime` dependency. The Swift adapter converts via `Date(timeIntervalSince1970:)`.
- Decode failures map to `QuoteError.Network` (no new `QuoteError.Decoding` case), matching increment 2/3 precedent.
- Baseline test counts before this plan: `./gradlew :shared:jvmTest` = **15 tests**; `swift test` = **198 tests**. Every task's "Expected" test count below is cumulative from this baseline.
- Kotlin enum case names may not bridge to Swift exactly as spelled in Kotlin source — confirm against the generated header (`shared/build/XCFrameworks/release/Shared.xcframework/macos-arm64/Shared.framework/Headers/Shared.h`) before finalizing any Swift code that references `Shared.Timeframe` or `Shared.AssetKind` cases. Increment 3 hit this exact issue with `QuoteError`.
- Frozen this increment: root `Package.swift`'s target list (only file contents inside existing targets change), the Swift `MarketDataRepository` protocol, `AppError`, and everything under `Sources/APTradeDomain`/`Sources/APTradeApplication`.

---

### Task 1: Kotlin domain types — `Timeframe`, `PricePoint`, `Candle`, `Asset`, `AssetKind`, window clamp

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Timeframe.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PricePoint.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Candle.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Asset.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/WindowClamp.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/TimeframeTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PricePointTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/CandleTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/AssetTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/WindowClampTest.kt`

**Interfaces:**
- Consumes: existing `Money` (for `PricePoint.close`/`Candle.open/high/low/close`).
- Produces: `Timeframe` enum (`OneDay, OneWeek, OneMonth, OneYear`) with `yahooRange: String`, `yahooInterval: String`, `windowDurationSeconds: Long`; `PricePoint(epochSeconds: Long, close: Money)`; `Candle(epochSeconds: Long, open: Money, high: Money, low: Money, close: Money, volume: Double = 0.0)`; `AssetKind` enum (`Stock, Etf, Crypto`); `Asset(symbol: String, name: String, kind: AssetKind)`; top-level `fun <T> clampToWindow(items: List<T>, windowDurationSeconds: Long, epochSeconds: (T) -> Long): List<T>` in package `com.aptrade.shared.infrastructure` — all consumed by Task 2 (mapper) and Task 3 (repository/port).

- [ ] **Step 1: Write the failing domain-type tests**

Create `shared/src/commonTest/kotlin/com/aptrade/shared/domain/TimeframeTest.kt`:
```kotlin
package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeframeTest {
    @Test
    fun yahooRangeAndIntervalPerTimeframe() {
        assertEquals("5d" to "5m", Timeframe.OneDay.yahooRange to Timeframe.OneDay.yahooInterval)
        assertEquals("1mo" to "60m", Timeframe.OneWeek.yahooRange to Timeframe.OneWeek.yahooInterval)
        assertEquals("3mo" to "1d", Timeframe.OneMonth.yahooRange to Timeframe.OneMonth.yahooInterval)
        assertEquals("1y" to "1d", Timeframe.OneYear.yahooRange to Timeframe.OneYear.yahooInterval)
    }

    @Test
    fun windowDurationSecondsPerTimeframe() {
        assertEquals(24L * 3600, Timeframe.OneDay.windowDurationSeconds)
        assertEquals(7L * 24 * 3600, Timeframe.OneWeek.windowDurationSeconds)
        assertEquals(30L * 24 * 3600, Timeframe.OneMonth.windowDurationSeconds)
        assertEquals(365L * 24 * 3600, Timeframe.OneYear.windowDurationSeconds)
    }
}
```

Create `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PricePointTest.kt`:
```kotlin
package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PricePointTest {
    @Test
    fun holdsEpochSecondsAndClose() {
        val point = PricePoint(epochSeconds = 1_700_000_000L, close = Money.usd("229.35"))
        assertEquals(1_700_000_000L, point.epochSeconds)
        assertEquals(Money.usd("229.35"), point.close)
    }
}
```

Create `shared/src/commonTest/kotlin/com/aptrade/shared/domain/CandleTest.kt`:
```kotlin
package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CandleTest {
    @Test
    fun holdsOhlcvWithDefaultZeroVolume() {
        val candle = Candle(
            epochSeconds = 1_700_000_000L,
            open = Money.usd("100.00"), high = Money.usd("101.00"),
            low = Money.usd("99.00"), close = Money.usd("100.50"),
        )
        assertEquals(0.0, candle.volume)
        assertEquals(Money.usd("101.00"), candle.high)
    }

    @Test
    fun holdsExplicitVolume() {
        val candle = Candle(
            epochSeconds = 1_700_000_000L,
            open = Money.usd("100.00"), high = Money.usd("101.00"),
            low = Money.usd("99.00"), close = Money.usd("100.50"), volume = 500.0,
        )
        assertEquals(500.0, candle.volume)
    }
}
```

Create `shared/src/commonTest/kotlin/com/aptrade/shared/domain/AssetTest.kt`:
```kotlin
package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class AssetTest {
    @Test
    fun holdsSymbolNameAndKind() {
        val asset = Asset(symbol = "AAPL", name = "Apple Inc.", kind = AssetKind.Stock)
        assertEquals("AAPL", asset.symbol)
        assertEquals("Apple Inc.", asset.name)
        assertEquals(AssetKind.Stock, asset.kind)
    }
}
```

Create `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/WindowClampTest.kt`:
```kotlin
package com.aptrade.shared.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowClampTest {
    private data class Stamped(val epochSeconds: Long)

    @Test
    fun keepsOnlyItemsWithinWindowOfNewest() {
        val items = listOf(Stamped(1000), Stamped(1500), Stamped(2000))
        val result = clampToWindow(items, windowDurationSeconds = 600) { it.epochSeconds }
        assertEquals(listOf(Stamped(1500), Stamped(2000)), result)
    }

    @Test
    fun anchorsToNewestItemNotSomeExternalNow() {
        // If this were anchored to wall-clock "now" (far larger than these timestamps),
        // nothing would survive a 600-second window. Anchoring to the newest item (2000)
        // keeps anything within 600s of it, regardless of what "now" actually is.
        val items = listOf(Stamped(1000), Stamped(2000))
        val result = clampToWindow(items, windowDurationSeconds = 600) { it.epochSeconds }
        assertEquals(listOf(Stamped(2000)), result)
    }

    @Test
    fun emptyListReturnsEmpty() {
        assertEquals(emptyList<Stamped>(), clampToWindow(emptyList(), 600) { it.epochSeconds })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest --console=plain
```
Expected: compilation FAILS (`Timeframe`, `PricePoint`, `Candle`, `Asset`, `AssetKind`, `clampToWindow` all unresolved).

- [ ] **Step 3: Implement the domain types**

Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Timeframe.kt`:
```kotlin
package com.aptrade.shared.domain

enum class Timeframe {
    OneDay, OneWeek, OneMonth, OneYear;

    val yahooRange: String
        get() = when (this) {
            OneDay -> "5d"
            OneWeek -> "1mo"
            OneMonth -> "3mo"
            OneYear -> "1y"
        }

    val yahooInterval: String
        get() = when (this) {
            OneDay -> "5m"
            OneWeek -> "60m"
            OneMonth -> "1d"
            OneYear -> "1d"
        }

    /** Rolling window to clamp raw fetched points to, anchored to the newest bar. */
    val windowDurationSeconds: Long
        get() = when (this) {
            OneDay -> 24L * 3600
            OneWeek -> 7L * 24 * 3600
            OneMonth -> 30L * 24 * 3600
            OneYear -> 365L * 24 * 3600
        }
}
```

Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PricePoint.kt`:
```kotlin
package com.aptrade.shared.domain

data class PricePoint(
    val epochSeconds: Long,
    val close: Money,
)
```

Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Candle.kt`:
```kotlin
package com.aptrade.shared.domain

/** One OHLC bar. `volume` is 0.0 when the source doesn't report it. */
data class Candle(
    val epochSeconds: Long,
    val open: Money,
    val high: Money,
    val low: Money,
    val close: Money,
    val volume: Double = 0.0,
)
```

Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Asset.kt`:
```kotlin
package com.aptrade.shared.domain

enum class AssetKind { Stock, Etf, Crypto }

data class Asset(
    val symbol: String,
    val name: String,
    val kind: AssetKind,
)
```

Create `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/WindowClamp.kt`:
```kotlin
package com.aptrade.shared.infrastructure

/**
 * Trims [items] to the exact rolling window of [windowDurationSeconds], anchored to the
 * newest item's timestamp rather than wall-clock now — Yahoo's own ranges count trading
 * days, not calendar time, so anchoring to "now" would leak stale data outside market
 * hours, on weekends, or after holidays. Anchoring to the newest bar always yields the
 * latest window.
 */
fun <T> clampToWindow(items: List<T>, windowDurationSeconds: Long, epochSeconds: (T) -> Long): List<T> {
    val newest = items.maxOfOrNull(epochSeconds) ?: return items
    val cutoff = newest - windowDurationSeconds
    return items.filter { epochSeconds(it) >= cutoff }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :shared:jvmTest --console=plain
```
Expected: BUILD SUCCESSFUL, 24 tests (15 baseline + 9 new: 2 Timeframe + 1 PricePoint + 2 Candle + 1 Asset + 3 WindowClamp), 0 failures.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/aptrade/shared/domain/Timeframe.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/domain/PricePoint.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/domain/Candle.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/domain/Asset.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/WindowClamp.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/domain/TimeframeTest.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/domain/PricePointTest.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/domain/CandleTest.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/domain/AssetTest.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/WindowClampTest.kt
git commit -m "feat(kmp): add Timeframe, PricePoint, Candle, Asset domain types + window clamp"
```

---

### Task 2: Chart DTO extensions + `YahooQuoteMapper.history/candles/asset`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooChartDTO.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapper.kt`
- Modify: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapperTest.kt`

**Interfaces:**
- Consumes: `PricePoint`, `Candle`, `Asset`, `AssetKind` (Task 1); existing `YahooChartResponse`, `BigDecimalWireSerializer`, `yahooJson`, `Money`, `QuoteError`.
- Produces: `YahooQuoteMapper.history(response: YahooChartResponse): List<PricePoint>`, `.candles(response): List<Candle>`, `.asset(response): Asset` — consumed by Task 3's `YahooMarketDataRepository`.

- [ ] **Step 1: Write the failing mapper tests**

Add these four tests to `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapperTest.kt` inside the existing `YahooQuoteMapperTest` class (keep all existing tests):
```kotlin
    @Test
    fun mapsHistorySkippingNullClose() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
            "timestamp":[1782000000,1782086400,1782172800],
            "indicators":{"quote":[{"close":[299.24,null,298.01]}]}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val points = YahooQuoteMapper.history(parsed)

        assertEquals(2, points.size)
        assertEquals(1782000000L, points[0].epochSeconds)
        assertEquals(Money(BigDecimal.parseString("299.24"), "USD"), points[0].close)
        assertEquals(1782172800L, points[1].epochSeconds)
        assertEquals(Money(BigDecimal.parseString("298.01"), "USD"), points[1].close)
    }

    @Test
    fun mapsCandlesFallingBackToCloseForMissingOhlc() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
            "timestamp":[1782000000,1782086400],
            "indicators":{"quote":[{"open":[290.00,null],"high":[295.00,null],
            "low":[288.00,null],"close":[294.30,298.01],"volume":[1000.0,null]}]}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val candles = YahooQuoteMapper.candles(parsed)

        assertEquals(2, candles.size)
        assertEquals(Money(BigDecimal.parseString("290.00"), "USD"), candles[0].open)
        assertEquals(Money(BigDecimal.parseString("295.00"), "USD"), candles[0].high)
        assertEquals(Money(BigDecimal.parseString("288.00"), "USD"), candles[0].low)
        assertEquals(1000.0, candles[0].volume)
        // Second bar: open/high/low/volume missing -> fall back to close / 0.
        assertEquals(Money(BigDecimal.parseString("298.01"), "USD"), candles[1].open)
        assertEquals(Money(BigDecimal.parseString("298.01"), "USD"), candles[1].high)
        assertEquals(Money(BigDecimal.parseString("298.01"), "USD"), candles[1].low)
        assertEquals(0.0, candles[1].volume)
    }

    @Test
    fun mapsAssetFromInstrumentType() {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "instrumentType":"EQUITY","longName":"Apple Inc."}}]}}
        """.trimIndent()
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val asset = YahooQuoteMapper.asset(parsed)

        assertEquals("AAPL", asset.symbol)
        assertEquals("Apple Inc.", asset.name)
        assertEquals(AssetKind.Stock, asset.kind)
    }

    @Test
    fun mapsAssetKindFromCryptoSymbolSuffixWhenInstrumentTypeMissing() {
        val body = """{"chart":{"result":[{"meta":{"symbol":"BTC-USD","currency":"USD"}}]}}"""
        val parsed = yahooJson.decodeFromString(YahooChartResponse.serializer(), body)

        val asset = YahooQuoteMapper.asset(parsed)

        assertEquals(AssetKind.Crypto, asset.kind)
    }
```
Add `import com.aptrade.shared.domain.AssetKind` to the top of that test file (alongside the existing `Money` import).

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :shared:jvmTest --console=plain
```
Expected: compilation FAILS (`YahooQuoteMapper.history`/`.candles`/`.asset` unresolved, `timestamp`/`indicators`/`instrumentType`/`longName` unresolved on the DTO).

- [ ] **Step 3: Extend the DTO**

Replace `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooChartDTO.kt` with:
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
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

/**
 * Reads a JSON array of numbers (with possible `null` entries) into exact BigDecimals,
 * element by element — same no-Double discipline as [BigDecimalWireSerializer], applied
 * to OHLC price arrays. Decode-only: this DTO is never re-serialized.
 */
object BigDecimalListWireSerializer : KSerializer<List<BigDecimal?>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimalList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<BigDecimal?> {
        val array = (decoder as JsonDecoder).decodeJsonElement().jsonArray
        return array.map { element ->
            if (element is JsonNull) null else BigDecimal.parseString(element.jsonPrimitive.content)
        }
    }

    override fun serialize(encoder: Encoder, value: List<BigDecimal?>) {
        throw UnsupportedOperationException("BigDecimalListWireSerializer is decode-only")
    }
}

val yahooJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
data class YahooChartResponse(val chart: Chart) {
    @Serializable
    data class Chart(val result: List<ResultItem>? = null)

    @Serializable
    data class ResultItem(
        val meta: Meta,
        val timestamp: List<Long>? = null,
        val indicators: Indicators? = null,
    )

    @Serializable
    data class Meta(
        val symbol: String,
        val currency: String? = null,
        val instrumentType: String? = null,
        @Serializable(with = BigDecimalWireSerializer::class)
        val regularMarketPrice: BigDecimal? = null,
        @Serializable(with = BigDecimalWireSerializer::class)
        val chartPreviousClose: BigDecimal? = null,
        val longName: String? = null,
        val shortName: String? = null,
    )

    @Serializable
    data class Indicators(val quote: List<QuoteBlock>? = null)

    @Serializable
    data class QuoteBlock(
        @Serializable(with = BigDecimalListWireSerializer::class)
        val open: List<BigDecimal?>? = null,
        @Serializable(with = BigDecimalListWireSerializer::class)
        val high: List<BigDecimal?>? = null,
        @Serializable(with = BigDecimalListWireSerializer::class)
        val low: List<BigDecimal?>? = null,
        @Serializable(with = BigDecimalListWireSerializer::class)
        val close: List<BigDecimal?>? = null,
        val volume: List<Double?>? = null,
    )
}
```
**Note:** `timestamp` and `indicators` are given `= null` defaults (unlike Swift's non-optional `indicators`) specifically so every existing quote-only test fixture — which omits both keys — keeps parsing. Do not make them non-optional.

- [ ] **Step 4: Extend the mapper**

Replace `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapper.kt` with:
```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
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
        val previousClose = prev?.let { Money(it, currency) } ?: Money(price, currency)
        return Quote(
            symbol = meta.symbol,
            price = Money(price, currency),
            previousClose = previousClose,
            changePercent = changePercent,
        )
    }

    fun history(response: YahooChartResponse): List<PricePoint> {
        val item = response.chart.result?.firstOrNull() ?: throw QuoteError.NotFound
        val stamps = item.timestamp ?: return emptyList()
        val closes = item.indicators?.quote?.firstOrNull()?.close ?: return emptyList()
        val currency = item.meta.currency ?: "USD"
        val points = mutableListOf<PricePoint>()
        for (i in stamps.indices) {
            if (i >= closes.size) break
            val close = closes[i] ?: continue
            points.add(PricePoint(epochSeconds = stamps[i], close = Money(close, currency)))
        }
        return points
    }

    fun candles(response: YahooChartResponse): List<Candle> {
        val item = response.chart.result?.firstOrNull() ?: throw QuoteError.NotFound
        val stamps = item.timestamp ?: return emptyList()
        val block = item.indicators?.quote?.firstOrNull() ?: return emptyList()
        val closes = block.close ?: return emptyList()
        val currency = item.meta.currency ?: "USD"
        val candles = mutableListOf<Candle>()
        for (i in stamps.indices) {
            if (i >= closes.size) break
            val close = closes[i] ?: continue
            // Fall back to close for any missing OHLC field so the bar still renders.
            val open = block.open?.getOrNull(i) ?: close
            val high = block.high?.getOrNull(i) ?: if (open.compareTo(close) >= 0) open else close
            val low = block.low?.getOrNull(i) ?: if (open.compareTo(close) <= 0) open else close
            val volume = block.volume?.getOrNull(i) ?: 0.0
            candles.add(
                Candle(
                    epochSeconds = stamps[i],
                    open = Money(open, currency), high = Money(high, currency),
                    low = Money(low, currency), close = Money(close, currency),
                    volume = volume,
                ),
            )
        }
        return candles
    }

    fun asset(response: YahooChartResponse): Asset {
        val meta = response.chart.result?.firstOrNull()?.meta ?: throw QuoteError.NotFound
        val name = meta.longName ?: meta.shortName ?: meta.symbol
        return Asset(symbol = meta.symbol, name = name, kind = kind(meta))
    }

    private fun kind(meta: YahooChartResponse.Meta): AssetKind = when (meta.instrumentType?.uppercase()) {
        "ETF" -> AssetKind.Etf
        "CRYPTOCURRENCY" -> AssetKind.Crypto
        "EQUITY" -> AssetKind.Stock
        else -> if (meta.symbol.uppercase().endsWith("-USD")) AssetKind.Crypto else AssetKind.Stock
    }
}
```
**Implementer note (flagged risk):** `open.compareTo(close)` assumes bignum's `BigDecimal` exposes `compareTo`. If this doesn't compile, check `com.ionspin.kotlin.bignum.decimal.BigDecimal`'s actual comparison API (it is a `Comparable<BigDecimal>` in recent bignum releases) and adjust — do not silently switch to `Double` comparison, which would defeat the exact-decimal requirement.

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :shared:jvmTest --console=plain
```
Expected: BUILD SUCCESSFUL, 28 tests (24 from Task 1 + 4 new), 0 failures.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooChartDTO.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapper.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapperTest.kt
git commit -m "feat(kmp): extend chart DTO + mapper for history, candles, and asset profile"
```

---

### Task 3: Broaden `MarketDataRepository` port + use cases + real `YahooMarketDataRepository` methods

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/MarketDataRepository.kt`
- Delete: `shared/src/commonMain/kotlin/com/aptrade/shared/application/QuoteRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchMarketQuotes.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchHistory.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchCandles.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchProfile.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepository.kt`
- Delete: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/StubQuoteRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepository.kt`
- Modify: `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchMarketQuotesTest.kt`
- Create: `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchHistoryTest.kt`
- Create: `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchCandlesTest.kt`
- Create: `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchProfileTest.kt`
- Create: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepositoryTest.kt`
- Delete: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/StubQuoteRepositoryTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepositoryTest.kt`

**Interfaces:**
- Consumes: `Timeframe`, `PricePoint`, `Candle`, `Asset`, `AssetKind` (Task 1), `YahooQuoteMapper.history/candles/asset` (Task 2).
- Produces: `MarketDataRepository` interface (`quotes`, `history`, `candles`, `profile`); `FetchHistory.execute(symbol, timeframe)`, `FetchCandles.execute(symbol, timeframe)`, `FetchProfile.execute(symbol)` (each `@Throws(QuoteError::class, CancellationException::class)`) — all consumed by Task 5's Swift adapter via the generated framework.

This task changes an interface that every implementer and consumer must satisfy simultaneously — Kotlin will not compile with a partial migration, so the middle of this task is expected to fail to build. Follow the steps in order; the build only turns green again at Step 12.

- [ ] **Step 1: Replace the port**

Delete `shared/src/commonMain/kotlin/com/aptrade/shared/application/QuoteRepository.kt`.

Create `shared/src/commonMain/kotlin/com/aptrade/shared/application/MarketDataRepository.kt`:
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
}
```

- [ ] **Step 2: Update `FetchMarketQuotes`'s dependency type**

Replace `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchMarketQuotes.kt` with:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Quote
import kotlin.coroutines.cancellation.CancellationException

class FetchMarketQuotes(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbols: List<String>): List<Quote> =
        repository.quotes(symbols)
}
```

- [ ] **Step 3: Write the three new use-case tests**

Create `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchHistoryTest.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchHistoryTest {
    @Test
    fun returnsHistoryFromRepository() = runTest {
        val expected = listOf(PricePoint(epochSeconds = 1000L, close = Money.usd("100.00")))
        val useCase = FetchHistory(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
                    assertEquals("AAPL", symbol)
                    assertEquals(Timeframe.OneWeek, timeframe)
                    return expected
                }
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
                override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
            },
        )

        assertEquals(expected, useCase.execute("AAPL", Timeframe.OneWeek))
    }
}
```

Create `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchCandlesTest.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchCandlesTest {
    @Test
    fun returnsCandlesFromRepository() = runTest {
        val expected = listOf(
            Candle(
                epochSeconds = 1000L,
                open = Money.usd("100.00"), high = Money.usd("101.00"),
                low = Money.usd("99.00"), close = Money.usd("100.50"), volume = 500.0,
            ),
        )
        val useCase = FetchCandles(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> {
                    assertEquals("AAPL", symbol)
                    assertEquals(Timeframe.OneMonth, timeframe)
                    return expected
                }
                override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
            },
        )

        assertEquals(expected, useCase.execute("AAPL", Timeframe.OneMonth))
    }
}
```

Create `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchProfileTest.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchProfileTest {
    @Test
    fun returnsProfileFromRepository() = runTest {
        val expected = Asset(symbol = "AAPL", name = "Apple Inc.", kind = AssetKind.Stock)
        val useCase = FetchProfile(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
                override suspend fun profile(symbol: String): Asset {
                    assertEquals("AAPL", symbol)
                    return expected
                }
            },
        )

        assertEquals(expected, useCase.execute("AAPL"))
    }
}
```

- [ ] **Step 4: Implement the three use cases**

Create `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchHistory.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Timeframe
import kotlin.coroutines.cancellation.CancellationException

class FetchHistory(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String, timeframe: Timeframe): List<PricePoint> =
        repository.history(symbol, timeframe)
}
```

Create `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchCandles.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Timeframe
import kotlin.coroutines.cancellation.CancellationException

class FetchCandles(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String, timeframe: Timeframe): List<Candle> =
        repository.candles(symbol, timeframe)
}
```

Create `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchProfile.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import kotlin.coroutines.cancellation.CancellationException

class FetchProfile(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String): Asset = repository.profile(symbol)
}
```

- [ ] **Step 5: Fix `FetchMarketQuotesTest`'s anonymous repository**

Replace `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchMarketQuotesTest.kt` with:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FetchMarketQuotesTest {
    @Test
    fun returnsQuotesFromRepository() = runTest {
        val expected = listOf(Quote("AAPL", Money.usd("100.00"), Money.usd("99.00"), 1.2))
        val useCase = FetchMarketQuotes(
            object : MarketDataRepository {
                override suspend fun quotes(symbols: List<String>): List<Quote> = expected
                override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
                override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
                override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
            },
        )

        assertEquals(expected, useCase.execute(listOf("AAPL")))
    }
}
```

- [ ] **Step 6: Rename the stub repository**

Delete `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/StubQuoteRepository.kt` and
`shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/StubQuoteRepositoryTest.kt`.

Create `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepository.kt`:
```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe

class StubMarketDataRepository : MarketDataRepository {
    override suspend fun quotes(symbols: List<String>): List<Quote> = listOf(
        Quote("AAPL", Money.usd("229.35"), Money.usd("227.44"), 0.84),
        Quote("MSFT", Money.usd("430.16"), Money.usd("431.97"), -0.42),
        Quote("BTC", Money.usd("61234.00"), Money.usd("59944.98"), 2.15),
    )

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = listOf(
        PricePoint(epochSeconds = 1_700_000_000L, close = Money.usd("229.35")),
        PricePoint(epochSeconds = 1_700_003_600L, close = Money.usd("230.10")),
    )

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = listOf(
        Candle(
            epochSeconds = 1_700_000_000L,
            open = Money.usd("228.50"), high = Money.usd("230.10"),
            low = Money.usd("228.00"), close = Money.usd("229.35"), volume = 1_000_000.0,
        ),
    )

    override suspend fun profile(symbol: String): Asset =
        Asset(symbol = symbol, name = symbol, kind = AssetKind.Stock)
}
```

Create `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/StubMarketDataRepositoryTest.kt`:
```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StubMarketDataRepositoryTest {
    private val repo = StubMarketDataRepository()

    @Test
    fun returnsThreeHardcodedQuotes() = runTest {
        val quotes = repo.quotes(listOf("AAPL"))
        assertEquals(3, quotes.size)
        assertEquals("AAPL", quotes.first().symbol)
    }

    @Test
    fun returnsHardcodedHistory() = runTest {
        val points = repo.history("AAPL", Timeframe.OneDay)
        assertEquals(2, points.size)
    }

    @Test
    fun returnsHardcodedCandles() = runTest {
        val candles = repo.candles("AAPL", Timeframe.OneDay)
        assertEquals(1, candles.size)
    }

    @Test
    fun returnsProfileWithGivenSymbol() = runTest {
        val asset = repo.profile("AAPL")
        assertEquals("AAPL", asset.symbol)
    }
}
```

- [ ] **Step 7: Run tests — confirm the expected mid-task failure**

```bash
./gradlew :shared:jvmTest --console=plain
```
Expected: compilation FAILS — `YahooMarketDataRepository` does not yet implement the broadened `MarketDataRepository` interface (`history`/`candles`/`profile` missing). This is the expected mid-task red state described above; continue to Step 8.

- [ ] **Step 8: Write the three new repository tests**

Add these three tests to `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepositoryTest.kt` inside the existing `YahooMarketDataRepositoryTest` class (keep all existing tests), and add `import com.aptrade.shared.domain.AssetKind` and `import com.aptrade.shared.domain.Timeframe` to its imports:
```kotlin
    @Test
    fun returnsHistoryClampedToWindow() = runTest {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
            "timestamp":[1000,1500,2000],
            "indicators":{"quote":[{"close":[100.00,101.00,102.00]}]}}]}}
        """.trimIndent()
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, body))

        val points = repo.history("AAPL", Timeframe.OneDay)

        // OneDay's windowDurationSeconds (86400s) is far larger than these deltas, so all 3 survive.
        assertEquals(3, points.size)
        assertEquals(Money(BigDecimal.parseString("102.00"), "USD"), points.last().close)
    }

    @Test
    fun mapsCandlesHttp429ToRateLimited() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.candles("AAPL", Timeframe.OneDay) }
        assertTrue(ex is QuoteError.RateLimited)
    }

    @Test
    fun returnsProfileFromMeta() = runTest {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "instrumentType":"EQUITY","longName":"Apple Inc."}}]}}
        """.trimIndent()
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, body))

        val asset = repo.profile("AAPL")

        assertEquals("AAPL", asset.symbol)
        assertEquals("Apple Inc.", asset.name)
        assertEquals(AssetKind.Stock, asset.kind)
    }
```

- [ ] **Step 9: Implement `history`/`candles`/`profile` on `YahooMarketDataRepository`, factoring out a shared `fetchChart` helper**

Replace `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepository.kt` with:
```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
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
) : MarketDataRepository {

    // Production / Swift-harness entry point: builds the default CIO client.
    constructor() : this(defaultYahooHttpClient())

    override suspend fun quotes(symbols: List<String>): List<Quote> = coroutineScope {
        symbols.map { symbol ->
            async { YahooQuoteMapper.quote(fetchChart(symbol, "1d", "1d")) }
        }.awaitAll()
    }

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
        val response = fetchChart(symbol, timeframe.yahooRange, timeframe.yahooInterval)
        val points = YahooQuoteMapper.history(response)
        return clampToWindow(points, timeframe.windowDurationSeconds) { it.epochSeconds }
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> {
        val response = fetchChart(symbol, timeframe.yahooRange, timeframe.yahooInterval)
        val candles = YahooQuoteMapper.candles(response)
        return clampToWindow(candles, timeframe.windowDurationSeconds) { it.epochSeconds }
    }

    override suspend fun profile(symbol: String): Asset =
        YahooQuoteMapper.asset(fetchChart(symbol, "1d", "1d"))

    private suspend fun fetchChart(symbol: String, range: String, interval: String): YahooChartResponse {
        val response = try {
            client.get("https://query1.finance.yahoo.com/v8/finance/chart/$symbol") {
                header("User-Agent", "Mozilla/5.0")
                url {
                    parameters.append("range", range)
                    parameters.append("interval", interval)
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
            response.body<YahooChartResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network("malformed response")
        }
    }
}
```

- [ ] **Step 10: Run tests to verify the whole suite passes**

```bash
./gradlew :shared:jvmTest --console=plain
```
Expected: BUILD SUCCESSFUL, 37 tests (28 from Task 2 + 3 use-case tests + 3 stub-repo delta [4 new − 1 replaced] + 3 new repository tests), 0 failures.

- [ ] **Step 11: Regression — Apple-target compile still green**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:compileKotlinMacosArm64 --console=plain
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add shared/src
git commit -m "feat(kmp): broaden MarketDataRepository port; real history/candles/profile on YahooMarketDataRepository"
```

---

### Task 4: Reassemble `Shared.xcframework`

**Files:** None (rebuild only; artifact is git-ignored).

**Interfaces:**
- Consumes: all Task 1-3 Kotlin additions.
- Produces: `shared/build/XCFrameworks/release/Shared.xcframework` exporting `Timeframe`, `PricePoint`, `Candle`, `Asset`, `AssetKind`, `FetchHistory`, `FetchCandles`, `FetchProfile` to Swift — consumed by Task 5.

- [ ] **Step 1: Assemble the release XCFramework**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:assembleSharedReleaseXCFramework --console=plain
```
Expected: BUILD SUCCESSFUL. Takes several minutes; do not abort.

- [ ] **Step 2: Verify the framework and inspect the generated header for the new types' exact bridged names**

```bash
ls shared/build/XCFrameworks/release/Shared.xcframework/macos-arm64/
grep -A2 "@interface SharedTimeframe" shared/build/XCFrameworks/release/Shared.xcframework/macos-arm64/Shared.framework/Headers/Shared.h
grep -B1 -A2 "swift_name(\"Timeframe" shared/build/XCFrameworks/release/Shared.xcframework/macos-arm64/Shared.framework/Headers/Shared.h
grep -B1 -A2 "swift_name(\"AssetKind" shared/build/XCFrameworks/release/Shared.xcframework/macos-arm64/Shared.framework/Headers/Shared.h
```
Expected: `Info.plist` + `ios-arm64`, `ios-arm64-simulator`, `macos-arm64`; the grep output shows the exact Swift-bridged names for each `Timeframe`/`AssetKind` case (e.g. confirm whether `OneDay` bridges as `.oneDay` or something else). **Record these exact names — Task 5 depends on them.**

- [ ] **Step 3: No commit** (git-ignored artifact).

---

### Task 5: Swift adapter — wire `history`/`candles`/`profile` through the shared core

**Files:**
- Modify: `Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift`
- Modify: `Tests/APTradeInfrastructureTests/SharedCoreMarketDataRepositoryTests.swift`

**Interfaces:**
- Consumes: `Shared.FetchHistory(repository:).execute(symbol:timeframe:)`, `Shared.FetchCandles(repository:).execute(symbol:timeframe:)`, `Shared.FetchProfile(repository:).execute(symbol:)`, `Shared.PricePoint.epochSeconds/.close`, `Shared.Candle.epochSeconds/.open/.high/.low/.close/.volume`, `Shared.Asset.symbol/.name/.kind`, `Shared.Timeframe`/`Shared.AssetKind` cases (exact names from Task 4 Step 2).
- Produces: `SharedCoreMarketDataRepository.history/candles/profile` now route through KMP; only `search` delegates to `fallback`.

Before writing code, confirm the exact `Shared.Timeframe`/`Shared.AssetKind` case names recorded in Task 4 Step 2. The code below assumes they bridge to lowerCamelCase (`.oneDay`, `.oneWeek`, `.oneMonth`, `.oneYear`, `.stock`, `.etf`, `.crypto`) — Kotlin `enum class` cases follow this convention by default (unlike the nested sealed-class `QuoteError`, which bridged differently). If the header shows different names, use those instead in every step below.

- [ ] **Step 1: Replace the test file with the extended suite (will fail to compile until Step 2)**

Replace `Tests/APTradeInfrastructureTests/SharedCoreMarketDataRepositoryTests.swift` with:
```swift
import Foundation
import XCTest
import APTradeApplication
import APTradeDomain
@preconcurrency import Shared
@testable import APTradeInfrastructure

// NOTE: KMP `Quote`/`Money`/`PricePoint`/`Candle`/`Asset`/`Timeframe` collide with
// APTradeDomain's — Shared.X vs APTradeDomain.X. Kotlin companion functions surface as
// `.companion`; Kotlin `object`s (sealed-class singletons) as `.shared`.
final class SharedCoreMarketDataRepositoryTests: XCTestCase {
    private func kmpMoney(_ text: String) -> Shared.Money {
        Shared.Money.companion.usd(value: text)
    }

    private func noOpRepo(fallback: MarketDataRepository) -> SharedCoreMarketDataRepository {
        SharedCoreMarketDataRepository(
            fallback: fallback,
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) })
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

    func testMapsPricePointExactly() throws {
        let kmp = Shared.PricePoint(epochSeconds: 1_700_000_000, close: kmpMoney("229.35"))
        let mapped = try SharedCoreMarketDataRepository.mapPricePoint(kmp)
        XCTAssertEqual(mapped.date, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(mapped.close.amount, Decimal(string: "229.35")!)
    }

    func testMapsCandleExactly() throws {
        let kmp = Shared.Candle(
            epochSeconds: 1_700_000_000,
            open: kmpMoney("100.00"), high: kmpMoney("101.00"),
            low: kmpMoney("99.00"), close: kmpMoney("100.50"), volume: 500.0)
        let mapped = try SharedCoreMarketDataRepository.mapCandle(kmp)
        XCTAssertEqual(mapped.date, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(mapped.open.amount, Decimal(string: "100.00")!)
        XCTAssertEqual(mapped.high.amount, Decimal(string: "101.00")!)
        XCTAssertEqual(mapped.low.amount, Decimal(string: "99.00")!)
        XCTAssertEqual(mapped.close.amount, Decimal(string: "100.50")!)
        XCTAssertEqual(mapped.volume, 500.0)
    }

    func testMapsAssetKinds() {
        XCTAssertEqual(SharedCoreMarketDataRepository.mapAssetKind(.stock), .stock)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapAssetKind(.etf), .etf)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapAssetKind(.crypto), .crypto)
    }

    func testMapsTimeframes() {
        XCTAssertEqual(SharedCoreMarketDataRepository.mapTimeframe(.oneDay), .oneDay)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapTimeframe(.oneWeek), .oneWeek)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapTimeframe(.oneMonth), .oneMonth)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapTimeframe(.oneYear), .oneYear)
    }

    func testMapsKotlinErrorsToAppError() {
        func nsError(_ kotlin: Any) -> Error {
            NSError(domain: "KotlinException", code: 0, userInfo: ["KotlinException": kotlin])
        }
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(Shared.QuoteError.RateLimited.shared)), .rateLimited)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(Shared.QuoteError.NotFound.shared)), .notFound)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(Shared.QuoteError.Network(reason: "boom"))), .network)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(URLError(.timedOut)), .network)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(AppError.notFound), .notFound)
    }

    func testMapsPlainNSErrorWithNoKotlinExceptionKeyToNetwork() {
        let error = NSError(domain: "SomeOtherDomain", code: 1, userInfo: ["unrelated": "value"])
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(error), .network)
    }

    func testDelegatesSearchToFallback() async throws {
        let fallback = RecordingRepository()
        let repo = noOpRepo(fallback: fallback)
        _ = try await repo.search(query: "app")
        let calls = await fallback.calls
        XCTAssertEqual(calls, ["search"])
    }

    func testQuoteForSuccessReturnsMappedQuote() async throws {
        let kmp = Shared.Quote(
            symbol: "AAPL",
            price: kmpMoney("229.35"),
            previousClose: kmpMoney("227.45"),
            changePercent: 0.84)
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { symbols in
                XCTAssertEqual(symbols, ["AAPL"])
                return [kmp]
            },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) })
        let quote = try await repo.quote(for: "AAPL")
        XCTAssertEqual(quote.symbol, "AAPL")
        XCTAssertEqual(quote.price.amount, Decimal(string: "229.35")!)
        XCTAssertEqual(quote.previousClose.amount, Decimal(string: "227.45")!)
    }

    func testQuoteForEmptyResultThrowsNotFound() async {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) })
        do {
            _ = try await repo.quote(for: "AAPL")
            XCTFail("Expected AppError.notFound")
        } catch {
            XCTAssertEqual(error as? AppError, .notFound)
        }
    }

    func testQuoteForKotlinErrorMapsToAppError() async {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in
                throw NSError(
                    domain: "KotlinException", code: 0,
                    userInfo: ["KotlinException": Shared.QuoteError.RateLimited.shared])
            },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) })
        do {
            _ = try await repo.quote(for: "AAPL")
            XCTFail("Expected AppError.rateLimited")
        } catch {
            XCTAssertEqual(error as? AppError, .rateLimited)
        }
    }

    func testHistoryForSuccessReturnsMappedPoints() async throws {
        let kmp = [Shared.PricePoint(epochSeconds: 1_700_000_000, close: kmpMoney("229.35"))]
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { symbol, timeframe in
                XCTAssertEqual(symbol, "AAPL")
                XCTAssertEqual(timeframe, .oneWeek)
                return kmp
            },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) })

        let points = try await repo.history(for: "AAPL", timeframe: .oneWeek)
        XCTAssertEqual(points.count, 1)
        XCTAssertEqual(points[0].close.amount, Decimal(string: "229.35")!)
    }

    func testCandlesForKotlinErrorMapsToAppError() async {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in
                throw NSError(
                    domain: "KotlinException", code: 0,
                    userInfo: ["KotlinException": Shared.QuoteError.RateLimited.shared])
            },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) })

        do {
            _ = try await repo.candles(for: "AAPL", timeframe: .oneDay)
            XCTFail("Expected AppError.rateLimited")
        } catch {
            XCTAssertEqual(error as? AppError, .rateLimited)
        }
    }

    func testProfileForSuccessReturnsMappedAsset() async throws {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) })

        let asset = try await repo.profile(for: "AAPL")
        XCTAssertEqual(asset.symbol, "AAPL")
        XCTAssertEqual(asset.name, "Apple Inc.")
        XCTAssertEqual(asset.kind, .stock)
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

- [ ] **Step 2: Run tests to verify they fail**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter SharedCoreMarketDataRepositoryTests
```
Expected: compilation FAILS (`SharedCoreMarketDataRepository`'s initializer doesn't accept `fetchHistory`/`fetchCandles`/`fetchProfile`; `mapPricePoint`/`mapCandle`/`mapAssetKind`/`mapTimeframe` don't exist).

- [ ] **Step 3: Implement the adapter**

Replace `Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift` with:
```swift
import Foundation
import APTradeApplication
import APTradeDomain
// @preconcurrency: silences non-Sendable diagnostics on KMP-bridged types (Shared.Quote,
// Shared.Money, Shared.PricePoint, Shared.Candle, Shared.Asset, and the Kotlin exception
// types surfaced through error handling below).
@preconcurrency import Shared

/// Serves `quote`/`history`/`candles`/`profile` from the shared Kotlin core; `search`
/// still delegates to the Swift-native fallback repository (not yet ported).
/// @unchecked Sendable: all stored properties are immutable, and Kotlin/Native
/// objects are safely shareable across threads under the current KMP memory model.
public final class SharedCoreMarketDataRepository: MarketDataRepository, @unchecked Sendable {
    private let fetch: @Sendable ([String]) async throws -> [Shared.Quote]
    private let fetchHistory: @Sendable (String, Shared.Timeframe) async throws -> [Shared.PricePoint]
    private let fetchCandles: @Sendable (String, Shared.Timeframe) async throws -> [Shared.Candle]
    private let fetchProfile: @Sendable (String) async throws -> Shared.Asset
    private let fallback: MarketDataRepository

    public convenience init(fallback: MarketDataRepository) {
        let repository = Shared.YahooMarketDataRepository()
        let quotesUseCase = Shared.FetchMarketQuotes(repository: repository)
        let historyUseCase = Shared.FetchHistory(repository: repository)
        let candlesUseCase = Shared.FetchCandles(repository: repository)
        let profileUseCase = Shared.FetchProfile(repository: repository)
        self.init(
            fallback: fallback,
            fetch: { try await quotesUseCase.execute(symbols: $0) },
            fetchHistory: { try await historyUseCase.execute(symbol: $0, timeframe: $1) },
            fetchCandles: { try await candlesUseCase.execute(symbol: $0, timeframe: $1) },
            fetchProfile: { try await profileUseCase.execute(symbol: $0) })
    }

    init(
        fallback: MarketDataRepository,
        fetch: @escaping @Sendable ([String]) async throws -> [Shared.Quote],
        fetchHistory: @escaping @Sendable (String, Shared.Timeframe) async throws -> [Shared.PricePoint],
        fetchCandles: @escaping @Sendable (String, Shared.Timeframe) async throws -> [Shared.Candle],
        fetchProfile: @escaping @Sendable (String) async throws -> Shared.Asset
    ) {
        self.fetch = fetch
        self.fetchHistory = fetchHistory
        self.fetchCandles = fetchCandles
        self.fetchProfile = fetchProfile
        self.fallback = fallback
    }

    public func quote(for symbol: String) async throws -> APTradeDomain.Quote {
        do {
            let quotes = try await fetch([symbol])
            guard let first = quotes.first else { throw AppError.notFound }
            return try Self.mapQuote(first)
        } catch {
            throw Self.mapError(error)
        }
    }

    public func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        do {
            let points = try await fetchHistory(symbol, Self.mapTimeframe(timeframe))
            return try points.map(Self.mapPricePoint)
        } catch {
            throw Self.mapError(error)
        }
    }

    public func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        do {
            let candles = try await fetchCandles(symbol, Self.mapTimeframe(timeframe))
            return try candles.map(Self.mapCandle)
        } catch {
            throw Self.mapError(error)
        }
    }

    public func profile(for symbol: String) async throws -> Asset {
        do {
            let asset = try await fetchProfile(symbol)
            return Self.mapAsset(asset)
        } catch {
            throw Self.mapError(error)
        }
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

    static func mapPricePoint(_ point: Shared.PricePoint) throws -> APTradeDomain.PricePoint {
        APTradeDomain.PricePoint(
            date: Date(timeIntervalSince1970: TimeInterval(point.epochSeconds)),
            close: try mapMoney(point.close))
    }

    static func mapCandle(_ candle: Shared.Candle) throws -> APTradeDomain.Candle {
        APTradeDomain.Candle(
            date: Date(timeIntervalSince1970: TimeInterval(candle.epochSeconds)),
            open: try mapMoney(candle.open),
            high: try mapMoney(candle.high),
            low: try mapMoney(candle.low),
            close: try mapMoney(candle.close),
            volume: candle.volume)
    }

    static func mapAsset(_ asset: Shared.Asset) -> APTradeDomain.Asset {
        APTradeDomain.Asset(symbol: asset.symbol, name: asset.name, kind: mapAssetKind(asset.kind))
    }

    static func mapAssetKind(_ kind: Shared.AssetKind) -> APTradeDomain.AssetKind {
        switch kind {
        case .stock: return .stock
        case .etf: return .etf
        case .crypto: return .crypto
        }
    }

    static func mapTimeframe(_ timeframe: Timeframe) -> Shared.Timeframe {
        switch timeframe {
        case .oneDay: return .oneDay
        case .oneWeek: return .oneWeek
        case .oneMonth: return .oneMonth
        case .oneYear: return .oneYear
        }
    }

    /// Kotlin exceptions cross the @Throws bridge as NSError carrying the original
    /// exception under the "KotlinException" userInfo key.
    static func mapError(_ error: Error) -> AppError {
        if let app = error as? AppError { return app }
        switch (error as NSError).userInfo["KotlinException"] {
        case is Shared.QuoteError.RateLimited: return .rateLimited
        case is Shared.QuoteError.NotFound: return .notFound
        default: return .network
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter SharedCoreMarketDataRepositoryTests
```
Expected: all tests in this suite PASS (15 tests: 8 carried over + 7 new).

- [ ] **Step 5: Full macOS suite**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test
```
Expected: entire suite green, 205 tests (198 baseline + 7 new).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift \
        Tests/APTradeInfrastructureTests/SharedCoreMarketDataRepositoryTests.swift
git commit -m "feat(kmp): route history, candles, and profile through the shared Kotlin core"
```

---

### Task 6: Regression — iOS scheme + xcframework confirmation

**Files:** None (verification only).

**Interfaces:**
- Consumes: Task 5's completed adapter.
- Produces: evidence the increment doesn't regress the iOS build or the frozen Swift app files.

- [ ] **Step 1: iOS package scheme still builds (arm64-only, per increment 3 precedent)**

```bash
mv APTrade.xcodeproj APTrade.xcodeproj.parked
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -scheme APTradeLite-Package -destination 'generic/platform=iOS Simulator' ARCHS=arm64 build -quiet
mv APTrade.xcodeproj.parked APTrade.xcodeproj
```
Expected: `** BUILD SUCCEEDED **`. Restore the project even if the build fails.

- [ ] **Step 2: Confirm frozen paths unmodified across the branch**

```bash
git diff --stat "$(git merge-base main HEAD)"..HEAD -- Package.swift Sources/APTradeDomain Sources/APTradeApplication
```
Expected: empty (only `Sources/APTradeInfrastructure` and `Package.swift`'s file contents inside existing targets — not the target list — changed; `Sources/APTradeDomain`/`Sources/APTradeApplication` untouched).

- [ ] **Step 3: No commit** (nothing changed).

---

## Definition of Done (whole plan)

1. `./gradlew :shared:jvmTest` — 37 tests, 0 failures.
2. `MarketDataRepository` port has `quotes`/`history`/`candles`/`profile`; `YahooMarketDataRepository` and `StubMarketDataRepository` implement all four; `FetchHistory`/`FetchCandles`/`FetchProfile` use cases exist.
3. `Timeframe`, `PricePoint`, `Candle`, `Asset`, `AssetKind` exist in `shared/commonMain/domain`, framework-free.
4. `SharedCoreMarketDataRepository.history/candles/profile` route through KMP; only `search` delegates to the Swift fallback.
5. `swift test` — 205 tests, 0 failures.
6. iOS `APTradeLite-Package` scheme builds (`ARCHS=arm64`).
7. Money and OHLC values remain exact-decimal end to end; no price passes through `Double`.

## Notes for the implementer

- Keep `JAVA_HOME` on JDK 17 in every `./gradlew` shell; keep `DEVELOPER_DIR` set for every Apple-target Gradle task and every `swift build`/`swift test`.
- Task 3 has an intentional mid-task compile failure (Step 7) — this is expected, not a sign something went wrong; proceed to Step 8.
- Before writing any Swift code in Task 5 that names a `Shared.Timeframe` or `Shared.AssetKind` case, confirm the exact bridged name against the generated header (Task 4 Step 2). Do not assume the lowerCamelCase guesses in this plan are correct without checking.
- If `BigDecimal.compareTo` (Task 2) doesn't compile as written, find bignum's actual comparison API rather than falling back to `Double` — the exact-decimal constraint applies to candle high/low derivation too.
