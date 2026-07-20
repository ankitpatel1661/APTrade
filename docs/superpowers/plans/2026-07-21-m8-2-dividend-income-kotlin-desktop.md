# M8.2 — Dividend & Income Engine: Kotlin shared core + Windows desktop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transcribe the M8.1 dividend & income engine to the Kotlin shared core and ship the full feature set on the Windows desktop app — automatic crediting with backfill, DRIP, Income pane, asset-detail dividend card, settings toggles, notifications, export rows.

**Architecture:** The Swift M8.1 as-built on `main` is the byte-authoritative reference — transcribe semantics (and every regression test) from the named Swift files, not from the spec alone. Domain math + the crediting engine land in `shared` (commonMain) so M8.3 Android reuses them; desktop adds ViewModels, Compose panes, coordinator/tray wiring, and file-store DTO evolution. The Yahoo dividend fetch (`dividendEvents`, `FetchDividendEvents`, `DividendEvent`) ALREADY exists in shared from M8.1 Tasks 1–2 — do not re-create it.

**Tech Stack:** Kotlin Multiplatform (ionspin bignum, kotlinx-serialization), Compose Desktop, JUnit (`./gradlew :shared:jvmTest`, `:desktopApp:test`).

## Global Constraints

- Swift reference on `main` at 0dc9e7a. Transcribe from AS-BUILT Swift files (each task names its reference); where a carry-note below prescribes an improvement, the carry-note wins over byte-fidelity and the divergence is recorded in code comments.
- **Carry-note improvements (from the M8.1 final review — do NOT transcribe these Swift inefficiencies verbatim):**
  1. Out-of-lock dedup pre-filter: candidate events already credited in the snapshot ledger are skipped BEFORE acquiring the mutex (in-lock re-check stays authoritative).
  2. DRIP quantity epsilon: clamp the buy so its cost can never exceed the credited cash (`quantity = cash/close` floored to the credit, no `InsufficientFunds` on an ulp).
  3. DRIP asset-kind fallback: if the position vanished between snapshot and lock, credit CASH — never fabricate an `Asset(kind = Stock)`.
  4. Launch catch-up marks `lastDividendDay`, so the same day's tick does not re-run the full Yahoo sweep.
  5. No per-call date-formatter allocation in month-key derivation.
- Money/decimal: ionspin `BigDecimal` with the existing `MONEY_MATH` DecimalMode; no Double round-trips of money; `BigDecimal.parseString` in tests.
- Concurrency: every portfolio mutation holds THE app-graph portfolio mutex (10th co-holder — update the co-holder doc lists on `BuyAsset.kt`/`SellAsset.kt` and peers); network fetches outside the lock; fresh reload + re-dedup inside the lock per event.
- Dedup key: symbol + `MarketCalendar.tradingDay(epochSeconds)` vs existing `Dividend` transactions — sole idempotency mechanism.
- Backfill: `dividendsFirstRunDay` persisted BEFORE processing; ex-day `< firstRunDay` → always cash; `>=` → live DRIP toggle at processing time. Reset-portfolio × stale marker semantics MUST be pinned by the transcribed regression test (Swift commit ef93d17).
- Notifications: crediting ungated; notification gated by `dividendNotifications`; backfill outcomes collapse into ONE summary notification (Swift commit 96daf8f).
- L10n: Kotlin catalog uses `%s` (+ `trf()` shim conventions); EN byte-equal to Swift raw values; DE/IT/ES copied from the Swift catalog (post-fix versions: "Jährliche Dividende", Erträge-based income nouns, "ca." badge, "data di stacco (stima)", "fecha ex-dividendo (est.)"); update the catalog count assertion.
- Desktop Income UI ships the UAT-polish look FROM THE START: Upcoming + Income-by-Holding side by side below the monthly chart; projected bars dashed-outline + faint fill vs solid received.
- Crypto (`AssetKind.Crypto`) excluded from every engine/UI dividend path.
- Test commands: `./gradlew :shared:jvmTest` (baseline 489) and `./gradlew :desktopApp:test` (baseline 267); JUnit XML is the count authority. Any shared/commonMain change makes `./scripts/build-shared.sh` (10-min timeout) + `DEVELOPER_DIR=/Applications/Xcode.app swift test` (baseline 469) MANDATORY gates in the final task (native-compile + macOS regression).
- Commit per task, conventional messages, explicit paths (NEVER `git add -A` — untracked `Screenshots/` is the user's). No pushes.

---

### Task 1: Shared domain — dividend transactions

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Trade.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Portfolio.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PortfolioAnalytics.kt` (the `when (transaction.side)` at ~:23)
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/BenchmarkTwin.kt` (the `when (txn.side)` at ~:72 — this is the Kotlin twin of the Swift equity-curve reconstruction bug caught in M8.1 T3 review)
- Modify: `shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/FilePortfolioStore.kt` (TransactionDTO)
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PortfolioDividendTest.kt` (new), `BenchmarkTwin` test file (extend), `shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/FilePortfolioStoreTest.kt` (extend)

**Swift reference:** `Sources/APTradeDomain/Trade.swift`, `Portfolio.swift` (`receivingDividend`, `buying(isDrip:)`), `PortfolioEquityCurve.swift` (exhaustive-switch fix + its regression test in `Tests/APTradeDomainTests/PortfolioEquityCurveTests.swift`), `Tests/APTradeDomainTests/PortfolioDividendTests.swift` (all 7 tests).

**Interfaces — produces:**

```kotlin
enum class TradeSide { Buy, Sell, Dividend }
// Transaction gains: val isDrip: Boolean = false   (last field)
// Portfolio gains:
/** Credits a dividend: cash += shares × amountPerShare, appends a Dividend txn
 *  (quantity = shares, price = amountPerShare, epochSeconds = exDate). Positions
 *  and cost basis untouched. Throws TradeError.InvalidQuantity when shares <= 0
 *  or amountPerShare.amount <= 0. */
fun receivingDividend(id: String, symbol: String, amountPerShare: Money,
                      shares: BigDecimal, exDateEpochSeconds: Long): Portfolio
// buying(...) gains trailing `isDrip: Boolean = false`, forwarded into its Transaction.
```

Semantics per Global Constraints: `Dividend` changes cash only. Sweep every `when`/ternary over `TradeSide`: `PortfolioAnalytics` (Dividend excluded from realized P&L), `BenchmarkTwin` (Dividend: units unchanged, cash-flow handled exactly as the Swift exhaustive switch does — transcribe its regression test: buy then dividend → reconstructed units unchanged, valuation includes the credit). `FilePortfolioStore.TransactionDTO` gains `val isDrip: Boolean = false` (absent-key tolerant — hand-written legacy JSON literal test, M7.2 T5 pattern; encode follows the store's existing default-omission convention). Desktop/`Main.kt` and `TrayNotifier` `when`s that stop compiling get MINIMAL neutral handling here (real work in Tasks 8–10); Kotlin non-exhaustive `when` STATEMENTS only warn — grep is mandatory, do not rely on the compiler: `grep -rn "TradeSide\." shared desktopApp --include="*.kt"` and handle every consumer site.

- [ ] **Step 1: Failing tests** — transcribe all 7 `PortfolioDividendTests` (credit math, InvalidQuantity guards, isDrip forwarded, DTO legacy decode, Dividend round-trip) + the BenchmarkTwin buy-then-dividend regression + FilePortfolioStore legacy-JSON test. Run: `./gradlew :shared:jvmTest --tests "*Dividend*" --tests "*Benchmark*"` — FAILS.
- [ ] **Step 2: Implement.** Run filtered — PASS; full `./gradlew :shared:jvmTest` — PASS.
- [ ] **Step 3: Commit** `feat(shared): dividend transactions — TradeSide.Dividend, isDrip, receivingDividend`

---

### Task 2: Shared domain — DividendMath

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/DividendMath.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/DividendMathTest.kt`

**Swift reference:** `Sources/APTradeDomain/DividendMath.swift` + all 15 tests in `Tests/APTradeDomainTests/DividendMathTests.swift` (14 original + monthly aggregation extras), transcribed name-for-name with byte-equal fixture values.

**Interfaces — produces:**

```kotlin
enum class DividendCadence { Monthly, Quarterly, SemiAnnual, Annual }
object DividendMath {
    /** Shares held STRICTLY BEFORE exDate (txn.epochSeconds < exDateEpochSeconds);
     *  Dividend txns contribute nothing; DRIP buys count like any buy. */
    fun sharesHeld(symbol: String, atEpochSeconds: Long, transactions: List<Transaction>): BigDecimal
    /** Sum of event amounts with exDate in (asOf − 365d, asOf] — −365d exact excluded, asOf included. */
    fun trailingAnnualPerShare(events: List<DividendEvent>, asOfEpochSeconds: Long): Money
    /** Median gap buckets (days): ≤45 Monthly, ≤135 Quarterly, ≤270 SemiAnnual, else Annual; null when <2 events. */
    fun inferredCadence(events: List<DividendEvent>): DividendCadence?
    /** last exDate + (30/91/182/365)d, amount = last amount; null when cadence null.
     *  NOTE: no now-awareness — CALLERS filter stale projections (> asOf), exactly as
     *  IncomeViewModel.swift and AssetDetailViewModel.swift do (M8.1 review precedent). */
    fun nextProjected(events: List<DividendEvent>): DividendEvent?
    /** Received dividend cash per UTC "yyyy-MM" month from Dividend txns only.
     *  Month key via epoch-day civil math (PieSchedule's helper family) — carry-note 5:
     *  NO per-call formatter/clock objects. */
    fun monthlyReceived(transactions: List<Transaction>): Map<String, Money>
    fun projectedAnnualIncome(positions: List<Position>, eventsBySymbol: Map<String, List<DividendEvent>>,
                              asOfEpochSeconds: Long): Money
}
```

- [ ] **Step 1: Failing tests** (all Swift fixtures byte-value-equal: 0.70 window sum, "2026-07" → 9.0 bucket, 10.0 projected income, strictly-before edge, ±365d boundary pair, cadence buckets, DRIP-buys-count). Run: `./gradlew :shared:jvmTest --tests "*DividendMath*"` — FAILS.
- [ ] **Step 2: Implement.** Filtered PASS; full shared suite PASS.
- [ ] **Step 3: Commit** `feat(shared): DividendMath — held-shares, trailing rate, cadence projection`

---

### Task 3: Shared application — planner event, scheduler state, settings

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/application/MarketActivityPlanner.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/settings/AppSettings.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/FileSchedulerStateStore.kt` + `FileSettingsStore.kt` (DTO fields, absent-key tolerant)
- Test: planner test file (extend), both file-store test files (extend, hand-written legacy JSON)

**Swift reference:** `Sources/APTradeApplication/MarketActivityPlanner.swift` (the UNGATED dividend block + `SchedulerState` fields), `Sources/APTradeDomain/AppSettings.swift`.

**Interfaces — produces:**

```kotlin
// ScheduledNotification gains: DividendCheckDue
// SchedulerState gains: val lastDividendDay: String? = null, val dividendsFirstRunDay: String? = null
// plan(...) gains NO new enable-flag for dividends — the block is UNGATED (crediting is
// bookkeeping truth): fires once per trading day at market-open via lastDividendDay,
// mirroring the contribution block's structure minus its settings gate.
// AppSettings gains: val dripEnabled: Boolean = false, val dividendNotifications: Boolean = true
```

- [ ] **Step 1: Failing tests** — (a) fires once on first open tick, not again that day; (b) fires with every enable-flag false; (c) closed-market suppression; (d) legacy settings/scheduler JSON decodes to defaults (hand-written literals). Run filtered — FAILS.
- [ ] **Step 2: Implement.** Filtered PASS; `./gradlew :shared:jvmTest :desktopApp:test` — PASS.
- [ ] **Step 3: Commit** `feat(shared): DividendCheckDue planner event + DRIP/notification settings`

---

### Task 4: Shared application — ProcessDueDividends engine

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/ProcessDueDividends.kt`
- Modify: co-holder doc lists on `BuyAsset.kt`, `SellAsset.kt` (and the other mutex co-holders' doc comments — now 10 co-holders)
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/ProcessDueDividendsTest.kt`

**Swift reference:** `Sources/APTradeApplication/DividendUseCases.swift` AS-BUILT (incl. `isBackfill` on both outcome cases) + all 10 tests in `Tests/APTradeApplicationTests/ProcessDueDividendsTests.swift` (9 scenarios a–i + the reset×stale-marker regression ef93d17 — MANDATORY transcription). Apply carry-notes 1–4 as recorded divergences (code comments name them).

**Interfaces — produces:**

```kotlin
sealed class DividendOutcome {
    data class Credited(val symbol: String, val cash: Money, val isBackfill: Boolean) : DividendOutcome()
    data class Reinvested(val symbol: String, val cash: Money, val shares: BigDecimal,
                          val isBackfill: Boolean) : DividendOutcome()
}
class ProcessDueDividends(
    private val portfolioStore: PortfolioStore,
    private val market: MarketDataRepository,        // dividendEvents + history for DRIP closes
    private val stateStore: SchedulerStateStore,
    private val calendar: MarketCalendar,
    private val portfolioMutex: Mutex,               // THE app-graph mutex
    private val isDripEnabled: () -> Boolean,
) {
    /** Never throws: per-symbol failures degrade silently; saved events stay saved. */
    suspend fun execute(nowEpochSeconds: Long): List<DividendOutcome>
}
```

Semantics = the 7 M8.1 rules (first-run marker before processing; non-crypto positions; `since` = symbol's earliest txn; strictly-before eligibility; symbol+trading-day dedup — pre-filtered out of lock per carry-note 1, re-checked in lock; per-event `mutex.withLock` with fresh reload; DRIP at ex-day close from a `fetchClosesByDay`-style helper replicated locally from `PieContributionUseCases.kt`, cost clamped per carry-note 2, cash-fallback on missing close AND on vanished position per carry-note 3; backfill always cash).

- [ ] **Step 1: Failing tests** — all 10 transcribed with byte-equal fixtures (backfill-despite-DRIP, DRIP fractional 0.25-share exactness, dedup replay, ex-date-buy earns nothing, sells reduce, close-missing fallback, crypto ignored, symbol-A-fails-B-processes, DRIP-off cash, reset×stale-marker) + 2 carry-note probes: (k) pre-filter: second run acquires the mutex ZERO times for already-credited events (count via a spy mutex or store-load counter); (l) vanished-position: position removed between snapshot and lock → cash credit, no fabricated buy. Run: `./gradlew :shared:jvmTest --tests "*ProcessDueDividends*"` — FAILS.
- [ ] **Step 2: Implement.** Filtered PASS; full shared suite PASS.
- [ ] **Step 3: Commit** `feat(shared): ProcessDueDividends — backfill, DRIP, ledger-dedup crediting`

---

### Task 5: Desktop L10n keys

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/l10n/L10n.kt` (or wherever the desktop catalog lives — follow M7.2 T10's commit 47b4dd9 as the template)
- Test: the catalog count/completeness test (update expected count)

All M8.1 keys ×4 languages: the 23 Task-8 keys + `settingsDividendNotifSubtitle` + `notifDividendBackfillBodyFmt` + `activityDividend` ("DIVIDEND" chip — uppercase-by-design, localized DIVIDENDE/DIVIDENDO/DIVIDENDO) + the 2 export row labels if the desktop renderer localizes (check `PdfPortfolioRenderer`'s existing label mechanism first; Swift renderer hardcodes EN — follow whichever convention the KOTLIN renderer already uses). EN byte-equal to the Swift catalog's post-fix raw values; format keys converted `%@`→`%s` with identical argument ORDER.

- [ ] **Step 1:** Add keys; count-assertion RED→GREEN gates the change. `./gradlew :desktopApp:test` — PASS.
- [ ] **Step 2: Commit** `feat(desktop): dividend & income L10n keys (EN/DE/IT/ES)`

---

### Task 6: Desktop IncomeViewModel

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/income/IncomeViewModel.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/income/IncomeViewModelTest.kt`

**Swift reference:** `Sources/APTradeApp/IncomeViewModel.swift` AS-BUILT (incl. the `buildUpcoming` stale-projection guard `projected.exDate > asOf`) + all 6 tests in `Tests/APTradeAppTests/IncomeViewModelTests.swift`. Desktop idioms: `MutableStateFlow` state + injected scope, exactly like `plans/PlansViewModel.kt` (NOT the Swift ObservableObject shape).

**Interfaces — produces:** a `State` data class carrying `cards: SummaryCards?`, `months: List<MonthBar>` (`isProjected`), `upcoming: List<UpcomingRow>`, `holdings: List<HoldingRow>`, `history: List<HistoryEntry>` (`wasReinvested`), `isLoading` — field names matching the Swift structs; `suspend fun load()`. Semantics: receivedYTD = UTC calendar year; wasReinvested pairing by symbol + trading day; quote fallback to cost basis; 730-day event lookback; per-symbol failure isolation; crypto excluded; projected months and upcoming rows filtered `> asOf`.

- [ ] **Step 1: Failing tests** (6 transcribed name-for-name, incl. the stale-projection regression). Run: `./gradlew :desktopApp:test --tests "*IncomeViewModel*"` — FAILS.
- [ ] **Step 2: Implement.** Filtered PASS; `:desktopApp:test` full — PASS.
- [ ] **Step 3: Commit** `feat(desktop): IncomeViewModel — cards, monthly bars, upcoming, history`

---

### Task 7: Desktop Income pane

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/income/IncomePane.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioPane.kt` (`PortfolioSection` gains `Income` after `Plans`; title `tr(L10n.Key.IncomeSection)`; render `IncomePane()`)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt` (VM wiring, PlansPane precedent)

**Swift reference:** `Sources/APTradeApp/IncomeSection.swift` AS-BUILT INCLUDING the UAT polish: 2×2 summary cards; monthly chart with SOLID gold received bars and DASHED-OUTLINE + 12%-alpha-fill projected bars (Compose: `drawRoundRect` with `Stroke(pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2f)))`) + matching legend swatch; **Upcoming and Income-by-Holding side by side** (`Row` of two equal-weight columns) below the chart; history feed with Reinvested badge; empty state. Desktop design system: `DK` tokens / `designkit` components, no hardcoded strings (the L10n sweep conventions from M7.2 T12 apply — declarative-only, verified by the same grep-script discipline).

- [ ] **Step 1: Implement pane + section case + wiring.**
- [ ] **Step 2: Verify:** `./gradlew :desktopApp:test` full — PASS (catalog sweep + existing suites); `./gradlew :desktopApp:run` boots and the Income section renders (visual check is the user's, boot check is yours — kill it after).
- [ ] **Step 3: Commit** `feat(desktop): Income pane — cards, monthly chart, side-by-side tables, history`

---

### Task 8: Desktop asset-detail dividend card

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/detail/DetailViewModel.kt`, `DetailPane.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/detail/DetailViewModelTest.kt` (extend)

**Swift reference:** `Sources/APTradeApp/AssetDetailViewModel.swift` AS-BUILT (`DividendInfo`, crypto-skip-without-fetch, nil-on-empty/failure, trailing-2y window, future-only `nextEstimatedExDate` guard from commit 8658792) + its 5 dividend tests; `AssetDetailView.swift` for the card (yield, annual rate, next est. ex-date + Est. badge, last-8 mini bar chart).

- [ ] **Step 1: Failing tests** (payer computes rate/yield; crypto never fetches — spy; empty → hidden; failure → hidden, other state intact; stale projection → date row hidden, card still shows). Run filtered — FAILS.
- [ ] **Step 2: Implement VM + card.** Filtered PASS; full `:desktopApp:test` PASS.
- [ ] **Step 3: Commit** `feat(desktop): dividend info card on asset detail`

---

### Task 9: Desktop settings toggles

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/ui/AccountPanel.kt`
- Test: settings panel test file if one exists; otherwise the settings-store round-trip test (extend)

**Swift reference:** M8.1 T12 — dividend-notifications toggle beside the Pie-contributions row (notifications group); DRIP toggle in the account/trading group with `SettingsDripFooter` subtitle. Persist through the existing `FileSettingsStore` path (fields exist since Task 3).

- [ ] **Step 1: Implement + round-trip test.** `./gradlew :desktopApp:test` — PASS.
- [ ] **Step 2: Commit** `feat(desktop): DRIP + dividend notification settings`

---

### Task 10: Desktop coordinator + tray wiring

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/DesktopMarketActivityCoordinator.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (wiring + the `DividendCheckDue` branch)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/TrayNotifier.kt` (`notifyDividend(title, body)`; ALSO convert the `:31` `if (side == TradeSide.Buy) "Bought" else "Sold"` ternary to an exhaustive `when` — the Kotlin twin of Swift fix 3c0ac79)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt` (ProcessDueDividends on THE mutex)
- Test: coordinator test file (extend)

**Swift reference:** `Sources/APTradeApp/MarketActivityCoordinator.swift` AS-BUILT incl. 96daf8f: launch catch-up UNGATED (contribution catch-up keeps its gate — verify relative structure); `DividendCheckDue` branch runs the engine; notification helper gated on `dividendNotifications`, per-outcome for live outcomes, ONE collapsed summary (`NotifDividendBackfillBodyFmt`, count + summed cash) for backfill outcomes; carry-note 4: the launch catch-up path marks `lastDividendDay` for today so the tick's planner event is consumed (RECORDED divergence from Swift — comment it).

- [ ] **Step 1: Failing tests** — (a) DividendCheckDue tick runs engine + notifies formatted cash/DRIP bodies; (b) notifications off → engine still runs, zero notifications; (c) launch catch-up once, ungated, before tick loop; (d) mixed 3-backfill+1-live → exactly 2 notifications; (e) carry-note 4: after launch catch-up, the same day's tick does not re-invoke the engine. Run filtered — FAILS.
- [ ] **Step 2: Implement.** Filtered PASS; full `:desktopApp:test` PASS.
- [ ] **Step 3: Commit** `feat(desktop): dividend check wired into coordinator + tray notifications`

---

### Task 11: Export fields + README + cross-platform gates

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PortfolioExport.kt` (+ its DTO at ~:165; absent-key-tolerant decode)
- Modify: the shared/desktop export use case (find the Kotlin builder of `PortfolioExport` — mirror Swift `ExportUseCases.swift`: `dividendsReceivedYTD` from the ledger's UTC-year Dividend txns; `projectedAnnualIncome` via `DividendMath.projectedAnnualIncome` with an optional events source defaulting to zero)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/PdfPortfolioRenderer.kt` (two summary rows, following how unrealized P&L renders)
- Modify: `README.md` (Windows column of the Dividend & Income parity table; suite counts)
- Test: export + renderer test files (extend)

- [ ] **Step 1: Failing tests** — hand-computable fixture (Swift's 25.00 / 5.00 / 7.50 values), nil-source → 0, renderer rows present, DTO legacy decode. Run filtered — FAILS, implement, PASS.
- [ ] **Step 2: Cross-platform gates** — full `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest` ALL PASS (android 195 must be untouched); then `./scripts/build-shared.sh` (10-min timeout — native-compile gate for every commonMain change this plan made); then `DEVELOPER_DIR=/Applications/Xcode.app swift test` — 469/469 (macOS regression against the rebuilt xcframework).
- [ ] **Step 3: Commit** `feat: desktop export income rows; README M8.2 close-out`

---

## Self-Review Notes

- **Spec coverage:** crediting engine + backfill + DRIP (T1/T4), planner/settings (T3), Income surfaces (T6/T7), asset detail (T8), toggles (T9), notifications incl. backfill collapse (T10), export (T11), L10n (T5), reset-wipes-free (same ledger), derived trailing rate (T2). Yahoo fetch pre-exists (M8.1 T1–2).
- **Both M8.1 review-caught bug twins are explicitly assigned:** BenchmarkTwin reconstruction (T1), TrayNotifier ternary (T10); stale-projection guards baked into T6/T8 via as-built references.
- **Carry-notes 1–5 all land:** 1–3 in T4, 4 in T10, 5 in T2 — each a recorded divergence.
- **Type consistency:** `DividendOutcome` (T4) consumed by T10; `DividendMath` (T2) by T4/T6/T8/T11; settings fields (T3) by T9/T10; `SchedulerState` fields (T3) by T4.
