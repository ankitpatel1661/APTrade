# M9.1 — Technical Screener: Swift core + macOS/iPhone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A whole-market S&P 500 technical screener on macOS + iPhone — on-demand throttled scan into a per-trading-day snapshot, 9 preset signal screens plus a saved custom-condition builder, as a fifth top-level tab.

**Architecture:** Pure math in `APTradeDomain` (`ScreenerMath` snapshot builder over the existing `TechnicalIndicators`, screen models + evaluation + presets), the throttled `ScreenerScanEngine` + store ports in `APTradeApplication`, a file-backed snapshot store (Infrastructure's FIRST file store) + a UserDefaults screen store, and a Screener tab in `APTradeApp`. Spec: `docs/superpowers/specs/2026-07-21-technical-screener-design.md`. M9.2/M9.3 transcribe this as-built.

**Tech Stack:** Swift 6, SwiftUI, XCTest, SwiftPM. Data via the existing bridged candles pipeline (`MarketDataRepository.candles(for:timeframe:)`, `.oneYear` = 1y daily ≈ 252 bars; `Candle` carries `volume`).

## Global Constraints

- Swift 6 strict concurrency; no force unwraps; new types `Sendable` where peers are; domain = Foundation only.
- The screener NEVER touches the portfolio: no TradeSerializer, no PortfolioStore writes. Read-only market data.
- All metric math over `Double` closes (existing `TechnicalIndicators` convention — indicators are display/analysis math, not money); `Money` only where price is displayed.
- All user-visible strings via `L10n`/`tr(_:)`, EN/DE/IT/ES rows (completeness test enforces).
- Snapshot store is FILE-backed (Application Support), NOT UserDefaults (hundreds of KB). Custom screens use UserDefaults (PieStore pattern). All persisted DTOs absent-key tolerant with hand-written legacy-JSON tests.
- Scan throttling: `batchSize = 4` concurrent, `interBatchDelay = 150ms` (named constants); HTTP-429/`rateLimited` → back off `2s` and retry that batch ONCE; second failure marks the batch's symbols failed and continues.
- iOS differences `#if os(iOS)`-gated; macOS otherwise byte-identical. Test commands: `DEVELOPER_DIR=/Applications/Xcode.app swift test` (baseline 469); iOS gate = package suite (scheme `APTradeLite-Package`, park APTrade.xcodeproj).
- Commit per task, conventional messages, explicit paths (NEVER `git add -A`). No pushes.

---

### Task 1: ScreenerMath — snapshot builder

**Files:**
- Create: `Sources/APTradeDomain/ScreenerMath.swift`
- Test: `Tests/APTradeDomainTests/ScreenerMathTests.swift`

**Interfaces — produces:**

```swift
/// One symbol's fully computed technical snapshot. All metrics nullable —
/// insufficient history yields nil, never a crash.
public struct ScreenerSnapshotRow: Equatable, Codable, Sendable, Identifiable {
    public var id: String { symbol }
    public let symbol: String
    public let name: String
    public let close: Double
    public let dayChangePercent: Double?
    public let rsi14: Double?
    public let macd: Double?
    public let macdSignal: Double?
    public let macdHistogram: Double?
    public let sma50: Double?
    public let sma200: Double?
    public let ema20: Double?
    public let pctVsSma50: Double?      // (close − sma50)/sma50 × 100
    public let pctVsSma200: Double?
    public let bollingerPercentB: Double?   // (close − lower)/(upper − lower)
    public let bollingerBandwidth: Double?  // (upper − lower)/middle
    public let week52High: Double?
    public let week52Low: Double?
    public let pctTo52wHigh: Double?    // (high − close)/high × 100, ≥ 0
    public let pctTo52wLow: Double?     // (close − low)/low × 100, ≥ 0
    public let relativeVolume: Double?  // today ÷ 20-day average volume (nil when avg 0)
    public let macdCrossedUp: Bool      // histogram ≤ 0 yesterday, > 0 today
    public let macdCrossedDown: Bool
    public let goldenCross: Bool        // sma50 ≤ sma200 yesterday, > today
    public let deathCross: Bool
    // memberwise public init
}

public enum ScreenerMath {
    /// Builds one row from ascending daily candles (needs ≥ 2 bars for day change,
    /// ≥ 201 for SMA-200/crosses; every metric degrades to nil independently).
    /// Cross flags need BOTH yesterday's and today's indicator values — computed
    /// here because only the scanner sees the full series.
    public static func snapshot(symbol: String, name: String, candles: [Candle]) -> ScreenerSnapshotRow?
    // nil only when candles is empty (no close at all).
}

public struct ScreenerSnapshot: Equatable, Codable, Sendable {
    public let tradingDay: String       // MarketCalendar day-string of scannedAt
    public let scannedAt: Date
    public let rows: [ScreenerSnapshotRow]
    public let failedSymbols: [String]
    // memberwise public init
}
```

Semantics: closes/volumes extracted as `[Double]` (`candle.close.amount` via `NSDecimalNumber.doubleValue`, matching how chart indicators already consume closes — check `IndicatorOverlays`/chart code and mirror); indicators via the existing `TechnicalIndicators` (sma/ema/rsi/bollingerBands/macd — last element = today, second-to-last = yesterday); 52-week range over the whole 1y series' highs/lows; `dayChangePercent` from the last two closes.

- [ ] **Step 1: Failing tests.** Hand-computable fixtures: (a) a small rising series → exact RSI/SMA/day-change values (compute expected with the same published formulas by hand, ≤ 30 bars, assert to 1e-9 accuracy); (b) short history (5 bars) → sma50/rsi/macd/crosses nil/false, close + dayChange still present; (c) `macdCrossedUp`: construct a series where histogram flips ≤0→>0 on the final bar (and a control where it stays positive → false); (d) `goldenCross` yesterday≤/today> fixture + control; (e) 52w distance percentages exact; (f) relativeVolume = today ÷ mean(last 20) exact, nil when volumes all 0; (g) empty candles → nil. Run: `swift test --filter ScreenerMathTests` — FAILS.
- [ ] **Step 2: Implement.** Filtered PASS; full `swift test` PASS.
- [ ] **Step 3: Commit** `feat(domain): ScreenerMath — per-symbol technical snapshot with cross flags`

---

### Task 2: Screen model, evaluation, presets

**Files:**
- Create: `Sources/APTradeDomain/Screener.swift`
- Test: `Tests/APTradeDomainTests/ScreenerTests.swift`

**Interfaces:**
- Consumes: `ScreenerSnapshotRow` (Task 1).
- Produces:

```swift
public enum ScreenerMetric: String, Codable, CaseIterable, Sendable {
    case price, dayChangePercent, rsi14, bollingerPercentB, bollingerBandwidth,
         pctTo52wHigh, pctTo52wLow, relativeVolume, pctVsSma50, pctVsSma200
    /// The row's value for this metric (price → close). Nil-propagating.
    public func value(in row: ScreenerSnapshotRow) -> Double?
}

public enum ScreenComparison: String, Codable, Sendable { case above, below }

public struct ScreenCondition: Equatable, Codable, Sendable {
    public let metric: ScreenerMetric
    public let comparison: ScreenComparison
    public let threshold: Double
    public func matches(_ row: ScreenerSnapshotRow) -> Bool  // nil metric → false
}

public struct CustomScreen: Equatable, Codable, Identifiable, Sendable {
    public let id: String
    public var name: String
    public var conditions: [ScreenCondition]   // AND-combined; empty = matches nothing
}

public enum PresetScreen: String, CaseIterable, Codable, Sendable {
    case rsiOversold, rsiOverbought, macdBullishCross, macdBearishCross,
         goldenCross, deathCross, bollingerSqueeze, near52wHigh, near52wLow
    public func matches(_ row: ScreenerSnapshotRow) -> Bool
    // rsiOversold: rsi14 < 30 · rsiOverbought: rsi14 > 70 · squeeze: bandwidth < 0.05
    // near52wHigh: pctTo52wHigh < 3 · near52wLow: pctTo52wLow < 3 · crosses: the flags
}

/// The active screen the UI runs — preset or custom — with one evaluation door.
public enum ScreenSelection: Equatable, Sendable {
    case preset(PresetScreen)
    case custom(CustomScreen)
    public func evaluate(_ rows: [ScreenerSnapshotRow]) -> [ScreenerSnapshotRow]
}
```

- [ ] **Step 1: Failing tests.** (a) each of the 9 presets against a purpose-built matching row AND a near-miss row (boundary: rsi exactly 30 does NOT match oversold — strict comparisons); (b) condition with nil metric → row excluded; (c) AND semantics: two conditions, row passing one → excluded; (d) empty custom conditions → no matches; (e) Codable round-trip of CustomScreen + hand-written legacy JSON without a future field decodes. Run filtered — FAILS.
- [ ] **Step 2: Implement.** Filtered + full PASS.
- [ ] **Step 3: Commit** `feat(domain): screen model, AND evaluation, 9 preset screens`

---

### Task 3: ScreenerScanEngine

**Files:**
- Create: `Sources/APTradeApplication/ScreenerUseCases.swift` (engine + ports)
- Test: `Tests/APTradeApplicationTests/ScreenerScanEngineTests.swift`

**Interfaces:**
- Consumes: `ScreenerMath.snapshot`, `ScreenerSnapshot` (Task 1), `MarketDataRepository.candles`, `MarketCalendar.tradingDay(of:)`, `SP500Symbols` + name lookup (find how Calendar resolves S&P names — `SP500Names` lives in APTradeApp; the engine takes `names: [String: String]` as input so domain/app stay UI-independent; the caller passes SP500Names' table).
- Produces:

```swift
public protocol ScreenerSnapshotStore: Sendable {
    func load() -> ScreenerSnapshot?
    func save(_ snapshot: ScreenerSnapshot)
}
public protocol ScreenStore: Sendable {
    func load() -> [CustomScreen]
    func save(_ screens: [CustomScreen])
}

public struct ScreenerScanEngine: Sendable {
    public static let batchSize = 4
    public static let interBatchDelayMs = 150
    public static let rateLimitBackoffMs = 2_000
    public init(market: MarketDataRepository, calendar: MarketCalendar,
                sleep: @escaping @Sendable (Int) async -> Void = { try? await Task.sleep(for: .milliseconds($0)) })
    /// Scans `symbols` in order, `batchSize` at a time concurrently, reporting
    /// (completed, total) after each batch. Per-symbol failure → symbol recorded in
    /// failedSymbols, scan continues. A batch where any fetch threw a rate-limit
    /// error sleeps rateLimitBackoffMs and retries that batch ONCE; symbols still
    /// failing are recorded. Cancellation propagates (throws CancellationError).
    public func scan(symbols: [String], names: [String: String], now: Date,
                     onProgress: @escaping @Sendable (Int, Int) -> Void) async throws -> ScreenerSnapshot
}
```

The injected `sleep` seam makes throttle/backoff testable without real delays. Identify the rate-limit error by checking what the bridged repository throws on 429 (grep `RateLimited` in the Swift error mapping — `AppError`/mapError in SharedCoreMarketDataRepository) and match that case.

- [ ] **Step 1: Failing tests.** Fake repo with per-symbol canned candles/failures + a concurrency counter + recorded sleep calls. (a) all succeed → rows for all, failedSymbols empty, progress sequence [(4,N)...(N,N)]; (b) max concurrent fetches never exceeds 4 (assert via counter high-water mark); (c) one symbol throws generic error → in failedSymbols, others present; (d) rate-limit on a batch → sleep(2000) recorded, batch retried once, succeeds on retry → no failures; (e) rate-limit twice → symbols failed, scan continues; (f) inter-batch sleep(150) recorded between batches; (g) tradingDay/scannedAt stamped from `now`. Run filtered — FAILS.
- [ ] **Step 2: Implement.** Filtered + full PASS.
- [ ] **Step 3: Commit** `feat(app): ScreenerScanEngine — throttled batches, backoff, failure isolation`

---

### Task 4: Stores — file-backed snapshot + UserDefaults screens

**Files:**
- Create: `Sources/APTradeInfrastructure/FileScreenerSnapshotStore.swift`
- Create: `Sources/APTradeInfrastructure/UserDefaultsScreenStore.swift`
- Test: `Tests/APTradeInfrastructureTests/ScreenerStoresTests.swift`

**Interfaces:**
- Consumes: the two ports (Task 3), `ScreenerSnapshot`/`CustomScreen` Codable (Tasks 1–2).
- Produces: `FileScreenerSnapshotStore(directory: URL? = nil)` — defaults to Application Support/APTrade (created on demand), file `screener-snapshot.json`, atomic write, corrupt/missing → nil WITHOUT overwriting the bad file (byte-equality no-overwrite test, house pattern); `UserDefaultsScreenStore` mirrors `UserDefaultsPieStore` (key `"screens"`, corrupt → []).

- [ ] **Step 1: Failing tests.** (a) snapshot round-trip via temp directory; (b) corrupt file → nil + bytes untouched; (c) missing file → nil; (d) screens round-trip + corrupt → []; (e) hand-written legacy snapshot JSON missing a nullable metric field decodes (absent-key tolerance). Run filtered — FAILS.
- [ ] **Step 2: Implement.** Filtered + full PASS.
- [ ] **Step 3: Commit** `feat(infra): file-backed screener snapshot store + screen store`

---

### Task 5: L10n keys (EN/DE/IT/ES)

**Files:**
- Modify: `Sources/APTradeApp/L10n.swift`
- Test: `Tests/APTradeAppTests/L10nTests.swift` (completeness test is the gate)

Keys (~32; EN raw values): `screenerTab` "Screener"; scan bar: `screenerScan` "Scan", `screenerRefresh` "Refresh", `screenerScanningFmt` "Scanning… %@ of %@", `screenerLastScanFmt` "%@ scanned · %@", `screenerFailedNoteFmt` "%@ symbols unavailable", `screenerNotScanned` "Scan the S&P 500 to run your first screen.", `screenerNoMatches` "No matches for this screen.", `screenerScanFailed` "Scan failed — check your connection and try again."; presets: `presetRsiOversold` "RSI Oversold", `presetRsiOverbought` "RSI Overbought", `presetMacdBullish` "MACD Bullish Cross", `presetMacdBearish` "MACD Bearish Cross", `presetGoldenCross` "Golden Cross", `presetDeathCross` "Death Cross", `presetBollingerSqueeze` "Bollinger Squeeze", `presetNear52wHigh` "Near 52-Week High", `presetNear52wLow` "Near 52-Week Low"; metrics (builder + columns): `metricPrice` "Price", `metricDayChange` "Day %", `metricRsi` "RSI (14)", `metricPercentB` "%B", `metricBandwidth` "Bandwidth", `metricTo52wHigh` "To 52w High", `metricTo52wLow` "To 52w Low", `metricRelVolume` "Rel. Volume", `metricVsSma50` "vs SMA 50", `metricVsSma200` "vs SMA 200"; builder: `screenerNewScreen` "New Screen", `screenerScreenName` "Screen Name", `screenerAddCondition` "Add Condition", `screenerAbove` "Above", `screenerBelow` "Below", `screenerMatchCountFmt` "%@ matches", `screenerSaveScreen` "Save Screen", `screenerDeleteScreen` "Delete Screen", `addToWatchlist` "Add to Watchlist" (check for an existing key first — reuse if present). Translations follow the reviewed catalog register (DE formal; finance terms: DE "Goldenes Kreuz"/"Todeskreuz" are the established German terms; RSI/MACD/%B stay untranslated as technical terms).

- [ ] **Step 1:** Keys + 4-language rows; L10nTests RED→GREEN; full suite PASS.
- [ ] **Step 2: Commit** `feat(app): screener L10n keys (EN/DE/IT/ES)`

---

### Task 6: ScreenerViewModel

**Files:**
- Create: `Sources/APTradeApp/ScreenerViewModel.swift`
- Test: `Tests/APTradeAppTests/ScreenerViewModelTests.swift`

**Interfaces:**
- Consumes: engine + stores (Tasks 3–4), `ScreenSelection`/presets (Task 2), `SP500Symbols` + `SP500Names`. `@Observable` (house VM convention — NOT ObservableObject) with injected deps + `now: () -> Date` seam.
- Produces:

```swift
@Observable @MainActor
final class ScreenerViewModel {
    enum ScanState: Equatable { case idle, scanning(done: Int, total: Int), failed }
    private(set) var scanState: ScanState
    private(set) var snapshot: ScreenerSnapshot?      // loaded from store on init
    private(set) var savedScreens: [CustomScreen]
    var selection: ScreenSelection                     // default .preset(.rsiOversold)
    private(set) var results: [ScreenerSnapshotRow]    // evaluate(selection) sorted
    var sortColumn: ScreenerSortColumn                 // symbol/price/dayChange/activeMetric
    var sortAscending: Bool
    var isSnapshotFresh: Bool                          // tradingDay == today
    func scan() async                                  // engine run → save → re-evaluate
    func select(_ selection: ScreenSelection)
    func saveScreen(_ screen: CustomScreen)            // insert-or-replace by id, persist
    func deleteScreen(id: String)
    func matchCount(for conditions: [ScreenCondition]) -> Int   // live builder count
}
```

Semantics: init loads snapshot + screens synchronously from stores; `scan()` guards re-entry (ignores while scanning), streams progress into `scanState`, saves on success, `failed` only when the engine throws (partial failures still succeed with failedSymbols); results re-sort on any selection/sort change; total-failure keeps the previous snapshot.

- [ ] **Step 1: Failing tests.** (a) init restores persisted snapshot + screens; (b) scan success persists + results populate for the default preset; (c) progress states observed in order; (d) engine throw → .failed, previous snapshot retained; (e) re-entrant scan ignored; (f) selection switch re-evaluates; (g) sort by column/direction correct incl. nil-metric rows sorted last; (h) save/delete screen persists through the store fake; (i) matchCount live against snapshot. Run filtered — FAILS.
- [ ] **Step 2: Implement.** Filtered + full PASS.
- [ ] **Step 3: Commit** `feat(app): ScreenerViewModel — scan orchestration, selection, sorting, builder support`

---

### Task 7: Screener UI — fifth tab, chips, scan bar, results

**Files:**
- Create: `Sources/APTradeApp/ScreenerView.swift`
- Modify: `Sources/APTradeApp/RootView.swift` (Tab enum gains `screener = "Screener"` with `tr(.screenerTab)` title, rendered like the other four; iOS bottom bar gains the fifth item — follow the existing tab-icon conventions, SF Symbol e.g. `line.3.horizontal.decrease.circle`)
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (makeScreenerViewModel: engine on `makeRepository()`, both stores, SP500Names table)
- Test: build-only (VM logic is Task 6); `swift test --filter L10nTests` guards strings

Layout per spec: chips row (9 presets via their L10n names + saved screens + "+" chip); scan bar (states: not-scanned CTA / scanning progress bar with `screenerScanningFmt` / fresh with `screenerLastScanFmt` + Refresh / `screenerScanFailed` + retry; failed-symbols muted note); results: macOS `Table`-style sortable columns (symbol, name, price, day % + the active screen's relevant metrics — map each preset/custom metric set to columns), iOS condensed rows (`#if os(iOS)`); row tap → existing asset-detail navigation (follow how Watchlist rows open detail); per-row add-to-watchlist (reuse the existing AddToWatchlist use case); three empty states. DesignKit components + Theme tokens only.

- [ ] **Step 1: Implement view + tab + composition.**
- [ ] **Step 2: Verify:** `swift build` clean; full `swift test` PASS; app boots (`swift run` binary path) with the Screener tab visible — kill after.
- [ ] **Step 3: Commit** `feat(app): Screener tab — presets, scan bar, sortable results`

---

### Task 8: Builder sheet

**Files:**
- Create: `Sources/APTradeApp/ScreenBuilderSheet.swift`
- Modify: `Sources/APTradeApp/ScreenerView.swift` ("+" chip and edit action present the sheet)
- Test: `Tests/APTradeAppTests/ScreenerViewModelTests.swift` (extend if new VM surface needed — builder state may live in a small `@Observable` ScreenBuilderModel with validation: non-empty name, ≥1 condition, numeric threshold; test it)

Sheet: name field, condition rows (metric Picker over `ScreenerMetric.allCases` with `metric*` labels, Above/Below segmented, numeric TextField), add/remove condition, live `screenerMatchCountFmt` via `viewModel.matchCount`, Save (insert-or-replace) / Delete for existing screens. Follows PieWizard's sheet idioms (`.sheet`, Theme styling, confirm patterns).

- [ ] **Step 1: Failing tests** for the builder model validation + save wiring. RED.
- [ ] **Step 2: Implement.** Filtered + full PASS; build clean.
- [ ] **Step 3: Commit** `feat(app): custom screen builder sheet with live match count`

---

### Task 9: macOS polish parity (carried M8 backlog)

**Files:**
- Modify: `Sources/APTradeDomain/Quantity.swift` (formatted: `maximumFractionDigits` 8 → 4 — display-only; check every `Quantity.formatted` consumer for tests asserting 8-digit output)
- Modify: `Sources/APTradeApp/PortfolioView.swift` (allocationView: By-Class and By-Holding groups side-by-side below the donut on macOS — mirror the desktop AllocationView Row-of-two-columns shape from M8.2 polish; iOS stays stacked `#if os(iOS)`)
- Test: `Tests/APTradeDomainTests` quantity-formatting test (extend/add: 0.16666666 → "0.1667"; "10" stays "10")

- [ ] **Step 1: Failing test** for 4dp formatting. RED → implement → GREEN.
- [ ] **Step 2: Allocation layout; `swift build` clean; full suite PASS.**
- [ ] **Step 3: Commit** `fix(app): 4dp share display + side-by-side macOS allocation (M8 polish parity)`

---

### Task 10: README + close-out gates

**Files:**
- Modify: `README.md` (Screener feature block, macOS+iPhone shipped / Windows+Android pending per the parity-table convention; M9 stays in the roadmap until M9.3; suite counts to observed)

- [ ] **Step 1: README.**
- [ ] **Step 2: Gates:** full `DEVELOPER_DIR=/Applications/Xcode.app swift test` (report observed; baseline 469 + this plan's additions); iOS package suite on the simulator (scheme `APTradeLite-Package`, park the xcodeproj — TEST SUCCEEDED is the gate); `./gradlew :shared:jvmTest` unchanged 538 (no shared changes in this plan — regression formality).
- [ ] **Step 3: Commit** `feat: M9.1 close-out — screener on macOS/iPhone, README`

---

## Self-Review Notes

- **Spec coverage:** snapshot metrics + cross flags (T1), screens/presets/evaluation (T2), throttled scan + backoff + failure isolation (T3), file snapshot store + screen store (T4), fifth tab + chips + scan bar + sortable results + empty states (T7), builder with live count (T8), phone adaptation `#if os(iOS)` (T7), L10n (T5), view-only/no-notifications (nothing scheduled anywhere), no-portfolio-touch (constraint), M8 polish backlog (T9), README (T10).
- **Type consistency:** `ScreenerSnapshot(Row)` (T1) consumed by T2/T3/T4/T6; `ScreenSelection.evaluate` single door (T2→T6); store ports (T3) implemented in T4, injected in T6/CompositionRoot (T7); `matchCount` (T6) consumed by T8.
- **Engine testability:** the injected `sleep` seam + fake repo make every throttle/backoff assertion deterministic — no real delays in tests.
- **Names table:** engine takes `names: [String: String]` so Domain/Application never import the UI-layer SP500Names; CompositionRoot passes it (T7).
