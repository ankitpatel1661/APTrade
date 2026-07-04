# Plan: Increment 6b.3 — macOS parity payback + deferred-minors batch

Branch: kmp-6b3-payback off main @ 0b776c5. Baselines: shared 162 / android 13 /
desktop 164 / Swift 193 / 3 slices. First increment touching Sources/ since the KMP
work began — the Swift suite is the anchor regression for Task 1.

## Global Constraints
- Zero user-visible desktop regressions; Swift changes limited to Task 1's two targets.
- MONEY_MATH on divisions; CancellationException-first; Main confinement; comment
  policy of the cleanup sweep applies (keep contracts/divergences, no narration).
- Suites --rerun-tasks XML-counted; DEVELOPER_DIR for Swift/xcframework/iOS.
- JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home.
- HARD RULE: implementers work directly; no Agent-tool delegation.

## Scope decisions (recorded)
- Benchmark TWIN stays desktop-only (user has not asked for macOS adoption).
- AssetKind export machine key: LOWERCASE ("stock"/"etf"/"crypto") is canonical —
  matches the Swift domain rawValue; Kotlin CSV/JSON change is free (explorer verified
  zero consumers/parsers on either platform; Swift XLSX/DOCX uppercase is presentation,
  unchanged). Record in PortfolioExport.kt KDoc.
- DROPPED from the batch, with rationale: Activity eager render (single-scroll Column
  is a deliberate, adjudicated design for a bounded paper portfolio); DonutChart
  single-slice ratio (harmless by construction, fractions non-negative, adjudicated).

### Task 1: Swift adopts the all-priced gate + benchmark head-trim

Files: Sources/APTradeDomain/PortfolioPerformance.swift,
Sources/APTradeApplication/PerformanceUseCases.swift,
Tests/APTradeDomainTests/PortfolioPerformanceTests.swift (+ application tests if any
pin benchmark handling).

1. performanceSeries (PortfolioPerformance.swift:44-69): adopt the Kotlin gate EXACTLY
   (shared/src/commonMain/kotlin/com/aptrade/shared/domain/PortfolioPerformance.kt:52-77
   is the reference — read it): an allPriced flag; a symbol WITH history but no close
   yet at the date sets allPriced=false; the whole date is skipped unless every
   symbol-with-history is priced. Symbols with NO history stay excluded from the gate
   (must not blank the curve). Forward-fill after gate-open byte-identical. Update the
   header comment: the desktop divergence is now ADOPTED (name the increment).
2. ComputePerformanceMetricsUseCase (PerformanceUseCases.swift:77,111): head-trim the
   benchmark curve to >= the (post-gate) portfolio curve's first date BEFORE the
   count>1 gate — mirror Kotlin FetchPerformanceReport.kt:56-69's semantics (trim for
   the overlay/metrics; Swift has no twin, so nothing else changes). Keep the existing
   suffix-pairing for beta/alpha (already END-paired).
3. Tests: update test_twoSymbols_forwardFillsMissingDates (currently asserts the cliff:
   count==3 with one symbol unpriced at leading dates) the same way Kotlin's rename
   did — gate-aware fixture + name. PORT Kotlin's gate fixtures (PortfolioPerformanceTest.kt:
   35,60,86,105,122,137 — six tests: forward-fill-after-gate, skip-unpriced-dates,
   start-at-first-all-priced, value-at-gate-open exact, single-symbol-unchanged,
   no-history-symbol-intact) to Swift with the same numeric expectations. Add one
   head-trim test in the application layer (benchmark closes before curve start are
   excluded from metrics input).
4. Also update the Kotlin PortfolioPerformance.kt header KDoc (lines 6-17): the
   divergence is adopted — reword to "adopted by macOS in 6b.3" (comment-only Kotlin
   change, allowed in this task).
5. Verify: DEVELOPER_DIR swift test — 193 + ~7 new, 0 failures (measure); iOS NOT
   needed here (Task 3). Gradle suites untouched (no Kotlin code change) — run
   :shared:jvmTest once anyway to prove the comment edit is inert (162).

### Task 2: Kotlin minors batch (shared + desktop)

1. EXPORT CASING (the recorded decision): shared PortfolioExport.kt:69 — Holding.kind
   = position.asset.kind.name.lowercase() → CSV (line 139) and JSON (177,205) now emit
   "stock"/"etf"/"crypto". Update goldens/tests. KDoc on Holding.kind: canonical
   machine key, lowercase, decided 6b.3, matches Swift domain rawValue.
2. ToggleBookmark race fix (store-level, per the 6c final review's recommendation):
   execute(article) — drop the `current` parameter; inside: kotlinx.coroutines.sync
   Mutex.withLock { val current = store.load(); toggle by id (remove-if-present else
   insert-at-0); store.save(result); result }. AppGraph exposes ONE ToggleBookmark
   instance shared by both VMs (verify current factory shape; make it a single val).
   Both VM call sites updated (state = returned list). Tests: update existing toggle
   tests; ADD a interleaved-callers test proving the second toggle sees the first's
   write (two toggles of different articles → both present).
3. PerformanceSection.kt:203-224: reorder the when — portfolio.size<2 → "No
   performance data yet." BEFORE benchmark==null → "Benchmark unavailable" (empty
   portfolio now gets the right copy; benchmark-failure-with-data keeps its message).
   maxDayOne stays first.
4. Main.kt:346-349 Export row: switch tab AND open the chooser — hoist a
   `pendingExport: MutableState<Boolean>` (or callback) from Main into PortfolioPane;
   PortfolioPane consumes it in a LaunchedEffect → sets its local exportOpen and
   clears the flag. Update the DEVIATION comment (now resolved). Esc chain untouched.
5. DK.kt:66-67: DKColorScheme captures DK.gold once — make the scheme accent-derived
   (e.g. a @Composable/remember(DK.accent.value) construction at the MaterialTheme
   call site, or a function invoked per composition). Verify Material `primary`
   tracks an accent switch (a small test is impractical — code-trace + note; keep
   the change minimal).
6. Tests only: TechnicalIndicators period<=0 (4 cases: sma/ema/rsi/bollinger return
   null-filled lists); PdfPortfolioRenderer truncate char-exact ("26-char name+…"
   boundary, exact string); DetailViewModel candles-path race test (symmetric to
   staleResponseNeverOverwritesNewerSelection — a gated in-flight candle fetch for
   symbol A must not write after switching to B); BrandTintTest production-bytes
   (read back tintedWordmark(Sapphire)'s ImageBitmap pixels and byte-compare against
   the hand-rolled remap — closes the plumbing-bug gap).
7. Verify: :shared:jvmTest + :desktopApp:test --rerun-tasks (counts grow — measure);
   compileKotlinMacosArm64 green.

### Task 3: Full regression + docs

xcframework reassembly (3 slices) + swift test (Task 1's new count) + iOS
APTradeLite-Package ARCHS=arm64 + all gradle suites XML-counted. README: spot-check
export-section claims (casing change is machine-key only — if the README quotes
example keys, update; likely no change); nothing else shipped from the roadmap.
Docs-only commit if needed; suite failure = BLOCKED.

- Coverage: cliff adoption → T1; casing decision → T2.1; bookmark race → T2.2;
  minors → T2.3-6; regression → T3.
- Swift bridging: T2 changes ToggleBookmark's public shared API (param dropped) —
  grep Sources/ for the bridged name before assuming safety (Swift has its OWN native
  ToggleBookmarkUseCase; the Kotlin one should have zero Swift consumers — verify).

---

# AMENDMENT 2026-07-04 — gate feedback (Task 4)

Authority: the 6b.3 human gate. Two findings, fix before merge.

### Task 4: candle-mode overlays + benchmark line distinction

Files: desktopApp detail/DetailPane.kt + designkit chart file(s) as needed;
portfolio/PerformanceSection.kt. UI waiver applies (pure helpers tested if added).

1. CANDLES + OVERLAYS (user rejects the 6b.2-era disclosed divergence): the branch at
   DetailPane.kt:207-224 forces PriceChartWithOverlays (a LINE from candles) whenever
   any overlay indicator is active, ignoring ChartMode.Candles. Fix: when
   mode == Candles && overlays active, render REAL CANDLESTICKS with the overlay
   polylines (SMA/EMA/VWAP/BB) drawn on top, sharing the candle index space for exact
   x-alignment (candle i center == overlay point i). Line mode with overlays keeps the
   existing PriceChartWithOverlays. RSI/MACD panes unchanged. Overlay colors/legend
   chips unchanged. macOS parity: AssetDetailView draws indicators over both styles.
   Degenerate guards (size<2) preserved.
2. BENCHMARK LINE DISTINCTION (Portfolio tab): the benchmark twin polyline becomes
   DASHED (silver, dash pattern distinct from the crosshair's (3,3) — use e.g. (8,6))
   AND a small legend row appears above/below the overlay chart: a gold solid swatch +
   "Portfolio" and a silver dashed swatch + the selected benchmark symbol (SPY/QQQ/VTI,
   live from state.benchmark). DK styling idiom (11sp tertiary labels). Crosshair/
   tooltip/header readout untouched.
3. Verify: :desktopApp:test --rerun-tasks 167 (no count change expected unless a pure
   helper gains a test); live-run ≥45s — candle mode + SMA shows candles+overlay, line
   mode unchanged, benchmark dashed + legend renders; zero exceptions.
