# KMP Increment 6a — Windows Desktop App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Compose Desktop `:desktopApp` module that recreates the macOS APTrade visual identity (design kit, shell, watchlist tab, asset detail, Ctrl+K palette) on the shared Kotlin core, with a new `WatchlistStore` persistence port in `:shared` and a GitHub Actions Windows job producing an `.msi`.

**Architecture:** Clean Architecture, JVM-only desktop UI over `:shared`'s existing `jvm()` target (Ktor CIO already wired). A plain `AppGraph` **class** (NOT an `object` — increment-5 review requirement) constructed once in `main()`. MVVM with StateFlow ViewModels taking an injected `CoroutineScope`. Watchlist persistence is a new port in `:shared` commonMain with a JSON-file implementation in `:desktopApp`.

**Tech Stack:** Kotlin 2.1.0, Compose Multiplatform **1.7.3** (tested against Kotlin 2.1.0), kotlinx-coroutines 1.9.0 (+`-swing`), kotlinx-serialization-json 1.7.3, Gradle wrapper 8.9, GitHub Actions `windows-latest` + Temurin 17.

**Spec:** `docs/superpowers/specs/2026-07-03-kmp-windows-6a-design.md`

## Global Constraints

- Before EVERY `./gradlew`: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`. Never system Gradle — always `./gradlew`.
- Kotlin stays **2.1.0**; Compose Multiplatform plugin pinned **1.7.3**; AGP stays 8.7.2. Do not bump anything else.
- Existing suites must stay green throughout: `:shared:jvmTest` = **44**, `:androidApp:testDebugUnitTest` = **13**, `swift test` (with `DEVELOPER_DIR=/Applications/Xcode.app`) = **193**.
- Verify Gradle test counts from JUnit XMLs, not console: `grep -o 'tests="[0-9]*"' <module>/build/test-results/**/TEST-*.xml | grep -o '[0-9]*' | paste -sd+ - | bc` — and use `--rerun-tasks` when the count is the evidence (cached runs report UP-TO-DATE having run nothing).
- Money display NEVER goes through `Double` — always `Money.amountText`/`formatted`. `Double` is allowed only for chart/sparkline pixel math (`amount.doubleValue(false)` — established pattern).
- In every coroutine `catch`: rethrow `CancellationException` BEFORE catching `QuoteError`.
- Palette (dark, from `Sources/APTradeApp/Theme.swift` — copy verbatim): bgTop `#0C0B09`, bgBottom `#050504`, surface `#16140F`, surfaceHi `#211D15`, hairline = white 7%, textPrimary `#F4F1EA`, textSecondary `#9C968A`, textTertiary `#615C51`, goldDeep `#A9772A`, gold `#D4A94E`, goldLight `#F2DDA0`, silver `#D8D5CE`, up `#46C98A`, down `#E06A5E`. Gains green / losses red is data, never branding.
- Commit after every task (at minimum). Plan-file checkboxes may be ticked as steps complete.

## Design note (refinement over the spec)

The spec's `WatchlistStore` said "load/save/observe". `observe` is dropped: the single 6a consumer (one ViewModel) owns the in-memory list and re-renders from its own StateFlow; a reactive store adds surface with zero consumers. Additionally the stored unit is `WatchlistEntry(symbol, name, kind)` — not bare symbols — because the macOS row renders name + symbol and the `KindToggle` needs each entry's kind; entries added via search already carry both (avoids N profile calls per launch).

---

### Task 1: `:desktopApp` module scaffold + empty window

**Files:**
- Modify: `build.gradle.kts` (root)
- Modify: `settings.gradle.kts`
- Create: `desktopApp/build.gradle.kts`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt`
- Create: `desktopApp/src/test/kotlin/com/aptrade/desktop/SmokeTest.kt`

**Interfaces:**
- Consumes: `:shared` jvm target (already exists).
- Produces: module skeleton every later task builds on; `main()` entry `com.aptrade.desktop.MainKt`; compose desktop packaging config (`packageMsi` used by Task 8).

- [ ] **Step 1: Add plugin pins to the root build**

In root `build.gradle.kts`, extend the `plugins` block (keep existing lines):

```kotlin
plugins {
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    kotlin("android") version "2.1.0" apply false
    kotlin("jvm") version "2.1.0" apply false                              // NEW
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false               // NEW
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
}
```

- [ ] **Step 2: Include the module**

In `settings.gradle.kts` add after the existing includes:

```kotlin
include(":desktopApp")
```

- [ ] **Step 3: Write `desktopApp/build.gradle.kts`**

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.aptrade.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "APTrade"
            packageVersion = "1.0.0"   // MSI requires a version with major > 0
            vendor = "APTrade"
        }
    }
}
```

- [ ] **Step 4: Minimal `Main.kt`**

```kotlin
package com.aptrade.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "APTrade") {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0C0B09)),
            contentAlignment = Alignment.Center,
        ) {
            Text("APTrade desktop — walking skeleton", color = Color(0xFFD4A94E))
        }
    }
}
```

- [ ] **Step 5: Smoke test** (`SmokeTest.kt`) — proves the test task and `:shared` dependency compile on this module:

```kotlin
package com.aptrade.desktop

import com.aptrade.shared.domain.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun sharedCoreIsOnTheClasspath() {
        // NOTE: value chosen with a non-zero last cent — Money.formatted drops
        // trailing zeros (known M1 debt), so "$1.50" would fail here.
        assertEquals("$1.25", Money.usd("1.25").formatted)
    }
}
```

- [ ] **Step 6: Build + test**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :desktopApp:test --console=plain
```
Expected: BUILD SUCCESSFUL; JUnit XML under `desktopApp/build/test-results/test/` shows `tests="1"`. If the Compose 1.7.3/Kotlin 2.1.0 resolution fails (it should not — 1.7.3 is tested with 2.1.0), STOP and report rather than bumping versions.

- [ ] **Step 7: Verify the window opens (manual, on this Mac)**

```bash
./gradlew :desktopApp:run --console=plain &
```
Expected: a dark window titled "APTrade" with gold placeholder text. Close it (Cmd+Q or window close), then kill the gradle run if lingering.

- [ ] **Step 8: Regression check — other modules untouched**

```bash
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest --console=plain --rerun-tasks
```
Expected: XML counts 44 and 13, zero failures.

- [ ] **Step 9: Commit**

```bash
git add build.gradle.kts settings.gradle.kts desktopApp/
git commit -m "feat(desktop): scaffold :desktopApp Compose Desktop module with empty window"
```

---

### Task 2: `WatchlistEntry` + `WatchlistStore` port + use cases in `:shared`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/WatchlistEntry.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/WatchlistStore.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchWatchlist.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/AddToWatchlist.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/RemoveFromWatchlist.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/WatchlistUseCasesTest.kt`

**Interfaces:**
- Consumes: `AssetKind` (existing enum: `Stock`, `Etf`, `Crypto`).
- Produces (used by Tasks 3, 5):
  - `data class WatchlistEntry(val symbol: String, val name: String, val kind: AssetKind)`
  - `interface WatchlistStore { suspend fun load(): List<WatchlistEntry>; suspend fun save(entries: List<WatchlistEntry>) }`
  - `FetchWatchlist(store, defaults).execute(): List<WatchlistEntry>`
  - `AddToWatchlist(store).execute(entry: WatchlistEntry): List<WatchlistEntry>`
  - `RemoveFromWatchlist(store).execute(symbol: String): List<WatchlistEntry>`

**NOTE:** this adds new files only — no existing interface broadens, so no test-double sweep is needed (the 4b lesson).

- [ ] **Step 1: Write the failing tests** (`WatchlistUseCasesTest.kt`):

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class InMemoryWatchlistStore : WatchlistStore {
    var stored: List<WatchlistEntry> = emptyList()
    var saveCount = 0
        private set

    override suspend fun load(): List<WatchlistEntry> = stored
    override suspend fun save(entries: List<WatchlistEntry>) {
        stored = entries
        saveCount++
    }
}

private val aapl = WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)
private val spy = WatchlistEntry("SPY", "SPDR S&P 500 ETF Trust", AssetKind.Etf)
private val btc = WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto)

class WatchlistUseCasesTest {

    @Test
    fun fetchSeedsAndPersistsDefaultsWhenStoreIsEmpty() = runTest {
        val store = InMemoryWatchlistStore()
        val result = FetchWatchlist(store, defaults = listOf(aapl, spy)).execute()
        assertEquals(listOf(aapl, spy), result)
        assertEquals(listOf(aapl, spy), store.stored)   // seed was persisted
    }

    @Test
    fun fetchReturnsStoredEntriesWithoutTouchingDefaults() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(btc) }
        val result = FetchWatchlist(store, defaults = listOf(aapl)).execute()
        assertEquals(listOf(btc), result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun addAppendsNormalizedSymbolAndPersists() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl) }
        val result = AddToWatchlist(store)
            .execute(WatchlistEntry("  msft ", "Microsoft Corporation", AssetKind.Stock))
        assertEquals(listOf("AAPL", "MSFT"), result.map { it.symbol })
        assertEquals(result, store.stored)
    }

    @Test
    fun addIsIdempotentForDuplicateSymbols() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl) }
        val result = AddToWatchlist(store).execute(aapl.copy(name = "Renamed"))
        assertEquals(listOf(aapl), result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun addIgnoresBlankSymbols() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl) }
        val result = AddToWatchlist(store).execute(WatchlistEntry("   ", "", AssetKind.Stock))
        assertEquals(listOf(aapl), result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun removeDropsTheSymbolAndPersists() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl, spy) }
        val result = RemoveFromWatchlist(store).execute("AAPL")
        assertEquals(listOf(spy), result)
        assertEquals(result, store.stored)
    }

    @Test
    fun removeOfUnknownSymbolIsANoOp() = runTest {
        val store = InMemoryWatchlistStore().apply { stored = listOf(aapl) }
        val result = RemoveFromWatchlist(store).execute("ZZZZ")
        assertEquals(listOf(aapl), result)
        assertEquals(0, store.saveCount)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest --console=plain
```
Expected: compilation FAILS — `WatchlistEntry`, `WatchlistStore`, use cases unresolved.

- [ ] **Step 3: Implement**

`WatchlistEntry.kt`:
```kotlin
package com.aptrade.shared.domain

/** One saved watchlist row. Name and kind are captured at add time (search results
 *  carry both) so the UI never needs a per-symbol profile fetch on launch. */
data class WatchlistEntry(
    val symbol: String,
    val name: String,
    val kind: AssetKind,
)
```

`WatchlistStore.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.WatchlistEntry

/** Persistence port for the user's ordered watchlist. Implementations live per
 *  platform (JSON file on desktop). Load returns an empty list when nothing was
 *  ever saved. */
interface WatchlistStore {
    suspend fun load(): List<WatchlistEntry>
    suspend fun save(entries: List<WatchlistEntry>)
}
```

`FetchWatchlist.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.WatchlistEntry

class FetchWatchlist(
    private val store: WatchlistStore,
    private val defaults: List<WatchlistEntry>,
) {
    /** First launch (empty store) seeds — and persists — the default watchlist. */
    suspend fun execute(): List<WatchlistEntry> {
        val stored = store.load()
        if (stored.isNotEmpty()) return stored
        store.save(defaults)
        return defaults
    }
}
```

`AddToWatchlist.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.WatchlistEntry

class AddToWatchlist(private val store: WatchlistStore) {
    /** Appends `entry` (symbol trimmed/uppercased). Blank or duplicate symbols are
     *  a no-op. Returns the resulting list either way. */
    suspend fun execute(entry: WatchlistEntry): List<WatchlistEntry> {
        val current = store.load()
        val symbol = entry.symbol.trim().uppercase()
        if (symbol.isEmpty() || current.any { it.symbol == symbol }) return current
        val updated = current + entry.copy(symbol = symbol)
        store.save(updated)
        return updated
    }
}
```

`RemoveFromWatchlist.kt`:
```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.WatchlistEntry

class RemoveFromWatchlist(private val store: WatchlistStore) {
    /** Removes `symbol` if present. Returns the resulting list either way. */
    suspend fun execute(symbol: String): List<WatchlistEntry> {
        val current = store.load()
        val updated = current.filterNot { it.symbol == symbol }
        if (updated.size == current.size) return current
        store.save(updated)
        return updated
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :shared:jvmTest --console=plain --rerun-tasks
```
Expected: PASS. XML count = **51** (44 + 7).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/aptrade/shared/domain/WatchlistEntry.kt shared/src/commonMain/kotlin/com/aptrade/shared/application/ shared/src/commonTest/kotlin/com/aptrade/shared/application/WatchlistUseCasesTest.kt
git commit -m "feat(shared): WatchlistStore port with Fetch/Add/RemoveFromWatchlist use cases"
```

---

### Task 3: JSON-file `WatchlistStore` + config-dir resolver in `:desktopApp`

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/ConfigDir.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/FileWatchlistStore.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/infra/ConfigDirTest.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/infra/FileWatchlistStoreTest.kt`

**Interfaces:**
- Consumes: `WatchlistStore`, `WatchlistEntry`, `AssetKind` (Task 2).
- Produces (used by Task 5's `AppGraph`):
  - `fun resolveConfigDir(osName: String = ..., env: (String) -> String? = System::getenv, userHome: String = ...): Path`
  - `class FileWatchlistStore(private val file: Path) : WatchlistStore`

- [ ] **Step 1: Write the failing tests**

`ConfigDirTest.kt`:
```kotlin
package com.aptrade.desktop.infra

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigDirTest {
    @Test
    fun windowsUsesAppData() {
        val dir = resolveConfigDir(
            osName = "Windows 11",
            env = { if (it == "APPDATA") "C:\\Users\\ap\\AppData\\Roaming" else null },
            userHome = "C:\\Users\\ap",
        )
        assertEquals(Path("C:\\Users\\ap\\AppData\\Roaming", "APTrade"), dir)
    }

    @Test
    fun windowsFallsBackToHomeWhenAppDataMissing() {
        val dir = resolveConfigDir(osName = "Windows 11", env = { null }, userHome = "C:\\Users\\ap")
        assertEquals(Path("C:\\Users\\ap", "APTrade"), dir)
    }

    @Test
    fun macUsesApplicationSupport() {
        val dir = resolveConfigDir(osName = "Mac OS X", env = { null }, userHome = "/Users/ap")
        assertEquals(Path("/Users/ap", "Library", "Application Support", "APTrade"), dir)
    }

    @Test
    fun linuxUsesDotConfig() {
        val dir = resolveConfigDir(osName = "Linux", env = { null }, userHome = "/home/ap")
        assertEquals(Path("/home/ap", ".config", "aptrade"), dir)
    }
}
```

`FileWatchlistStoreTest.kt`:
```kotlin
package com.aptrade.desktop.infra

import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileWatchlistStoreTest {
    private val entries = listOf(
        WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
        WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto),
    )

    @Test
    fun roundTripsEntries() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("watchlist.json")
        val store = FileWatchlistStore(file)
        store.save(entries)
        assertEquals(entries, store.load())
        assertEquals(entries, FileWatchlistStore(file).load())   // fresh instance, same file
    }

    @Test
    fun missingFileLoadsEmpty() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("nope.json")
        assertEquals(emptyList(), FileWatchlistStore(file).load())
    }

    @Test
    fun corruptFileLoadsEmpty() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("watchlist.json")
        file.writeText("{not json[")
        assertEquals(emptyList(), FileWatchlistStore(file).load())
    }

    @Test
    fun saveCreatesParentDirsAndLeavesNoTempFile() = runTest {
        val dir = createTempDirectory("aptrade-test").resolve("deep").resolve("nested")
        val file = dir.resolve("watchlist.json")
        FileWatchlistStore(file).save(entries)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("AAPL"))
        assertEquals(listOf("watchlist.json"), dir.toFile().list()!!.toList())  // temp file was renamed away
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :desktopApp:test --console=plain
```
Expected: compilation FAILS — `resolveConfigDir`, `FileWatchlistStore` unresolved.

- [ ] **Step 3: Implement**

`ConfigDir.kt`:
```kotlin
package com.aptrade.desktop.infra

import java.nio.file.Path
import kotlin.io.path.Path

/** Platform config directory: %APPDATA%\APTrade on Windows,
 *  ~/Library/Application Support/APTrade on macOS, ~/.config/aptrade elsewhere.
 *  Parameters exist for tests; production callers use the defaults. */
fun resolveConfigDir(
    osName: String = System.getProperty("os.name") ?: "",
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
): Path {
    val os = osName.lowercase()
    return when {
        os.contains("win") -> {
            val appData = env("APPDATA")
            if (appData != null) Path(appData, "APTrade") else Path(userHome, "APTrade")
        }
        os.contains("mac") -> Path(userHome, "Library", "Application Support", "APTrade")
        else -> Path(userHome, ".config", "aptrade")
    }
}
```

`FileWatchlistStore.kt`:
```kotlin
package com.aptrade.desktop.infra

import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** JSON-file watchlist. Writes are atomic: temp file + rename, so a crash mid-save
 *  can never leave a half-written watchlist. Missing or corrupt file loads empty
 *  (first-launch seeding is FetchWatchlist's job). */
class FileWatchlistStore(private val file: Path) : WatchlistStore {

    @Serializable
    private data class EntryDTO(val symbol: String, val name: String, val kind: String)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(EntryDTO.serializer())

    override suspend fun load(): List<WatchlistEntry> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(serializer, file.readText()).mapNotNull { dto ->
                val kind = AssetKind.entries.firstOrNull { it.name == dto.kind }
                    ?: return@mapNotNull null   // unknown kind from a future version: skip the row
                WatchlistEntry(dto.symbol, dto.name, kind)
            }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    override suspend fun save(entries: List<WatchlistEntry>) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val text = json.encodeToString(serializer, entries.map { EntryDTO(it.symbol, it.name, it.kind.name) })
        val temp = Files.createTempFile(file.parent, "watchlist", ".tmp")
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: PASS, XML count = **9** (1 smoke + 4 config + 4 store).

- [ ] **Step 5: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): JSON-file WatchlistStore with atomic writes and config-dir resolver"
```

---

### Task 4: DesignKit — palette, Inter typography, formatting logic, components

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/DK.kt` (colors, gradient, theme wrapper)
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/Formatting.kt` (pure logic)
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/Components.kt` (SuperscriptPrice, ChangePill, KindToggle, TimeframeBar, StatTile, LiveBadge, PulseBar, BrandMark)
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/Charts.kt` (Sparkline, LineChart, CandleChart)
- Create: `desktopApp/src/main/resources/brand/AppWordmark.png`, `desktopApp/src/main/resources/brand/AppLogo.png` (copied)
- Create: `desktopApp/src/main/resources/fonts/` (Inter static TTFs ×4)
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/designkit/FormattingTest.kt`

**Interfaces:**
- Consumes: nothing app-specific (pure Compose + stdlib) — deliberately extraction-ready: **no `com.aptrade.desktop.*` imports outside `designkit`, no Android/AWT-window APIs**.
- Produces (used by Tasks 6–7):
  - `object DK` with `val bgTop/bgBottom/surface/surfaceHi/hairline/textPrimary/textSecondary/textTertiary/goldDeep/gold/goldLight/silver/up/down: Color`, `fun changeColor(changePercent: Double?): Color`, `val goldGradient: Brush`, `val backgroundGradient: Brush`
  - `val InterFamily: FontFamily`; `@Composable fun APTradeDesktopTheme(content)`
  - `data class PriceParts(val symbol: String, val whole: String, val fraction: String)`; `fun splitPrice(amountText: String, currencySymbol: String = "$"): PriceParts`; `fun formatPercent(value: Double?): String`
  - Composables: `SuperscriptPrice(amountText: String, size: TextUnit = 34.sp, color: Color = DK.textPrimary)`, `ChangePill(changePercent: Double?)`, `KindToggle(selection: AssetKind, counts: Map<AssetKind, Int>, onSelect: (AssetKind) -> Unit)`, `TimeframeBar(selection: Timeframe, onSelect: (Timeframe) -> Unit)`, `StatTile(label: String, value: String, valueColor: Color = DK.textPrimary)`, `LiveBadge()`, `PulseBar(advancers: Int, decliners: Int)`, `BrandWordmark(height: Dp)`, `Sparkline(values: List<Double>, color: Color, modifier: Modifier)`, `LineChart(values: List<Double>, modifier: Modifier)`, `CandleChart(candles: List<ChartCandle>, modifier: Modifier)`, `data class ChartCandle(val open: Double, val high: Double, val low: Double, val close: Double)`
  - `fun timeframeLabel(tf: Timeframe): String` ("1D"/"1W"/"1M"/"1Y") and `fun kindLabel(kind: AssetKind): String` ("Stock"/"ETF"/"Crypto")

- [ ] **Step 1: Copy brand assets**

```bash
mkdir -p desktopApp/src/main/resources/brand desktopApp/src/main/resources/fonts
cp "Sources/APTradeApp/Resources/AppWordmark.png" desktopApp/src/main/resources/brand/
cp "Sources/APTradeApp/Resources/AppLogo.png" desktopApp/src/main/resources/brand/
```
(The shipped PNGs are the dark-mode champagne-gold originals — exactly what 6a's dark-only theme needs; no recoloring port.)

- [ ] **Step 2: Fetch Inter static TTFs** (Regular / Medium / SemiBold / Bold)

```bash
cd /tmp && curl -fLO https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip
unzip -l Inter-4.1.zip | grep -Ei 'ttf.*(Regular|Medium|SemiBold|Bold)' | head -20
```
Locate the four **static, non-Display, non-Italic** TTFs in the listing (expected under `extras/ttf/`), extract them, and copy as `Inter-Regular.ttf`, `Inter-Medium.ttf`, `Inter-SemiBold.ttf`, `Inter-Bold.ttf` into `desktopApp/src/main/resources/fonts/`. If the exact member paths differ from `extras/ttf/Inter-<W>.ttf`, use the paths the listing shows — the four target filenames in `resources/fonts/` are what the code below depends on.

- [ ] **Step 3: Failing tests for the pure logic** (`FormattingTest.kt`):

```kotlin
package com.aptrade.desktop.designkit

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
    @Test fun splitsWholeAndCents() =
        assertEquals(PriceParts("$", "308", "63"), splitPrice("308.63"))

    @Test fun groupsThousands() =
        assertEquals(PriceParts("$", "61,369", "17"), splitPrice("61369.17"))

    @Test fun padsSingleFractionDigit() =                     // Money.formatted drops trailing zeros
        assertEquals(PriceParts("$", "1,694", "20"), splitPrice("1694.2"))

    @Test fun padsMissingFraction() =
        assertEquals(PriceParts("$", "42", "00"), splitPrice("42"))

    @Test fun truncatesLongFractionToTwoDigits() =
        assertEquals(PriceParts("$", "0", "12"), splitPrice("0.129"))

    @Test fun keepsNegativeSignOnWhole() =
        assertEquals(PriceParts("$", "-1,234", "50"), splitPrice("-1234.5"))

    @Test fun formatsPositivePercentWithSign() = assertEquals("+4.84%", formatPercent(4.84))
    @Test fun formatsNegativePercent() = assertEquals("-0.13%", formatPercent(-0.13))
    @Test fun formatsZeroPercentWithoutSign() = assertEquals("0.00%", formatPercent(0.0))
    @Test fun formatsNullPercentAsDash() = assertEquals("—", formatPercent(null))
}
```

- [ ] **Step 4: Run to verify failure**

```bash
./gradlew :desktopApp:test --console=plain
```
Expected: compilation FAILS — `PriceParts`, `splitPrice`, `formatPercent` unresolved.

- [ ] **Step 5: Implement `Formatting.kt`** (string math only — money never goes through Double):

```kotlin
package com.aptrade.desktop.designkit

/** Pieces of the SuperscriptPrice treatment: "$" + "61,369" + raised "17". */
data class PriceParts(val symbol: String, val whole: String, val fraction: String)

/** Splits Money.amountText (a plain decimal string, possibly with dropped trailing
 *  zeros — known Money.formatted debt) into grouped whole + exactly-2-digit fraction.
 *  Pure string math: the exact decimal never rides through Double. */
fun splitPrice(amountText: String, currencySymbol: String = "$"): PriceParts {
    val negative = amountText.startsWith("-")
    val unsigned = amountText.removePrefix("-")
    val whole = unsigned.substringBefore('.')
    val fraction = unsigned.substringAfter('.', "").padEnd(2, '0').take(2)
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return PriceParts(currencySymbol, (if (negative) "-" else "") + grouped, fraction)
}

/** "+4.84%" / "-0.13%" / "0.00%" / "—" — matches Swift Percentage.formatted. */
fun formatPercent(value: Double?): String {
    if (value == null) return "—"
    val rounded = kotlin.math.round(value * 100) / 100
    val body = buildString {
        val abs = kotlin.math.abs(rounded)
        val whole = abs.toLong()
        val cents = kotlin.math.round((abs - whole) * 100).toLong().toString().padStart(2, '0')
        // group thousands in the whole part (e.g. +1,234.50%)
        append(whole.toString().reversed().chunked(3).joinToString(",").reversed())
        append('.').append(cents)
    }
    val sign = if (rounded > 0) "+" else if (rounded < 0) "-" else ""
    return "$sign$body%"
}
```

- [ ] **Step 6: Run the formatting tests**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: PASS, XML count = **19** (9 + 10).

- [ ] **Step 7: Implement `DK.kt`** — exact hex from Theme.swift (Global Constraints table):

```kotlin
package com.aptrade.desktop.designkit

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font   // the desktop overload that takes `resource`
import androidx.compose.ui.text.TextStyle

/** "Gold on black": the APTrade identity, transcribed from Sources/APTradeApp/Theme.swift
 *  (dark-mode values; light theme is increment 6d). Gains stay green, losses stay red —
 *  price-direction color is data, never branding. */
object DK {
    val bgTop = Color(0xFF0C0B09)
    val bgBottom = Color(0xFF050504)
    val surface = Color(0xFF16140F)
    val surfaceHi = Color(0xFF211D15)
    val hairline = Color.White.copy(alpha = 0.07f)

    val textPrimary = Color(0xFFF4F1EA)
    val textSecondary = Color(0xFF9C968A)
    val textTertiary = Color(0xFF615C51)

    val goldDeep = Color(0xFFA9772A)
    val gold = Color(0xFFD4A94E)
    val goldLight = Color(0xFFF2DDA0)
    val silver = Color(0xFFD8D5CE)

    val up = Color(0xFF46C98A)
    val down = Color(0xFFE06A5E)

    fun changeColor(changePercent: Double?): Color = when {
        changePercent == null -> textSecondary
        changePercent > 0 -> up
        changePercent < 0 -> down
        else -> textSecondary
    }

    /** The logo's diagonal gold gradient (deep → mid → light, bottom-left → top-right). */
    val goldGradient = Brush.linearGradient(listOf(goldDeep, gold, goldLight))

    val backgroundGradient = Brush.verticalGradient(listOf(bgTop, bgBottom))
}

val InterFamily = FontFamily(
    Font(resource = "fonts/Inter-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/Inter-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/Inter-SemiBold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/Inter-Bold.ttf", weight = FontWeight.Bold),
)

private val DKColorScheme = darkColorScheme(
    primary = DK.gold,
    onPrimary = Color.Black,
    background = DK.bgTop,
    onBackground = DK.textPrimary,
    surface = DK.surface,
    onSurface = DK.textPrimary,
    surfaceVariant = DK.surfaceHi,
    onSurfaceVariant = DK.textSecondary,
    outline = DK.hairline,
    error = DK.down,
)

@Composable
fun APTradeDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DKColorScheme,
        typography = Typography(bodyLarge = TextStyle(fontFamily = InterFamily)),
        content = content,
    )
}
```

- [ ] **Step 8: Implement `Components.kt`**

All sizes/opacities transcribed from `DesignKit.swift`. Every numeric text style sets `fontFeatureSettings = "tnum"` (tabular numerals = `.monospacedDigit()`).

```kotlin
package com.aptrade.desktop.designkit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Timeframe

fun timeframeLabel(tf: Timeframe): String = when (tf) {
    Timeframe.OneDay -> "1D"; Timeframe.OneWeek -> "1W"
    Timeframe.OneMonth -> "1M"; Timeframe.OneYear -> "1Y"
}

fun kindLabel(kind: AssetKind): String = when (kind) {
    AssetKind.Stock -> "Stock"; AssetKind.Etf -> "ETF"; AssetKind.Crypto -> "Crypto"
}

private fun numericStyle(size: TextUnit, weight: FontWeight, color: Color) = TextStyle(
    fontFamily = InterFamily, fontSize = size, fontWeight = weight, color = color,
    fontFeatureSettings = "tnum",
)

/** The full "AP Trade" lockup PNG (dark-mode champagne original). */
@Composable
fun BrandWordmark(height: Dp) {
    Image(
        painter = painterResource("brand/AppWordmark.png"),
        contentDescription = "APTrade",
        modifier = Modifier.height(height),
    )
}

/** Pulsing gold "LIVE" capsule — DesignKit.swift LiveBadge (1.1s ease pulse). */
@Composable
fun LiveBadge() {
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.gold.copy(alpha = 0.10f))
            .border(1.dp, DK.gold.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(6.dp).alpha(pulse).background(DK.gold, CircleShape))
        Text("LIVE", style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, color = DK.gold, letterSpacing = 1.6.sp))
    }
}

/** "$308⁶³" — symbol and cents at half size, raised to the top. */
@Composable
fun SuperscriptPrice(amountText: String, size: TextUnit = 34.sp, color: Color = DK.textPrimary) {
    val parts = splitPrice(amountText)
    Row(verticalAlignment = Alignment.Top) {
        Text(parts.symbol, style = numericStyle(size * 0.5f, FontWeight.SemiBold, DK.textSecondary),
            modifier = Modifier.padding(end = 1.dp))
        Text(parts.whole, style = numericStyle(size, FontWeight.SemiBold, color))
        Text(parts.fraction, style = numericStyle(size * 0.5f, FontWeight.SemiBold, color.copy(alpha = 0.85f)),
            modifier = Modifier.padding(start = 1.dp))
    }
}

/** Bordered, faintly tinted percent chip in its own direction color. */
@Composable
fun ChangePill(changePercent: Double?) {
    val color = DK.changeColor(changePercent)
    Text(
        formatPercent(changePercent),
        style = numericStyle(12.sp, FontWeight.SemiBold, color),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Stocks / ETFs / Crypto segmented capsule with per-kind counts. */
@Composable
fun KindToggle(selection: AssetKind, counts: Map<AssetKind, Int>, onSelect: (AssetKind) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        for (kind in listOf(AssetKind.Stock, AssetKind.Etf, AssetKind.Crypto)) {
            val selected = kind == selection
            val count = counts[kind] ?: 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) DK.surfaceHi else Color.Transparent)
                    .then(if (selected) Modifier.border(1.dp, DK.gold.copy(alpha = 0.40f), RoundedCornerShape(50)) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(kind) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    when (kind) { AssetKind.Stock -> "Stocks"; AssetKind.Etf -> "ETFs"; AssetKind.Crypto -> "Crypto" },
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (selected) DK.textPrimary else DK.textSecondary),
                )
                if (count > 0) Text("$count", style = numericStyle(11.sp, FontWeight.SemiBold,
                    if (selected) DK.gold else DK.textTertiary))
            }
        }
    }
}

/** Underline-selected 1D / 1W / 1M / 1Y row. */
@Composable
fun TimeframeBar(selection: Timeframe, onSelect: (Timeframe) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        for (tf in Timeframe.entries) {
            val selected = tf == selection
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tf) },
            ) {
                Text(timeframeLabel(tf), style = numericStyle(13.sp, FontWeight.SemiBold,
                    if (selected) DK.gold else DK.textSecondary))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.height(2.dp).fillMaxWidth(0.6f).clip(RoundedCornerShape(1.dp))
                    .background(if (selected) DK.gold else Color.Transparent))
            }
        }
    }
}

/** One labeled figure in the key-stats grid. */
@Composable
fun StatTile(label: String, value: String, valueColor: Color = DK.textPrimary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, color = DK.textTertiary, letterSpacing = 1.sp))
        Text(value, style = numericStyle(16.sp, FontWeight.SemiBold, valueColor))
    }
}

/** Thin advancers/decliners split capsule. */
@Composable
fun PulseBar(advancers: Int, decliners: Int, modifier: Modifier = Modifier) {
    val total = (advancers + decliners).coerceAtLeast(1)
    Row(modifier.height(4.dp).clip(RoundedCornerShape(50))) {
        Box(Modifier.weight(advancers.toFloat().coerceAtLeast(0.0001f)).fillMaxHeight().background(DK.up))
        Box(Modifier.weight((total - advancers).toFloat().coerceAtLeast(0.0001f)).fillMaxHeight().background(DK.down))
    }
}
```

- [ ] **Step 9: Implement `Charts.kt`** — Sparkline is a port of `Sparkline.swift` (gradient fill 0.22→0, stroke 1.5, 2px inset); Line/Candle adapted from `androidApp` `Charts.kt` with DK colors:

```kotlin
package com.aptrade.desktop.designkit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

data class ChartCandle(val open: Double, val high: Double, val low: Double, val close: Double)

/** Minimal intraday trace with soft gradient fill — Sparkline.swift, dark-mode tuning. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min(); val max = values.max()
        val range = max - min
        val stepX = size.width / (values.size - 1)
        val inset = 2f
        fun pt(i: Int): Offset {
            val n = if (range == 0.0) 0.5 else (values[i] - min) / range
            return Offset(i * stepX, inset + (1 - n.toFloat()) * (size.height - inset * 2))
        }
        val line = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until values.size) lineTo(pt(i).x, pt(i).y) }
        val fill = Path().apply {
            addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)),
            startY = 0f, endY = size.height))
        drawPath(line, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Gold line chart for the detail pane (values are pixel-math Doubles). */
@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier, color: Color = DK.gold) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min(); val max = values.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - min) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun CandleChart(candles: List<ChartCandle>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (candles.isEmpty()) return@Canvas
        val min = candles.minOf { it.low }; val max = candles.maxOf { it.high }
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val slot = size.width / candles.size
        val bodyWidth = (slot * 0.6f).coerceAtLeast(1f)
        fun y(v: Double) = size.height - ((v - min) / span * size.height).toFloat()
        candles.forEachIndexed { i, c ->
            val cx = i * slot + slot / 2f
            val color = if (c.close >= c.open) DK.up else DK.down
            drawLine(color, Offset(cx, y(c.high)), Offset(cx, y(c.low)), 1.dp.toPx())
            val top = y(maxOf(c.open, c.close)); val bottom = y(minOf(c.open, c.close))
            drawRect(color, Offset(cx - bodyWidth / 2f, top), Size(bodyWidth, (bottom - top).coerceAtLeast(1f)))
        }
    }
}
```

- [ ] **Step 10: Build + full desktop test run**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: BUILD SUCCESSFUL (composables compile), XML count = **19**.

- [ ] **Step 11: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): DesignKit — gold-on-black palette, Inter, price/percent formatting, components, charts"
```

---

### Task 5: `AppGraph` + `WatchlistViewModel` (polling, add/remove, kind filter)

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/ui/ErrorMessages.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/watchlist/WatchlistViewModel.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/FakeMarketDataRepository.kt` (copy of the Android fake, desktop package)
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/watchlist/WatchlistViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 use cases; Task 3 `FileWatchlistStore`/`resolveConfigDir`; shared `FetchMarketQuotes`, `FetchHistory`, `QuoteError`, `Timeframe.OneDay`.
- Produces (used by Tasks 6–7):
  - `class AppGraph(repository: MarketDataRepository = YahooMarketDataRepository(), store: WatchlistStore = FileWatchlistStore(resolveConfigDir().resolve("watchlist.json")))` exposing `fetchMarketQuotes/fetchSearch/fetchProfile/fetchHistory/fetchCandles/fetchWatchlist/addToWatchlist/removeFromWatchlist` (+ `defaultEntries`)
  - `class WatchlistViewModel(graph deps..., scope: CoroutineScope, tickMillis: Long = 15_000, sparkEveryTicks: Int = 4)` with `val state: StateFlow<WatchlistUiState>`, `fun start()`, `fun refresh()`, `fun onKindSelect(AssetKind)`, `fun onSelect(symbol: String)`, `suspend fun add(entry: WatchlistEntry)` exposed as `fun onAdd(...)`, `fun onRemove(symbol: String)`
  - `data class WatchRow(symbol, name, kind, amountText: String?, changePercent: Double?, spark: List<Double>)`
  - `data class WatchlistUiState(isLoading, kind: AssetKind = AssetKind.Stock, rows: List<WatchRow> /* filtered by kind */, counts: Map<AssetKind, Int>, advancers: Int, decliners: Int, selectedSymbol: String?, error: String?)`

- [ ] **Step 1: `ErrorMessages.kt`** (mirrors the Android `userMessage()` mapper):

```kotlin
package com.aptrade.desktop.ui

import com.aptrade.shared.application.QuoteError

fun QuoteError.userMessage(): String = when (this) {
    is QuoteError.RateLimited -> "Rate limited — try again in a moment."
    is QuoteError.NotFound -> "No data found for this symbol."
    is QuoteError.Network -> "Network problem: $reason"
}
```

- [ ] **Step 2: Copy the fake** — create `desktopApp/src/test/kotlin/com/aptrade/desktop/FakeMarketDataRepository.kt` with the exact content of `androidApp/src/test/kotlin/com/aptrade/android/FakeMarketDataRepository.kt`, changing only the package line to `package com.aptrade.desktop`. (Deliberate duplication: modules must not depend on each other's test sources; a shared test-fixtures module is not justified by 2 consumers.)

- [ ] **Step 3: Write the failing ViewModel tests** (`WatchlistViewModelTest.kt`):

```kotlin
package com.aptrade.desktop.watchlist

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class InMemoryStore : WatchlistStore {
    var stored: List<WatchlistEntry> = emptyList()
    override suspend fun load() = stored
    override suspend fun save(entries: List<WatchlistEntry>) { stored = entries }
}

private val defaults = listOf(
    WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
    WatchlistEntry("SPY", "SPDR S&P 500 ETF Trust", AssetKind.Etf),
    WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto),
)

private fun quote(symbol: String, price: String, change: Double) =
    Quote(symbol, Money.usd(price), Money.usd(price), change)

private fun vm(
    repo: FakeMarketDataRepository,
    store: InMemoryStore,
    scope: kotlinx.coroutines.CoroutineScope,
) = WatchlistViewModel(
    fetchMarketQuotes = FetchMarketQuotes(repo),
    fetchHistory = FetchHistory(repo),
    fetchWatchlist = FetchWatchlist(store, defaults),
    addToWatchlist = AddToWatchlist(store),
    removeFromWatchlist = RemoveFromWatchlist(store),
    scope = scope,
)

class WatchlistViewModelTest {

    @Test
    fun startLoadsSeededWatchlistWithQuotesAndCounts() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()

        val s = vm.state.value
        assertEquals(false, s.isLoading)
        assertEquals(AssetKind.Stock, s.kind)
        assertEquals(listOf("AAPL"), s.rows.map { it.symbol })       // filtered to Stocks
        assertEquals(mapOf(AssetKind.Stock to 1, AssetKind.Etf to 1, AssetKind.Crypto to 1), s.counts)
        assertEquals("100.00", s.rows.single().amountText)
    }

    @Test
    fun kindSelectionSwitchesVisibleRows() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        vm.onKindSelect(AssetKind.Crypto); runCurrent()
        assertEquals(listOf("BTC-USD"), vm.state.value.rows.map { it.symbol })
    }

    @Test
    fun pollTickRefreshesPrices() = runTest {
        val repo = FakeMarketDataRepository()
        var price = "100.00"
        repo.quotesImpl = { symbols -> symbols.map { quote(it, price, 1.0) } }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        price = "101.00"
        advanceTimeBy(15_001); runCurrent()
        assertEquals("101.00", vm.state.value.rows.single().amountText)
    }

    @Test
    fun pollFailureKeepsLastGoodRowsAndSetsError() = runTest {
        val repo = FakeMarketDataRepository()
        var fail = false
        repo.quotesImpl = { symbols ->
            if (fail) throw QuoteError.Network("boom") else symbols.map { quote(it, "100.00", 1.0) }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        fail = true
        advanceTimeBy(15_001); runCurrent()
        val s = vm.state.value
        assertEquals("100.00", s.rows.single().amountText)   // stale-but-present beats empty
        assertNotNull(s.error)
        fail = false
        advanceTimeBy(15_001); runCurrent()
        assertNull(vm.state.value.error)                      // recovers
    }

    @Test
    fun sparklinesFetchOnFirstTickOnly_thenEveryFourth() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        var historyCalls = 0
        repo.historyImpl = { _, _ -> historyCalls++
            listOf(PricePoint(1, Money.usd("99.00")), PricePoint(2, Money.usd("100.00"))) }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(3, historyCalls)                          // tick 0: all 3 symbols
        assertEquals(listOf(99.0, 100.0), vm.state.value.rows.single().spark)
        advanceTimeBy(15_001); runCurrent()                    // tick 1: quotes only
        assertEquals(3, historyCalls)
        advanceTimeBy(3 * 15_000 + 1); runCurrent()            // tick 4: sparks again
        assertEquals(6, historyCalls)
    }

    @Test
    fun sparklineFailureIsNotFatal() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        repo.historyImpl = { _, _ -> throw QuoteError.Network("spark down") }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        val s = vm.state.value
        assertEquals("100.00", s.rows.single().amountText)     // quotes still rendered
        assertTrue(s.rows.single().spark.isEmpty())
        assertNull(s.error)                                    // sparklines are decoration
    }

    @Test
    fun addAppendsPersistsAndFetchesItsQuote() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        val store = InMemoryStore()
        val vm = vm(repo, store, backgroundScope)
        vm.start(); runCurrent()
        vm.onAdd(WatchlistEntry("MSFT", "Microsoft Corporation", AssetKind.Stock)); runCurrent()
        assertEquals(listOf("AAPL", "MSFT"), vm.state.value.rows.map { it.symbol })
        assertTrue(store.stored.any { it.symbol == "MSFT" })
    }

    @Test
    fun removeDropsRowAndPersists() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        val store = InMemoryStore()
        val vm = vm(repo, store, backgroundScope)
        vm.start(); runCurrent()
        vm.onRemove("AAPL"); runCurrent()
        assertEquals(emptyList(), vm.state.value.rows)
        assertTrue(store.stored.none { it.symbol == "AAPL" })
    }

    @Test
    fun advancersAndDeclinersCountAcrossAllKinds() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols ->
            symbols.map { quote(it, "100.00", if (it == "AAPL") -1.0 else 2.0) }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(2, vm.state.value.advancers)
        assertEquals(1, vm.state.value.decliners)
    }

    @Test
    fun selectionIsTracked() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.00", 1.0) } }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        vm.onSelect("AAPL")
        assertEquals("AAPL", vm.state.value.selectedSymbol)
    }
}
```

- [ ] **Step 4: Run to verify failure**

```bash
./gradlew :desktopApp:test --console=plain
```
Expected: compilation FAILS — `WatchlistViewModel` unresolved.

- [ ] **Step 5: Implement `WatchlistViewModel.kt`**

```kotlin
package com.aptrade.desktop.watchlist

import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WatchRow(
    val symbol: String,
    val name: String,
    val kind: AssetKind,
    val amountText: String?,      // exact decimal string; null until first quote lands
    val changePercent: Double?,
    val spark: List<Double> = emptyList(),
)

data class WatchlistUiState(
    val isLoading: Boolean = true,
    val kind: AssetKind = AssetKind.Stock,
    val rows: List<WatchRow> = emptyList(),          // filtered to `kind`, watchlist order
    val counts: Map<AssetKind, Int> = emptyMap(),
    val advancers: Int = 0,
    val decliners: Int = 0,
    val selectedSymbol: String? = null,
    val error: String? = null,
)

/** Owns the watchlist + 15s polling loop (quotes every tick, sparklines every
 *  `sparkEveryTicks`-th — the macOS cadence). Poll failures keep the last good
 *  rows and surface a banner; sparkline failures are silently tolerated. */
class WatchlistViewModel(
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val fetchHistory: FetchHistory,
    private val fetchWatchlist: FetchWatchlist,
    private val addToWatchlist: AddToWatchlist,
    private val removeFromWatchlist: RemoveFromWatchlist,
    private val scope: CoroutineScope,
    private val tickMillis: Long = 15_000,
    private val sparkEveryTicks: Int = 4,
) {
    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state

    private var entries: List<WatchlistEntry> = emptyList()
    private var quotes: Map<String, Pair<String, Double>> = emptyMap()  // symbol -> (amountText, change%)
    private var sparks: Map<String, List<Double>> = emptyMap()
    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            entries = fetchWatchlist.execute()
            var tick = 0
            while (isActive) {
                refreshQuotes()
                if (tick % sparkEveryTicks == 0) refreshSparks()
                publish(loading = false)
                tick++
                delay(tickMillis)
            }
        }
    }

    fun refresh() {
        scope.launch { refreshQuotes(); refreshSparks(); publish(loading = false) }
    }

    fun onKindSelect(kind: AssetKind) {
        _state.update { it.copy(kind = kind) }
        publish(loading = _state.value.isLoading)
    }

    fun onSelect(symbol: String) = _state.update { it.copy(selectedSymbol = symbol) }

    fun onAdd(entry: WatchlistEntry) {
        scope.launch {
            entries = addToWatchlist.execute(entry)
            refreshQuotes()
            publish(loading = false)
        }
    }

    fun onRemove(symbol: String) {
        scope.launch {
            entries = removeFromWatchlist.execute(symbol)
            quotes = quotes - symbol
            sparks = sparks - symbol
            _state.update { if (it.selectedSymbol == symbol) it.copy(selectedSymbol = null) else it }
            publish(loading = false)
        }
    }

    private suspend fun refreshQuotes() {
        if (entries.isEmpty()) { quotes = emptyMap(); return }
        try {
            val fetched = fetchMarketQuotes.execute(entries.map { it.symbol })
            quotes = fetched.associate { it.symbol to (it.price.amountText to it.changePercent) }
            _state.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuoteError) {
            _state.update { it.copy(error = e.userMessage()) }   // keep last-good quotes
        }
    }

    private suspend fun refreshSparks() {
        val updated = sparks.toMutableMap()
        for (entry in entries) {
            try {
                updated[entry.symbol] = fetchHistory.execute(entry.symbol, Timeframe.OneDay)
                    .map { it.close.amount.doubleValue(false) }   // pixel math only
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                // sparklines are decoration — never fail the list for one
            }
        }
        sparks = updated
    }

    private fun publish(loading: Boolean) {
        val current = _state.value
        val rows = entries.filter { it.kind == current.kind }.map { e ->
            val q = quotes[e.symbol]
            WatchRow(e.symbol, e.name, e.kind, q?.first, q?.second, sparks[e.symbol] ?: emptyList())
        }
        val changes = entries.mapNotNull { quotes[it.symbol]?.second }
        _state.update {
            it.copy(
                isLoading = loading,
                rows = rows,
                counts = entries.groupingBy { e -> e.kind }.eachCount(),
                advancers = changes.count { c -> c > 0 },
                decliners = changes.count { c -> c < 0 },
            )
        }
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: PASS, XML count = **29** (19 + 10).

- [ ] **Step 7: Implement `AppGraph.kt`**

```kotlin
package com.aptrade.desktop

import com.aptrade.desktop.infra.FileWatchlistStore
import com.aptrade.desktop.infra.resolveConfigDir
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.infrastructure.YahooMarketDataRepository

/** Composition root. A plain CLASS constructed exactly once in main() — deliberately
 *  NOT an `object` (increment-5 review: don't copy the Android singleton to desktop).
 *  Exactly ONE YahooMarketDataRepository (one Ktor client) exists per process. */
class AppGraph(
    repository: MarketDataRepository = YahooMarketDataRepository(),
    store: WatchlistStore = FileWatchlistStore(resolveConfigDir().resolve("watchlist.json")),
) {
    val fetchMarketQuotes = FetchMarketQuotes(repository)
    val fetchSearch = FetchSearch(repository)
    val fetchProfile = FetchProfile(repository)
    val fetchHistory = FetchHistory(repository)
    val fetchCandles = FetchCandles(repository)

    val defaultEntries = listOf(
        WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
        WatchlistEntry("SPY", "SPDR S&P 500 ETF Trust", AssetKind.Etf),
        WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto),
        WatchlistEntry("ETH-USD", "Ethereum USD", AssetKind.Crypto),
    )
    val fetchWatchlist = FetchWatchlist(store, defaultEntries)
    val addToWatchlist = AddToWatchlist(store)
    val removeFromWatchlist = RemoveFromWatchlist(store)
}
```

- [ ] **Step 8: Full build + commit**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
git add desktopApp/src
git commit -m "feat(desktop): AppGraph class and WatchlistViewModel with 15s polling and persistence"
```

---

### Task 6: `SearchViewModel` (palette) + `DetailViewModel`

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/search/SearchViewModel.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/detail/DetailViewModel.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/search/SearchViewModelTest.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/detail/DetailViewModelTest.kt`

**Interfaces:**
- Consumes: shared `FetchSearch/FetchProfile/FetchHistory/FetchCandles/FetchMarketQuotes`, `Asset`, `Timeframe`; `designkit.ChartCandle`; `ui.userMessage()`.
- Produces (used by Task 7):
  - `class SearchViewModel(fetchSearch, scope, debounceMillis = 300)`: `state: StateFlow<SearchUiState>` (`query, isSearching, results: List<Asset>, selectedIndex: Int, error`), `onQueryChange(String)`, `moveSelection(delta: Int)`, `selectedAsset(): Asset?`, `reset()`
  - `class DetailViewModel(symbol: String, fetchProfile, fetchMarketQuotes, fetchHistory, fetchCandles, scope)`: `state: StateFlow<DetailUiState>` (`symbol, name?, kindLabel?, amountText?, changePercent?, previousCloseText?, profileError?, timeframe, mode: ChartMode, lineValues, candles: List<ChartCandle>, isLoadingChart, chartError?`), `onTimeframeChange(Timeframe)`, `onModeChange(ChartMode)`, `retryChart()`; `enum class ChartMode { Line, Candles }`

- [ ] **Step 1: Failing tests**

`SearchViewModelTest.kt` (adapted from the Android suite, plus selection):

```kotlin
package com.aptrade.desktop.search

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SearchViewModelTest {
    private val apple = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
    private val ampl = Asset("AMPL", "Amplitude Inc.", AssetKind.Stock)

    @Test
    fun debouncesAndSearches() = runTest {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { listOf(apple, ampl) }
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("app")
        advanceTimeBy(299); runCurrent()
        assertEquals(0, repo.searchCallCount)          // still inside the debounce window
        advanceTimeBy(2); runCurrent()
        assertEquals(1, repo.searchCallCount)
        assertEquals(listOf(apple, ampl), vm.state.value.results)
    }

    @Test
    fun retypingRestartsDebounceWithoutExtraCalls() = runTest {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { listOf(apple) }
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("a"); advanceTimeBy(200)
        vm.onQueryChange("ap"); advanceTimeBy(200)
        vm.onQueryChange("app"); advanceTimeBy(301); runCurrent()
        assertEquals(1, repo.searchCallCount)          // only the final query hit the network
    }

    @Test
    fun blankQueryClearsWithoutNetworkCall() = runTest {
        val repo = FakeMarketDataRepository()
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("   "); advanceTimeBy(301); runCurrent()
        assertEquals(0, repo.searchCallCount)
        assertEquals(emptyList(), vm.state.value.results)
    }

    @Test
    fun arrowSelectionClampsAndActivates() = runTest {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { listOf(apple, ampl) }
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("app"); advanceTimeBy(301); runCurrent()
        assertEquals(0, vm.state.value.selectedIndex)
        vm.moveSelection(1)
        assertEquals(1, vm.state.value.selectedIndex)
        vm.moveSelection(1)
        assertEquals(1, vm.state.value.selectedIndex)  // clamped at last result
        assertEquals(ampl, vm.selectedAsset())
        vm.moveSelection(-5)
        assertEquals(0, vm.state.value.selectedIndex)  // clamped at first
    }

    @Test
    fun errorSurfacesAndResetClears() = runTest {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { throw QuoteError.RateLimited }
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("app"); advanceTimeBy(301); runCurrent()
        assertNotNull(vm.state.value.error)
        vm.reset()
        assertEquals(SearchUiState(), vm.state.value)
        assertNull(vm.state.value.error)
    }
}
```

`DetailViewModelTest.kt`:

```kotlin
package com.aptrade.desktop.detail

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private fun vm(repo: FakeMarketDataRepository, scope: kotlinx.coroutines.CoroutineScope) =
    DetailViewModel("AAPL", FetchProfile(repo), FetchMarketQuotes(repo),
        FetchHistory(repo), FetchCandles(repo), scope)

class DetailViewModelTest {

    @Test
    fun loadsProfileQuoteAndLineChart() = runTest {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset("AAPL", "Apple Inc.", AssetKind.Stock) }
        repo.quotesImpl = { listOf(Quote("AAPL", Money.usd("308.63"), Money.usd("294.40"), 4.84)) }
        repo.historyImpl = { _, _ -> listOf(PricePoint(1, Money.usd("294.00")), PricePoint(2, Money.usd("308.63"))) }
        val vm = vm(repo, backgroundScope); runCurrent()

        val s = vm.state.value
        assertEquals("Apple Inc.", s.name)
        assertEquals("308.63", s.amountText)
        assertEquals("294.40", s.previousCloseText)
        assertEquals(4.84, s.changePercent)
        assertEquals(listOf(294.00, 308.63), s.lineValues)
        assertEquals(false, s.isLoadingChart)
    }

    @Test
    fun candlesModeFetchesCandles() = runTest {
        val repo = FakeMarketDataRepository()
        repo.candlesImpl = { _, _ -> listOf(
            Candle(1, Money.usd("1.00"), Money.usd("3.00"), Money.usd("0.50"), Money.usd("2.00"))) }
        val vm = vm(repo, backgroundScope); runCurrent()
        vm.onModeChange(ChartMode.Candles); runCurrent()
        assertEquals(1, vm.state.value.candles.size)
        assertEquals(2.0, vm.state.value.candles.single().close)
    }

    @Test
    fun timeframeChangeRefetchesForThatTimeframe() = runTest {
        val repo = FakeMarketDataRepository()
        var lastTf: Timeframe? = null
        repo.historyImpl = { _, tf -> lastTf = tf; emptyList() }
        val vm = vm(repo, backgroundScope); runCurrent()
        vm.onTimeframeChange(Timeframe.OneMonth); runCurrent()
        assertEquals(Timeframe.OneMonth, lastTf)
        assertEquals(Timeframe.OneMonth, vm.state.value.timeframe)
    }

    @Test
    fun staleResponseNeverOverwritesNewerSelection() = runTest {
        val repo = FakeMarketDataRepository()
        val slowFirst = CompletableDeferred<Unit>()
        repo.historyImpl = { _, tf ->
            if (tf == Timeframe.OneDay) { slowFirst.await() }   // first (1D) request hangs
            listOf(PricePoint(1, Money.usd(if (tf == Timeframe.OneDay) "1.00" else "2.00")))
        }
        val vm = vm(repo, backgroundScope); runCurrent()        // 1D fetch in flight
        vm.onTimeframeChange(Timeframe.OneMonth); runCurrent()  // cancels it, fetches 1M
        slowFirst.complete(Unit); runCurrent()
        assertEquals(listOf(2.0), vm.state.value.lineValues)    // 1M won; stale 1D never rendered
    }

    @Test
    fun chartErrorSurfacesAndRetryRecovers() = runTest {
        val repo = FakeMarketDataRepository()
        var fail = true
        repo.historyImpl = { _, _ ->
            if (fail) throw QuoteError.Network("boom")
            else listOf(PricePoint(1, Money.usd("1.00")))
        }
        val vm = vm(repo, backgroundScope); runCurrent()
        assertNotNull(vm.state.value.chartError)
        fail = false
        vm.retryChart(); runCurrent()
        assertEquals(listOf(1.0), vm.state.value.lineValues)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :desktopApp:test --console=plain
```
Expected: compilation FAILS — the two ViewModels unresolved.

- [ ] **Step 3: Implement**

`SearchViewModel.kt`:
```kotlin
package com.aptrade.desktop.search

import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Asset> = emptyList(),
    val selectedIndex: Int = 0,
    val error: String? = null,
)

/** Ctrl+K palette state: 300ms debounce, blank queries short-circuit locally,
 *  keyboard selection clamped to the result range. */
class SearchViewModel(
    private val fetchSearch: FetchSearch,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 300,
) {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state
    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(isSearching = false, results = emptyList(), selectedIndex = 0, error = null) }
            return
        }
        searchJob = scope.launch {
            delay(debounceMillis)
            _state.update { it.copy(isSearching = true, error = null) }
            try {
                val assets = fetchSearch.execute(trimmed)
                _state.update { it.copy(isSearching = false, results = assets, selectedIndex = 0) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isSearching = false, error = e.userMessage()) }
            }
        }
    }

    fun moveSelection(delta: Int) = _state.update {
        val last = (it.results.size - 1).coerceAtLeast(0)
        it.copy(selectedIndex = (it.selectedIndex + delta).coerceIn(0, last))
    }

    fun selectedAsset(): Asset? = _state.value.results.getOrNull(_state.value.selectedIndex)

    fun reset() {
        searchJob?.cancel()
        _state.value = SearchUiState()
    }
}
```

`DetailViewModel.kt` (Android's, adapted: injected scope, plus the quote row for stat tiles; the snapshot-locals stale-response guard kept):
```kotlin
package com.aptrade.desktop.detail

import com.aptrade.desktop.designkit.ChartCandle
import com.aptrade.desktop.designkit.kindLabel
import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChartMode { Line, Candles }

data class DetailUiState(
    val symbol: String,
    val name: String? = null,
    val kindLabel: String? = null,
    val amountText: String? = null,
    val changePercent: Double? = null,
    val previousCloseText: String? = null,
    val profileError: String? = null,
    val timeframe: Timeframe = Timeframe.OneDay,
    val mode: ChartMode = ChartMode.Line,
    val lineValues: List<Double> = emptyList(),
    val candles: List<ChartCandle> = emptyList(),
    val isLoadingChart: Boolean = true,
    val chartError: String? = null,
)

class DetailViewModel(
    private val symbol: String,
    private val fetchProfile: FetchProfile,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val fetchHistory: FetchHistory,
    private val fetchCandles: FetchCandles,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DetailUiState(symbol = symbol))
    val state: StateFlow<DetailUiState> = _state
    private var chartJob: Job? = null

    init {
        scope.launch {
            try {
                val asset = fetchProfile.execute(symbol)
                _state.update { it.copy(name = asset.name, kindLabel = kindLabel(asset.kind)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(profileError = e.userMessage()) }
            }
        }
        scope.launch {
            try {
                val quote = fetchMarketQuotes.execute(listOf(symbol)).firstOrNull() ?: return@launch
                _state.update {
                    it.copy(amountText = quote.price.amountText,
                        changePercent = quote.changePercent,
                        previousCloseText = quote.previousClose.amountText)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                // stat tiles stay empty; the chart error path covers messaging
            }
        }
        loadChart()
    }

    fun onTimeframeChange(timeframe: Timeframe) {
        _state.update { it.copy(timeframe = timeframe) }
        loadChart()
    }

    fun onModeChange(mode: ChartMode) {
        _state.update { it.copy(mode = mode) }
        loadChart()
    }

    fun retryChart() = loadChart()

    private fun loadChart() {
        // Snapshot before launching so the coroutine renders the selection this call
        // was triggered for, even if state mutates before or while it runs.
        val timeframe = _state.value.timeframe
        val mode = _state.value.mode
        chartJob?.cancel()
        chartJob = scope.launch {
            _state.update { it.copy(isLoadingChart = true, chartError = null) }
            try {
                when (mode) {
                    ChartMode.Line -> {
                        val points = fetchHistory.execute(symbol, timeframe)
                        _state.update { it.copy(isLoadingChart = false,
                            lineValues = points.map { p -> p.close.amount.doubleValue(false) }) }
                    }
                    ChartMode.Candles -> {
                        val bars = fetchCandles.execute(symbol, timeframe)
                        _state.update { it.copy(isLoadingChart = false,
                            candles = bars.map { c -> ChartCandle(
                                c.open.amount.doubleValue(false), c.high.amount.doubleValue(false),
                                c.low.amount.doubleValue(false), c.close.amount.doubleValue(false)) }) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isLoadingChart = false, chartError = e.userMessage()) }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: PASS, XML count = **39** (29 + 10).

- [ ] **Step 5: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): palette SearchViewModel and DetailViewModel with stale-response guard"
```

---

### Task 7: Screens, shell, palette overlay, wiring — the visible app

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/ui/AppShell.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/watchlist/WatchlistPane.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/detail/DetailPane.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/search/PaletteOverlay.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (replace placeholder)

**Interfaces:**
- Consumes: everything from Tasks 4–6 (exact names in those tasks' Produces blocks).
- Produces: the runnable app. No downstream code consumers — Task 8/9 consume the build.

This task is UI composition — no unit tests (ViewModels already carry the behavior). Verification is running the app. Layout fidelity targets, from the macOS sources:

- **Shell** (`RootView.macBody`): full-window vertical gradient `DK.backgroundGradient`; header = centered `BrandWordmark(height = 108.dp)` with a search (palette) icon button top-right; below it a centered tab row `Watchlist / Portfolio / News` (13sp SemiBold, selected gold + gold underline, unselected `textSecondary` — same visual language as `TimeframeBar`); content fills the rest. Window: `rememberWindowState(width = 1280.dp, height = 800.dp)`, `window.minimumSize = Dimension(1000, 680)`.
- **Watchlist tab**: master–detail `Row`: left pane `weight(0.42f)`, right `weight(0.58f)`, 1dp `DK.hairline` divider. Left: `KindToggle` + `LiveBadge` row, `PulseBar` under it, add-field (magnifier icon + borderless TextField on `DK.surface`, rounded 10dp — inline debounced suggestions dropdown listing name/symbol/kind, click or Enter adds via `vm.onAdd`), then rows in a `LazyColumn`.
- **Row** (from `WatchlistRow`): name 15sp SemiBold `textPrimary` over symbol 12sp Medium `textSecondary` (tnum); `Sparkline` 72×32dp colored `DK.changeColor(row.changePercent)`; right column `SuperscriptPrice(amountText, 18.sp)` over `ChangePill`; vertical padding 13dp, horizontal 10dp; hover (via `onPointerEvent(Enter/Exit)`) shows a small "✕" remove button and a `DK.surfaceHi` rounded-10dp row background; click selects → detail pane.
- **Detail pane** (from `AssetDetailView` core): header name (20sp SemiBold) + kind chip (10sp Bold `textSecondary`), `SuperscriptPrice(34.sp)` + `ChangePill`; `TimeframeBar`; Line/Candles toggle (two 12sp labels, selected gold underline, same idiom); chart area 280dp height (`LineChart` gold / `CandleChart`); loading = centered `CircularProgressIndicator(color = DK.gold)`; error = message + gold "Retry" text button calling `retryChart()`; stat grid = 2×2 `StatTile`s: PRICE (`$` + amountText), CHANGE (`formatPercent`, `valueColor = DK.changeColor(...)`), PREV CLOSE, KIND. Empty state (nothing selected): `textTertiary` "Select a symbol" centered.
- **Palette** (from `CommandPaletteView`): full-window `Box` scrim `Color.Black.copy(alpha = 0.45f)` (click closes); panel width 520dp, top padding 80dp, `DK.surface` rounded 14dp, 1dp hairline border; search field 16sp with magnifier, auto-focused (`FocusRequester` + `LaunchedEffect`); results max height 320dp; selected row background `DK.surfaceHi` rounded 8dp; row = name 14sp Medium + symbol 11sp `textSecondary` (tnum), kind label right-aligned 10sp Bold. Keys on the field: `onPreviewKeyEvent` — Up/Down → `moveSelection(∓1)`, Enter → `selectedAsset()` → add to watchlist + select + close, Esc → close. Empty results + non-blank query → "No matches" 13sp `textSecondary`.
- **Ctrl+K**: `onPreviewKeyEvent` on the `Window`: `Ctrl+K` (and `Cmd+K` on Mac — check `isMetaPressed || isCtrlPressed`) opens the palette. `vm.reset()` on close.
- **Lifetimes**: `main()` builds ONE `AppGraph`; `WatchlistViewModel` scope = `CoroutineScope(SupervisorJob() + Dispatchers.Main)` created in `main()`, `vm.start()` once; each `DetailViewModel` is created per selection inside the pane via `remember(selectedSymbol)` with a `rememberCoroutineScope()`-independent scope: `remember(selectedSymbol) { CoroutineScope(SupervisorJob() + Dispatchers.Main) }` + `DisposableEffect(selectedSymbol) { onDispose { scope.cancel() } }` — a stale detail load must die with its symbol.

- [ ] **Step 1: Implement the five files per the fidelity targets above.** Keep every color/size/spacing from the targets; anything unspecified follows the nearest macOS sibling. No business logic in composables — ViewModels only.

- [ ] **Step 2: Build + tests still green**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: PASS, count still **39**.

- [ ] **Step 3: Run and verify live (manual, real Yahoo data)**

```bash
./gradlew :desktopApp:run --console=plain
```
Checklist: seeded watchlist shows 4 entries across kinds with live prices and sparklines; KindToggle filters with counts; add "MSFT" via the field's suggestions; remove it via hover ✕; restart the app — MSFT persistence (add again, quit, relaunch, still there); Ctrl+K palette opens/searches/Enter adds; select a row → detail chart renders 1D line, switch 1M + Candles; Portfolio/News tabs show the placeholder.

- [ ] **Step 4: Screenshot for the ledger**

```bash
mkdir -p Screenshots && screencapture -x Screenshots/desktop-6a-watchlist.png
```
(Take while the app is frontmost; repeat for the detail pane and palette as `desktop-6a-detail.png`, `desktop-6a-palette.png`.)

- [ ] **Step 5: Commit**

```bash
git add desktopApp/src Screenshots/
git commit -m "feat(desktop): app shell, watchlist master-detail, Ctrl+K palette — macOS visual identity"
```

---

### Task 8: CI — Windows build, tests, `.msi` artifact

**Files:**
- Create: `.github/workflows/windows-desktop.yml`

**Interfaces:**
- Consumes: the buildable `:desktopApp` (Tasks 1–7).
- Produces: the increment's Windows ground truth.

- [ ] **Step 1: Write the workflow**

```yaml
name: windows-desktop

on:
  push:
    branches: [main, kmp-windows-6a]
  workflow_dispatch:

jobs:
  build:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4

      - name: Desktop and shared tests on Windows
        run: ./gradlew :desktopApp:test :shared:jvmTest --console=plain

      - name: Package MSI
        run: ./gradlew :desktopApp:packageMsi --console=plain

      - uses: actions/upload-artifact@v4
        with:
          name: APTrade-msi
          path: desktopApp/build/compose/binaries/main/msi/*.msi
          if-no-files-found: error
```

Notes for the implementer: GitHub Windows runners ship the WiX toolset jpackage needs for `.msi`. If `packageMsi` fails on WiX anyway, add a `choco install wixtoolset --no-progress -y` step before packaging (and note it in the task ledger) — do NOT silently switch the target format.

- [ ] **Step 2: Sanity-check the YAML locally**

```bash
ruby -ryaml -e "YAML.load_file('.github/workflows/windows-desktop.yml'); puts 'yaml ok'"
```
Expected: `yaml ok`.

- [ ] **Step 3: Commit and push the branch, then watch the run**

```bash
git add .github/workflows/windows-desktop.yml
git commit -m "ci: Windows runner builds :desktopApp, runs JVM suites, uploads .msi artifact"
git push origin HEAD
sleep 15   # give Actions a moment to register the run
RUN_ID=$(gh run list --workflow=windows-desktop --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status || gh run view "$RUN_ID" --log-failed
```
Expected: the `windows-desktop` run goes green and shows an `APTrade-msi` artifact. This step REQUIRES the branch to be pushed to GitHub — coordinate with the orchestrator if pushes are batched. Iterate here until green; every fix is a normal commit.

---

### Task 9: Docs, regression sweep, final verification

**Files:**
- Modify: `README.md` (platforms section: add Windows/desktop run + packaging instructions; prune the roadmap line per the README-roadmap-closeout convention — done at merge)
- Modify: `.claude/skills/aptrade/SKILL.md` (Build·Run·Test section: add `:desktopApp` commands)

**Interfaces:**
- Consumes: everything.
- Produces: the merge-ready increment.

- [ ] **Step 1: README** — in the same style as the Android section, document: what `:desktopApp` is (Compose Desktop, Windows target, macOS dev proxy), `./gradlew :desktopApp:run`, `./gradlew :desktopApp:test`, where the watchlist JSON lives per-OS, and that Windows `.msi` comes from the `windows-desktop` GitHub Actions workflow.

- [ ] **Step 2: aptrade skill** — add to the Build·Run·Test block:

```bash
./gradlew :desktopApp:run    # Compose Desktop app (Windows target; runs on macOS for dev)
./gradlew :desktopApp:test   # desktop ViewModel/store suites
```

- [ ] **Step 3: Full regression sweep** (all counts from JUnit XMLs / test output, not consoles):

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :desktopApp:test --console=plain --rerun-tasks
export DEVELOPER_DIR=/Applications/Xcode.app
./gradlew :shared:assembleSharedReleaseXCFramework --console=plain
swift test 2>&1 | tail -3
xcodebuild -scheme APTradeLite-Package -destination 'generic/platform=iOS Simulator' ARCHS=arm64 build -quiet && echo IOS-OK
```
Expected: shared **51**, androidApp **13**, desktopApp **39**, `swift test` **193** pass, xcframework builds (3 slices), `IOS-OK`. (Shared commonMain changed in Task 2, so the Apple pipeline must be re-proven.)

- [ ] **Step 4: Side-by-side fidelity check (with the user)**

```bash
swift build && "$(swift build --show-bin-path)/APTradeMac" &
./gradlew :desktopApp:run --console=plain &
```
Both apps side by side on this Mac; the bar is "reads as the same app": wordmark header, gold accents, row anatomy, superscript prices, pills, palette. Capture `Screenshots/desktop-6a-side-by-side.png`. The user gives the final visual verdict.

- [ ] **Step 5: Commit**

```bash
git add README.md .claude/skills/aptrade/SKILL.md Screenshots/
git commit -m "docs: document the Windows Compose Desktop app (increment 6a)"
```

---

## Self-review notes (already applied)

- Spec coverage: stack decision (T1), design kit + Inter + assets (T4), shell/watchlist/detail/palette + layout fidelity (T7), watchlist port + use cases (T2), JSON store + config dir (T3), 15s polling + spark cadence + stale-guard (T5–6), CI msi + Windows tests (T8), docs + suites + side-by-side (T9). `observe` on the store deliberately dropped — recorded in the Design note.
- Type consistency: `WatchlistEntry(symbol, name, kind)` used identically in T2/T3/T5/T7; `ChartCandle` defined once in designkit (T4), consumed by `DetailViewModel` (T6) and `CandleChart` (T7); `amountText` is the only money-to-UI carrier everywhere.
- Deliberate scope cuts (all in spec's out-of-scope): no reorder/drag, no alerts button on rows, no account panel, no light theme, no localization (`tr(...)` strings hard-coded English), no expandable sparkline.
