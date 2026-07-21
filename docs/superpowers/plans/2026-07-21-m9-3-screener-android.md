# M9.3 — Technical Screener: Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the technical screener on Android — the final M9 increment, bringing all four platforms to parity: fifth bottom-nav tab, 9 preset signal screens + custom AND-builder, on-demand throttled S&P 500 scan into the shared per-trading-day snapshot store, phone-adapted results list with sorting and add-to-watchlist, detail navigation with Buy.

**Architecture:** The shared Kotlin core already carries everything (Screener domain + 9 presets, ScreenerMath, ScreenerScanEngine incl. the two mandated improvements, FileScreenerSnapshotStore/FileScreenStore, SP500Symbols, all 37 L10n keys — M9.2). Android adds ViewModel + Compose screens + wiring, transcribed from the **desktop as-built on `main` (3f510e5)** — the same twin-transcription discipline as M8.3 (desktop→android). Phone LAYOUT semantics (which columns collapse on narrow) come from the Swift iPhone narrow variant of `ScreenerView.swift`; all CODE and TESTS transcribe from the Kotlin desktop suite, never Swift (carry-note 5). DO NOT touch `shared/` except where a task explicitly says so (Task 1 only).

**Tech Stack:** Kotlin, Jetpack Compose, androidx ViewModel/viewModelScope, JUnit (`./gradlew :androidApp:testDebugUnitTest`).

## Global Constraints

M9.3 carry-notes from the M9.2 final review (progress.md), all binding:

1. **Reuse commonMain/jvmCommonMain** — Android writes only VM + UI + wiring. The engine, math, domain, stores are consumed as-is.
2. **VM init sync file I/O will bite:** desktop `ScreenerViewModel.init` calls `snapshotStore.load()` / `screenStore.load()` synchronously. On Android that runs on the main thread → StrictMode `DiskReadViolation`. The Android VM loads both stores inside `viewModelScope.launch` on an injected `ioDispatcher: CoroutineDispatcher = Dispatchers.IO`, then applies state — a RECORDED DIVERGENCE with a code comment naming desktop's sync init as the reference and StrictMode as the reason. Persistence writes (`saveScreen`/`deleteScreen`/post-scan snapshot save) route through the same dispatcher.
3. **SP500Names is desktop-only** — Task 1 promotes it to `shared` jvmCommonMain (NOT commonMain: keeps the ~500-entry table out of the iOS xcframework; both consumers are JVM), then Android reuses it.
4. **VM invariants checklist (re-earn every one, they are test-pinned on desktop):** scan Job + ownership token `===` guard; `CancellationException`-first catch (rethrow-or-return before generic catch); total-failure keeps previous snapshot and maps to Failed; `ScreenerScanAborted` → Failed with previous snapshot KEPT; selection re-sync after screen delete; nil-last in BOTH sort directions; main-confined scope.
5. **TEST-FAKE TRAP:** transcribe tests from the KOTLIN desktop suite (`desktopApp/.../screener/ScreenerViewModelTest.kt`, `ScreenBuilderModelTest.kt`), not Swift — the Kotlin fake defaults to EMPTY candles where the Swift fake defaults to a single candle; transcribing from Swift silently inverts several fixtures.
6. **Wire format:** snapshot files written by other platforms degrade safely to null (missing-key → lenient null, never crash/overwrite) — already handled in the shared stores; do not add Android-side migration.
7. **Empty-state cascade is order-sensitive** — copy the desktop `when` shape verbatim (no-snapshot → scanning-no-CTA → no-matches), do not reorder branches.
8. **Material3 DropdownMenu theming** — reuse whatever the desktop builder dialog ships for the metric dropdown; UAT feedback on its theming applies to both platforms, so keep the implementations shaped alike.

House rules: raw-fraction unit convention everywhere (M9.1-earned, comments at both desktop sites — preserve); no portfolio mutex anywhere in screener code (read-only feature); `tr()`/`trf()` for all copy using the existing 37 `L10n.Key.Screener*` keys (no new keys expected); UI composition ships without unit tests (standing waiver) — ViewModels and models carry the behavior; every task ends with the FULL android suite green plus `./gradlew :androidApp:assembleDebug` clean; work on branch `feature/m9-3-screener-android`.

---

### Task 1: Promote SP500Names to shared jvmCommonMain

**Files:**
- Create: `shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/SP500Names.kt` (moved content; visibility `internal` → **public** `val SP500Names: Map<String, String>`, package `com.aptrade.shared.infrastructure`; carry the existing KDoc, updating the "desktop-only" rationale to name both JVM consumers and why it stays out of commonMain — xcframework size)
- Delete: `desktopApp/src/main/kotlin/com/aptrade/desktop/calendar/SP500Names.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt:4` (import swap → `com.aptrade.shared.infrastructure.SP500Names`) and the stale "SP500Names is desktop-only" comment block at `AppGraph.kt:226-228`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/calendar/CalendarPane.kt:232` (import swap, same symbol name)

**Interfaces:**
- Produces: `com.aptrade.shared.infrastructure.SP500Names: Map<String, String>` — consumed by desktop AppGraph/CalendarPane (existing call sites, unchanged semantics) and by Android AppGraph in Task 2.

- [ ] **Step 1: Move the file** (git mv + package/visibility edit; content byte-identical otherwise). No behavior change anywhere.
- [ ] **Step 2: Gates:** `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest` ALL green (desktop 340 / shared 587 / android 221 unchanged — pure relocation).
- [ ] **Step 3: Commit** `refactor(shared): promote SP500Names to jvmCommonMain for Android reuse`

---

### Task 2: Android ScreenerViewModel

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/screener/ScreenerViewModel.kt`
- Create: `androidApp/src/test/kotlin/com/aptrade/android/screener/ScreenerViewModelTest.kt`
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt` (screener wiring — mirror desktop `AppGraph.kt:221-249`: `screenerScanEngine = ScreenerScanEngine(repository, marketCalendar)` on the graph's existing repository/calendar; `screenerSnapshotStore = FileScreenerSnapshotStore(configDir().resolve("screener-snapshot.json"))`; `screenStore = FileScreenStore(configDir().resolve("screens.json"))`; no factory function needed — the screen constructs the VM via `viewModel { }` per the `DetailScreen.kt:84` precedent, passing `SP500Symbols.set.toList()` and `SP500Names`)

**Desktop reference:** `desktopApp/src/main/kotlin/com/aptrade/desktop/screener/ScreenerViewModel.kt` AS-BUILT + ALL 22 tests in `desktopApp/src/test/kotlin/com/aptrade/desktop/screener/ScreenerViewModelTest.kt` (including its fakes — carry-note 5: transcribe the KOTLIN fakes, byte-equal fixtures). Normalization per the M7.3/M8.3 android-VM precedent (`androidApp/.../plans/PlansViewModel.kt` shape: class extends `ViewModel`, injected-scope call sites become `viewModelScope`; test scaffolding per `PlansViewModelTest.kt`'s dispatcher idiom). Keep `ScreenerSortColumn`, `ScreenerScanState`, `ScreenerUiState` shapes and every public method name identical (`isSnapshotFresh`, `startScan`, `cancelScan`, `select`, `setSortColumn`, `setSortAscending`, `saveScreen`, `deleteScreen`, `matchCount`).

**Interfaces:**
- Consumes: shared `ScreenerScanEngine`, `FileScreenerSnapshotStore`, `FileScreenStore`, `MarketCalendar`, `SP500Symbols`, Task 1's `SP500Names`.
- Produces: `ScreenerViewModel(engine, snapshotStore, screenStore, symbols: List<String>, names: Map<String, String>, calendar, nowEpochSeconds, ioDispatcher)` extending `ViewModel`, `state: StateFlow<ScreenerUiState>` — consumed by Tasks 3/4.

**THE divergence (Global Constraint 2):** init-time store loads and all store writes hop through the injected `ioDispatcher` (default `Dispatchers.IO`); comment names the desktop sync init + StrictMode. Tests inject the test dispatcher and `runCurrent()` past the init load — expect a handful of transcribed tests to need that one extra line; nothing else may change in fixtures or assertions.

- [ ] **Step 1: Failing tests** — 22 transcribed name-for-name from the desktop suite. Run: `./gradlew :androidApp:testDebugUnitTest --tests "*ScreenerViewModel*"` — FAILS (class absent).
- [ ] **Step 2: Implement VM + AppGraph wiring.** Filtered run PASS; full android suite PASS; `assembleDebug` clean.
- [ ] **Step 3: Commit** `feat(android): ScreenerViewModel — scan orchestration, selection, sorting, IO-dispatched stores`

---

### Task 3: Android ScreenerScreen — fifth tab

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/screener/ScreenerScreen.kt`
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppShell.kt` (`ShellTab` gains `Screener("screener", L10n.Key.ScreenerTab)` after `Calendar`; icon per the file's documented core-icons substitution policy — pick the closest core analog to a filter/funnel, note the substitution in the existing KDoc list)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/MainActivity.kt:112-125` (`when (tab)` gains `ShellTab.Screener -> ScreenerScreen(padding, onOpenDetail = { symbol -> navController.navigate("detail/$symbol") })`)

**Desktop reference:** `desktopApp/src/main/kotlin/com/aptrade/desktop/screener/ScreenerPane.kt` AS-BUILT for behavior: chips row (9 presets + saved-screen chips + "+" new chip + ✎ edit affordance — Task 4 wires the sheet, this task renders the chips with the "+"/✎ disabled-or-stubbed), 4-state scan bar (freshness-gated idle icon/label via `isSnapshotFresh()`, progress with done/total counts, failed + retry, failed-symbols note), the order-sensitive 3-branch empty-state cascade (Global Constraint 7 — copy the `when` shape), header-click sort toggling (same column flips direction, new column resets ascending, nil-last both directions), per-row add-to-watchlist star seeded from the real watchlist store (`AppGraph.fetchWatchlist` / `AppGraph.addToWatchlist` — desktop's seeding precedent), row tap → `onOpenDetail(symbol)` (the shared `detail/{symbol}` route already carries Buy).

**Phone layout (Swift `Sources/APTradeApp/ScreenerView.swift` NARROW variant is the layout reference — layout only, no code transcription):** compact rows — symbol + company name stacked left; price, day-change, and the ONE active-metric column right (the narrow `activeMetricColumn` concept: preset screens surface their signature metric, custom screens the first condition's metric — read which metric from the Swift narrow overloads, render via the desktop VM's existing `ScreenerSortColumn.ActiveMetric` support). Sort control: compact tappable header strip over the list (Symbol / Price / Chg / metric label) instead of desktop's wide table header. Single-column vertical scroll under the scan bar; `LazyColumn` with `items(key = { it.symbol })` (T1-minor resolution precedent from M9.2 T7).

**Lifecycle:** `DisposableEffect(Unit) { onDispose { viewModel.cancelScan() } }` — the androidx VM outlives tab switches (scoped to the shell back-stack entry), but scan cancellation on tab-away matches the iPhone (R4) and desktop behavior twins. Comment this: the VM survives, the scan does not.

**Interfaces:**
- Consumes: Task 2's `ScreenerViewModel` (constructed in-screen via `viewModel { }` from AppGraph pieces), `ShellTab`, the `detail/{symbol}` route.
- Produces: `ScreenerScreen(padding: PaddingValues, onOpenDetail: (String) -> Unit)`; chips row exposes `onNewScreen: () -> Unit` / `onEditScreen: (CustomScreen) -> Unit` seams for Task 4.

- [ ] **Step 1: Implement screen + tab + routing.** UI composition — no new unit tests (standing waiver); VM behavior already pinned by Task 2.
- [ ] **Step 2: Gates:** full android suite PASS; `./gradlew :androidApp:assembleDebug` clean.
- [ ] **Step 3: Commit** `feat(android): Screener tab — presets, scan bar, sortable results, watchlist stars`

---

### Task 4: Android screen builder sheet

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/screener/ScreenBuilderModel.kt` (the model class extracted from desktop's `ScreenBuilderDialog.kt:80-…` — model only, byte-faithful; keep the Compose `mutableStateOf`-backed draft-var design and the injected `decimalSeparator: Char` defaulting from the JVM locale)
- Create: `androidApp/src/main/kotlin/com/aptrade/android/screener/ScreenBuilderSheet.kt` (UI: `ModalBottomSheet` per the `plans/PieWizardSheet.kt` precedent — NOT a desktop dialog; New/Edit title switch, condition list with metric `DropdownMenu` (Global Constraint 8: same Material3 shape as desktop), live match count via `viewModel.matchCount(...)`, delete behind a destructive confirm per the house confirm-dialog precedent)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/screener/ScreenerScreen.kt` (wire "+" chip → new sheet, ✎ on saved chips → edit sheet pre-filled)
- Create: `androidApp/src/test/kotlin/com/aptrade/android/screener/ScreenBuilderModelTest.kt`

**Desktop reference:** `desktopApp/.../screener/ScreenBuilderDialog.kt` (model) + ALL 22 tests in `desktopApp/.../screener/ScreenBuilderModelTest.kt` — transcribe from Kotlin (carry-note 5), byte-equal fixtures: validation, id preservation on edit, locale-gated comma parsing via the injected separator.

**Interfaces:**
- Consumes: Task 2's `saveScreen`/`deleteScreen`/`matchCount`, Task 3's `onNewScreen`/`onEditScreen` seams, shared `CustomScreen`/`ScreenCondition`.
- Produces: complete custom-screen CRUD on Android.

- [ ] **Step 1: Failing tests** — 22 transcribed name-for-name. Run: `./gradlew :androidApp:testDebugUnitTest --tests "*ScreenBuilderModel*"` — FAILS.
- [ ] **Step 2: Implement model, GREEN filtered; then sheet + screen wiring.** Full android suite PASS; `assembleDebug` clean.
- [ ] **Step 3: Commit** `feat(android): custom screen builder sheet with live match count`

---

### Task 5: README + cross-platform gates + M9 close-out

**Files:**
- Modify: `README.md` (Android shipped in the Screener parity line; PRUNE M9 from the Roadmap section — milestone complete, per the roadmap close-out house rule; observed test counts)

- [ ] **Step 1: README.**
- [ ] **Step 2: Gates:** `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest` ALL green; `./gradlew :androidApp:assembleDebug` clean; `DEVELOPER_DIR=/Applications/Xcode.app swift test` 560/560 (belt-and-braces — commonMain untouched this increment, jvmCommonMain move cannot reach the xcframework; no `build-shared.sh` rebuild needed, note this in the ledger). Any failure → BLOCKED with verbatim capture.
- [ ] **Step 3: Commit** `feat: M9.3 close-out — screener on Android, README; M9 complete`

---

## Self-Review Notes

- Spec coverage vs the 8 carry-notes: (1) Tasks 2–4 write only androidApp + the explicit Task 1 shared move; (2) Global Constraint 2 + Task 2's divergence block; (3) Task 1; (4) Task 2 re-earns the checklist via the 22 transcribed tests; (5) named in Global Constraints and both test-transcribing tasks; (6) no Android-side migration anywhere; (7) Task 3 copies the `when` shape; (8) Task 4 mirrors the desktop dropdown.
- Type consistency: `ScreenerViewModel` public surface (T2) → consumed by T3/T4 by those exact names; `onNewScreen`/`onEditScreen` seams (T3) → wired in T4; `SP500Names` (T1) → T2's AppGraph wiring.
- Not in scope by design: mid-detail tab-carry (impossible on Android — detail is a push route covering the shell); Material3 dropdown theming changes (UAT-gated, applies to both platforms when the verdict lands); store I/O fault policy (house-wide follow-up chip, unchanged).
- Merge/push at close: merge `feature/m9-3-screener-android` → main after the final whole-branch review, then push all repos (post-M9 arc complete per the session plan).
