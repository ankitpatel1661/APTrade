# M7.1 — Investment Plans (Pies): Swift core + macOS/iPhone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Target-weight investment Pies over the existing paper portfolio — self-balancing contributions, catch-up scheduling, manual rebalance with preview, and an in-wizard DCA backtest — on macOS and iPhone (shared SwiftUI code).

**Architecture:** Pure domain math in `APTradeDomain` (Pie model + distribute/rebalance/schedule/backtest functions), use cases + `PieStore` port in `APTradeApplication`, `UserDefaults` adapter in `APTradeInfrastructure`, and a Plans section in the existing Portfolio sub-switcher in `APTradeApp`. Spec: `docs/superpowers/specs/2026-07-20-investment-plans-pies-design.md`. Plans M7.2 (Kotlin+desktop) and M7.3 (Android) transcribe this reference with identical fixtures.

**Tech Stack:** Swift 6 (`async/await`, `Sendable`), SwiftUI, XCTest, SwiftPM.

## Global Constraints

- Swift 6 strict concurrency; no force unwraps; every new type `Sendable` where the layer's peers are.
- Domain layer: Foundation only (Date/Decimal/TimeZone are fine — `MarketCalendar` precedent); no networking, no persistence.
- Money is USD-only in M7 (`Money(amount:)` default currency). All money rounding to 2 dp via `NSDecimalRound(..., 2, .plain)`.
- All user-visible strings go through `L10n`/`tr(_:)`; new keys need EN/DE/IT/ES rows (catalog-completeness test enforces).
- iOS differences `#if os(iOS)`-gated; macOS output otherwise byte-identical.
- Test commands: `DEVELOPER_DIR=/Applications/Xcode.app swift test` (needs `./scripts/build-shared.sh` once per clone). Filter: `swift test --filter <Name>`.
- Commit after every task; conventional-commit messages; no pushes (user pushes at milestone end).
- Weight arithmetic: `targetWeight` percent points as `Percentage(value:)` (e.g. 40 = 40%); validation demands exact sum 100.

---

### Task 1: Pie domain model

**Files:**
- Create: `Sources/APTradeDomain/Pie.swift`
- Test: `Tests/APTradeDomainTests/PieTests.swift`

**Interfaces — produces:**

```swift
public enum PieCadence: String, Codable, CaseIterable, Sendable { case weekly, biweekly, monthly }

public struct ContributionSchedule: Equatable, Codable, Sendable {
    public let amount: Money
    public let cadence: PieCadence
    public let nextDueDay: String          // "yyyy-MM-dd", already a trading day
    public init(amount: Money, cadence: PieCadence, nextDueDay: String)
}

public struct PieSlice: Equatable, Codable, Sendable {
    public let symbol: String
    public let assetKind: AssetKind
    public let targetWeight: Percentage
    public init(symbol: String, assetKind: AssetKind, targetWeight: Percentage)
}

public struct PieLedgerEntry: Equatable, Codable, Sendable {
    public let symbol: String
    public let quantity: Quantity
    public init(symbol: String, quantity: Quantity)
}

public enum PieActivityKind: String, Codable, Sendable { case contribution, rebalance, missedInsufficientCash, manualAdjustment }

public struct PieActivityEntry: Equatable, Codable, Sendable, Identifiable {
    public let id: UUID
    public let kind: PieActivityKind
    public let day: String
    public let amount: Money?              // nil for manualAdjustment
    public init(id: UUID = UUID(), kind: PieActivityKind, day: String, amount: Money?)
}

public struct Pie: Equatable, Codable, Sendable, Identifiable {
    public let id: String                  // UUID string
    public let name: String
    public let slices: [PieSlice]
    public let schedule: ContributionSchedule?
    public let createdDay: String
    public let ledger: [PieLedgerEntry]
    public let activity: [PieActivityEntry]
    /// Throws PieError.invalidWeights unless slices are non-empty, symbols unique,
    /// and weights sum to exactly 100.
    public init(id: String = UUID().uuidString, name: String, slices: [PieSlice],
                schedule: ContributionSchedule?, createdDay: String,
                ledger: [PieLedgerEntry] = [], activity: [PieActivityEntry] = []) throws
    public func quantity(of symbol: String) -> Quantity   // .init(0) when absent
}

public enum PieError: Error, Equatable { case invalidWeights, duplicateSymbols, emptySlices }
```

- [ ] **Step 1: Failing tests.** `PieTests`: (a) weights 60/40 constructs; (b) 60/39 throws `.invalidWeights`; (c) duplicate symbol throws `.duplicateSymbols`; (d) empty slices throws `.emptySlices`; (e) Codable round-trip preserves all fields; (f) `quantity(of:)` returns ledger quantity and zero for unknown symbol. Run `swift test --filter PieTests` — FAILS (types unresolved).
- [ ] **Step 2: Implement `Pie.swift`** exactly per the interface block; validation in `Pie.init`: `guard !slices.isEmpty else { throw PieError.emptySlices }`, uniqueness via `Set(slices.map(\.symbol)).count == slices.count`, weights `slices.reduce(Decimal(0)) { $0 + $1.targetWeight.value } == 100`.
- [ ] **Step 3:** `swift test --filter PieTests` — PASS.
- [ ] **Step 4: Commit** `feat(domain): Pie model with slice/schedule/ledger and construction validation`

---

### Task 2: PieMath.distribute — self-balancing contribution split

**Files:**
- Create: `Sources/APTradeDomain/PieMath.swift`
- Test: `Tests/APTradeDomainTests/PieMathDistributeTests.swift`

**Interfaces — produces:**

```swift
public enum PieMath {
    /// Splits `contribution` across slices, preferring underweight slices.
    /// Output values are ≥ 0, rounded to cents, and sum EXACTLY to `contribution`
    /// (remainder cents go to the largest-target slice; ties → lexicographically
    /// first symbol). `currentValues` may omit symbols (treated as 0).
    public static func distribute(contribution: Money,
                                  currentValues: [String: Money],
                                  targets: [PieSlice]) -> [String: Money]
}
```

**Algorithm (implement exactly):** `totalAfter = Σcurrent + contribution`; per slice `ideal = totalAfter × weight/100`, `deficit = max(0, ideal − current)`. If `Σdeficits ≤ contribution`: give each slice its deficit, then split the leftover pro-rata by target weight. Else give `contribution × deficit/Σdeficits`. Round each share to 2 dp (`NSDecimalRound(.plain)`); compute `remainder = contribution − Σrounded` and add it to the largest-target slice.

- [ ] **Step 1: Failing tests.** (a) empty Pie ($0 current), 50/50, $100 → 50/50 exactly; (b) drifted Pie (A=$70, B=$30, targets 50/50), $20 → all $20 to B; (c) drift larger than contribution (A=$90, B=$10, 50/50, $10) → all to B; (d) three slices with cent-rounding ($100 into 33.33/33.33/33.34 targets) → outputs sum to exactly $100.00; (e) property loop over 25 seeded cases (`var rng = SystemRandomNumberGenerator()` replaced by a fixed array of tuples) asserting exact-sum + non-negativity + no output for symbols absent from targets. Run — FAILS.
- [ ] **Step 2: Implement.** Pure static func per the algorithm; helper `private static func rounded(_ d: Decimal) -> Decimal` using `NSDecimalRound`.
- [ ] **Step 3:** `swift test --filter PieMathDistribute` — PASS.
- [ ] **Step 4: Commit** `feat(domain): self-balancing contribution distribution with exact-sum invariant`

---

### Task 3: PieMath.drift + rebalancePlan

**Files:**
- Modify: `Sources/APTradeDomain/PieMath.swift`
- Test: `Tests/APTradeDomainTests/PieMathRebalanceTests.swift`

**Interfaces — produces:**

```swift
public enum RebalanceSide: String, Codable, Sendable { case buy, sell }
public struct RebalanceOrder: Equatable, Sendable {
    public let symbol: String
    public let side: RebalanceSide
    public let amount: Money               // always positive
    public init(symbol: String, side: RebalanceSide, amount: Money)
}
extension PieMath {
    /// Signed (actual − target) percent points per slice, 2 dp.
    public static func drift(currentValues: [String: Money], targets: [PieSlice]) -> [String: Percentage]
    /// Orders restoring targets at the current total. Net cash exactly zero
    /// (cent remainder folded into the largest-target slice's order).
    /// Orders under $0.01 are dropped. Empty when already at target.
    public static func rebalancePlan(currentValues: [String: Money], targets: [PieSlice]) -> [RebalanceOrder]
}
```

- [ ] **Step 1: Failing tests.** drift: (A=$70,B=$30, 50/50) → A +20.00, B −20.00; at-target → all zero. rebalancePlan: same fixture → sell A $20, buy B $20; three-slice cent case nets to exactly $0.00 (Σbuys == Σsells); at-target → `[]`; zero-total pie → `[]`. Run — FAILS.
- [ ] **Step 2: Implement.** `delta_i = target_i/100 × total − current_i`; positive → buy, negative → sell; round to cents; fold net-cash remainder into the largest-target slice's order (adjusting its amount, flipping side only if the fold crosses zero — cover with the cent case).
- [ ] **Step 3:** `swift test --filter PieMathRebalance` — PASS.
- [ ] **Step 4: Commit** `feat(domain): pie drift and net-zero rebalance planning`

---

### Task 4: PieSchedule — due-day generation with trading-day roll

**Files:**
- Create: `Sources/APTradeDomain/PieSchedule.swift`
- Test: `Tests/APTradeDomainTests/PieScheduleTests.swift`

**Interfaces — produces:**

```swift
public enum PieSchedule {
    /// Parses "yyyy-MM-dd" at noon ET; nil on malformed input.
    public static func date(fromDay day: String, calendar: MarketCalendar) -> Date?
    /// First trading day ≥ `day` (skips weekends + `calendar.holiday(on:)` days).
    public static func rollToTradingDay(_ day: String, calendar: MarketCalendar) -> String
    /// All contribution days in (`afterDay`, `throughDay`], stepping `cadence`
    /// from `anchorDay` (weekly +7d, biweekly +14d, monthly +1 month clamped),
    /// each rolled forward to a trading day. Sorted ascending, deduped.
    public static func dueDays(anchorDay: String, cadence: PieCadence,
                               afterDay: String, throughDay: String,
                               calendar: MarketCalendar) -> [String]
    public static func nextDueDay(anchorDay: String, cadence: PieCadence,
                                  afterDay: String, calendar: MarketCalendar) -> String
}
```

Implementation notes: use `Foundation.Calendar(identifier: .gregorian)` with `timeZone = TimeZone(identifier: "America/New_York")`; day-string comparisons are plain `String` comparisons (ISO shape sorts lexicographically). Monthly stepping via `DateComponents(month: n)` from the anchor (so Jan 31 → Feb 28 clamp comes from Foundation, not hand math).

- [ ] **Step 1: Failing tests.** (a) roll: `2026-11-26` (Thanksgiving) → `2026-11-27`; `2026-07-04`-observed `2026-07-03` → next trading day; Saturday `2026-07-25` → `2026-07-27`. (b) weekly dueDays anchor `2026-07-06`, after `2026-07-06`, through `2026-07-27` → `[13, 20, 27]` July days. (c) monthly anchor `2026-01-31` produces a Feb day ≤ 28 rolled to trading. (d) dueDays across Thanksgiving week rolls the holiday hit. (e) empty when `afterDay ≥ throughDay`. Run — FAILS.
- [ ] **Step 2: Implement** per notes. `rollToTradingDay` loops `+1 day` while weekend or `calendar.holiday(on:) != nil` (cap 10 iterations, then return input — defensive, unreachable with real calendars).
- [ ] **Step 3:** `swift test --filter PieSchedule` — PASS.
- [ ] **Step 4: Commit** `feat(domain): cadence due-day generation with holiday-aware trading-day roll`

---

### Task 5: DCA backtest

**Files:**
- Create: `Sources/APTradeDomain/PieBacktest.swift`
- Test: `Tests/APTradeDomainTests/PieBacktestTests.swift`

**Interfaces — produces:**

```swift
public struct BacktestPoint: Equatable, Sendable {
    public let day: String
    public let invested: Money
    public let value: Money
    public init(day: String, invested: Money, value: Money)
}
public struct BacktestReport: Equatable, Sendable {
    public let points: [BacktestPoint]     // one per executed contribution + final day
    public let totalInvested: Money
    public let finalValue: Money
    public let totalReturn: Percentage     // (final/invested − 1) × 100, 2 dp
    public let lumpSumFinalValue: Money    // whole total invested on first due day
    public init(...)                        // memberwise
}
public enum PieMathBacktest {
    /// `dailyCloses[symbol][day]` = close. A due day missing a close for ANY slice
    /// symbol is skipped entirely (no partial buys — mirrors live insufficient-data
    /// semantics). Returns nil when no due day is executable ("insufficient history").
    public static func dcaBacktest(slices: [PieSlice], amount: Money, cadence: PieCadence,
                                   startDay: String, endDay: String,
                                   dailyCloses: [String: [String: Money]],
                                   calendar: MarketCalendar) -> BacktestReport?
}
```

- [ ] **Step 1: Failing tests.** Fixture: two symbols, 60/40, flat prices (A=$10, B=$20 every day) — monthly $100 over 3 months → invested $300, value $300, return 0.00%. Rising fixture (A doubles linearly) → final value computed by hand in the test and matched exactly. Lump-sum on the rising fixture beats DCA (assert `lumpSumFinalValue > finalValue`). Missing-close day is skipped (invested $200 not $300). All-days-missing → nil. Run — FAILS.
- [ ] **Step 2: Implement.** Iterate `PieSchedule.dueDays`; per executable day: `distribute(amount, currentSimValues, slices)` → per-symbol `quantity += share/close`; value each point with that day's closes; final point valued at `endDay`'s (or last available ≤ endDay) closes. Lump-sum: one distribute of `totalInvested` on the first executable day, valued at the same final closes.
- [ ] **Step 3:** `swift test --filter PieBacktest` — PASS.
- [ ] **Step 4: Commit** `feat(domain): historical DCA backtest with lump-sum comparison`

---

### Task 6: Transaction pieId tag

**Files:**
- Modify: `Sources/APTradeDomain/Trade.swift:13-30` (Transaction)
- Modify: `Sources/APTradeDomain/Portfolio.swift` (`buying`/`selling` signatures)
- Test: `Tests/APTradeDomainTests/TransactionPieIdTests.swift`

**Interfaces — produces:** `Transaction.pieId: String?` (last stored property; init gains `pieId: String? = nil`). `Portfolio.buying(_:quantity:at:on:pieId:)` and `selling(_:quantity:at:on:pieId:)` gain trailing `pieId: String? = nil`, threaded into the created `Transaction`.

- [ ] **Step 1: Failing tests.** (a) JSON of a pre-M7 transaction (hand-written literal without `pieId`) decodes with `pieId == nil` (synthesized Codable uses `decodeIfPresent` for optionals — the test proves it); (b) round-trip preserves a set pieId; (c) `buying(..., pieId: "p1")` yields a transaction tagged `"p1"`; untagged call yields nil. Run — FAILS.
- [ ] **Step 2: Implement** the three signature changes. All existing call sites compile unchanged (defaulted parameter).
- [ ] **Step 3:** Full `swift test` — PASS (no regressions; existing portfolio tests untouched).
- [ ] **Step 4: Commit** `feat(domain): optional pieId attribution on transactions and trades`

---

### Task 7: PieStore port, CRUD use cases, UserDefaults adapter

**Files:**
- Modify: `Sources/APTradeApplication/Ports.swift` (append protocol)
- Create: `Sources/APTradeApplication/PieUseCases.swift`
- Create: `Sources/APTradeInfrastructure/UserDefaultsPieStore.swift`
- Test: `Tests/APTradeApplicationTests/PieUseCasesTests.swift`, `Tests/APTradeInfrastructureTests/UserDefaultsPieStoreTests.swift`

**Interfaces — produces:**

```swift
public protocol PieStore: Sendable {
    func load() -> [Pie]
    func save(_ pies: [Pie])
}
public struct LoadPies: Sendable { public init(store: PieStore); public func callAsFunction() -> [Pie] }
public struct SavePie: Sendable {   // create-or-replace by id
    public init(store: PieStore); public func callAsFunction(_ pie: Pie) -> [Pie]
}
public struct DeletePie: Sendable { public init(store: PieStore); public func callAsFunction(id: String) -> [Pie] }
```

`UserDefaultsPieStore` mirrors `UserDefaultsBookmarkStore` byte-for-byte in shape (key `"pies"`, decode-failure → `[]` without overwriting).

- [ ] **Step 1: Failing tests.** In-memory fake store in the use-case test file (`final class FakePieStore: PieStore` with a var array). SavePie inserts new / replaces same-id; DeletePie removes by id, no-ops on unknown id; LoadPies returns stored. Adapter test: round-trip through an isolated `UserDefaults(suiteName: #file)`, corrupt-data → `[]` (existing store tests show the suite pattern — see `Tests/APTradeInfrastructureTests/UserDefaultsWatchlistStoreTests.swift`). Run — FAILS.
- [ ] **Step 2: Implement** all three use cases + adapter.
- [ ] **Step 3:** `swift test --filter Pie` — PASS.
- [ ] **Step 4: Commit** `feat(app): PieStore port, CRUD use cases, UserDefaults adapter`

---

### Task 8: ContributeToPie

**Files:**
- Modify: `Sources/APTradeApplication/PieUseCases.swift`
- Test: `Tests/APTradeApplicationTests/ContributeToPieTests.swift`

**Interfaces — produces:**

```swift
public enum ContributionOutcome: Equatable, Sendable {
    case executed(Portfolio, Pie)
    case skippedInsufficientCash(Pie)      // pie updated with missed activity entry
}
public struct ContributeToPie: Sendable {
    public init(pieStore: PieStore, portfolioStore: PortfolioStore, market: MarketDataRepository)
    /// Prices via live quotes; `day` stamps activity + transaction date.
    public func callAsFunction(pieId: String, amount: Money, day: String, now: Date) async throws -> ContributionOutcome
}
```

Semantics (all asserted): fetch quotes for all slice symbols; slice current values = pie ledger quantity × quote price; `PieMath.distribute`; **pre-check** `amount ≤ portfolio.cash` else record `missedInsufficientCash` activity on the pie, save pie, return `.skippedInsufficientCash` (whole contribution skipped, never partial). Otherwise: for each share, `quantity = share/price` (no rounding — fractional shares), `portfolio.buying(asset, ..., pieId: pieId)`; ledger quantities incremented; `contribution` activity appended; both stores saved. Asset construction for a ledger symbol reuses the slice's `assetKind` and symbol (match how `BuyAsset` constructs — see `Sources/APTradeApplication/PortfolioUseCases.swift:17`).

- [ ] **Step 1: Failing tests.** Fake `MarketDataRepository` (transcribe the fake used in `Tests/APTradeApplicationTests/WatchlistUseCasesTests.swift` — quote map keyed by symbol) + fake stores. Cases: (a) $100 into fresh 50/50 pie at A=$10,B=$25 → portfolio holds 5 A + 2 B, cash −$100, both transactions tagged with pieId, ledger matches, activity has one `contribution`; (b) cash $50 < amount $100 → `.skippedInsufficientCash`, portfolio untouched, pie gains `missedInsufficientCash` entry; (c) drifted ledger routes to underweight slice (reuses Task 2 fixture (b) through the full use case). Run — FAILS.
- [ ] **Step 2: Implement.** Run tests — PASS.
- [ ] **Step 3: Commit** `feat(app): pie contributions with self-balancing buys and skip-on-insufficient-cash`

---

### Task 9: ExecuteDueContributions — catch-up

**Files:**
- Modify: `Sources/APTradeApplication/PieUseCases.swift`
- Test: `Tests/APTradeApplicationTests/ExecuteDueContributionsTests.swift`

**Interfaces — produces:**

```swift
public struct ExecuteDueContributions: Sendable {
    public init(pieStore: PieStore, portfolioStore: PortfolioStore,
                market: MarketDataRepository, calendar: MarketCalendar)
    /// For every scheduled pie: due days in (lastKnown, today] execute in date
    /// order at that day's historical CLOSE (from daily history), today's due day
    /// at the live quote. Advances schedule.nextDueDay past today. Returns
    /// per-pie outcomes for notification display.
    public func callAsFunction(now: Date) async -> [(pie: Pie, outcomes: [ContributionOutcome])]
}
```

Historical closes come through the existing `MarketDataRepository` daily-history method (find the exact signature via `grep -n 'func' Sources/APTradeApplication/Ports.swift` — the method the performance reconstruction already uses for daily series; reuse it identically). A due day whose close is missing for any slice symbol is recorded as missed with `manualAdjustment`? — NO: record nothing and leave that day consumed (mirror backtest skip semantics; asserted in test d).

- [ ] **Step 1: Failing tests.** Fake market with canned daily closes + live quotes. (a) two missed monthly days execute in ascending order, each at its own day's close, portfolio cash reflects both; (b) `nextDueDay` advanced to the first due day after `now`; (c) insufficient cash on the second day → first executes, second recorded missed; (d) missing close for one symbol on day 1 → day 1 skipped silently, day 2 executes; (e) pies without schedules untouched. Run — FAILS.
- [ ] **Step 2: Implement.** Run — PASS.
- [ ] **Step 3: Commit** `feat(app): catch-up execution of missed pie contributions at historical closes`

---

### Task 10: RebalancePie + ledger clamp

**Files:**
- Modify: `Sources/APTradeApplication/PieUseCases.swift`
- Test: `Tests/APTradeApplicationTests/RebalancePieTests.swift`

**Interfaces — produces:**

```swift
public struct RebalancePie: Sendable {
    public init(pieStore: PieStore, portfolioStore: PortfolioStore, market: MarketDataRepository)
    /// Preview: current values + orders; executes nothing.
    public func preview(pieId: String) async throws -> [RebalanceOrder]
    /// Executes the previewed orders: sells first (freeing cash), then buys, all
    /// tagged pieId; ledger updated; `rebalance` activity appended.
    public func execute(pieId: String, day: String, now: Date) async throws -> (Portfolio, Pie)
}
public struct ReconcilePieLedgers: Sendable {
    public init(pieStore: PieStore, portfolioStore: PortfolioStore)
    /// Clamps every pie ledger entry to the actually-held portfolio quantity
    /// (largest-ledger pie clamps first when multiple pies claim one symbol).
    /// Appends a `manualAdjustment` activity entry to each clamped pie.
    public func callAsFunction() -> [Pie]
}
```

- [ ] **Step 1: Failing tests.** (a) preview matches `PieMath.rebalancePlan` for a drifted fixture and leaves stores untouched; (b) execute on (A=$70,B=$30, 50/50) sells A $20 / buys B $20 — cash unchanged within a cent, ledger reflects new quantities, transactions tagged; (c) sell-before-buy proven by a fixture whose buys exceed starting cash but fit after sells; (d) reconcile: portfolio holds 3 A, pies claim 5 A (pie1 ledger 4, pie2 ledger 1) → pie1 clamps to 2 (largest first), pie2 keeps 1, both… only pie1 gains `manualAdjustment`; (e) no clamp → no activity. Run — FAILS.
- [ ] **Step 2: Implement.** Run — PASS.
- [ ] **Step 3: Commit** `feat(app): manual rebalance with preview and pie-ledger reconciliation`

---

### Task 11: SimulateDCA

**Files:**
- Modify: `Sources/APTradeApplication/PieUseCases.swift`
- Test: `Tests/APTradeApplicationTests/SimulateDCATests.swift`

**Interfaces — produces:**

```swift
public struct SimulateDCA: Sendable {
    public init(market: MarketDataRepository, calendar: MarketCalendar)
    /// Fetches daily history for each slice symbol over `years` (1/3/5) and runs
    /// PieMathBacktest.dcaBacktest. nil = insufficient history (UI shows a note).
    public func callAsFunction(slices: [PieSlice], amount: Money, cadence: PieCadence,
                               years: Int, now: Date) async -> BacktestReport?
}
```

- [ ] **Step 1: Failing tests.** Fake market serving fixture daily series → report matches a direct `dcaBacktest` call on the same data; a symbol with no history → nil; network failure on one symbol → nil (degrade, never throw — matches `FetchEarningsCalendarUseCase` degrade-to-empty precedent). Run — FAILS.
- [ ] **Step 2: Implement.** Run — PASS.
- [ ] **Step 3: Commit** `feat(app): DCA simulation use case over live daily history`

---

### Task 12: Planner event + settings toggle + notifier

**Files:**
- Modify: `Sources/APTradeApplication/MarketActivityPlanner.swift` (`ScheduledNotification.contributionCheckDue`, `SchedulerState.lastContributionDay: String?`)
- Modify: Swift `AppSettings` (`grep -rn 'earningsReports' Sources/APTradeDomain` — add `pieContributions: Bool = true` beside it, same missing-key-tolerant decode mechanism)
- Modify: `Sources/APTradeApplication/Ports.swift` — `MarketEventNotifier` family gains `func notifyPieContribution(title: String, body: String) async` (mirror `notifyEarnings` at `Ports.swift:84`)
- Test: modify `Tests/APTradeApplicationTests/MarketActivityPlannerTests.swift`; extend the AppSettings round-trip test file

- [ ] **Step 1: Failing tests.** Planner: during open market, `contributionCheckDue` fires once per trading day when `settings.pieContributions` (mirror the two `earningsCheckDue` cases exactly — once-per-day guard via `lastContributionDay`, suppressed when toggle off). Settings: JSON without `pieContributions` decodes to `true`. Run — FAILS.
- [ ] **Step 2: Implement** — transcribe the `earningsCheckDue` emission block with the new state field/toggle; extend notifier protocol + its `UserNotificationAlertNotifier` and test-fake conformances (`grep -rn 'notifyEarnings' Sources Tests` and mirror every site).
- [ ] **Step 3:** Full `swift test` — PASS.
- [ ] **Step 4: Commit** `feat(app): contribution-check planner event, settings gate, notifier port`

---

### Task 13: ViewModels

**Files:**
- Create: `Sources/APTradeApp/PlansViewModel.swift`, `Sources/APTradeApp/PieWizardViewModel.swift`
- Test: `Tests/APTradeAppTests/PlansViewModelTests.swift`, `Tests/APTradeAppTests/PieWizardViewModelTests.swift`

**Interfaces — produces:**

```swift
@Observable @MainActor public final class PlansViewModel {
    public struct PieRow: Identifiable, Equatable {   // list card
        public let id: String; public let name: String
        public let currentValue: Money; public let nextContributionLabel: String?
        public let maxDriftPP: Decimal                 // badge shown when > 5
        public let sliceWeights: [(String, Percentage)]
    }
    public private(set) var rows: [PieRow]
    public private(set) var detail: PieDetail?         // struct: slices w/ target vs actual + drift, activity, schedule
    public private(set) var rebalancePreview: [RebalanceOrder]?
    public private(set) var errorMessage: String?
    public func onAppear() async                       // loads pies, quotes, reconciles ledgers
    public func openDetail(id: String) async
    public func contributeNow(id: String, amount: Money) async
    public func requestRebalance(id: String) async     // fills rebalancePreview
    public func confirmRebalance(id: String) async
    public func deletePie(id: String) async
}
@Observable @MainActor public final class PieWizardViewModel {
    public var name: String; public var slices: [PieSlice]
    public var scheduleAmountText: String; public var cadence: PieCadence; public var scheduleEnabled: Bool
    public private(set) var weightSumPP: Decimal       // live 100-check
    public private(set) var canSave: Bool
    public private(set) var backtest: BacktestReport?  // nil until run / insufficient
    public func addSlice(asset: Asset) ; public func removeSlice(symbol: String)
    public func setWeight(symbol: String, pp: Decimal) ; public func equalSplit()
    public func runBacktest(years: Int) async
    public func save() async -> Bool                   // creates/updates via SavePie
}
```

Both take their use cases via init injection (match `WatchlistViewModel`'s constructor style — `grep -n 'init' Sources/APTradeApp/WatchlistViewModel.swift`).

- [ ] **Step 1: Failing tests.** Plans: onAppear builds rows with values from fake quotes; drift badge math (>5pp fixture); contributeNow refreshes rows and surfaces insufficient-cash as `errorMessage` (localized key from Task 15); rebalance request→confirm flow mutates stores. Wizard: weight sum validation drives `canSave` (100 exact); equalSplit on 3 slices yields 33.33/33.33/33.34 (largest-remainder — reuse `PieMath` rounding helper by making it `internal static`); save constructs a valid Pie (schedule only when enabled, `nextDueDay` = `PieSchedule.nextDueDay` from today); backtest wires SimulateDCA. Run — FAILS.
- [ ] **Step 2: Implement both.** Run `swift test --filter ViewModelTests` — PASS.
- [ ] **Step 3: Commit** `feat(app): Plans and Pie-wizard view models`

---

### Task 14: SwiftUI — Plans section, detail, wizard, rebalance sheet

**Files:**
- Modify: `Sources/APTradeApp/PortfolioView.swift:6-15` (Section enum: `case plans = "Plans"`, title `tr(.plansSection)`, render arm at `:300` switch)
- Create: `Sources/APTradeApp/PlansSection.swift` (list + detail), `Sources/APTradeApp/PieWizardView.swift`
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (construct stores/use cases/VMs; find wiring block via `grep -n 'PortfolioView\|performanceVM' Sources/APTradeApp`)

UI anatomy (all strings via Task 15 keys; every color from `Theme`):
- **List:** `ForEach(vm.rows)` cards — name, mini-donut (reuse the existing allocation donut view: `grep -rn 'Donut\|allocationView' Sources/APTradeApp/PortfolioView.swift` and extract/reuse its chart builder), value via the existing `MoneyText`-style superscript formatting, next-contribution line, drift badge (`Theme.gold` capsule) when `maxDriftPP > 5`. Empty state: icon + `tr(.plansEmptyTitle)` + Create button. Toolbar "+" opens the wizard.
- **Detail:** donut of target weights; per-slice rows: symbol, target vs actual pp, signed drift bar (rect scaled by |drift|, green/red per sign — data colors, never accent); schedule line; buttons: Contribute now (numeric field sheet), Rebalance now, Edit, Delete (confirmation `alert`). Rebalance sheet lists `RebalanceOrder` rows (side-colored), Confirm honors the existing Confirm Trades setting (`grep -rn 'confirmTrades' Sources/APTradeApp` and reuse the same gate), Cancel dismisses.
- **Wizard:** 4 steps in a sheet (`NavigationStack`): name field → slice editor (search field reusing the palette's search use case + weight steppers + equal-split button + live sum label turning red ≠ 100) → schedule (toggle, amount field, cadence `Picker`) → backtest pane (`Chart` with invested + value lines over 1Y/3Y/5Y picker, total-return line, lump-sum footnote; `tr(.backtestInsufficient)` when nil). Save disabled until `canSave`.
- iOS: sheets get `presentationBackground` theming like the existing alert sheet (`grep -rn 'presentationBackground' Sources/APTradeApp` — copy the exact modifier chain). macOS unchanged elsewhere.

- [ ] **Step 1:** Implement views + switcher arm + wiring. Build: `swift build` — compiles.
- [ ] **Step 2:** Full `swift test` — PASS (VM tests cover logic; views are declarative-only per house rules — no logic to unit-test).
- [ ] **Step 3:** Launch `"$(swift build --show-bin-path)/APTradeMac"` — visually verify: Plans section appears, wizard creates a Pie, detail renders, contribute/rebalance flows work. (User-verified on this machine — computer-use can't target the dev binary.)
- [ ] **Step 4: Commit** `feat(macos): Plans section — pie list, detail, wizard, rebalance preview`

---

### Task 15: L10n keys (EN/DE/IT/ES)

**Files:**
- Modify: `Sources/APTradeApp/L10n.swift` (new `// MARK: Plans` key block + `table` rows)
- Test: existing catalog-completeness test (`grep -rn 'CaseIterable' Tests/APTradeAppTests/L10nTests.swift`) — no new test file needed

Keys (raw value = English source): `plansSection = "Plans"`, `plansEmptyTitle = "Build your first Plan"`, `createPlan = "Create Plan"`, `editPlan = "Edit Plan"`, `deletePlan = "Delete Plan"`, `deletePlanConfirm = "Delete this plan? Holdings stay in your portfolio."`, `sliceWeights = "Weights"`, `equalSplit = "Equal split"`, `weightSumLabel = "Total"`, `scheduleSection = "Schedule"`, `cadenceWeekly = "Weekly"`, `cadenceBiweekly = "Every 2 weeks"`, `cadenceMonthly = "Monthly"`, `nextContribution = "Next contribution"`, `contributeNow = "Contribute Now"`, `rebalanceNow = "Rebalance Now"`, `rebalancePreviewTitle = "Rebalance Preview"`, `driftLabel = "Drift"`, `backtestTitle = "Backtest"`, `backtestInvested = "Invested"`, `backtestValue = "Value"`, `backtestLumpSum = "Lump sum comparison"`, `backtestInsufficient = "Not enough price history for this backtest."`, `missedContribution = "Contribution skipped — insufficient cash"`, `manualAdjustmentNote = "Manually adjusted"`, `pieContributionsToggle = "Plan Contributions"`, plus notification title/body pairs `notifPieExecutedTitle/Body`, `notifPieSkippedTitle/Body`. DE/IT/ES translations are the author's own — mark provisional in the commit body per the 6e convention.

- [ ] **Step 1:** Add keys + 4-language rows. Run the catalog test: `swift test --filter L10n` — PASS (completeness enforced).
- [ ] **Step 2:** Sweep Task 13/14 files for any hard-coded string (`grep -n '"' Sources/APTradeApp/PlansSection.swift Sources/APTradeApp/PieWizardView.swift | grep -v 'tr(\|Key\|systemImage\|identifier'`) — zero user-visible literals.
- [ ] **Step 3: Commit** `feat(l10n): Plans catalog keys in EN/DE/IT/ES (non-EN provisional)`

---

### Task 16: Coordinator wiring, catch-up on launch, README

**Files:**
- Modify: `Sources/APTradeApp/MarketActivityCoordinator.swift` — handle `.contributionCheckDue`: run `ExecuteDueContributions`, then notify per outcome (executed → `notifPieExecutedTitle`; skipped → `notifPieSkippedTitle`) via the Task 12 notifier method; also invoke once at coordinator start (catch-up on launch) before the tick loop (find the start/tick structure via `grep -n 'func\|Task {' Sources/APTradeApp/MarketActivityCoordinator.swift`)
- Modify: `Sources/APTradeApp/RootView.swift` notifications settings page — add the `pieContributionsToggle` row beside the earnings toggle (`grep -n 'earnings' Sources/APTradeApp/RootView.swift`)
- Modify: `README.md` — add an "Investment Plans" Features block (after "Calendar & earnings") + Domain/Application/Infrastructure architecture-list mentions + bump test counts to actual
- Test: `Tests/APTradeAppTests/MarketActivityCoordinatorTests.swift` (extend if present; else cover via planner tests already done)

- [ ] **Step 1:** Wire coordinator + settings row. Build + full suite: `DEVELOPER_DIR=/Applications/Xcode.app swift test` — ALL PASS; record the new total.
- [ ] **Step 2:** iOS simulator suite (park `APTrade.xcodeproj` per the README gotcha): `xcodebuild test -scheme APTradeLite-Package -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -skipPackagePluginValidation` — PASS.
- [ ] **Step 3:** README block (feature bullets: Pies, self-balancing contributions, catch-up, rebalance preview, wizard backtest; architecture lists gain `Pie`/`PieMath`/`PieStore`/`UserDefaultsPieStore`; tests badge → new count).
- [ ] **Step 4: Commit** `feat(macos,ios): Plans wired into coordinator + notifications; README M7.1`

---

## Self-Review Notes

- **Spec coverage:** §A model→Tasks 1–6; distribute/rebalance/drift/backtest→2,3,5; §B execution/catch-up/insufficient-cash/notifications→9,12,16; §C persistence→7; §D UI/wizard/L10n→13–15; §E fixtures/tests→every task; manual-sell clamp→10; Confirm Trades gate→14. Kotlin/desktop/Android sections of the spec are M7.2/M7.3 plans (see header).
- **Type consistency:** `PieSlice`/`PieCadence`/`ContributionSchedule` names used identically in Tasks 1–14; `RebalanceOrder` produced in 3, consumed in 10/13/14; `ContributionOutcome` produced in 8, consumed in 9/16.
- **Known repo gotchas honored:** `DEVELOPER_DIR` for tests; xcodeproj shadows package scheme (Task 16 Step 2); computer-use can't verify the dev binary (Task 14 Step 3 → user verifies).
