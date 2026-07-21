# M9.2 — Technical Screener: Kotlin shared core + Windows desktop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transcribe the M9.1 screener to the Kotlin shared core and ship it on the Windows desktop app — fifth AppTab with on-demand throttled S&P 500 scan, daily snapshot cache, 9 presets, and the custom screen builder.

**Architecture:** The Swift M9.1 as-built on `main` is byte-authoritative — transcribe semantics AND every regression test from the named Swift files. Domain math + engine + stores land in `shared` (commonMain / jvmCommonMain) so M9.3 Android reuses them; desktop adds the ViewModel, the ScreenerPane, and the builder dialog. Two mandated engine improvements (below) are recorded divergences.

**Tech Stack:** Kotlin Multiplatform (ionspin bignum only where Money appears — indicator math is Double, matching both stacks' TechnicalIndicators), Compose Desktop, JUnit.

## Global Constraints

- Swift reference on `main` at c6427a3. Transcribe from AS-BUILT files; each task names its references. Every M9.1 review-earned convention MUST survive transcription:
  - `relativeVolume` 20-day window INCLUDES today (the Swift source names the wrong alternative — keep that comment style).
  - Cross flags from n−2/n−1 indicator pairs, both non-nil else false; snapshot nil only on empty candles; flat-price Bollinger: %B nil, bandwidth 0.0.
  - Strict preset boundaries (rsi==30 does NOT match); metric mapping proven by a 10-distinct-values anti-transposition test.
  - Bandwidth/%B are RAW fractions EVERYWHERE (columns, presets, builder) — comments at the display sites say M9.2 must preserve; honor them.
  - VM selection re-sync rule (saveScreen selects when active OR new; deleteScreen falls back to the default preset); nil-metric rows sort LAST both directions; offline/total-failure scan keeps the previous snapshot and shows the failed state; scan task is VM-owned and cancellable (pane teardown cancels; CancellationException → idle, nothing persisted); locale-gated comma-decimal parsing via an injected separator.
- **Mandated engine improvements (recorded divergences from Swift, each with a named comment + test):**
  1. Rate-limited batch retry re-fetches ONLY the still-failed symbols (Swift refetches the whole batch and can flip an attempt-1 success to failure — both problems close).
  2. After 3 CONSECUTIVE rate-limited batches (post-retry), abort the scan as a failure (Swift crawls on for ~6 min of doubled load under a hard limit).
- Throttle constants: batchSize 4, interBatchDelayMs 150, rateLimitBackoffMs 2000 — same as Swift; injected delay seam (suspend lambda) is the only sleeping mechanism.
- Kotlin idioms: engine/tests per PieContributionUseCases/ProcessDueDividends conventions; CancellationException always rethrown; NO portfolio mutex involvement (screener is read-only market data); file stores in shared/jvmCommonMain beside FilePortfolioStore (desktop + android reuse), no-overwrite-on-corrupt with byte-equality tests, absent-key-tolerant DTOs with hand-written legacy JSON.
- L10n: shared catalog; transcribe the POST-FIX rows (DE "Filter" noun, "MACD Aufwärtskreuzung"/"Abwärtskreuzung", IT "data di stacco"-register discipline); %@→%s identical argument order; count assertion updated.
- Test commands: `./gradlew :shared:jvmTest` (baseline 538), `:desktopApp:test` (295), `:androidApp:testDebugUnitTest` (221 — untouched, compile-surface gate only); JUnit XML is the count authority. Final task: `./scripts/build-shared.sh` (10-min timeout, native-compile gate for commonMain changes) + `DEVELOPER_DIR=/Applications/Xcode.app swift test` (560) against the rebuilt xcframework.
- Commit per task, conventional messages, explicit paths (NEVER `git add -A`). No pushes.

---

### Task 1: Kotlin ScreenerMath

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/ScreenerMath.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/ScreenerMathTest.kt`

**Swift reference:** `Sources/APTradeDomain/ScreenerMath.swift` + all 10 tests in `Tests/APTradeDomainTests/ScreenerMathTests.swift`, byte-equal fixtures. `ScreenerSnapshotRow` (kotlinx-serialization @Serializable data class, all metrics `Double?`, four Boolean flags, `symbol`/`name`/`close`), `ScreenerSnapshot(tradingDay, scannedAtEpochSeconds, rows, failedSymbols)`, `ScreenerMath.snapshot(symbol, name, candles): ScreenerSnapshotRow?` over the existing Kotlin `TechnicalIndicators` (do NOT reimplement indicators). Closes/volumes as `List<Double>` from `Candle` (`close.amount.doubleValue(false)` — the existing chart-consumer convention).

- [ ] **Step 1: Failing tests** (10 transcribed name-for-name). Run: `./gradlew :shared:jvmTest --tests "*ScreenerMath*"` — FAILS.
- [ ] **Step 2: Implement.** Filtered PASS; full shared PASS.
- [ ] **Step 3: Commit** `feat(shared): ScreenerMath — per-symbol technical snapshot with cross flags`

---

### Task 2: Kotlin screen models + presets

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Screener.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/ScreenerTest.kt`

**Swift reference:** `Sources/APTradeDomain/Screener.swift` + all 21 tests in `Tests/APTradeDomainTests/ScreenerTests.swift`. `ScreenerMetric` (10 cases, `value(row): Double?`), `ScreenComparison`, `ScreenCondition.matches` (null → false, strict < / >), `CustomScreen` (@Serializable; AND; empty = matches nothing), `PresetScreen` (9, exact thresholds), `ScreenSelection` sealed class with the single `evaluate` door.

- [ ] **Step 1: Failing tests** incl. the strict-boundary pairs and the 10-distinct-values mapping test. RED.
- [ ] **Step 2: Implement.** Filtered + full shared PASS.
- [ ] **Step 3: Commit** `feat(shared): screen model, AND evaluation, 9 preset screens`

---

### Task 3: Kotlin ScreenerScanEngine + ports

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/ScreenerUseCases.kt` (engine + `ScreenerSnapshotStore`/`ScreenStore` interfaces)
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/ScreenerScanEngineTest.kt`

**Swift reference:** `Sources/APTradeApplication/ScreenerUseCases.swift` + all 8 tests in `Tests/APTradeApplicationTests/ScreenerScanEngineTests.swift` (semaphore/`Channel`-based concurrency high-water counter replacing Swift's actor; injected `delay: suspend (Int) -> Unit` seam). Rate-limit case = `QuoteError.RateLimited`. Then the two MANDATED improvements with their own tests: (k) retry fetches only the still-failed symbols of the batch (spy asserts succeeded symbols fetched ONCE; attempt-1 successes survive a degrading retry); (l) 3 consecutive rate-limited batches → engine throws a domain failure (define `ScreenerScanAborted` exception or result flag — mirror how the VM will consume it; document) and nothing is returned. Deterministic row order via symbols-walk; `ensureActive()` between batches.

- [ ] **Step 1: Failing tests** (8 transcribed + k + l). RED.
- [ ] **Step 2: Implement.** Filtered + full shared PASS.
- [ ] **Step 3: Commit** `feat(shared): ScreenerScanEngine — throttled batches, targeted retry, 429 abort`

---

### Task 4: Kotlin stores

**Files:**
- Create: `shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/FileScreenerSnapshotStore.kt`, `FileScreenStore.kt`
- Test: `shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/ScreenerStoresTest.kt`

**Swift reference:** the two Swift stores + `Tests/APTradeInfrastructureTests/ScreenerStoresTests.swift` (6 tests), adapted to the FilePortfolioStore/FilePieStore house pattern (injected directory, `screener-snapshot.json` / `screens.json`, corrupt → null/empty WITHOUT overwriting — byte-equality; absent-key-tolerant DTOs with hand-written legacy JSON missing one nullable metric).

- [ ] **Step 1: Failing tests.** RED. **Step 2: Implement.** Filtered + full shared PASS. **Step 3: Commit** `feat(shared): file-backed screener snapshot + screen stores`

---

### Task 5: Shared L10n keys

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/l10n/L10n.kt` (+ count assertion in its test)

Transcribe the M9.1 key set ×4 from `Sources/APTradeApp/L10n.swift` POST-FIX rows: the ~37 screener keys (tab, scan bar incl. the 4 Fmt keys converted %@→%s same order, 9 presets incl. DE Aufwärts-/Abwärtskreuzung, 10 metrics, builder incl. screenerEditScreen, addToWatchlist if absent — grep first; `refresh`/`byClass` already exist in the Kotlin catalog — verify, reuse, do not duplicate).

- [ ] **Step 1:** Keys + rows; count assertion RED→GREEN; `./gradlew :shared:jvmTest :desktopApp:test` PASS.
- [ ] **Step 2: Commit** `feat(desktop): screener L10n keys (EN/DE/IT/ES)`

---

### Task 6: Desktop ScreenerViewModel

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/screener/ScreenerViewModel.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/screener/ScreenerViewModelTest.kt`

**Swift reference:** `Sources/APTradeApp/ScreenerViewModel.swift` AS-BUILT (incl. the final-review fix wave: VM-owned cancellable scan Job with ownership token, offline-total-failure → failed-state + previous snapshot KEPT, selection re-sync rule, nil-last both directions, isSnapshotFresh) + all 22 tests in `Tests/APTradeAppTests/ScreenerViewModelTests.swift`. Desktop idioms: `MutableStateFlow<State>` + injected `CoroutineScope` (PlansViewModel/IncomeViewModel shape — scope-launched methods, `runCurrent()` test driving). `scanState` sealed class (Idle/Scanning(done,total)/Failed). The Task-3 abort failure maps to the Failed state.

- [ ] **Step 1: Failing tests** (22 transcribed). RED. **Step 2: Implement.** Filtered + full `:desktopApp:test` PASS. **Step 3: Commit** `feat(desktop): ScreenerViewModel — scan orchestration, selection, sorting, builder support`

---

### Task 7: Desktop ScreenerPane — fifth AppTab

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/screener/ScreenerPane.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/ui/AppShell.kt` (`AppTab` gains `Screener` after `Calendar`; label `tr(L10n.Key.ScreenerTab)`), `Main.kt` (route the tab), `AppGraph.kt` (VM factory: engine on the graph's market repository, both file stores, `SP500Symbols` + desktop's SP500Names table)

**Swift reference:** `Sources/APTradeApp/ScreenerView.swift` AS-BUILT (chips row incl. saved-screen chips + "+"; 4-state scan bar with freshness-gated idle icon/label, progress with counts, failed + retry, failed-symbols note; sortable columns — desktop is always wide: full column set, header-click sort toggling, nil-last; 3 empty states incl. no-CTA-under-progress; row click → DetailPane navigation per the desktop pattern; per-row add-to-watchlist seeded from the real watchlist store). Compose idioms per IncomePane/PlansPane (DK tokens, tr(), VM lifecycle with pane-teardown scan cancellation — `DisposableEffect onDispose { vm.cancelScan() }`).

- [ ] **Step 1: Implement pane + tab + wiring.** **Step 2:** `:desktopApp:test` full PASS; `./gradlew :desktopApp:run` boots with the Screener tab (kill after). **Step 3: Commit** `feat(desktop): Screener tab — presets, scan bar, sortable results`

---

### Task 8: Desktop builder dialog

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/screener/ScreenBuilderDialog.kt`
- Modify: `ScreenerPane.kt` ("+" chip + right-click/context edit on saved chips), test file (builder-model tests)

**Swift reference:** `Sources/APTradeApp/ScreenBuilderSheet.swift` AS-BUILT + all 22 `ScreenBuilderModelTests` (validation, id preservation, locale-gated comma parsing via injected separator — Kotlin: injected `decimalSeparator: Char` defaulting from the JVM locale). Dialog per PieWizardDialog idioms; delete behind a destructive confirm; live match count; edit pre-fill; title switches New/Edit.

- [ ] **Step 1: Failing builder-model tests** (22 transcribed). RED. **Step 2: Implement.** Filtered + full `:desktopApp:test` PASS. **Step 3: Commit** `feat(desktop): custom screen builder dialog with live match count`

---

### Task 9: README + cross-platform gates

**Files:**
- Modify: `README.md` (Windows shipped in the Screener parity line; M9 stays in roadmap; observed counts)

- [ ] **Step 1: README.**
- [ ] **Step 2: Gates:** `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest` ALL green (android 221 untouched); `./scripts/build-shared.sh` (native gate, 10-min timeout); `DEVELOPER_DIR=/Applications/Xcode.app swift test` — 560/560 vs the rebuilt xcframework. Any failure → BLOCKED with verbatim capture.
- [ ] **Step 3: Commit** `feat: M9.2 close-out — screener on Windows desktop, README`

---

## Self-Review Notes

- Spec coverage: math/models/engine/stores shared for M9.3 reuse (T1–T4), L10n (T5), VM incl. every M9.1 review-earned rule (T6), pane + builder (T7–T8), gates (T9). The two mandated engine improvements are the only semantic divergences; both tested and comment-named.
- Type consistency: ScreenerSnapshot(Row) T1 → T3/T4/T6; ScreenSelection T2 → T6/T7; store interfaces T3 → T4 → AppGraph T7; abort failure T3 → Failed state T6.
- No portfolio-mutex involvement anywhere; no xcframework consumer changes (Swift never calls the Kotlin screener) — the native gate is purely the commonMain compile check.
