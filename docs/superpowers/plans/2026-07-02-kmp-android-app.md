# Increment 5: androidTarget + Android Compose App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `androidTarget` to the shared Kotlin core and ship a three-screen Android Compose walking skeleton (quotes, search, asset detail with charts) that exercises all five shared-core methods, verified live on a headless emulator.

**Architecture:** The `shared` KMP module gains an Android library target whose only platform-specific code is an OkHttp Ktor engine actual. A new `:androidApp` module (plain Jetpack Compose, MVVM) consumes `:shared` as a direct project dependency — Kotlin-to-Kotlin, no bridging. A single `AppGraph` object owns ONE `YahooMarketDataRepository` per process; ViewModels take shared use cases as constructor parameters and are unit-tested against a fake `MarketDataRepository`.

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.2, Jetpack Compose (BOM 2024.12.01, Material3), Navigation Compose, Ktor OkHttp engine, kotlinx-coroutines-test, Android SDK 35 + ARM64 emulator (headless).

**Spec:** `docs/superpowers/specs/2026-07-02-kmp-android-app-design.md`

## Global Constraints

- Every `./gradlew` shell needs `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`; never system Gradle.
- Every Apple-target Gradle task / `swift test` / `xcodebuild` needs `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- Android SDK lives at `ANDROID_HOME="$HOME/Library/Android/sdk"` (created in Task 1); export it in every Android build/emulator shell.
- Versions (exact): AGP **8.7.2** (inside Kotlin 2.1.0's tested compatibility range — 8.7.3+ emits an untested-AGP warning, and warnings are review findings), Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` **2.1.0**, Compose BOM **2024.12.01**, activity-compose **1.9.3**, lifecycle-viewmodel-compose **2.8.7**, navigation-compose **2.8.5**, kotlinx-coroutines-android **1.9.0**, ktor-client-okhttp **3.0.3**.
- Android config: `compileSdk` 35, `minSdk` 26, `targetSdk` 35, JVM target 17. Namespaces: `com.aptrade.shared` (library), `com.aptrade.android` (app).
- Baseline test counts before this plan: `./gradlew :shared:jvmTest` = **44 tests**; `swift test` = **206 tests**. Apple targets, the xcframework, and all Swift sources are untouched by this increment.
- Exactly ONE `YahooMarketDataRepository` (⇒ one Ktor `HttpClient`) per process — the increment-3 lesson. It lives in `AppGraph`; nothing else constructs a repository.
- Exact-decimal rule: money is displayed via `Money.formatted` / `Money.amountText`; `Double` is permitted only for `changePercent` and chart pixel math (presentation-only Y coordinates), matching the shared core's own discipline.
- Default quote symbols (the macOS seed set): `AAPL`, `SPY`, `BTC-USD`, `ETH-USD`.
- `Timeframe` UI labels: OneDay="1D", OneWeek="1W", OneMonth="1M", OneYear="1Y" — those four cases only.
- Errors: catch `QuoteError` → per-screen error state with Retry; ALWAYS re-throw `CancellationException` first; never swallow an error silently.
- The emulator AVD is named `aptrade_api35`. Emulator verification screenshots go to `.superpowers/sdd/android-verify/` (gitignored).

---

### Task 1: Android SDK toolchain (one-time machine setup)

**Files:**
- Create: `local.properties` (repo root — already gitignored via `.gitignore` line `local.properties`; verify, do not commit)
- No tracked-file changes; nothing to commit in this task.

**Interfaces:**
- Produces: a working `sdkmanager`/`adb`/`emulator` toolchain at `$HOME/Library/Android/sdk`, an AVD named `aptrade_api35`, and `local.properties` with `sdk.dir` — consumed by every later task.

- [ ] **Step 1: Install command-line tools via Homebrew**

```bash
brew install --cask android-commandlinetools
```
Expected: cask installs; `sdkmanager` becomes available on PATH (under `/opt/homebrew/bin` or the cask's share dir). Downloads in this task total several GB — use long command timeouts (10 min) and re-run any `sdkmanager` command that times out; it resumes.

- [ ] **Step 2: Install SDK packages into a dedicated SDK root**

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
mkdir -p "$ANDROID_HOME"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "cmdline-tools;latest" "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" "system-images;android-35;google_apis;arm64-v8a"
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
```
Expected: all packages report installed; second `--licenses` run says all licenses accepted. If `brew`'s `sdkmanager` misbehaves with `--sdk_root`, fall back to running the freshly installed `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager` for the remaining packages.

- [ ] **Step 3: Create the AVD**

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
echo "no" | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n aptrade_api35 -k "system-images;android-35;google_apis;arm64-v8a" --device "pixel_7"
```
Expected: AVD created ("no" answers the custom-hardware-profile prompt).

- [ ] **Step 4: Write `local.properties` and verify the toolchain**

```bash
cd "Trading app"
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
git check-ignore local.properties && echo IGNORED
"$HOME/Library/Android/sdk/platform-tools/adb" version
"$HOME/Library/Android/sdk/emulator/emulator" -list-avds
```
Expected: `IGNORED`; adb prints a version; `-list-avds` prints `aptrade_api35`. No tracked files changed (`git status --short` shows nothing new besides pre-existing untracked entries).

---

### Task 2: Gradle plumbing + `androidTarget` on `:shared`

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (root)
- Modify: `gradle.properties`
- Modify: `shared/build.gradle.kts`
- Create: `shared/src/androidMain/kotlin/com/aptrade/shared/infrastructure/YahooHttpClient.android.kt`

**Interfaces:**
- Consumes: existing `expect fun defaultYahooHttpClient(): HttpClient` in `shared/src/commonMain/.../YahooHttpClient.kt` and its `installYahoo()` config helper.
- Produces: `:shared` compiling for Android (`compileDebugKotlinAndroid`), consumable as a project dependency by Task 3's `:androidApp`. AGP + Compose plugins registered for Task 3 to apply.

- [ ] **Step 1: Register `google()` repositories**

Replace `settings.gradle.kts` with:

```kotlin
rootProject.name = "aptrade"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":shared")
```

- [ ] **Step 2: Register AGP + Compose plugins in the root build**

Replace root `build.gradle.kts` with:

```kotlin
plugins {
    kotlin("multiplatform") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    kotlin("android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
}
```

- [ ] **Step 3: Add AndroidX flag to `gradle.properties`**

Append to `gradle.properties`:

```properties
android.useAndroidX=true
```

- [ ] **Step 4: Add `androidTarget` to `shared/build.gradle.kts`**

Replace the file with:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    jvm()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val xcf = XCFramework("Shared")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        macosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("com.ionspin.kotlin:bignum:0.3.10")
            implementation("io.ktor:ktor-client-core:3.0.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation("io.ktor:ktor-client-mock:3.0.3")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.0.3")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.0.3")
        }
        appleMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.0.3")
        }
    }
}

android {
    namespace = "com.aptrade.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

- [ ] **Step 5: Add the Android engine actual**

Create `shared/src/androidMain/kotlin/com/aptrade/shared/infrastructure/YahooHttpClient.android.kt`:

```kotlin
package com.aptrade.shared.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun defaultYahooHttpClient(): HttpClient = HttpClient(OkHttp) { installYahoo() }
```

- [ ] **Step 6: Verify Android compile + no regression**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app"
./gradlew :shared:compileDebugKotlinAndroid --console=plain
./gradlew :shared:jvmTest --console=plain --rerun-tasks
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:compileKotlinMacosArm64 --console=plain
```
Expected: all three BUILD SUCCESSFUL; jvmTest still **44 tests, 0 failures** (verify via `grep -o 'tests="[0-9]*"' shared/build/test-results/jvmTest/*.xml | awk -F'"' '{s+=$2} END {print s}'` → `44`). Output free of AGP/Kotlin compatibility warnings.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties shared/build.gradle.kts \
        shared/src/androidMain/kotlin/com/aptrade/shared/infrastructure/YahooHttpClient.android.kt
git commit -m "feat(kmp): add androidTarget to shared core with OkHttp Ktor engine"
```

---

### Task 3: `:androidApp` module skeleton (theme, AppGraph, nav scaffold)

**Files:**
- Modify: `settings.gradle.kts` (add `include(":androidApp")`)
- Create: `androidApp/build.gradle.kts`
- Create: `androidApp/src/main/AndroidManifest.xml`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/ui/theme/Theme.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/MainActivity.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/ui/Placeholder.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/quotes/QuotesScreen.kt` (placeholder body)
- Create: `androidApp/src/main/kotlin/com/aptrade/android/search/SearchScreen.kt` (placeholder body)
- Create: `androidApp/src/main/kotlin/com/aptrade/android/detail/DetailScreen.kt` (placeholder body)

**Interfaces:**
- Consumes: `:shared`'s `FetchMarketQuotes`, `FetchSearch`, `FetchProfile`, `FetchHistory`, `FetchCandles`, `MarketDataRepository`, `YahooMarketDataRepository` (all `com.aptrade.shared.application` / `.infrastructure`).
- Produces: `AppGraph.fetchMarketQuotes/.fetchSearch/.fetchProfile/.fetchHistory/.fetchCandles`, `AppGraph.defaultSymbols: List<String>`; screen composable signatures `QuotesScreen(onOpenSearch: () -> Unit, onOpenDetail: (String) -> Unit)`, `SearchScreen(onOpenDetail: (String) -> Unit)`, `DetailScreen(symbol: String)`; theme colors `GainGreen`/`LossRed` and `APTradeTheme {}` — Tasks 4–6 replace the placeholder screen bodies but MUST keep these exact signatures.

- [ ] **Step 1: Include the module**

In `settings.gradle.kts`, after `include(":shared")` add:

```kotlin
include(":androidApp")
```

- [ ] **Step 2: Module build file**

Create `androidApp/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.aptrade.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aptrade.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 3: Manifest**

Create `androidApp/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:label="APTrade"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 4: Theme (gold-on-black, dark-only)**

Create `androidApp/src/main/kotlin/com/aptrade/android/ui/theme/Theme.kt`:

```kotlin
package com.aptrade.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Champagne-gold-on-black, approximating the macOS DesignKit brand ramp
// (deep #A9772A … light #F2DDA0); the mid-tone is used as the accent.
private val ChampagneGold = Color(0xFFD9B45B)
private val NearBlack = Color(0xFF0B0B0E)
private val DarkSurface = Color(0xFF16161B)
private val OffWhite = Color(0xFFEDEDF0)

val GainGreen = Color(0xFF34C759)
val LossRed = Color(0xFFFF453A)

private val APTradeColors = darkColorScheme(
    primary = ChampagneGold,
    onPrimary = Color.Black,
    secondary = ChampagneGold,
    onSecondary = Color.Black,
    background = NearBlack,
    onBackground = OffWhite,
    surface = DarkSurface,
    onSurface = OffWhite,
    surfaceVariant = Color(0xFF1E1E24),
    onSurfaceVariant = Color(0xFFB9B9C0),
    error = LossRed,
)

@Composable
fun APTradeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = APTradeColors, content = content)
}
```

- [ ] **Step 5: AppGraph**

Create `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt`:

```kotlin
package com.aptrade.android

import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.infrastructure.YahooMarketDataRepository

/**
 * Process-wide composition root, mirroring the Swift CompositionRoot's `static let`
 * pattern. Exactly ONE YahooMarketDataRepository (and therefore ONE Ktor HttpClient
 * with its own connection pool) exists per process — constructing a repository per
 * ViewModel would leak a new never-closed client each time.
 */
object AppGraph {
    private val repository: MarketDataRepository = YahooMarketDataRepository()

    val fetchMarketQuotes = FetchMarketQuotes(repository)
    val fetchSearch = FetchSearch(repository)
    val fetchProfile = FetchProfile(repository)
    val fetchHistory = FetchHistory(repository)
    val fetchCandles = FetchCandles(repository)

    // The macOS app's seed watchlist.
    val defaultSymbols = listOf("AAPL", "SPY", "BTC-USD", "ETH-USD")
}
```

- [ ] **Step 6: Placeholder + screens + MainActivity**

Create `androidApp/src/main/kotlin/com/aptrade/android/ui/Placeholder.kt`:

```kotlin
package com.aptrade.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// Temporary stand-in used by the nav scaffold until each screen lands
// (quotes: Task 4, search: Task 5, detail: Task 6 — Task 6 deletes this file).
@Composable
fun PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title)
    }
}
```

Create `androidApp/src/main/kotlin/com/aptrade/android/quotes/QuotesScreen.kt`:

```kotlin
package com.aptrade.android.quotes

import androidx.compose.runtime.Composable
import com.aptrade.android.ui.PlaceholderScreen

@Composable
fun QuotesScreen(onOpenSearch: () -> Unit, onOpenDetail: (String) -> Unit) {
    PlaceholderScreen("Quotes")
}
```

Create `androidApp/src/main/kotlin/com/aptrade/android/search/SearchScreen.kt`:

```kotlin
package com.aptrade.android.search

import androidx.compose.runtime.Composable
import com.aptrade.android.ui.PlaceholderScreen

@Composable
fun SearchScreen(onOpenDetail: (String) -> Unit) {
    PlaceholderScreen("Search")
}
```

Create `androidApp/src/main/kotlin/com/aptrade/android/detail/DetailScreen.kt`:

```kotlin
package com.aptrade.android.detail

import androidx.compose.runtime.Composable
import com.aptrade.android.ui.PlaceholderScreen

@Composable
fun DetailScreen(symbol: String) {
    PlaceholderScreen("Detail: $symbol")
}
```

Create `androidApp/src/main/kotlin/com/aptrade/android/MainActivity.kt`:

```kotlin
package com.aptrade.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aptrade.android.detail.DetailScreen
import com.aptrade.android.quotes.QuotesScreen
import com.aptrade.android.search.SearchScreen
import com.aptrade.android.ui.theme.APTradeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            APTradeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }
            }
        }
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "quotes") {
        composable("quotes") {
            QuotesScreen(
                onOpenSearch = { navController.navigate("search") },
                onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
            )
        }
        composable("search") {
            SearchScreen(onOpenDetail = { symbol -> navController.navigate("detail/$symbol") })
        }
        composable("detail/{symbol}") { backStackEntry ->
            DetailScreen(symbol = backStackEntry.arguments?.getString("symbol").orEmpty())
        }
    }
}
```

- [ ] **Step 7: Verify it builds**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app"
./gradlew :androidApp:assembleDebug --console=plain
ls androidApp/build/outputs/apk/debug/androidApp-debug.apk
```
Expected: BUILD SUCCESSFUL; the debug APK exists. No compiler warnings in the output.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts androidApp/
git commit -m "feat(android): scaffold androidApp module with Compose nav, theme, and AppGraph"
```

---

### Task 4: Quotes screen (ViewModel + tests + UI)

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/ui/Mappers.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/ui/ErrorPane.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/quotes/QuotesViewModel.kt`
- Replace: `androidApp/src/main/kotlin/com/aptrade/android/quotes/QuotesScreen.kt`
- Create: `androidApp/src/test/kotlin/com/aptrade/android/FakeMarketDataRepository.kt`
- Test: `androidApp/src/test/kotlin/com/aptrade/android/quotes/QuotesViewModelTest.kt`

**Interfaces:**
- Consumes: `AppGraph.fetchMarketQuotes`, `AppGraph.defaultSymbols` (Task 3); shared `FetchMarketQuotes.execute(symbols): List<Quote>`, `Quote(symbol, price: Money, previousClose: Money, changePercent: Double)`, `Money.formatted`, `QuoteError`.
- Produces: `QuotesViewModel(fetchMarketQuotes, symbols)` with `state: StateFlow<QuotesUiState>` and `refresh()`; `QuoteError.userMessage(): String` and `ErrorPane(message, onRetry, modifier)` reused by Tasks 5–6; `FakeMarketDataRepository` (all five methods overridable via `*Impl` lambdas, plus `searchCallCount`) reused by Tasks 5–6 tests. Screen signature unchanged: `QuotesScreen(onOpenSearch, onOpenDetail)`.

- [ ] **Step 1: Shared UI helpers**

Create `androidApp/src/main/kotlin/com/aptrade/android/ui/Mappers.kt`:

```kotlin
package com.aptrade.android.ui

import com.aptrade.shared.application.QuoteError

fun QuoteError.userMessage(): String = when (this) {
    is QuoteError.RateLimited -> "Rate limited by the data provider — try again shortly."
    is QuoteError.NotFound -> "No data found."
    is QuoteError.Network -> "Network error — check your connection and retry."
}
```

Create `androidApp/src/main/kotlin/com/aptrade/android/ui/ErrorPane.kt`:

```kotlin
package com.aptrade.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ErrorPane(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
```

- [ ] **Step 2: Write the failing ViewModel tests**

Create `androidApp/src/test/kotlin/com/aptrade/android/FakeMarketDataRepository.kt`:

```kotlin
package com.aptrade.android

import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe

class FakeMarketDataRepository : MarketDataRepository {
    var quotesImpl: suspend (List<String>) -> List<Quote> = { emptyList() }
    var historyImpl: suspend (String, Timeframe) -> List<PricePoint> = { _, _ -> emptyList() }
    var candlesImpl: suspend (String, Timeframe) -> List<Candle> = { _, _ -> emptyList() }
    var profileImpl: suspend (String) -> Asset = { Asset(it, it, AssetKind.Stock) }
    var searchImpl: suspend (String) -> List<Asset> = { emptyList() }

    var searchCallCount = 0
        private set

    override suspend fun quotes(symbols: List<String>): List<Quote> = quotesImpl(symbols)
    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> =
        historyImpl(symbol, timeframe)
    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> =
        candlesImpl(symbol, timeframe)
    override suspend fun profile(symbol: String): Asset = profileImpl(symbol)
    override suspend fun search(query: String): List<Asset> {
        searchCallCount++
        return searchImpl(query)
    }
}
```

Create `androidApp/src/test/kotlin/com/aptrade/android/quotes/QuotesViewModelTest.kt`:

```kotlin
package com.aptrade.android.quotes

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuotesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun quote(symbol: String, price: String, change: Double) =
        Quote(symbol, Money.usd(price), Money.usd(price), change)

    @Test
    fun loadsQuotesOnInit() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "229.35", 0.84), quote("SPY", "512.17", -0.21)) }
        val vm = QuotesViewModel(FetchMarketQuotes(repo), listOf("AAPL", "SPY"))

        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.isLoading)
        assertEquals(2, state.rows.size)
        assertEquals("AAPL", state.rows[0].symbol)
        assertEquals("$229.35", state.rows[0].priceText)
        assertEquals(0.84, state.rows[0].changePercent)
        assertNull(state.error)
    }

    @Test
    fun mapsQuoteErrorToErrorState() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { throw QuoteError.RateLimited }
        val vm = QuotesViewModel(FetchMarketQuotes(repo), listOf("AAPL"))

        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.isLoading)
        assertNotNull(state.error)
        assertEquals(0, state.rows.size)
    }

    @Test
    fun refreshReplacesRows() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "229.35", 0.84)) }
        val vm = QuotesViewModel(FetchMarketQuotes(repo), listOf("AAPL"))
        dispatcher.scheduler.advanceUntilIdle()

        repo.quotesImpl = { listOf(quote("AAPL", "231.47", 1.51)) }
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals("$231.47", state.rows[0].priceText)
        assertEquals(false, state.isRefreshing)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :androidApp:testDebugUnitTest --console=plain
```
Expected: FAIL — compile error, `unresolved reference: QuotesViewModel` (the class doesn't exist yet).

- [ ] **Step 4: Implement the ViewModel**

Create `androidApp/src/main/kotlin/com/aptrade/android/quotes/QuotesViewModel.kt`:

```kotlin
package com.aptrade.android.quotes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.QuoteError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuoteRow(
    val symbol: String,
    val priceText: String,
    val changePercent: Double,
)

data class QuotesUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val rows: List<QuoteRow> = emptyList(),
    val error: String? = null,
)

class QuotesViewModel(
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val symbols: List<String>,
) : ViewModel() {

    private val _state = MutableStateFlow(QuotesUiState(isLoading = true))
    val state: StateFlow<QuotesUiState> = _state

    init {
        load(initial = true)
    }

    fun refresh() = load(initial = false)

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = initial, isRefreshing = !initial, error = null) }
            try {
                val quotes = fetchMarketQuotes.execute(symbols)
                _state.update { state ->
                    state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        rows = quotes.map { QuoteRow(it.symbol, it.price.formatted, it.changePercent) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isLoading = false, isRefreshing = false, error = e.userMessage()) }
            }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :androidApp:testDebugUnitTest --console=plain
```
Expected: PASS — 3 tests, 0 failures (verify via the XML: `grep -o 'tests="[0-9]*"' androidApp/build/test-results/testDebugUnitTest/*.xml | awk -F'"' '{s+=$2} END {print s}'` → `3`).

- [ ] **Step 6: Real Quotes UI**

Replace `androidApp/src/main/kotlin/com/aptrade/android/quotes/QuotesScreen.kt` with:

```kotlin
package com.aptrade.android.quotes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.ui.ErrorPane
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import java.util.Locale

@Composable
fun QuotesScreen(onOpenSearch: () -> Unit, onOpenDetail: (String) -> Unit) {
    val viewModel: QuotesViewModel = viewModel {
        QuotesViewModel(AppGraph.fetchMarketQuotes, AppGraph.defaultSymbols)
    }
    val state by viewModel.state.collectAsState()
    QuotesContent(state, viewModel::refresh, onOpenSearch, onOpenDetail)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuotesContent(
    state: QuotesUiState,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APTrade") },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null ->
                    ErrorPane(state.error, onRetry = onRefresh, Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.symbol }) { row ->
                        QuoteRowItem(row, onClick = { onOpenDetail(row.symbol) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteRowItem(row: QuoteRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.symbol, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(row.priceText, style = MaterialTheme.typography.bodyLarge)
            val up = row.changePercent >= 0
            Text(
                text = String.format(Locale.US, "%+.2f%%", row.changePercent),
                color = if (up) GainGreen else LossRed,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
```

- [ ] **Step 7: Verify build + tests together**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app"
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest --console=plain
```
Expected: BUILD SUCCESSFUL, 3 tests passing, no warnings.

- [ ] **Step 8: Commit**

```bash
git add androidApp/
git commit -m "feat(android): quotes screen with live prices via shared FetchMarketQuotes"
```

---

### Task 5: Search screen (ViewModel + tests + UI)

**Files:**
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/ui/Mappers.kt` (add `AssetKind.label()`)
- Create: `androidApp/src/main/kotlin/com/aptrade/android/search/SearchViewModel.kt`
- Replace: `androidApp/src/main/kotlin/com/aptrade/android/search/SearchScreen.kt`
- Test: `androidApp/src/test/kotlin/com/aptrade/android/search/SearchViewModelTest.kt`

**Interfaces:**
- Consumes: `AppGraph.fetchSearch`; shared `FetchSearch.execute(query): List<Asset>`, `Asset(symbol, name, kind)`, `AssetKind`; `FakeMarketDataRepository` (`searchImpl`, `searchCallCount`), `ErrorPane`, `QuoteError.userMessage()` from Task 4.
- Produces: `SearchViewModel(fetchSearch, debounceMillis = 300)` with `state: StateFlow<SearchUiState>`, `onQueryChange(String)`, `retry()`; `AssetRow(symbol, name, kindLabel)` (also reused by Task 6's kind badge convention); `AssetKind.label(): String`. Screen signature unchanged: `SearchScreen(onOpenDetail)`.

- [ ] **Step 1: Add the kind label mapper**

Append to `androidApp/src/main/kotlin/com/aptrade/android/ui/Mappers.kt`:

```kotlin
fun com.aptrade.shared.domain.AssetKind.label(): String = when (this) {
    com.aptrade.shared.domain.AssetKind.Stock -> "Stock"
    com.aptrade.shared.domain.AssetKind.Etf -> "ETF"
    com.aptrade.shared.domain.AssetKind.Crypto -> "Crypto"
}
```

(Convert to top-of-file imports — `import com.aptrade.shared.domain.AssetKind` — and unqualified names; shown qualified here only for unambiguous placement.)

- [ ] **Step 2: Write the failing tests**

Create `androidApp/src/test/kotlin/com/aptrade/android/search/SearchViewModelTest.kt`:

```kotlin
package com.aptrade.android.search

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun emptyOrBlankQueryNeverHitsRepository() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        val vm = SearchViewModel(FetchSearch(repo))

        vm.onQueryChange("")
        vm.onQueryChange("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, repo.searchCallCount)
        assertEquals(0, vm.state.value.results.size)
    }

    @Test
    fun rapidTypingIsDebouncedToASingleSearch() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { q -> listOf(Asset("AAPL", "Apple Inc. ($q)", AssetKind.Stock)) }
        val vm = SearchViewModel(FetchSearch(repo))

        vm.onQueryChange("a")
        dispatcher.scheduler.advanceTimeBy(100)
        vm.onQueryChange("ap")
        dispatcher.scheduler.advanceTimeBy(100)
        vm.onQueryChange("apple")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repo.searchCallCount)
        assertEquals("Apple Inc. (apple)", vm.state.value.results[0].name)
    }

    @Test
    fun mapsResultsWithKindLabels() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = {
            listOf(
                Asset("AAPL", "Apple Inc.", AssetKind.Stock),
                Asset("SPY", "SPDR S&P 500", AssetKind.Etf),
                Asset("BTC-USD", "Bitcoin", AssetKind.Crypto),
            )
        }
        val vm = SearchViewModel(FetchSearch(repo))

        vm.onQueryChange("a")
        dispatcher.scheduler.advanceUntilIdle()

        val rows = vm.state.value.results
        assertEquals(listOf("Stock", "ETF", "Crypto"), rows.map { it.kindLabel })
        assertEquals("AAPL", rows[0].symbol)
    }

    @Test
    fun mapsQuoteErrorToErrorState() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { throw QuoteError.Network("boom") }
        val vm = SearchViewModel(FetchSearch(repo))

        vm.onQueryChange("apple")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.state.value.error)
        assertEquals(false, vm.state.value.isSearching)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :androidApp:testDebugUnitTest --console=plain
```
Expected: FAIL — compile error, `unresolved reference: SearchViewModel`.

- [ ] **Step 4: Implement the ViewModel**

Create `androidApp/src/main/kotlin/com/aptrade/android/search/SearchViewModel.kt`:

```kotlin
package com.aptrade.android.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.label
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.QuoteError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AssetRow(
    val symbol: String,
    val name: String,
    val kindLabel: String,
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<AssetRow> = emptyList(),
    val error: String? = null,
)

class SearchViewModel(
    private val fetchSearch: FetchSearch,
    private val debounceMillis: Long = 300,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state
    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            // Mirrors Swift's SearchAssetsUseCase: blank queries short-circuit locally.
            _state.update { it.copy(isSearching = false, results = emptyList(), error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(debounceMillis)
            _state.update { it.copy(isSearching = true, error = null) }
            try {
                val assets = fetchSearch.execute(trimmed)
                _state.update { state ->
                    state.copy(
                        isSearching = false,
                        results = assets.map { AssetRow(it.symbol, it.name, it.kind.label()) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isSearching = false, error = e.userMessage()) }
            }
        }
    }

    fun retry() = onQueryChange(_state.value.query)
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :androidApp:testDebugUnitTest --console=plain
```
Expected: PASS — 7 tests total (3 quotes + 4 search), 0 failures.

- [ ] **Step 6: Real Search UI**

Replace `androidApp/src/main/kotlin/com/aptrade/android/search/SearchScreen.kt` with:

```kotlin
package com.aptrade.android.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.ui.ErrorPane

@Composable
fun SearchScreen(onOpenDetail: (String) -> Unit) {
    val viewModel: SearchViewModel = viewModel { SearchViewModel(AppGraph.fetchSearch) }
    val state by viewModel.state.collectAsState()
    val focusRequester = remember { FocusRequester() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .focusRequester(focusRequester),
            placeholder = { Text("Search stocks, ETFs, crypto") },
            singleLine = true,
        )
        Box(Modifier.fillMaxSize()) {
            when {
                state.isSearching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null ->
                    ErrorPane(state.error!!, onRetry = viewModel::retry, Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.results, key = { it.symbol }) { row ->
                        ResultRow(row, onClick = { onOpenDetail(row.symbol) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun ResultRow(row: AssetRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.symbol, style = MaterialTheme.typography.titleMedium)
            Text(
                row.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AssistChip(onClick = onClick, label = { Text(row.kindLabel) })
    }
}
```

- [ ] **Step 7: Verify build + tests together**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app"
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest --console=plain
```
Expected: BUILD SUCCESSFUL, 7 tests passing, no warnings.

- [ ] **Step 8: Commit**

```bash
git add androidApp/
git commit -m "feat(android): debounced asset search via shared FetchSearch"
```

---

### Task 6: Detail screen (ViewModel + tests + Canvas charts + UI)

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/detail/DetailViewModel.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/ui/chart/Charts.kt`
- Replace: `androidApp/src/main/kotlin/com/aptrade/android/detail/DetailScreen.kt`
- Delete: `androidApp/src/main/kotlin/com/aptrade/android/ui/Placeholder.kt` (last placeholder gone)
- Test: `androidApp/src/test/kotlin/com/aptrade/android/detail/DetailViewModelTest.kt`

**Interfaces:**
- Consumes: `AppGraph.fetchProfile/.fetchHistory/.fetchCandles`; shared `FetchProfile.execute(symbol): Asset`, `FetchHistory.execute(symbol, timeframe): List<PricePoint>`, `FetchCandles.execute(symbol, timeframe): List<Candle>`, `Timeframe` (OneDay/OneWeek/OneMonth/OneYear), `PricePoint.close: Money`, `Candle.open/high/low/close: Money`; `Money.amount.doubleValue(false)` (bignum BigDecimal → Double, presentation-only pixel math — same call the shared mapper uses for `changePercent`); `AssetKind.label()`, `ErrorPane`, `userMessage()`, `FakeMarketDataRepository` from Tasks 4–5.
- Produces: `DetailViewModel(symbol, fetchProfile, fetchHistory, fetchCandles)` with `state: StateFlow<DetailUiState>`, `onTimeframeChange(Timeframe)`, `onModeChange(ChartMode)`, `retryChart()`; `ChartMode { Line, Candles }`; `CandleBar(open, high, low, close: Double)`; `LineChart(values, modifier)` / `CandleChart(candles, modifier)` composables. Screen signature unchanged: `DetailScreen(symbol)`.

- [ ] **Step 1: Write the failing tests**

Create `androidApp/src/test/kotlin/com/aptrade/android/detail/DetailViewModelTest.kt`:

```kotlin
package com.aptrade.android.detail

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(repo: FakeMarketDataRepository, symbol: String = "AAPL") = DetailViewModel(
        symbol = symbol,
        fetchProfile = FetchProfile(repo),
        fetchHistory = FetchHistory(repo),
        fetchCandles = FetchCandles(repo),
    )

    @Test
    fun loadsProfileHeaderOnInit() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { Asset(it, "Apple Inc.", AssetKind.Stock) }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Apple Inc.", viewModel.state.value.name)
        assertEquals("Stock", viewModel.state.value.kindLabel)
    }

    @Test
    fun profileErrorIsSurfacedNotSwallowed() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.profileImpl = { throw QuoteError.NotFound }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.state.value.profileError)
    }

    @Test
    fun loadsLineValuesOnInit() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.historyImpl = { _, _ ->
            listOf(
                PricePoint(1_700_000_000L, Money.usd("100.50")),
                PricePoint(1_700_003_600L, Money.usd("101.25")),
            )
        }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(false, state.isLoadingChart)
        assertEquals(listOf(100.50, 101.25), state.lineValues)
    }

    @Test
    fun switchingToCandlesFetchesCandles() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.candlesImpl = { _, _ ->
            listOf(
                Candle(
                    epochSeconds = 1_700_000_000L,
                    open = Money.usd("100.00"), high = Money.usd("102.00"),
                    low = Money.usd("99.50"), close = Money.usd("101.50"), volume = 1000.0,
                ),
            )
        }
        val viewModel = vm(repo)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onModeChange(ChartMode.Candles)
        dispatcher.scheduler.advanceUntilIdle()

        val candles = viewModel.state.value.candles
        assertEquals(1, candles.size)
        assertEquals(CandleBar(100.00, 102.00, 99.50, 101.50), candles[0])
    }

    @Test
    fun timeframeChangeRefetches() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        var requestedTimeframe: Timeframe? = null
        repo.historyImpl = { _, timeframe ->
            requestedTimeframe = timeframe
            listOf(PricePoint(1_700_000_000L, Money.usd("100.50")))
        }
        val viewModel = vm(repo)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onTimeframeChange(Timeframe.OneMonth)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Timeframe.OneMonth, requestedTimeframe)
        assertEquals(Timeframe.OneMonth, viewModel.state.value.timeframe)
    }

    @Test
    fun chartErrorIsMapped() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.historyImpl = { _, _ -> throw QuoteError.RateLimited }
        val viewModel = vm(repo)

        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.state.value.chartError)
        assertEquals(false, viewModel.state.value.isLoadingChart)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :androidApp:testDebugUnitTest --console=plain
```
Expected: FAIL — compile error, `unresolved reference: DetailViewModel`.

- [ ] **Step 3: Implement the ViewModel**

Create `androidApp/src/main/kotlin/com/aptrade/android/detail/DetailViewModel.kt`:

```kotlin
package com.aptrade.android.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.label
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChartMode { Line, Candles }

data class CandleBar(val open: Double, val high: Double, val low: Double, val close: Double)

data class DetailUiState(
    val symbol: String,
    val name: String? = null,
    val kindLabel: String? = null,
    val profileError: String? = null,
    val timeframe: Timeframe = Timeframe.OneDay,
    val mode: ChartMode = ChartMode.Line,
    val lineValues: List<Double> = emptyList(),
    val candles: List<CandleBar> = emptyList(),
    val isLoadingChart: Boolean = true,
    val chartError: String? = null,
)

class DetailViewModel(
    private val symbol: String,
    private val fetchProfile: FetchProfile,
    private val fetchHistory: FetchHistory,
    private val fetchCandles: FetchCandles,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState(symbol = symbol))
    val state: StateFlow<DetailUiState> = _state
    private var chartJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val asset = fetchProfile.execute(symbol)
                _state.update { it.copy(name = asset.name, kindLabel = asset.kind.label()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(profileError = e.userMessage()) }
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
        chartJob?.cancel()
        chartJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingChart = true, chartError = null) }
            try {
                when (_state.value.mode) {
                    ChartMode.Line -> {
                        val points = fetchHistory.execute(symbol, _state.value.timeframe)
                        _state.update { state ->
                            state.copy(
                                isLoadingChart = false,
                                // Pixel math only — money display always goes through
                                // Money.formatted/amountText, never this Double.
                                lineValues = points.map { it.close.amount.doubleValue(false) },
                            )
                        }
                    }
                    ChartMode.Candles -> {
                        val candles = fetchCandles.execute(symbol, _state.value.timeframe)
                        _state.update { state ->
                            state.copy(
                                isLoadingChart = false,
                                candles = candles.map {
                                    CandleBar(
                                        it.open.amount.doubleValue(false),
                                        it.high.amount.doubleValue(false),
                                        it.low.amount.doubleValue(false),
                                        it.close.amount.doubleValue(false),
                                    )
                                },
                            )
                        }
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

- [ ] **Step 4: Run tests to verify they pass**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app" && ./gradlew :androidApp:testDebugUnitTest --console=plain
```
Expected: PASS — 13 tests total (3 quotes + 4 search + 6 detail), 0 failures.

- [ ] **Step 5: Canvas charts**

Create `androidApp/src/main/kotlin/com/aptrade/android/ui/chart/Charts.kt`:

```kotlin
package com.aptrade.android.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aptrade.android.detail.CandleBar
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed

@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, value ->
            val x = i * stepX
            val y = size.height - ((value - min) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun CandleChart(
    candles: List<CandleBar>,
    modifier: Modifier = Modifier,
    upColor: Color = GainGreen,
    downColor: Color = LossRed,
) {
    Canvas(modifier = modifier) {
        if (candles.isEmpty()) return@Canvas
        val min = candles.minOf { it.low }
        val max = candles.maxOf { it.high }
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val slot = size.width / candles.size
        val bodyWidth = (slot * 0.6f).coerceAtLeast(1f)

        fun y(value: Double): Float = size.height - ((value - min) / span * size.height).toFloat()

        candles.forEachIndexed { i, candle ->
            val centerX = i * slot + slot / 2f
            val color = if (candle.close >= candle.open) upColor else downColor
            drawLine(
                color = color,
                start = Offset(centerX, y(candle.high)),
                end = Offset(centerX, y(candle.low)),
                strokeWidth = 1.dp.toPx(),
            )
            val top = y(maxOf(candle.open, candle.close))
            val bottom = y(minOf(candle.open, candle.close))
            drawRect(
                color = color,
                topLeft = Offset(centerX - bodyWidth / 2f, top),
                size = Size(bodyWidth, (bottom - top).coerceAtLeast(1f)),
            )
        }
    }
}
```

- [ ] **Step 6: Real Detail UI + delete placeholder**

Replace `androidApp/src/main/kotlin/com/aptrade/android/detail/DetailScreen.kt` with:

```kotlin
package com.aptrade.android.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.ui.ErrorPane
import com.aptrade.android.ui.chart.CandleChart
import com.aptrade.android.ui.chart.LineChart
import com.aptrade.shared.domain.Timeframe

private val timeframeLabels = listOf(
    Timeframe.OneDay to "1D",
    Timeframe.OneWeek to "1W",
    Timeframe.OneMonth to "1M",
    Timeframe.OneYear to "1Y",
)

@Composable
fun DetailScreen(symbol: String) {
    val viewModel: DetailViewModel = viewModel(key = symbol) {
        DetailViewModel(symbol, AppGraph.fetchProfile, AppGraph.fetchHistory, AppGraph.fetchCandles)
    }
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.symbol, style = MaterialTheme.typography.headlineMedium)
                state.name?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.kindLabel?.let { AssistChip(onClick = {}, label = { Text(it) }) }
        }
        state.profileError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))

        Row {
            timeframeLabels.forEach { (timeframe, label) ->
                FilterChip(
                    selected = state.timeframe == timeframe,
                    onClick = { viewModel.onTimeframeChange(timeframe) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            FilterChip(
                selected = state.mode == ChartMode.Line,
                onClick = { viewModel.onModeChange(ChartMode.Line) },
                label = { Text("Line") },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = state.mode == ChartMode.Candles,
                onClick = { viewModel.onModeChange(ChartMode.Candles) },
                label = { Text("Candles") },
            )
        }
        Spacer(Modifier.height(16.dp))

        Box(Modifier.fillMaxWidth().height(240.dp)) {
            when {
                state.isLoadingChart -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.chartError != null ->
                    ErrorPane(state.chartError!!, onRetry = viewModel::retryChart, Modifier.align(Alignment.Center))
                state.mode == ChartMode.Line ->
                    LineChart(state.lineValues, Modifier.fillMaxSize())
                else ->
                    CandleChart(state.candles, Modifier.fillMaxSize())
            }
        }
    }
}
```

Delete `androidApp/src/main/kotlin/com/aptrade/android/ui/Placeholder.kt`:

```bash
git rm androidApp/src/main/kotlin/com/aptrade/android/ui/Placeholder.kt
```

- [ ] **Step 7: Verify build + tests together**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app"
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest --console=plain
```
Expected: BUILD SUCCESSFUL, 13 tests passing, no warnings, no lingering `PlaceholderScreen` references (`grep -rn "PlaceholderScreen" androidApp/src` → no output).

- [ ] **Step 8: Commit**

```bash
git add -A androidApp/
git commit -m "feat(android): asset detail with profile header and Canvas line/candle charts"
```

---

### Task 7: Live emulator end-to-end proof + full regression + README

**Files:**
- Modify: `README.md` (add "Android app" section)
- Create (scratch, gitignored): `.superpowers/sdd/android-verify/` screenshots + `tap-by-text.sh`

**Interfaces:**
- Consumes: Tasks 1–6 complete (toolchain, APK, screens).

- [ ] **Step 1: Build the APK and boot the emulator**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
cd "Trading app"
./gradlew :androidApp:assembleDebug --console=plain
"$ANDROID_HOME/emulator/emulator" -avd aptrade_api35 -no-window -no-audio -no-boot-anim -no-snapshot &
"$ANDROID_HOME/platform-tools/adb" wait-for-device
until [ "$("$ANDROID_HOME/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo BOOTED
```
Expected: `BOOTED` (first boot can take 2–4 minutes; run the emulator in the background and poll).

- [ ] **Step 2: Install, launch, and screenshot the quotes screen**

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"; PATH="$ANDROID_HOME/platform-tools:$PATH"
cd "Trading app"
mkdir -p .superpowers/sdd/android-verify
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb logcat -c
adb shell am start -n com.aptrade.android/.MainActivity
sleep 10
adb exec-out screencap -p > .superpowers/sdd/android-verify/01-quotes.png
```
Expected: install succeeds; screenshot shows the APTrade top bar and four populated rows (AAPL, SPY, BTC-USD, ETH-USD) with prices and colored % changes — live Yahoo data.

- [ ] **Step 3: Write the tap helper**

Create `.superpowers/sdd/android-verify/tap-by-text.sh`:

```bash
#!/bin/bash
# Taps the center of the first UI node whose text or content-desc equals $1.
set -euo pipefail
TARGET="$1"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb pull /sdcard/ui.xml /tmp/aptrade-ui.xml >/dev/null
COORDS=$(python3 - "$TARGET" <<'EOF'
import re, sys
target = re.escape(sys.argv[1])
xml = open('/tmp/aptrade-ui.xml').read()
m = re.search(r'(?:text|content-desc)="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % target, xml)
if not m:
    sys.exit("NODE NOT FOUND: " + sys.argv[1])
print((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
EOF
)
adb shell input tap $COORDS
```

`chmod +x .superpowers/sdd/android-verify/tap-by-text.sh`

- [ ] **Step 4: Drive search → detail → timeframe → candles, screenshotting each step**

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"; PATH="$ANDROID_HOME/platform-tools:$PATH"
cd "Trading app"
V=.superpowers/sdd/android-verify
"$V/tap-by-text.sh" "Search"          # content-desc on the top-bar icon
sleep 2
adb shell input text "apple"          # field is autofocused
sleep 4                                # debounce + network
adb exec-out screencap -p > "$V/02-search.png"
"$V/tap-by-text.sh" "AAPL"
sleep 6                                # profile + history fetch
adb exec-out screencap -p > "$V/03-detail-line.png"
"$V/tap-by-text.sh" "1M"
sleep 4
"$V/tap-by-text.sh" "Candles"
sleep 4
adb exec-out screencap -p > "$V/04-detail-candles-1m.png"
```
Expected: `02-search.png` shows results including AAPL/Apple Inc. with kind chips; `03-detail-line.png` shows the AAPL header, chips, and a gold line chart; `04-detail-candles-1m.png` shows green/red candles with 1M and Candles chips selected.

- [ ] **Step 5: Crash check and emulator shutdown**

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"; PATH="$ANDROID_HOME/platform-tools:$PATH"
adb logcat -d | grep -E "FATAL|AndroidRuntime.*(Exception|Error)" | grep -i aptrade || echo "NO CRASHES"
adb emu kill
```
Expected: `NO CRASHES`.

- [ ] **Step 6: Full regression sweep**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
cd "Trading app"
./gradlew :shared:jvmTest --console=plain --rerun-tasks          # expect 44 tests, 0 failures
./gradlew :androidApp:testDebugUnitTest --console=plain          # expect 13 tests, 0 failures
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:assembleSharedReleaseXCFramework --console=plain   # expect BUILD SUCCESSFUL, 3 slices
swift test 2>&1 | grep -iE "executed [0-9]+ test" | tail -2      # expect 206 tests, 0 failures
xcodebuild -scheme APTradeLite-Package -destination 'generic/platform=iOS Simulator' ARCHS=arm64 build -quiet
```
Expected: all green with the exact counts above. For the `xcodebuild` step, apply the known workaround if the scheme is missing: temporarily move the gitignored `APTrade.xcodeproj` aside (it shadows the package), build, then restore it.

- [ ] **Step 7: README section**

Add to `README.md`, after the "Building the shared Kotlin core" section, adjusting the heading level to match its neighbors:

```markdown
## Android app (walking skeleton)

A three-screen Jetpack Compose app (`androidApp/`) runs on the same shared Kotlin core as the
macOS/iOS app: live quotes for the default watchlist, debounced asset search, and an asset
detail view with line/candlestick charts across 1D/1W/1M/1Y timeframes.

Requirements: Android SDK (API 35) with `sdk.dir` in `local.properties`, JDK 17.

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :androidApp:assembleDebug          # build the debug APK
./gradlew :androidApp:testDebugUnitTest      # ViewModel unit tests
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```
```

Also add `androidApp/                 Android app (Jetpack Compose walking skeleton)` to the README's project-structure listing, matching its alignment style.

- [ ] **Step 8: Commit**

```bash
git add README.md
git commit -m "docs: document the Android walking-skeleton app"
```
