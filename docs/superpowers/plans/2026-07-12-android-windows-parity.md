# Android → Windows Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Android app to full Windows-desktop feature parity — persisted watchlist, price alerts with system notifications, news with bookmarks, and complete settings (dark/light theme, accent, 4-language switcher) — by promoting the desktop's tested stores + L10n catalog into shared KMP and building Android Compose screens over them.

**Architecture:** Approach A from the spec (`docs/superpowers/specs/2026-07-12-android-windows-parity-design.md`): desktop JVM file-stores move to `shared/jvmCommonMain` (the proven `FilePortfolioStore` pattern), the L10n catalog + `AppLanguage` + a colors-stripped `AccentTheme` move to `shared/commonMain`, and `desktopApp` keeps thin re-exports so its behavior is byte-identical (its test suite is the regression guard). Android then wires the same stores into `AppGraph` and adds a Material 3 shell + feature screens whose anatomy mirrors the desktop panes.

**Tech Stack:** Kotlin Multiplatform (Gradle 8.9 wrapper, JDK 17), Jetpack Compose (BOM 2024.12.01, Material 3), kotlinx-serialization, NavHost, NotificationManager, androidx.browser (Custom Tabs).

## Global Constraints

- **Desktop behavior byte-identical.** Every promotion keeps JSON file formats and string values verbatim; `desktopApp` call sites keep compiling via re-exports/typealiases (import-line adjustments allowed); the full desktop test suite must be green after every task.
- **Shared `commonMain` stays Compose-free and JVM-free** (it feeds the Apple xcframework). JVM-only code goes to `shared/jvmCommonMain`.
- **Toolchain (every task):** `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"` first; always `./gradlew` (never system Gradle 9). Test commands: `./gradlew :desktopApp:test`, `./gradlew :androidApp:testDebugUnitTest`, `./gradlew :shared:jvmTest`.
- **Do NOT touch** `Sources/` (Swift), `Package.swift`, `project.yml`, or anything Apple. Do not run Apple-target Gradle tasks (`assemble*XCFramework`) — not needed here.
- **Android:** minSdk 26, compileSdk 35, package root `com.aptrade.android`. Never guess bridged/shared names — check the shared source first.
- **Money/decimal:** exact-decimal strings via shared `Money`; `changePercent` is `Double`. Never parse prices into `Double` for display.
- Commit after every task with conventional-commit messages.

## File Structure (end state)

```
shared/src/commonMain/kotlin/com/aptrade/shared/l10n/L10n.kt            (moved catalog, pkg com.aptrade.shared.l10n)
shared/src/commonMain/kotlin/com/aptrade/shared/l10n/AppLanguage.kt     (moved)
shared/src/commonMain/kotlin/com/aptrade/shared/settings/AccentTheme.kt (identity only: displayName+tagline)
shared/src/commonMain/kotlin/com/aptrade/shared/settings/AppSettings.kt (moved from desktop FileSettingsStore.kt)
shared/src/jvmCommonMain/.../infrastructure/FileWatchlistStore.kt        (moved)
shared/src/jvmCommonMain/.../infrastructure/FileAlertStore.kt            (moved)
shared/src/jvmCommonMain/.../infrastructure/FileSettingsStore.kt         (moved)
shared/src/jvmCommonMain/.../infrastructure/FileBookmarkStore.kt         (moved)
shared/src/jvmCommonMain/.../infrastructure/FinnhubKeyConfig.kt          (moved)
shared/src/jvmCommonTest/...                                             (their tests, moved)
desktopApp/.../l10n/L10n.kt, AppLanguage.kt                              (re-export typealiases)
desktopApp/.../designkit/AccentTheme.kt                                  (typealias + deep/mid/light extension vals)
desktopApp/.../infra/File*.kt, FinnhubKeyConfig.kt                       (re-export typealiases)
androidApp/.../AppShell.kt                (NEW: Scaffold + NavigationBar + top bar)
androidApp/.../l10n/LocalizationManager.kt (NEW: Android tr()/trf() over shared catalog)
androidApp/.../watchlist/WatchlistScreen.kt + WatchlistViewModel.kt      (NEW; quotes/ retired)
androidApp/.../watchlist/PriceAlertSheet.kt + AlertFormState.kt          (NEW)
androidApp/.../alerts/AlertNotifier.kt    (NEW: NotificationManager delivery)
androidApp/.../news/NewsScreen.kt + NewsViewModel.kt + ArticleRow.kt     (NEW)
androidApp/.../settings/SettingsScreen.kt + SettingsViewModel.kt         (NEW: account pages)
androidApp/.../ui/theme/Theme.kt          (MODIFIED: + lightColorScheme + accent wiring)
androidApp/.../AppGraph.kt                (MODIFIED: watchlist/alerts/news/settings wiring)
```

---

### Task 1: Baseline verify (no code change)

**Files:** none modified; results recorded in `docs/superpowers/plans/android-parity-baseline.md`.

**Interfaces:**
- Consumes: nothing.
- Produces: recorded baseline counts consumed by Task 9.

- [ ] **Step 1: Run all three JVM suites and record counts**

```bash
cd "/Users/ap/Desktop/Work_25_02/Claude/Software engineering/Trading app"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :desktopApp:test :shared:jvmTest :androidApp:testDebugUnitTest 2>&1 | /usr/bin/tail -15
```

Expected: `BUILD SUCCESSFUL`. Extract per-module test counts from the XML reports:

```bash
for d in desktopApp/build/test-results/test shared/build/test-results/jvmTest androidApp/build/test-results/testDebugUnitTest; do
  echo "$d: $(/usr/bin/grep -h 'tests=' $d/*.xml 2>/dev/null | /usr/bin/sed -E 's/.*tests="([0-9]+)".*/\1/' | /usr/bin/paste -sd+ - | /usr/bin/bc)"
done
```

- [ ] **Step 2: Verify the Android app assembles**

```bash
./gradlew :androidApp:assembleDebug 2>&1 | /usr/bin/tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Record + commit**

Write the three counts + assemble result + date to `docs/superpowers/plans/android-parity-baseline.md`, then:

```bash
git add docs/superpowers/plans/android-parity-baseline.md
git commit -m "test(android): record pre-parity test/build baseline"
```

---

### Task 2: Promote L10n + AppLanguage + AccentTheme identity to shared/commonMain

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/l10n/L10n.kt` (moved from `desktopApp/src/main/kotlin/com/aptrade/desktop/l10n/L10n.kt`, package renamed)
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/l10n/AppLanguage.kt` (moved)
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/settings/AccentTheme.kt` (identity-only)
- Create: `shared/src/commonTest/kotlin/com/aptrade/shared/l10n/L10nCatalogTest.kt` (moved from desktop, package renamed)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/l10n/L10n.kt` → re-export
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/l10n/AppLanguage.kt` → re-export
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/AccentTheme.kt` → typealias + color extension vals
- Delete: `desktopApp/src/test/kotlin/com/aptrade/desktop/l10n/L10nCatalogTest.kt` (moved to shared)

**Interfaces:**
- Consumes: existing desktop files (verbatim content).
- Produces (used by Tasks 3-8): `com.aptrade.shared.l10n.L10n` (object, `enum class Key(val english: String)`, `fun string(key: Key, language: AppLanguage): String`, internal `table`), `com.aptrade.shared.l10n.AppLanguage` (enum English/German/Italian/Spanish, `code`, `displayName`), `com.aptrade.shared.settings.AccentTheme` (enum ChampagneGold/RoseGold/Sapphire/Amethyst/Platinum with `displayName`, `tagline` — NO colors).

- [ ] **Step 1: Move the L10n catalog verbatim**

`git mv` is not required (different module); copy content preserving every string:

- Copy `desktopApp/src/main/kotlin/com/aptrade/desktop/l10n/L10n.kt` to `shared/src/commonMain/kotlin/com/aptrade/shared/l10n/L10n.kt`, changing ONLY the package line to `package com.aptrade.shared.l10n`. Every key, English value, and DE/IT/ES table entry is byte-identical. (`java.util`/Compose are not imported by this file — it is pure Kotlin; verify with a grep for `import` before assuming.)
- Same for `AppLanguage.kt` → `package com.aptrade.shared.l10n`.

- [ ] **Step 2: Create the shared AccentTheme identity**

`shared/src/commonMain/kotlin/com/aptrade/shared/settings/AccentTheme.kt`:

```kotlin
package com.aptrade.shared.settings

/** The selectable brand accent — identity only (name + picker copy). The per-platform
 *  color ramps (deep/mid/light stops) live in each platform's design kit; commonMain
 *  must stay free of any UI framework so the Apple xcframework stays clean. Constant
 *  names are the persisted enum names in settings.json — never rename. */
enum class AccentTheme(val displayName: String, val tagline: String) {
    ChampagneGold(displayName = "Champagne Gold", tagline = "Default — gold on black"),
    RoseGold(displayName = "Rose Gold", tagline = "Warm copper blush"),
    Sapphire(displayName = "Sapphire", tagline = "Deep cobalt blue"),
    Amethyst(displayName = "Amethyst", tagline = "Regal violet"),
    Platinum(displayName = "Platinum", tagline = "Cool brushed silver"),
}
```

- [ ] **Step 3: Replace the desktop originals with re-exports**

`desktopApp/src/main/kotlin/com/aptrade/desktop/l10n/L10n.kt` becomes exactly:

```kotlin
package com.aptrade.desktop.l10n

/** Moved to shared (com.aptrade.shared.l10n.L10n) so Android reuses the catalog.
 *  This alias keeps every desktop call site (`L10n.Key.Watchlist`, `L10n.string(...)`)
 *  compiling unchanged. */
typealias L10n = com.aptrade.shared.l10n.L10n
```

`desktopApp/src/main/kotlin/com/aptrade/desktop/l10n/AppLanguage.kt` becomes exactly:

```kotlin
package com.aptrade.desktop.l10n

/** Moved to shared (com.aptrade.shared.l10n.AppLanguage). */
typealias AppLanguage = com.aptrade.shared.l10n.AppLanguage
```

`desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/AccentTheme.kt` becomes exactly (hex stops copied verbatim from the current file — the existing `AccentThemeTest`/`DKColorTableTest` enforce the values):

```kotlin
package com.aptrade.desktop.designkit

import androidx.compose.ui.graphics.Color

/** Identity moved to shared (com.aptrade.shared.settings.AccentTheme); the Compose color
 *  ramp stays desktop-local as extension properties so commonMain stays Compose-free.
 *  Hex stops are verbatim from the pre-promotion enum (guarded by AccentThemeTest). */
typealias AccentTheme = com.aptrade.shared.settings.AccentTheme

val AccentTheme.deep: Color get() = when (this) {
    AccentTheme.ChampagneGold -> Color(0xFFA9772A)
    AccentTheme.RoseGold -> Color(0xFF8E4A3C)
    AccentTheme.Sapphire -> Color(0xFF1C3F73)
    AccentTheme.Amethyst -> Color(0xFF512D78)
    AccentTheme.Platinum -> Color(0xFF646B78)
}

val AccentTheme.mid: Color get() = when (this) {
    AccentTheme.ChampagneGold -> Color(0xFFD4A94E)
    AccentTheme.RoseGold -> Color(0xFFCD846F)
    AccentTheme.Sapphire -> Color(0xFF417FD4)
    AccentTheme.Amethyst -> Color(0xFF8A5BC9)
    AccentTheme.Platinum -> Color(0xFFA3AAB6)
}

val AccentTheme.light: Color get() = when (this) {
    AccentTheme.ChampagneGold -> Color(0xFFF2DDA0)
    AccentTheme.RoseGold -> Color(0xFFEDC4B4)
    AccentTheme.Sapphire -> Color(0xFF9CC2F1)
    AccentTheme.Amethyst -> Color(0xFFC6A8ED)
    AccentTheme.Platinum -> Color(0xFFDADFE7)
}
```

Then compile; the compiler will name every desktop file using `accent.deep/.mid/.light` from another package — add `import com.aptrade.desktop.designkit.deep` (etc.) or a wildcard import there. Change imports only; no logic edits.

- [ ] **Step 4: Move the catalog test to shared**

Copy `desktopApp/src/test/kotlin/com/aptrade/desktop/l10n/L10nCatalogTest.kt` to `shared/src/commonTest/kotlin/com/aptrade/shared/l10n/L10nCatalogTest.kt` with package `com.aptrade.shared.l10n` (drop desktop-only imports; the test asserts every key has non-empty EN/DE/IT/ES — keep every assertion). Delete the desktop copy. If the desktop test also asserts `tr()`-layer behavior (check `TrfTest.kt` — it stays in desktop; only the catalog test moves).

- [ ] **Step 5: Run the suites**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest :desktopApp:test 2>&1 | /usr/bin/tail -8
```

Expected: BUILD SUCCESSFUL; desktop count unchanged minus the moved catalog test, shared count up by the same.

- [ ] **Step 6: Commit**

```bash
git add shared/src desktopApp/src
git commit -m "refactor(shared): promote L10n catalog, AppLanguage, AccentTheme identity to shared"
```

---

### Task 3: Promote the five JVM file-stores to shared/jvmCommonMain

**Files:**
- Create in `shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/`: `FileWatchlistStore.kt`, `FileAlertStore.kt`, `FileSettingsStore.kt`, `FileBookmarkStore.kt`, `FinnhubKeyConfig.kt` (moved from `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/`, packages → `com.aptrade.shared.infrastructure`)
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/settings/AppSettings.kt` (the `AppSettings` data class extracted from desktop `FileSettingsStore.kt`, now referencing shared `AccentTheme`/`AppLanguage`)
- Create in `shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/`: the five matching test files moved from `desktopApp/src/test/kotlin/com/aptrade/desktop/infra/`
- Modify: the five desktop originals → one-line re-export typealiases (pattern below)
- Delete: the five desktop test originals (moved)

**Interfaces:**
- Consumes: shared ports `WatchlistStore`, `AlertStore` (in `com.aptrade.shared.application`), Task 2's `AccentTheme`/`AppLanguage`.
- Produces (used by Tasks 4-8): `com.aptrade.shared.infrastructure.FileWatchlistStore(file: Path)`, `FileAlertStore(file: Path)`, `FileSettingsStore(file: Path)` with `suspend fun load(): AppSettings` / `suspend fun save(settings: AppSettings)`, `FileBookmarkStore(file: Path)`, `FinnhubKeyConfig` (same API as desktop — read the file for exact signatures before wiring); `com.aptrade.shared.settings.AppSettings` (all 12 fields with the exact defaults currently in desktop's file).

- [ ] **Step 1: Move each store verbatim**

For each of the five files: copy to the new path, change the package line to `com.aptrade.shared.infrastructure`, and update imports that referenced `com.aptrade.desktop.l10n.AppLanguage` / `com.aptrade.desktop.designkit.AccentTheme` to the shared equivalents (`com.aptrade.shared.l10n.AppLanguage`, `com.aptrade.shared.settings.AccentTheme`). No other edits — DTOs, Json config, atomic-move logic all byte-identical.

For `FileSettingsStore.kt`: split the `AppSettings` data class out into `shared/src/commonMain/kotlin/com/aptrade/shared/settings/AppSettings.kt` (it is pure Kotlin — 12 fields, defaults verbatim from desktop including the documented `isDarkMode = true`, `language = AppLanguage.English`); the store file keeps the DTO + Json logic in jvmCommonMain and imports `com.aptrade.shared.settings.AppSettings`.

- [ ] **Step 2: Desktop re-exports**

Each of the five desktop files becomes a re-export, e.g. `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/FileWatchlistStore.kt`:

```kotlin
package com.aptrade.desktop.infra

/** Moved to shared/jvmCommonMain so Android reuses the same store. */
typealias FileWatchlistStore = com.aptrade.shared.infrastructure.FileWatchlistStore
```

Same one-liner pattern for `FileAlertStore`, `FileSettingsStore`, `FileBookmarkStore`, `FinnhubKeyConfig`. Additionally in the `FileSettingsStore.kt` re-export file add `typealias AppSettings = com.aptrade.shared.settings.AppSettings` (desktop call sites import `AppSettings` from `com.aptrade.desktop.infra`).

- [ ] **Step 3: Move the five test files**

Copy each test from `desktopApp/src/test/.../infra/` to `shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/`, package `com.aptrade.shared.infrastructure`, imports updated the same way. Delete the desktop copies. If a test reaches for a desktop-only helper (check imports first), leave THAT test in desktop against the re-export instead of moving it — coverage must not drop either way.

- [ ] **Step 4: Run all suites**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest 2>&1 | /usr/bin/tail -8
```

Expected: BUILD SUCCESSFUL; total test count across modules equals the Task 1 baseline (tests moved, none lost).

- [ ] **Step 5: Commit**

```bash
git add shared/src desktopApp/src
git commit -m "refactor(shared): promote watchlist/alert/settings/bookmark/key stores to jvmCommonMain"
```

---

### Task 4: Android navigation shell + AppGraph wiring

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/AppShell.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/l10n/LocalizationManager.kt`
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/MainActivity.kt` (NavHost → shell)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt` (add watchlist/alerts/news/settings graph)

**Interfaces:**
- Consumes: Task 2 `L10n`/`AppLanguage`, Task 3 stores; existing `AppGraph.initialize(filesDir)` pattern and use cases in `shared/commonMain` (`FetchWatchlist`, `AddToWatchlist`, `RemoveFromWatchlist`, `AlertUseCases`: check `shared/src/commonMain/kotlin/com/aptrade/shared/application/` for exact class names/ctors before wiring — never guess).
- Produces: `AppShell(...)` composable hosting three tabs; `AppGraph.watchlistStore/alertStore/settingsStore/bookmarkStore` vals + use-case vals (exact names below); `tr(key)`/`trf(key, args)` top-level functions in `com.aptrade.android.l10n`.

- [ ] **Step 1: Android LocalizationManager (mirror of desktop's)**

`androidApp/src/main/kotlin/com/aptrade/android/l10n/LocalizationManager.kt`:

```kotlin
package com.aptrade.android.l10n

import androidx.compose.runtime.mutableStateOf
import com.aptrade.shared.l10n.AppLanguage
import com.aptrade.shared.l10n.L10n

/** Active language as Compose snapshot state — every tr() reader recomposes on change.
 *  Mirrors desktopApp's LocalizationManager over the same shared catalog. Persistence
 *  wiring arrives with the Language settings page (Task 7). */
object LocalizationManager {
    val current = mutableStateOf(AppLanguage.English)
}

fun tr(key: L10n.Key): String = L10n.string(key, LocalizationManager.current.value)

/** Swift-style placeholders (%@, %lld) → java.util.Formatter, Locale.ROOT pinned so
 *  numeric formatting stays locale-invariant while prose translates (same rationale as
 *  the desktop trf()). */
fun trf(key: L10n.Key, vararg args: Any?): String {
    val template = tr(key).replace("%@", "%s").replace("%lld", "%d")
    return String.format(java.util.Locale.ROOT, template, *args)
}
```

- [ ] **Step 2: Extend AppGraph**

In `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt`, following the existing `portfolio: PortfolioGraph by lazy` + `initialize(filesDir)` pattern, add (adjusting names to the ACTUAL shared use-case classes found in Step 0 grep — the wiring shape is binding, invented names are not):

```kotlin
// Watchlist + alerts + news + settings — all against app-private filesDir, mirroring
// the desktop ConfigDir wiring but rooted in Android's sandbox.
val watchlistStore by lazy { FileWatchlistStore(configDir().resolve("watchlist.json")) }
val alertStore by lazy { FileAlertStore(configDir().resolve("alerts.json")) }
val settingsStore by lazy { FileSettingsStore(configDir().resolve("settings.json")) }
val bookmarkStore by lazy { FileBookmarkStore(configDir().resolve("bookmarks.json")) }

private fun configDir(): java.nio.file.Path {
    val dir = requireNotNull(filesDir) { "AppGraph.initialize(filesDir) must run before stores are touched" }
    return dir.toPath().resolve("aptrade")
}

val fetchWatchlist by lazy { FetchWatchlist(watchlistStore) }
val addToWatchlist by lazy { AddToWatchlist(watchlistStore) }
val removeFromWatchlist by lazy { RemoveFromWatchlist(watchlistStore) }
```

(Check `FetchWatchlist`'s actual constructor in shared — desktop's `AppGraph.kt`/`AppGraphLocal.kt` shows the working wiring to copy from. Same for alert/news use cases, added in their tasks.)

- [ ] **Step 3: Build the shell**

`androidApp/src/main/kotlin/com/aptrade/android/AppShell.kt` — Material 3 scaffold; the three tab roots render inside it, search/detail push over it:

```kotlin
package com.aptrade.android

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import com.aptrade.android.l10n.tr
import com.aptrade.shared.l10n.L10n

enum class ShellTab(val route: String, val labelKey: L10n.Key) {
    Watchlist("watchlist", L10n.Key.Watchlist),
    Portfolio("portfolio", L10n.Key.Portfolio),
    News("news", L10n.Key.News),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    selected: ShellTab,
    onSelectTab: (ShellTab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APTrade") },
                actions = {
                    IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, contentDescription = tr(L10n.Key.Search)) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.MoreHoriz, contentDescription = tr(L10n.Key.Account)) }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                ShellTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { onSelectTab(tab) },
                        icon = {
                            val icon = when (tab) {
                                ShellTab.Watchlist -> Icons.Filled.RemoveRedEye
                                ShellTab.Portfolio -> Icons.Filled.PieChart
                                ShellTab.News -> Icons.Filled.Article
                            }
                            Icon(icon, contentDescription = tr(tab.labelKey))
                        },
                        label = { Text(tr(tab.labelKey)) },
                    )
                }
            }
        },
    ) { padding -> content(padding) }
}
```

(Icon names: verify against `material-icons-core` — if `RemoveRedEye`/`PieChart`/`Article`/`MoreHoriz` are missing from the core set, pick the closest available core icon rather than adding the extended-icons dependency. `L10n.Key.Search`/`Account` — verify the exact key names exist in the catalog; desktop uses them, but check.)

- [ ] **Step 4: Rewire MainActivity**

Replace `AppNavHost` so tab roots live under the shell and search/detail/settings push above it. Keep route names. `QuotesScreen` remains temporarily wired as the Watchlist tab body (Task 5 replaces it):

```kotlin
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    var tab by rememberSaveable { mutableStateOf(ShellTab.Watchlist) }
    NavHost(navController = navController, startDestination = "shell") {
        composable("shell") {
            AppShell(
                selected = tab,
                onSelectTab = { tab = it },
                onOpenSearch = { navController.navigate("search") },
                onOpenSettings = { navController.navigate("settings") },
            ) { padding ->
                when (tab) {
                    ShellTab.Watchlist -> QuotesScreen(   // Task 5 swaps in WatchlistScreen
                        onOpenSearch = { navController.navigate("search") },
                        onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
                        onOpenPortfolio = {},             // superseded by the tab bar
                    )
                    ShellTab.Portfolio -> PortfolioScreen(
                        onBack = {},                       // tab root: no back
                        onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
                    )
                    ShellTab.News -> NewsPlaceholder()     // Task 6 replaces
                }
            }
        }
        composable("search") { SearchScreen(onOpenDetail = { symbol -> navController.navigate("detail/$symbol") }) }
        composable("detail/{symbol}") { back -> DetailScreen(symbol = back.arguments?.getString("symbol").orEmpty()) }
        composable("settings") { SettingsPlaceholder() }   // Task 7 replaces
    }
}
```

`NewsPlaceholder`/`SettingsPlaceholder` are 5-line centered-`Text(tr(L10n.Key.News))`-style composables in `AppShell.kt` — they exist only so this task ships compiling, navigable software.

- [ ] **Step 5: Build + test + commit**

```bash
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest 2>&1 | /usr/bin/tail -5
git add androidApp/src
git commit -m "feat(android): Material 3 shell with bottom tabs + shared L10n tr()/trf()"
```

---

### Task 5: Watchlist screen (replaces Quotes)

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/watchlist/WatchlistScreen.kt`, `WatchlistViewModel.kt`
- Test: `androidApp/src/test/kotlin/com/aptrade/android/watchlist/WatchlistViewModelTest.kt` (+ reuse existing `FakeMarketDataRepository`; add `FakeWatchlistStore`)
- Modify: `MainActivity.kt` (swap QuotesScreen → WatchlistScreen), `AppGraph.kt` (VM factory)
- Delete: `androidApp/.../quotes/QuotesScreen.kt`, `QuotesViewModel.kt`, and `quotes/QuotesViewModelTest.kt` (superseded; keep `FakeMarketDataRepository` — move it if it lives under quotes/)

**Interfaces:**
- Consumes: `FetchWatchlist`/`AddToWatchlist`/`RemoveFromWatchlist` + `FetchMarketQuotes` (AppGraph), Task 4 shell slot.
- Produces: `WatchlistViewModel(fetchWatchlist, addToWatchlist, removeFromWatchlist, fetchMarketQuotes, pollIntervalMs)` exposing `StateFlow<WatchlistUiState>`; `data class WatchRow(symbol, name, kind, amountText: String?, changePercent: Double?, alertCount: Int)`; `data class WatchlistUiState(isLoading, rows, error)`; `fun remove(symbol: String)`, `fun refresh()`. Task 6 (alerts) adds `alertCounts` + bell tap. Reference anatomy: `desktopApp/src/main/kotlin/com/aptrade/desktop/watchlist/WatchlistPane.kt` and `WatchlistViewModel.kt` — read both before implementing; mirror the row shape (name+symbol left, price `amountText` exact-decimal string + colored %-change right), the desktop 15s poll cadence, and first-launch seeding semantics (`FetchWatchlist` owns seeding — do not seed in the VM).

- [ ] **Step 1: Write the failing ViewModel test**

`androidApp/src/test/kotlin/com/aptrade/android/watchlist/WatchlistViewModelTest.kt`:

```kotlin
package com.aptrade.android.watchlist

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeWatchlistStore(
    var entries: List<WatchlistEntry> = emptyList(),
) : WatchlistStore {
    override suspend fun load(): List<WatchlistEntry> = entries
    override suspend fun save(entries: List<WatchlistEntry>) { this.entries = entries }
}

class WatchlistViewModelTest {

    private fun vm(store: FakeWatchlistStore, repo: FakeMarketDataRepository) = WatchlistViewModel(
        fetchWatchlist = FetchWatchlist(store),
        addToWatchlist = AddToWatchlist(store),
        removeFromWatchlist = RemoveFromWatchlist(store),
        fetchMarketQuotes = FetchMarketQuotes(repo),
        pollIntervalMs = Long.MAX_VALUE, // no re-poll during tests
    )

    @Test
    fun `load populates rows in watchlist order with quotes`() = runTest {
        val store = FakeWatchlistStore(listOf(
            WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
            WatchlistEntry("BTC-USD", "Bitcoin", AssetKind.Crypto),
        ))
        val viewModel = vm(store, FakeMarketDataRepository())
        viewModel.load()
        val state = viewModel.state.value
        assertEquals(listOf("AAPL", "BTC-USD"), state.rows.map { it.symbol })
        assertTrue(state.rows.first().amountText != null)
    }

    @Test
    fun `remove drops the row and persists`() = runTest {
        val store = FakeWatchlistStore(listOf(WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)))
        val viewModel = vm(store, FakeMarketDataRepository())
        viewModel.load()
        viewModel.remove("AAPL")
        assertEquals(emptyList(), viewModel.state.value.rows)
        assertEquals(emptyList(), store.entries)
    }
}
```

Adjust constructor calls to the ACTUAL shared use-case signatures (check `FetchWatchlist.kt` — desktop wiring in `desktopApp/.../AppGraph.kt` is the working example). `FakeMarketDataRepository` already exists in `androidApp/src/test` — extend it if it lacks a method the VM calls, mirroring how the desktop `FakeMarketDataRepository` fakes it.

- [ ] **Step 2: Run to verify failure** — `./gradlew :androidApp:testDebugUnitTest --tests '*WatchlistViewModelTest*' 2>&1 | /usr/bin/tail -5` → FAIL (unresolved `WatchlistViewModel`).

- [ ] **Step 3: Implement `WatchlistViewModel`**

Mirror `desktopApp/.../watchlist/WatchlistViewModel.kt`'s load/poll/remove structure but Android-idiomatic (`androidx.lifecycle.ViewModel` + `viewModelScope`, `StateFlow`). Keep `amountText` as the exact-decimal string from `Money` — never a `Double`. Include `refresh()` re-running the quote fetch and `remove(symbol)` calling the use case then reloading.

- [ ] **Step 4: Tests pass** — same Gradle filter → PASS.

- [ ] **Step 5: Implement `WatchlistScreen`**

LazyColumn of rows (name/symbol left; `amountText` + `%+.2f%%`-formatted colored change right — `GainGreen`/`LossRed` from Theme.kt), `SwipeToDismissBox` for remove, empty-state prompting search, pull-refresh via `PullToRefreshBox`, wired into the shell slot in `MainActivity` (replace `QuotesScreen`; delete quotes files; move `FakeMarketDataRepository` up to `androidApp/src/test/kotlin/com/aptrade/android/` if it lived under quotes/). Add-from-search: in `SearchScreen`, add a trailing `IconButton(Icons.Filled.Add)` per result row calling `AppGraph.addToWatchlist`, then a snackbar confirmation — read `SearchScreen.kt` first and follow its row composable's existing structure.

- [ ] **Step 6: Full android suite + assemble + commit**

```bash
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug 2>&1 | /usr/bin/tail -5
git add -A androidApp/src
git commit -m "feat(android): persisted watchlist screen with live prices, swipe-remove, add-from-search"
```

---

### Task 6: Price alerts + system notifications

**Files:**
- Create: `androidApp/.../watchlist/PriceAlertSheet.kt`, `watchlist/AlertFormState.kt`, `alerts/AndroidAlertNotifier.kt`
- Test: `androidApp/src/test/kotlin/com/aptrade/android/watchlist/AlertFormStateTest.kt`
- Modify: `WatchlistViewModel.kt` (+alert counts, evaluation in poll), `WatchlistScreen.kt` (+bell), `AppGraph.kt` (+alert wiring), `androidApp/src/main/AndroidManifest.xml` (+`POST_NOTIFICATIONS`), `MainActivity.kt` (+permission request)

**Interfaces:**
- Consumes: shared `AlertUseCases`/`AlertStore`/`AlertNotifier` ports (read `shared/.../application/AlertUseCases.kt` + desktop's wiring in `AppGraph.kt`/`AppGraphNotifyOrderFillTest.kt` first), Task 3 `FileAlertStore`, Task 5 rows.
- Produces: `AndroidAlertNotifier(context) : AlertNotifier` posting to channel id `"price_alerts"`; `AlertFormState` (pure form model mirroring desktop's `AlertFormState.kt` — read it; same validation rules verbatim); bell on each row opening `PriceAlertSheet` (ModalBottomSheet: above/below/% segmented control, target field, existing-alerts list with delete).

- [ ] **Step 1: Port `AlertFormState` + its test from desktop.** Copy `desktopApp/.../watchlist/AlertFormState.kt` and `AlertFormStateTest.kt` into the android package (package rename + `com.aptrade.shared` imports; it is pure Kotlin). Run the test → PASS before proceeding (it validates threshold parsing/validation rules).
- [ ] **Step 2: Manifest + channel + notifier.** Add `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`. `AndroidAlertNotifier` creates `NotificationChannel("price_alerts", tr(L10n.Key.PriceAlerts), IMPORTANCE_HIGH)` lazily and posts a notification (title = symbol, text = the same message format desktop's `TrayNotifier` builds — read `TrayNotifier.kt:1-110` and reuse its formatting logic verbatim where pure). In `MainActivity.onCreate`, request `POST_NOTIFICATIONS` via `registerForActivityResult(RequestPermission())` when `Build.VERSION.SDK_INT >= 33`; denial is non-fatal (alerts still evaluate, in-app only).
- [ ] **Step 3: Wire evaluation into the poll.** In `AppGraph`: `alertUseCases` mirrored from desktop wiring; in `WatchlistViewModel`'s poll loop, after quotes land, run `evaluateAlerts` (same call desktop's `WatchlistViewModel.kt:79` region makes) and forward triggers to the notifier; expose `alertCounts: Map<String, Int>` in the ui state for the bell badge.
- [ ] **Step 4: Bell + sheet UI.** Row bell icon (filled when `alertCount > 0`, gold tint) → `ModalBottomSheet` with the form; mirror desktop `PriceAlertSheet.kt` anatomy (current price line, segmented kind picker, labeled field, Add button on the gold gradient, existing alerts with delete).
- [ ] **Step 5: VM test additions.** Extend `WatchlistViewModelTest` with: a triggered alert increments nothing but fires the (faked) notifier once; `alertCounts` reflects store contents. Use a `FakeAlertStore` + recording fake notifier.
- [ ] **Step 6: Suite + assemble + commit** — `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug` green, then `git commit -m "feat(android): price alerts with sheet, badge, and system notifications"`.

---

### Task 7: News tab with bookmarks

**Files:**
- Create: `androidApp/.../news/NewsScreen.kt`, `news/NewsViewModel.kt`, `news/ArticleRow.kt`
- Test: `androidApp/src/test/kotlin/com/aptrade/android/news/NewsViewModelTest.kt`
- Modify: `AppGraph.kt` (+news wiring incl. `FinnhubNewsRepository` + `FinnhubKeyConfig` + `FileBookmarkStore`), `MainActivity.kt` (replace `NewsPlaceholder`), `androidApp/build.gradle.kts` (+`implementation("androidx.browser:browser:1.8.0")`)

**Interfaces:**
- Consumes: shared `NewsUseCases`/`NewsPorts`/`FinnhubNewsRepository`/`NewsCategory` (read them first; desktop wiring in `AppGraph.kt` + `news/NewsViewModel.kt` is the working example), Task 3 `FileBookmarkStore` + `FinnhubKeyConfig`.
- Produces: `NewsViewModel(newsUseCases..., bookmarkStore, hasKey: Boolean)` with `StateFlow<NewsUiState>` (`articles`, `category`, `bookmarkedIds`, `isLoading`, `error`, `needsKey`); `NewsScreen` with category chips (mirror desktop `NewsPane.kt`'s categories), bookmark toggle per row, article tap → Chrome Custom Tab (`CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))`).

- [ ] **Step 1: Failing VM test** — mirror `desktopApp/src/test/.../news/NewsViewModelTest.kt`'s cases (load populates by category; bookmark toggle persists; missing key → `needsKey=true` and no fetch) against fakes (`FakeNewsRepository` mirroring the desktop fake, `FakeBookmarkStore`). Copy the desktop test's fake-shapes; package-rename.
- [ ] **Step 2: FAIL run**, **Step 3: implement VM**, **Step 4: PASS run** — standard TDD; VM mirrors desktop `NewsViewModel.kt` structure on `viewModelScope`/`StateFlow`.
- [ ] **Step 5: Screen** — category chip row (`FilterChip`s), `ArticleRow` (headline, source, relative time via a `DateUtils.getRelativeTimeSpanString` helper, thumbnail via `AsyncImage` ONLY if coil is already a dependency — it is not: skip thumbnails, desktop's RemoteImage pattern is desktop-only; text-first rows), bookmark icon toggle, missing-key empty state using `L10n.Key.ConnectNewsSource`/`FinnhubKeyInstructions` keys (verify exact key names in the shared catalog — desktop uses them). Custom-tab open on tap.
- [ ] **Step 6: Suite + assemble + commit** — `git commit -m "feat(android): news tab with categories, bookmarks, custom-tab reader"`.

---

### Task 8: Settings screens + language switcher + light theme + accent

**Files:**
- Create: `androidApp/.../settings/SettingsScreen.kt`, `settings/SettingsViewModel.kt`
- Test: `androidApp/src/test/kotlin/com/aptrade/android/settings/SettingsViewModelTest.kt`
- Modify: `androidApp/.../ui/theme/Theme.kt` (+light scheme, accent-driven primary), `MainActivity.kt` (replace `SettingsPlaceholder`, theme from settings state), `AppGraph.kt` (+`settingsStore` VM factory)

**Interfaces:**
- Consumes: Task 3 `FileSettingsStore`/`AppSettings` (12 fields, defaults verbatim), Task 2 `AccentTheme` identity + Task 4 `LocalizationManager`.
- Produces: `SettingsViewModel(settingsStore)` with `StateFlow<AppSettings>` + `fun update(transform: (AppSettings) -> AppSettings)` that persists via a `Mutex`-serialized load-merge-save (desktop closed a lost-update race here — mirror `desktopApp`'s persist pattern, see `SettingsPersistTest.kt`); `APTradeTheme(settings: AppSettings)` selecting dark/light scheme + accent primary.

- [ ] **Step 1: Failing persistence test** — mirror desktop `SettingsPersistTest.kt`: toggling a flag persists; two concurrent updates both land (Mutex test); language change persists and `LocalizationManager.current` follows.
- [ ] **Step 2-4: TDD the ViewModel** as above.
- [ ] **Step 5: Theme.** In `Theme.kt` add a `lightColorScheme` — transcribe the light-mode color table from desktop's `DK.kt` 6d.2 light colors (read `desktopApp/.../designkit/DK.kt` and `BrandTint.kt`; `DKColorTableTest` documents the values) into Material 3 slots; accent → `primary`/`secondary` from a local `AccentTheme→Color` ramp table (mirror the desktop extension values from Task 2's designkit file — android keeps its own copy, same hexes, in `Theme.kt`).
- [ ] **Step 6: Screens.** Settings NavHost-pushed screen with the desktop `AccountPanel` page set: menu (Profile / Account Settings / Notifications / Appearance / Language / Security & Privacy / Help & Support / About), each a sub-page mirroring `AccountPanel.kt`'s rows — toggles bound to `SettingsViewModel.update`, appearance page with Dark/Light rows + accent rows (ramp circles + checkmark), language page with the four `AppLanguage` rows (selection updates `LocalizationManager.current` AND persists). Every string through `tr()` — no hardcoded English.
- [ ] **Step 7: Suite + assemble + commit** — `git commit -m "feat(android): settings pages, language switcher, light theme, accent picker"`.

---

### Task 9: Full verification + emulator UAT

**Files:** none (defect fixes fold back into owning files as separate `fix(android):` commits).

**Interfaces:** consumes the Task 1 baseline.

- [ ] **Step 1: All suites**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :desktopApp:test :shared:jvmTest :androidApp:testDebugUnitTest 2>&1 | /usr/bin/tail -8
```

Expected: BUILD SUCCESSFUL; desktop count = baseline minus moved tests; shared = baseline + moved + new; android = baseline + new VMs. No net coverage loss vs Task 1 (sum ≥ baseline sum).

- [ ] **Step 2: Desktop smoke run** — `./gradlew :desktopApp:run` briefly (agent launches, confirms the window opens and watchlist renders, quits). This proves the promotions didn't break the running desktop app, not just its tests.

- [ ] **Step 3: Install on emulator**

```bash
./gradlew :androidApp:installDebug 2>&1 | /usr/bin/tail -3
adb shell monkey -p com.aptrade.android 1
```

(If the emulator isn't running: `emulator -list-avds` then boot one; BlueStacks port collisions — `adb kill-server && adb start-server` first if devices don't show.)

- [ ] **Step 4: User UAT checklist (user-performed — computer-use cannot drive the emulator build)**

- [ ] Bottom tabs: Watchlist / Portfolio / News all reachable; top-bar search + settings
- [ ] Watchlist: add from search, live prices + colored daily %, swipe-to-remove, persists across relaunch
- [ ] Alerts: bell opens sheet; above/below/% forms validate; alert fires a system notification when its condition trips (set a threshold just past the current price and wait a poll); badge counts
- [ ] News: categories switch; bookmark toggles persist; article opens in custom tab; key-missing empty state if no Finnhub key
- [ ] Settings: every sub-page opens; toggles persist across relaunch; Language switch re-renders the whole UI live in DE/IT/ES; Dark/Light switch recolors; accent changes primary color
- [ ] Portfolio + detail still work as before (regression)

- [ ] **Step 5: Fix defects found, re-run Steps 1-4 until clean; final statement of counts.**

---

## Deviation rule

If a shared use-case signature differs from what a task sketches, the SHARED code is authoritative — adapt the Android wiring, never the shared API. If a promotion forces a desktop source change beyond imports/typealiases, STOP and report (BLOCKED) rather than altering desktop logic.
