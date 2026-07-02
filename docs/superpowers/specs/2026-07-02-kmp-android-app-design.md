# Increment 5: androidTarget + Android Compose App

## Context

Increments 1–4b built the shared Kotlin core (Domain + Application + Infrastructure) and put the
real macOS/iOS app on it: all five `MarketDataRepository` methods — `quotes`, `history`, `candles`,
`profile`, `search` — now route through Kotlin, and the Swift-native fallback is retired. Per the
walking-skeleton spec's roadmap, increment 5 adds the `androidTarget` and the first Android app
(Jetpack Compose), and increment 6 (Windows) follows after.

This machine currently has **no Android toolchain** (`ANDROID_HOME` empty, no SDK, no Android
Studio, no emulator) — the walking-skeleton increment deliberately deferred Android for this
reason. This increment therefore includes one-time toolchain setup.

## Scope decisions (locked)

1. **Toolchain: headless CLI tools + emulator.** Android command-line tools via Homebrew, SDK
   packages via `sdkmanager`, and an ARM64 emulator image (native on Apple Silicon) so the app can
   be built, installed, driven, and screenshot-verified entirely from the command line — the same
   live-proof discipline as past increments' macOS harness runs. No Android Studio.
2. **App scope: quotes + search + detail.** Three screens exercising ALL five shared-core methods.
   No local persistence, fixed default symbol list. Watchlist editing, portfolio, news, alerts
   come in later increments.
3. **Structure: plain Android app module (`:androidApp`), Jetpack Compose.** Same Gradle build,
   `:shared` consumed as a direct project dependency (Kotlin-to-Kotlin — no bridging layer).
   NOT Compose Multiplatform: increment 6 makes its own Windows UI-stack decision with real
   knowledge in hand; promoting Compose code to a multiplatform module later is a mechanical
   refactor if Compose Desktop wins.

## Part 1: Toolchain (one-time machine setup)

- `brew install --cask android-commandlinetools`, then `sdkmanager` installs: `platform-tools`,
  `platforms;android-35`, matching `build-tools`, `emulator`, and an ARM64 system image
  (e.g. `system-images;android-35;google_apis;arm64-v8a`). Accept licenses.
- `ANDROID_HOME` exported in build shells; `local.properties` at the repo root gets `sdk.dir`
  (the Gradle-standard mechanism; file is gitignored — verify or add the ignore entry).
- One AVD created for verification (ARM64, API 35).
- Existing JDK 17 (Homebrew path) and Gradle 8.9 wrapper are kept. AGP 8.7.x is added — chosen
  for compatibility with both Gradle 8.9 and Kotlin 2.1.0.
- `settings.gradle.kts`: add `google()` to both `pluginManagement` and
  `dependencyResolutionManagement` repositories; `include(":androidApp")`.
- Root `build.gradle.kts`: register AGP (`com.android.application`, `com.android.library`) and
  the Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose` 2.1.0, matching Kotlin)
  with `apply false`.

## Part 2: `shared` module gains `androidTarget`

- `shared/build.gradle.kts`: apply `com.android.library`; add `androidTarget()` alongside the
  existing jvm/iOS/macOS targets; `android {}` block with namespace `com.aptrade.shared`,
  `compileSdk` 35, `minSdk` 26.
- New source set `androidMain` with a single file: the `actual fun defaultYahooHttpClient()`
  using the **OkHttp** Ktor engine (`ktor-client-okhttp:3.0.3`) — the standard Android engine,
  through the same expect/actual seam that already splits Darwin (Apple) vs CIO (JVM).
- Everything else in the shared core compiles for Android as-is (pure Kotlin + multiplatform
  deps). No API changes, no new domain/application/infrastructure code.
- `jvmTest` remains the primary test surface for common code. Android unit-test execution of
  commonTest is not a gate this increment.
- The xcframework, all Apple targets, and the Swift app are untouched. Regression gate: the
  xcframework still assembles and the Apple suites stay green.

## Part 3: `:androidApp` module

**Module setup:** `com.android.application` + `org.jetbrains.kotlin.android` +
`org.jetbrains.kotlin.plugin.compose`. Namespace `com.aptrade.android`, `compileSdk` 35,
`minSdk` 26, `targetSdk` 35. Dependencies: Compose BOM (Material3, ui, foundation),
`androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.navigation:navigation-compose`,
`kotlinx-coroutines-android`, and `project(":shared")`. Debug builds only — no signing config,
no release hardening this increment. `INTERNET` permission in the manifest.

**Composition root:** a small `AppGraph` object holding ONE `YahooMarketDataRepository` instance
app-wide (the increment-3 lesson: exactly one Ktor client per process) and exposing the five
shared use cases (`FetchMarketQuotes`, `FetchSearch`, `FetchProfile`, `FetchHistory`,
`FetchCandles`). Plain object, no DI framework — mirrors the Swift `CompositionRoot`'s
`static let` pattern. ViewModels take use cases as constructor parameters (factory closures via
`viewModel { }`), keeping them unit-testable against fakes.

**Architecture:** MVVM. One ViewModel per screen exposing a single immutable UI-state data class
via `StateFlow`; Compose screens are declarative renderers of that state. No business logic in
composables, no networking in ViewModels beyond invoking shared use cases — the same layer rules
as the Swift side (CLAUDE.md applies).

**Screens (single-activity, Navigation Compose):**

1. **Quotes** (start destination) — the macOS default symbol set rendered as a list: symbol,
   current price, daily % change (green/red). Data via `FetchMarketQuotes`. Fetch on entry +
   Material3 pull-to-refresh. No background polling this increment. Top-bar action navigates to
   Search; tapping a row opens Detail.
2. **Search** — text field with ~300 ms debounce → `FetchSearch`; results list shows symbol,
   name, kind badge. Empty/whitespace queries short-circuit locally to an empty result (no
   network call), mirroring Swift's `SearchAssetsUseCase`. Tapping a result opens Detail.
3. **Detail** — `FetchProfile` header (symbol, name, kind badge); timeframe selector
   (1D/1W/1M/1Y — the four `Timeframe` cases); chart drawn with a custom Compose `Canvas`:
   **line mode** from `FetchHistory` and a toggle to **candlestick mode** from `FetchCandles`
   (up/down candles colored). No third-party chart library. Data refetches on timeframe or mode
   change; in-flight requests cancelled naturally via `viewModelScope`.

**Theme:** dark gold-on-black Material3 theme matching the APTrade design system (near-black
surfaces, gold primary accent). Dark-only this increment.

**Money display:** `Money` amounts come across as exact decimal (`amountText`/`BigDecimal`) —
format via the shared value, never round-trip prices through `Double` (the project's standing
exact-decimal rule).

**Error handling:** shared-core `QuoteError` (`RateLimited`, `NotFound`, `Network`) maps to a
per-screen error state with a human-readable message and a Retry action. Loading states are
explicit in each UI-state class. `CancellationException` propagates (never swallowed as an
error state).

## Part 4: Testing & verification

- **Unit tests:** each ViewModel tested as a plain JVM unit test (`:androidApp` `testDebugUnitTest`)
  against a fake `MarketDataRepository` — success, error-mapping, and (for Search) debounce/empty
  -query behavior. Coroutines tested with `kotlinx-coroutines-test`.
- **Shared-core regression:** `./gradlew :shared:jvmTest` stays green (44 tests baseline);
  `:shared:compileDebugKotlinAndroid` (or equivalent android compile task) becomes a new gate.
- **Apple regression:** xcframework assembly, `swift test` (206 baseline), and the iOS
  `APTradeLite-Package` scheme build (`ARCHS=arm64`) all stay green.
- **Live end-to-end proof (the increment's Definition of Done):** build the debug APK, boot the
  headless ARM64 emulator, `adb install`, drive all three screens (launch → quotes populate →
  search "apple" → open AAPL detail → switch timeframe → toggle candles), capture
  `adb exec-out screencap` screenshots at each step for the user to inspect, and verify logcat
  shows no crash. Real Yahoo endpoints, real device-class runtime — the Android analogue of the
  macOS harness runs that closed past increments.

## Out of scope (later increments)

Watchlist editing/persistence (DataStore/Room), portfolio & paper trading, news, alerts,
background polling/scheduler, localization, light theme, release signing, Play Store packaging,
Android CI, Compose Multiplatform restructuring, and all Windows work (increment 6).
