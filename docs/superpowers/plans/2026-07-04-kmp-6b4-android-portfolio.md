# Plan: Increment 6b.4 — Android Portfolio screen

Branch: kmp-6b4-android off main @ (post-6b.3 head). Baselines: shared 165 /
android 13 / desktop 167 / Swift 200 / 3 slices. The Android app (increment 5:
quotes/search/detail) gains a Portfolio destination on the existing shared core.

## Global Constraints
- Money as amountText/BigDecimal end-to-end; Double only chart pixels/ratios;
  MONEY_MATH on any division (there should be none new — the shared core owns math).
- CancellationException-first; androidApp VM conventions (androidx ViewModel +
  viewModelScope + MutableStateFlow, rethrow-then-catch-domain-error — read
  QuotesViewModel.kt first).
- Suites --rerun-tasks XML-counted. No Swift/desktop behavior changes (desktop import
  updates allowed ONLY for the store relocation in Task 1).
- JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; ./gradlew.
- HARD RULE: implementers work directly; no Agent-tool delegation.
- Increment-5 gotchas apply at the E2E task: emulator aptrade_api35, port 5562 if
  BlueStacks collides, ANDROID_HOME=$HOME/Library/Android/sdk, JDK17 for sdk tools.

## Scope decisions (recorded)
- Phone-first subset of the desktop tab: summary header, span+benchmark dual-line
  performance chart (dashed twin + legend — NO hover scrubber on touch v1), holdings
  with tap → trade sheet (BUY/SELL), allocation BARS (no donut v1), activity with
  dates, reset with confirm, export CSV/JSON via the Android share sheet
  (ACTION_SEND, text payload v1 — no FileProvider config, no PDF on Android;
  RECORDED DIVERGENCES).
- Store: RELOCATE FilePortfolioStore from desktopApp into :shared so both JVM-family
  targets share one implementation (preferred); if the KMP intermediate-source-set
  wiring (a jvm+android shared group) fights the toolchain, FALL BACK to a verbatim
  androidApp copy (6a FakeMarketDataRepository waiver precedent) and flag it.

### Task 1: Portfolio store on Android + AppGraph wiring

1. PREFERRED: create an intermediate source set in :shared shared by the jvm and
   android targets (Kotlin 2.1 custom hierarchy group, e.g. `jvmCommon`), move
   desktopApp/.../infra/FilePortfolioStore.kt (+ its private DTOs) there (package
   com.aptrade.shared.infrastructure), update the desktop import (ONLY that), and
   verify: desktop 167 green, android compiles, Apple targets UNAFFECTED
   (compileKotlinMacosArm64 green — the source set must not join the Apple tree).
   FALLBACK (if >1h of toolchain fighting): copy the file verbatim into
   androidApp/.../infra/ and FLAG the duplication loudly.
2. androidApp AppGraph: portfolioStore = FilePortfolioStore(context.filesDir.toPath()
   .resolve("portfolio.json")) — AppGraph is an `object` today with no Context; adopt
   the minimal correct injection (e.g. an init(context) from MainActivity/Application
   BEFORE first use, or convert to a class held by the Application — read Main-
   Activity's current AppGraph usage and choose the smallest safe change; document).
   Wire: FetchPortfolio, ResetPortfolio, FetchPerformanceReport (execute(timeframe,
   benchmark, portfolio, riskFree)), quotes for held symbols via the existing
   repository. VERIFY whether shared BuyAsset/SellAsset use cases exist
   (grep shared/src/commonMain/kotlin/com/aptrade/shared/application/ — 6b.1 created
   them; the explorer could not find them — if absent, the VM calls
   Portfolio.buying/selling + store.save directly, mirroring whatever the DESKTOP
   PortfolioViewModel actually does — read it and match).
3. Tests: FakePortfolioStore (androidApp test, lambda-configurable like
   FakeMarketDataRepository); a store round-trip test on a temp dir if the relocation
   landed in :shared (jvmTest covers it — count grows there instead).
   Counts: measure; android likely +1-2.

### Task 2: Android PortfolioViewModel

androidApp .../portfolio/PortfolioViewModel.kt (+ test). Mirror the DESKTOP
PortfolioViewModel's semantics at phone scale — read it first
(desktopApp/.../portfolio/PortfolioViewModel.kt) and transcribe the load-bearing
contracts (raw-vs-formatted money fields, quote merge, one-shot report per
span/benchmark change, trade flow with tradeError, reset). State: summary
(totalValueText raw + formatted display fields — reuse the desktop's formatMoney?
NO — that's desktop designkit; Android formats with its own minimal helper or
java.text.NumberFormat en_US currency — pin by test, "$35,040.46" shape), holdings
rows, span (1D/1W/1M/1Y/MAX), benchmark (SPY/QQQ/VTI), performanceValues +
benchmarkTwinValues (Doubles for pixels), transactions with dateText ("MMM d, uuuu,
h:mm a" en_US — the desktop pattern), isLoading/error, tradeError. 15s quote poll
while the screen is resumed (viewModelScope + a start/stop from the screen lifecycle
— mirror QuotesViewModel's approach if it polls; if it doesn't, a simple
while(isActive) delay(15_000) loop started once). buy/sell(symbol, quantityText):
parse quantity (plain toBigDecimal-or-null), quote-first ordering, portfolio =
result, save via store (or shared use cases if they exist — Task 1's finding
governs), tradeError from TradeError.userMessage-equivalent (check what shared
exposes; desktop has userMessage mapping — Android may need its own small mapper).
exportCsv()/exportJson(): return the rendered string (PortfolioExport.from + render)
for the screen to share. Tests (≥8): summary math from fake store+quotes, span
refetch log, benchmark refetch, twin null path, buy success + insufficient funds
error, reset, dateText exact, poll tick merges quotes (advanceTimeBy).

### Task 3: Dual-line chart for Android

androidApp ui/chart/Charts.kt: add DualLineChart(primary: List<Double>, secondary:
List<Double>?, modifier, primaryColor, secondaryColor, secondaryDashed=true) —
shared min/max across both series (the 6c.5 hazard: per-series scaling silently
distorts the comparison — one min/max over the concatenation), dashed secondary
(PathEffect.dashPathEffect(8,6)-style), degenerate guards (size<2 → nothing).
Keep the existing LineChart untouched (detail screen uses it). A small composable
legend row (solid swatch + label, dashed swatch + label) beside it. Pure helper for
the normalization if extracted → test it; otherwise UI waiver applies.

### Task 4: PortfolioScreen UI + navigation

1. Route "portfolio" in the NavHost; entry: an icon/action on QuotesScreen's TopAppBar
   (next to the existing Search action — same idiom). Back = popBackStack.
2. Screen (LazyColumn root): summary header (large total value, day-change pill
   colored GainGreen/LossRed, cash/holdings/unrealized/realized row); span selector
   (SegmentedButton or the pill idiom from SearchScreen kind chips) + benchmark
   selector; DualLineChart (portfolio gold solid / twin dashed) + legend + the
   day-one/"Benchmark unavailable" empty texts (desktop's exact strings);
   holdings rows (symbol, name, qty@avgCost, market value, P&L colored) — tap opens
   TradeSheet (ModalBottomSheet: BUY/SELL toggle, quantity field, live price, Confirm
   gated on valid quantity, inline tradeError — mirror desktop TradeDialog semantics);
   allocation section (BY HOLDING/BY CLASS bars — percent-labeled rows, no donut v1);
   activity rows (side chip, symbol, dateText, amount, qty@price — the macOS/desktop
   anatomy); Reset row/button with an AlertDialog confirm ("Start over with
   $100,000?" — the desktop string); Export row → CSV/JSON choice → share sheet
   (Intent.ACTION_SEND, type text/csv | application/json, EXTRA_TEXT payload,
   startActivity chooser — a tiny helper beside the screen; RECORDED DIVERGENCE
   text-payload v1).
3. UI waiver: no composable tests. Compile + :androidApp:test green.

### Task 5: E2E + regression + docs

1. Emulator E2E (increment-5 Task 7 shape): boot aptrade_api35 (port workaround if
   needed), install debug APK, drive: open portfolio (empty → starting $100k), buy
   (e.g. AAPL 5) via the sheet, see holdings/summary update, span switch, benchmark
   switch (twin line renders), activity row with date, reset. Screenshots per
   increment-5 convention; logcat crash check (NO CRASHES). Emulator shut down after.
2. Full regression: all gradle suites --rerun-tasks XML-counted (shared/android/
   desktop — counts per T1-T4 measured), xcframework 3 slices + swift test 200 + iOS
   ARCHS=arm64 (only needed if :shared changed — the Task 1 relocation changes
   :shared → REQUIRED).
3. README: Android section gains the portfolio screen paragraph (features +
   divergences: share-sheet export, no PDF, no scrubber, bars-only allocation);
   roadmap: 6b.4 ships → prune ("Still to come" leaves only 6d). SKILL.md check.

- Coverage: store → T1; VM → T2; chart → T3; UI/nav → T4; proof/docs → T5.
- Known unknowns with guardrails: BuyAsset/SellAsset existence (T1 greps; desktop VM
  is the behavioral reference either way); AppGraph Context injection shape (T1 reads
  MainActivity and chooses minimal-safe); KMP intermediate source set (T1 has the
  explicit fallback); Android money formatting (T2 pins by test).

---

# AMENDMENT 2026-07-04 — Task 6: first-buy path (E2E finding)

Authority: Task 5's step-4 probe. A fresh Android portfolio has NO first-buy entry
point (TradeSheet opens only from holding rows; DetailScreen lacks the BUY that
macOS/desktop have). This guts the feature on first install — in-scope fix.

### Task 6: BUY/SELL from the Android DetailScreen

1. Extract TradeSheet (+ its success-detection/hasSubmitted logic) from
   PortfolioScreen.kt into portfolio/TradeSheet.kt, reused by both callers —
   behavior identical (the 29 tests + compile stay green).
2. DetailScreen gains a "BUY / SELL" action (single button, desktop parity idiom —
   TopAppBar action or a prominent button near the price; match the screen's
   existing Material3 idiom) opening the TradeSheet for the current symbol.
   The Asset: symbol from the route; name/kind from the detail state (read
   DetailViewModel — increment 5 loads a profile; fall back to Asset(symbol,
   symbol, Stock) ONLY if kind is genuinely absent, and flag it).
3. Trade wiring: the shared BuyAsset/SellAsset use cases are store-mediated
   (quote-first, load-fresh, save) — DetailScreen may call them via a minimal
   addition to DetailViewModel (buy/sell + tradeError + transactionCount for the
   sheet's success detection; CancellationException-first; mirror the
   PortfolioViewModel's trade methods — read them). Do NOT duplicate portfolio
   state into the detail VM beyond what the sheet needs.
4. STALENESS COHERENCE: verify PortfolioViewModel.start() RELOADS the portfolio
   when re-armed (nav to detail STOPs it, return STARTs it) so a trade made from
   detail appears on return. If start() does not reload today, make it reload
   (small VM change + a test pinning trade-from-elsewhere-appears-after-restart).
5. Tests: DetailViewModel trade additions (≥3: buy success, tradeError mapping,
   transactionCount increments); the start()-reload pin if changed.
6. Mini-E2E (focused, not the full script): fresh app data (adb pm clear), quotes →
   detail (AAPL) → BUY 1 via the sheet → back → portfolio shows the position.
   Screenshots (3-4); logcat zero-FATAL; emulator down after. This is THE
   acceptance of the gap fix.
7. README: replace the first-buy divergence disclosure with the shipped behavior.
