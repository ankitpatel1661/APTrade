# Plan: Increment 6c.5 — benchmark cash-flow replay + accent-tinted brand art

Authority: 2026-07-04 human-gate feedback (user mandate). Branch: kmp-fidelity-6c5 off
main @ dd6635a. Baselines: shared 152 / android 13 / desktop 150 / Swift 193 / 3 slices.

## Why (user's words, translated)

1. "If I buy ten stocks today, compare with the same amount of QQQ/VOO/SPY" — the
   Performance overlay must become a CASH-FLOW-REPLAY TWIN: the same dollars invested
   into the benchmark at each trade's moment, charted dollar-vs-dollar against the
   portfolio. This goes BEYOND macOS (which rebases closes to 100) — a deliberate,
   recorded desktop-first design. It also fixes "Benchmark unavailable" on 1D during
   market holidays at the source (forward-fill prices the twin from the last session).
2. The Sapphire accent recolors the UI but not the logo — macOS remaps the wordmark's
   gold pixels onto the accent ramp (BrandImage); port it. Reverses 6b.2's recorded
   static-wordmark divergence.

## Global Constraints (binding, all tasks)

- Money exact: BigDecimal/amountText; EVERY BigDecimal division uses MONEY_MATH
  DecimalMode (ionspin throws otherwise). Double only for chart pixels/ratios.
- CancellationException-first in every catch. Main-confined VMs. Esc chain untouched.
- RAW-vs-formatted contract unchanged (PerfPointUi texts display-only, etc.).
- commonMain framework-free apart from sanctioned Ktor. No AWT/java.time in shared.
- Metrics stay macOS parity: RiskMetrics + beta/alpha still computed from portfolio
  values + benchmarkCloses EXACTLY as today — the twin changes ONLY the overlay chart.
- Standing waiver: UI composition without unit tests; pure logic is tested (TDD).
- Suites --rerun-tasks XML-counted; Swift/iOS re-proof at the final task (:shared grows).
- JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; ./gradlew.
- HARD RULE: implementers do the work themselves — no Agent-tool delegation.
- Expected trajectory: shared 152→~160; desktop 150→~154(T2)→~160(T3). Measured governs.

### Task 1: Shared — benchmark twin series + report wiring

Files: shared/src/commonMain/kotlin/com/aptrade/shared/domain/BenchmarkTwin.kt (new),
application/FetchPerformanceReport.kt (touched), matching tests.

1. Pure function in domain (KDoc: desktop-first design, NOT a Swift transcription;
   flag macOS adoption to 6b.3):
   `fun benchmarkTwinSeries(transactions: List<Transaction>, benchmarkPoints:
   List<PricePoint>, cash: Money, curveDates: List<Long>): List<Money>?`
   - Returns null when benchmarkPoints is empty; otherwise a list EXACTLY aligned
     1:1 with curveDates.
   - closeAt(t) = close of the last benchmarkPoint with epochSeconds <= t; if t
     precedes the first point, use the FIRST point's close (documented approximation
     for trades older than the fetched window).
   - Units U (BigDecimal, starts ZERO), transactions processed in epoch order:
     BUY: U += (price×quantity) / closeAt(t) — the division uses MONEY_MATH.
     SELL: U -= minOf(U, proceeds / closeAt(t)) — clamped at ZERO, KDoc documents the
     clamp (a twin that underperformed to zero cannot fund further withdrawals).
     (Compute cost/proceeds from the transaction's own price×quantity — verify the
     Transaction field names in Trade.kt; do NOT re-derive from quotes.)
   - Twin value at date d = cash.amount + U × closeAt(d) (same forward-fill helper),
     returned as Money in cash's currency. Cash is the CURRENT portfolio cash —
     constant across dates, symmetric with performanceSeries' constant-cash backtest
     (KDoc must state this symmetry explicitly).
2. FetchPerformanceReport: keep points/benchmarkCloses/metrics EXACTLY as today; add
   `benchmarkTwinValues: List<Money>?` to PerformanceReport, computed via
   benchmarkTwinSeries(transactions from the loaded portfolio, benchmark PricePoints
   ALREADY fetched (pre-head-trim — the twin needs the full window incl. candles
   before curve start; verify the head-trim only applies to benchmarkCloses, and pass
   the UNTRIMMED points to the twin), portfolio.cash, points.map{epochSeconds}).
   The portfolio is already loaded in the use case — check what it exposes
   (transactions list) and wire without new network calls.
3. Tests (≥8, exact BigDecimal hand-traceable fixtures):
   (a) single BUY $1000 at closeB=100 → U=10; twin at later date closeB=110 →
   cash+1100 exactly. (b) two buys at different closes accumulate units exactly.
   (c) SELL clamp: sell proceeds larger than U×closeB → U clamps to 0, never negative.
   (d) transaction before first benchmark candle → first-candle close used.
   (e) forward-fill: curve date between candles uses the earlier close.
   (f) empty benchmarkPoints → null. (g) alignment: output size == curveDates.size.
   (h) MONEY_MATH: a division that would throw without the DecimalMode (e.g. 1000/3)
   produces a value (no exception) — pins the mode is applied.
4. compileKotlinMacosArm64 green. shared 152→~160 measured.

### Task 2: Desktop — dollar-vs-dollar overlay chart

Files: portfolio/PortfolioViewModel.kt, portfolio/PerformanceSection.kt (+ tests).

1. VM: replace the chart feeds performanceRebased/benchmarkRebased with
   `performanceValues: List<Double>` (report points' value.amount.doubleValue(false) —
   pixels-only Double, sanctioned) and `benchmarkTwinValues: List<Double>?` (same
   conversion from the report's twin Moneys). performancePoints (scrubber) unchanged.
   MetricTexts unchanged (metrics still from report.metrics). "RiskMetrics.rebase"
   usage disappears from the VM — grep-prove no leftover rebase references in
   desktopApp (rebase itself STAYS in shared: metrics/macOS use it).
2. PerformanceSection: overlay draws the two DOLLAR series with a SHARED min/max
   (portfolio gold, twin silver — same stroke/geometry as today); scrubber/tooltip/
   header readout unchanged (they ride performancePoints); "Benchmark unavailable"
   shown ONLY when benchmarkTwinValues == null; the MAX day-one message condition
   unchanged. The two series are equal length by construction (T1 alignment) — assert
   nothing in UI; the VM test pins it.
3. Tests: update compilation-affected VM tests; add (a) performanceValues exact for a
   fixture report; (b) twin null → benchmarkTwinValues null (unavailable path);
   (c) twin present → converted values exact for a 3-point fixture. desktop ~154
   measured.

### Task 3: Desktop — accent-tinted wordmark (BrandImage port)

Files: designkit/BrandTint.kt (new) + Components.kt BrandWordmark (touched) + tests.

macOS algorithm (explorer-pinned from DesignKit.swift:95-162 — binding):
per-pixel over RGBA: skip a==0; UN-PREMULTIPLY before math; classify NEUTRAL when
(r−b) < 40/255 → dark mode leaves untouched (desktop is dark-only: always untouched);
champagneGold accent → whole-image passthrough (ship the original, zero cost);
otherwise GOLD → lum = 0.299r+0.587g+0.114b; t = ((lum−0.49)/(0.86−0.49)).coerceIn(0,1);
sampleRamp piecewise-linear: t≤0.5 lerp(deep→mid, t*2) else lerp(mid→light,(t−0.5)*2);
write back RE-PREMULTIPLIED with the original alpha.

1. Pure helpers in BrandTint.kt, each `internal` and unit-tested WITHOUT images:
   isNeutralPixel(r,g,b in 0..1 floats), goldT(lum), sampleRamp(accent, t) →
   (r,g,b floats). Tests (≥6): neutral threshold both sides of 40/255; t clamp at
   lum 0.49/0.86/outside; ramp endpoints (t=0→deep exact hex, t=0.5→mid, t=1→light)
   and midpoint lerp value for sapphire hand-computed.
2. tintedWordmark(accent): decode brand/AppWordmark.png resource bytes via the
   RemoteImage.kt Skia bridge idiom (Image.makeFromEncoded → Bitmap allocN32Pixels →
   readPixels → MUTATE the pixel buffer per the algorithm → asComposeImageBitmap);
   NOTE Skia N32 is PREMULTIPLIED BGRA or RGBA depending on platform byte order —
   read the Bitmap's imageInfo/colorType and handle the channel order correctly
   (this is the port's one real hazard: verify empirically in a test that a known
   gold pixel classifies as gold, e.g. decode the actual resource and assert the
   remap changed it for sapphire and left it for champagne). Cache: simple
   synchronized map keyed by accent (≤5 entries, process lifetime). Do the decode+
   remap off the UI thread (Dispatchers.IO) like RemoteImage's fix.
3. BrandWordmark(height): champagneGold → existing painterResource path (unchanged
   pixels, no decode); other accents → tinted ImageBitmap (remember(DK.accent.value));
   consumed automatically at AppShell:56 + AccountPanel About (both call BrandWordmark).
   Accent switch must retint live (snapshot state read inside composition).
4. Tests: pure helpers as above + the empirical resource-pixel test (2). Live-run
   ≥60s: switch to sapphire → wordmark recolors (log-clean); back to champagne →
   original. Visual fidelity itself is human-gated. desktop ~160 measured.

### Task 4: Full regression + docs

Same shape as prior close-outs. :shared grew → xcframework (3 slices) + swift test 193
+ iOS APTradeLite-Package ARCHS=arm64; all gradle suites --rerun-tasks XML-counted
(shared/desktop per T1/T3 measured, android 13). README: Performance section now
describes the cash-flow-replay benchmark twin (dollar-vs-dollar, desktop-first design
beyond macOS) and the accent-tinted wordmark (divergence clause REMOVED/updated);
roadmap untouched unless something listed shipped. SKILL.md check. Docs-only commit;
suite failure = BLOCKED.

- Coverage: gate item 1+3 (replay + holiday fix) → T1+T2; item 2 (logo) → T3.
- Known unknowns w/ guardrails: Transaction field names (T1 reads Trade.kt); Skia N32
  channel order (T3 empirical test mandated); PerformanceReport consumers on other
  platforms (none — desktop-only today; Swift bridging surface grows by one field,
  re-proved in T4).
