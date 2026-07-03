# KMP Increment 6b.2 — Portfolio & Detail Fidelity + Intelligence (Design)

## Context

6b.1 shipped the shared portfolio core and the desktop Portfolio tab (merged 0885595,
all suites green). At the visual gate the user paper-traded successfully and returned
seven change requests plus one controller-spotted formatting defect. The user chose to
fold everything — including the benchmark/risk intelligence originally slated for a
separate 6b.2 and the detail-chart indicators — into ONE combined increment. macOS is
the fidelity reference throughout; parity facts below were pinned from the Swift
sources at spec time (file:line refs in the parity report, ledger entry 6b.2).

## Goal of 6b.2

The desktop app's Portfolio tab and asset detail screen reach macOS parity on
presentation (money formatting, stat grid + position panel, allocation donut, export
ergonomics incl. PDF) and gain the macOS intelligence features (chart indicators and
the Performance section with benchmark overlay + risk metrics), while the shared core
grows only pure, testable math.

## Requirements

### 1. Detail trade entry — single "BUY / SELL" button (user-mandated divergence)
macOS shows TWO capsules (Buy filled gold-gradient, Sell outline gold). The user
explicitly requested ONE button labeled "BUY / SELL" instead. It opens the existing
TradeDialog with Buy preselected; the dialog's internal side toggle covers sell.
Deliberate divergence from macOS, recorded here. Sell on a non-held asset surfaces
the existing "Insufficient shares." path — no special casing.

### 2. Chart indicators on the detail chart (macOS parity)
Math ported to `:shared` commonMain as pure functions (Double series in/out — chart
domain, NOT money), transcribed from `Sources/APTradeDomain/TechnicalIndicators.swift`:
- SMA(period): sliding-window mean.
- EMA(period): multiplier 2/(period+1), seeded with SMA of first `period` values.
- RSI(period=14): Wilder's smoothing (initial simple averages over first `period`
  deltas, then avg=(avg*(period-1)+new)/period); RSI=100−100/(1+RS); avgLoss==0→100.
- VWAP(typical=(H+L+C)/3, cumulative from series start): Σ(typical·vol)/Σvol, null
  until cumulative volume > 0.
- Bollinger(period=20, multiplier=2.0): middle=SMA, POPULATION stddev per window,
  upper/lower = middle ± multiplier·stddev.
- MACD(12/26/9): macd=EMA12−EMA26; signal=EMA(macd,9); histogram=macd−signal.
Each unit-tested against hand-computed fixtures.

UI (DetailPane), per `AssetDetailView.swift`:
- Six multi-select chips in a horizontally scrolling row (none selected by default),
  each a colored dot + label: "SMA 20", "EMA 12", "VWAP", "BB 20", "RSI 14",
  "MACD 12·26·9". View constants: smaPeriod=20, emaPeriod=12, rsiPeriod=14,
  bollingerPeriod=20; MACD 12/26/9.
- Overlays on the price chart: SMA/EMA solid 1.5-width lines; VWAP dashed [5,3];
  Bollinger translucent band (0.07 opacity fill) + upper/lower solid + middle dashed
  [3,3] at 0.6 opacity. Y-domain padded 12% and extended to include BB extremes.
- Sub-panes below the chart when toggled: RSI pane (height 90, domain 0..100, dashed
  guides at 30/70 labeled Oversold/Overbought, x-axis hidden, header "RSI 14");
  MACD pane (height 100, histogram bars up/down-colored at 0.5 opacity, MACD +
  Signal lines, dot legend, x-axis hidden).
- Colors: SMA=DK.gold; EMA=teal rgb(0.30,0.74,0.86); VWAP=DK.silver;
  BB=blue rgb(0.38,0.56,0.95); RSI=violet rgb(0.65,0.49,0.92);
  MACD=amber rgb(0.90,0.58,0.26); MACD signal=pink rgb(0.84,0.45,0.67).
- NOTE: VWAP/BB/MACD/RSI need candle-series data (H/L/C/volume) on the timeframes
  the desktop chart already fetches for candle mode; reuse that series. No user-
  configurable parameters (macOS hardcodes them too).

### 3. Detail stat grid + position panel (macOS parity)
Replace the current 2-tile footer with, per `AssetDetailView.keyStats`/`positionPanel`:
- "KEY STATS" card (2-column grid, card surface at 0.5 opacity, hairline border,
  header tracking wide): Last, Previous close, Day change (signed $, changeColor),
  Day change % (changeColor), Symbol, Type (Stock/ETF/Crypto label).
- "YOUR POSITION" card, only when the asset is held: Shares, Average cost, Market
  value, Unrealized P&L (signed, pnl color).
The KMP shared Quote (symbol/price/previousClose/changePercent) and Position models
already carry every needed field — NO shared model growth for this item. Day change $
is derived price−previousClose (existing convention).

### 4. Portfolio export — single "Export…" button with chooser incl. PDF
macOS offers PDF/XLSX/DOCX via a confirmation dialog and NO CSV/JSON; desktop 6b.1
shipped CSV/JSON only. Per user: ONE "Export…" button opening a DK-styled chooser
with **CSV / JSON / PDF** (CSV+JSON kept, PDF added; XLSX/DOCX remain out of scope).
- PDF renderer: Apache PDFBox (pinned version, Apache-2.0), `desktopApp` infra ONLY —
  `:shared` and designkit never see it. Consumes the existing format-agnostic
  `PortfolioExport` snapshot.
- PDF content mirrors the macOS `PDFExportRenderer`: landscape US Letter (792×612pt,
  40pt margins); account title; "Portfolio Statement · <Month d, yyyy h:mm>";
  summary block (Total Value, Cash, Holdings Value, Day P&L signed, Unrealized P&L
  signed); holdings table SYMBOL / NAME (trunc 26) / QTY / AVG COST / LAST /
  MKT VALUE / UNREAL P&L (up/down colored) / ALLOC (1 decimal %); empty-holdings
  message "No holdings — the account is all cash."; paginate on overflow.
- Default filename `APTrade-Portfolio-yyyy-MM-dd.pdf` (date-stamped, macOS parity);
  CSV/JSON keep their existing names.

### 5. MAX day-one empty state
Inception-trim logic is correct (macOS-identical): on day one the trimmed series has
≤1 daily point. Fix is presentational only: when span==Max, the portfolio has
transactions, and the series has <2 points, show "Tracking starts today — performance
appears after your first market day." instead of the generic empty message. No shared
semantics change.

### 6. Performance section (macOS parity — the original 6b.2 core)
Port the macOS Performance section (`PerformanceSection.swift`,
`PerformanceViewModel.swift`, `RiskMetrics.swift`, `ComputePerformanceMetricsUseCase`):
- Benchmark picker: segmented SPY / QQQ / VTI (default SPY). (User said "SPY, VOO or
  something" — actual macOS set is SPY/QQQ/VTI; parity governs.)
- Overlay chart (height 200): TWO lines, both REBASED to 100 at series start
  (value/first×100): Portfolio (gold) vs benchmark (secondary/silver). Benchmark
  history via existing `MarketDataRepository.history`; benchmark fetch failure is
  swallowed (never sinks the report) → "Benchmark unavailable" text replaces the
  chart when its history is missing.
- Metric grid, 7 tiles: Total Return, Annualized Return, Volatility, Max Drawdown,
  Sharpe, Beta, Alpha (Beta/Alpha "—" when no benchmark data).
- Risk math ported to `:shared` commonMain, pure + unit-tested (Double domain):
  dailyReturns; totalReturn; annualizedReturn (CAGR, 252 periods/yr); annualized
  volatility (SAMPLE stddev × √252); maxDrawdown (worst peak-to-trough); sharpe =
  (annualizedReturn − riskFree)/vol; beta = cov(p,b)/var(b) over index-paired daily
  returns; alpha = CAPM (riskFree default 4%). Keep macOS's index-based day pairing
  (documented limitation for crypto calendars — do not "fix" silently).
- Placement: a section on the Portfolio tab (below the existing P&L chart block),
  matching macOS's separation: the summary P&L chart stays benchmark-free.
- HHI diversification warnings exist on macOS in this section — OUT of scope here
  (defer; keep the section focused on chart + metrics).

### 7. Allocation donut (macOS parity)
`DonutChart` in designkit (pure Compose Canvas, no new deps), per
`PortfolioView.allocationDonut`:
- True donut: inner radius 64% of outer, 1.5° angular inset between slices, rounded
  slice ends (corner radius 3), 150×150dp, slices from allocationByKind in fixed
  order Stock/ETF/Crypto with zero-value kinds omitted.
- Colors: Stock=DK.gold, ETF=DK.goldDeep, Crypto=DK.silver.
- Manual legend to the right: 9dp color dot + class label + right-aligned percent
  (1 decimal).
- Center overlay: "HOLDINGS" (8sp bold, wide tracking, textTertiary) over total
  holdings value (14sp bold, tnum, shrink-to-fit).
- Existing "BY HOLDING" bars remain below (macOS layout).

### 8. Money formatting (defect fix, macOS parity)
Per `Money.formatted` (Swift): en_US currency — "$", comma thousands grouping,
ALWAYS exactly 2 decimals, formatter-provided minus (−$1,234.56 style), NO plus sign
from the formatter; call sites add "+" only for strictly positive values (zero gets
no sign). Add a designkit `formatMoney(...)`/`signedMoney(...)` pair (rounding
half-even, matching NSNumberFormatter's default) and route the Portfolio stat tiles,
day-change pill, holdings rows, and PDF/export text through it. `splitPrice` keeps
its superscript role; this fixes the raw `35040.455`-style tiles.

## Also landing here (6b.1 final-review deferrals, same files)
- refreshQuotes per-symbol merge (keep last-good quote when a poll returns a subset).
- Hoist the duplicated 3-line `assetKindFromLabel` (Main.kt + DetailPane.kt) into one
  shared desktop util.
- ResetConfirmDialog consumes Esc (same onPreviewKeyEvent pattern as TradeDialog).
- Upgrade the vacuous zeroAllocationRendersAsZero test (nonzero holdings, zero total).
- TradeFormState: parse once (lazy) instead of per derived call.

## Architecture rules (unchanged from 6b.1)
- Indicator + risk math: `:shared` commonMain, pure, no framework imports, Double
  domain (chart/statistics), unit-tested with hand-computed fixtures.
- Money/quantity remain BigDecimal end-to-end; Double only for ratios, returns, and
  chart pixels. Every BigDecimal division through MONEY_MATH DecimalMode(38,
  HALF_AWAY_FROM_ZERO).
- CancellationException rethrown first in every coroutine catch.
- UI composition ships without unit tests (standing waiver); ViewModels and shared
  math carry the behavior.
- PDFBox confined to desktopApp infra; designkit stays dependency-clean.

## Out of scope for 6b.2
- XLSX/DOCX export on desktop (macOS-only for now).
- HHI diversification/concentration warnings.
- macOS adoption of the shared core (6b.3) — incl. the AssetKind export-key casing
  decision (breaking machine-key change, decide once at 6b.3).
- Android portfolio screen (6b.4).
- User-configurable indicator parameters (macOS hardcodes them too).

## Risks
- `:shared` grows again → full Apple re-proof (xcframework + Swift 193 + iOS build)
  in the regression task.
- PDFBox is the first third-party JVM rendering dependency in desktopApp — pin the
  version; keep it behind an ExportSave-style infra seam.
- Indicator/risk math must match macOS numerically — transcribe formulas exactly
  (population vs sample stddev is deliberate per formula above) and fixture-test.
- The desktop detail chart must expose candle series (H/L/C/volume) to indicator
  code on line-mode timeframes too; reuse the existing candle fetch, don't add a
  second data path.
- Beta/alpha index-based pairing is a documented macOS limitation — port as-is.
