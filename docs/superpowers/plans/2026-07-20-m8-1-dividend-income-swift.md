# M8.1 — Dividend & Income Engine: Shared-core fetch + Swift core + macOS/iPhone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Held stocks/ETFs automatically earn their dividends — credited as cash or DRIP-reinvested, fully backfilled from the transaction ledger — surfaced through an Income section, a history feed, upcoming-dividend projections with notifications, and dividend info on asset detail (macOS + iPhone).

**Architecture:** Dividend events are fetched once, in the shared Kotlin core (`YahooMarketDataRepository`, chart endpoint + `events=div`), and bridged to Swift through the existing xcframework. Dividends are **first-class transactions** (`TradeSide.dividend`; DRIP = dividend entry + `isDrip` buy). Pure math in `APTradeDomain` (`DividendMath`), the `ProcessDueDividends` engine in `APTradeApplication` (mirrors `ExecuteDueContributions`: TradeSerializer, ledger dedup, crash-safe replays), UI as an Income section in the Portfolio sub-switcher. Spec: `docs/superpowers/specs/2026-07-20-dividend-income-engine-design.md`. M8.2 (Kotlin engine + Windows) and M8.3 (Android) transcribe this reference with identical fixtures.

**Tech Stack:** Kotlin Multiplatform (ktor, kotlinx-serialization, bignum), Swift 6 (`async/await`, `Sendable`), SwiftUI, XCTest, SwiftPM.

## Global Constraints

- Swift 6 strict concurrency; no force unwraps; every new type `Sendable` where the layer's peers are.
- Swift domain layer: Foundation only; no networking, no persistence. Kotlin shared domain: no ktor/serialization imports.
- Money is USD-only (`Money(amount:)` default currency). Money rounding to 2 dp via `NSDecimalRound(..., 2, .plain)` where amounts are displayed/credited; DRIP share quantities stay unrounded (fractional, like Pies).
- All user-visible strings go through `L10n`/`tr(_:)`; new keys need EN/DE/IT/ES rows (catalog-completeness test enforces).
- iOS differences `#if os(iOS)`-gated; macOS output otherwise byte-identical.
- Swift test command: `DEVELOPER_DIR=/Applications/Xcode.app swift test` (needs `./scripts/build-shared.sh` once per clone, and again after any shared-core change — allow a 10-minute timeout, the Gradle xcframework assembly is slow). Filter: `swift test --filter <Name>`.
- Kotlin test command: `./gradlew :shared:jvmTest` (set `JAVA_HOME` per `gradlew` expectations if the default JDK is wrong).
- **Never guess bridged Kotlin names in Swift** — after rebuilding the xcframework, verify the exact symbol names in the generated `Shared.xcframework` Objective-C header before writing Swift code against them.
- Crypto never pays dividends: every engine/UI path filters to `AssetKind.stock` / `.etf` (Kotlin: `AssetKind.Stock` / `.Etf`).
- Dividend transactions: `quantity` = shares held at ex-date, `price` = amount per share, `date` = ex-date, cash effect `+quantity×price`, positions/cost basis untouched.
- Dedup key everywhere: a `.dividend` transaction with the same `symbol` and the same ex-date **trading day** (`MarketCalendar.tradingDay(of:)`) means already credited.
- Commit after every task; conventional-commit messages; no pushes (user pushes at milestone end).

---

### Task 1: Kotlin — DividendEvent domain + Yahoo DTO/mapper

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/DividendEvent.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooChartDTO.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooQuoteMapper.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooDividendMapperTest.kt`

**Interfaces — produces:**

```kotlin
// domain/DividendEvent.kt — pure domain, no framework imports
data class DividendEvent(
    val symbol: String,
    val exDateEpochSeconds: Long,
    val amountPerShare: Money,
)

// YahooChartDTO.kt — ResultItem gains:
val events: Events? = null
@Serializable data class Events(val dividends: Map<String, DividendCell>? = null)
@Serializable data class DividendCell(
    @Serializable(with = BigDecimalWireSerializer::class) val amount: BigDecimal? = null,
    val date: Long? = null,
)

// YahooQuoteMapper gains:
/** Parses events.dividends into ascending-by-exDate events. Cells with a null
 *  amount or date, or a non-positive amount, are dropped (never throw). Events
 *  strictly before fromEpochSeconds are filtered out. Currency from meta ("USD"
 *  fallback). Symbol from meta.symbol. */
fun dividends(response: YahooChartResponse, fromEpochSeconds: Long): List<DividendEvent>
```

- [ ] **Step 1: Failing tests.** Fixture JSON string shaped like a real chart response (`chart.result[0]` with `meta.symbol = "AAPL"`, `meta.currency = "USD"`, and an `events.dividends` map of 4 cells: two valid quarterly amounts as exact decimals like `0.24`, one cell missing `amount`, one with `date` null), decoded via the existing `yahooJson`. Asserts: (a) two events, ascending `exDateEpochSeconds`; (b) amounts exact via BigDecimal (`"0.24"`, no Double drift); (c) malformed cells dropped; (d) `fromEpochSeconds` past the first event filters it; (e) response with no `events` block → empty list. Run: `./gradlew :shared:jvmTest --tests "*YahooDividendMapper*"` — FAILS (unresolved references).
- [ ] **Step 2: Implement** `DividendEvent`, the DTO additions, and `dividends(...)`. Run — PASS.
- [ ] **Step 3: Commit** `feat(shared): Yahoo dividend event DTO + mapper`

---

### Task 2: Kotlin — repository method + FetchDividendEvents + xcframework rebuild

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/application/MarketDataRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepository.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchDividendEvents.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/YahooMarketDataRepositoryTest.kt` (extend)
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchDividendEventsTest.kt`

**Interfaces:**
- Consumes: `YahooQuoteMapper.dividends(response, fromEpochSeconds)`, `DividendEvent` (Task 1).
- Produces:

```kotlin
// MarketDataRepository gains (default so StubMarketDataRepository & fakes compile unchanged):
suspend fun dividendEvents(symbol: String, fromEpochSeconds: Long): List<DividendEvent> = emptyList()

// YahooMarketDataRepository overrides it:
//   fetchChart(symbol, range = "max", interval = "1mo", events = "div")  — extend the
//   private fetchChart with an `events: String? = null` param appended as the `events`
//   query parameter only when non-null (existing call sites unchanged).
//   Returns YahooQuoteMapper.dividends(response, fromEpochSeconds).

// application/FetchDividendEvents.kt — mirrors FetchCandles exactly:
class FetchDividendEvents(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String, fromEpochSeconds: Long): List<DividendEvent>
}
```

- [ ] **Step 1: Failing tests.** Repository test (existing mock-engine pattern in `YahooMarketDataRepositoryTest`): asserts the request URL contains `range=max`, `interval=1mo`, `events=div`, and that a canned dividend payload maps through. Use-case test: fake `MarketDataRepository` returning two events → `execute` passes them through; the interface default returns `emptyList()`. Run: `./gradlew :shared:jvmTest --tests "*Dividend*"` — FAILS.
- [ ] **Step 2: Implement.** Run — PASS. Then run the full shared suite: `./gradlew :shared:jvmTest` — PASS (no regressions from the fetchChart signature change).
- [ ] **Step 3: Rebuild the xcframework** for Swift consumption: `./scripts/build-shared.sh` (10-minute timeout). Then verify the bridged names (`DividendEvent`, `FetchDividendEvents`, the `dividendEvents` method) in the generated Shared header — record the exact spellings for Task 5.
- [ ] **Step 4: Commit** `feat(shared): dividendEvents repository method + FetchDividendEvents use case`

---

### Task 3: Swift domain — dividend transactions

**Files:**
- Modify: `Sources/APTradeDomain/Trade.swift`
- Modify: `Sources/APTradeDomain/Portfolio.swift`
- Test: `Tests/APTradeDomainTests/PortfolioDividendTests.swift`

**Interfaces — produces:**

```swift
// Trade.swift
public enum TradeSide: String, Codable, Sendable {
    case buy, sell, dividend
}
// Transaction gains a stored property (after pieId):
public let isDrip: Bool
// init gains `isDrip: Bool = false` (last parameter). Add a custom
// `init(from decoder:)` that uses decodeIfPresent for BOTH `pieId` and `isDrip`
// (defaults nil / false) so every previously persisted ledger decodes unchanged.

// Portfolio.swift
/// Credits a dividend: cash += shares × amountPerShare, appends a `.dividend`
/// transaction (quantity = shares, price = amountPerShare, date = exDate).
/// Positions and cost basis untouched. Throws `TradeError.invalidQuantity`
/// when `shares.isZero` or `amountPerShare.amount <= 0`.
public func receivingDividend(_ symbol: String, amountPerShare: Money,
                              shares: Quantity, on exDate: Date) throws -> Portfolio
// buying(_:quantity:at:on:pieId:) gains `isDrip: Bool = false`, forwarded into
// the Transaction it records.
```

Sweep every exhaustive `switch` over `TradeSide` the compiler flags (expect: activity rendering in `PortfolioView`, export/CSV naming, trade sheets) — for now map `.dividend` to a sensible neutral (label "Dividend" comes in Task 8's L10n; use the raw value until then only inside non-UI code; UI files touched later can temporarily use `tr(.activityDividend)` once Task 8 lands — if a UI file must compile NOW, add the key in THIS task instead and note it for Task 8).

- [ ] **Step 1: Failing tests.** (a) `receivingDividend` adds cash `shares × amount`, appends `.dividend` txn with the exact fields, positions unchanged; (b) zero shares / non-positive amount throw `invalidQuantity`; (c) `buying(..., isDrip: true)` records `isDrip` on the txn; (d) back-compat: JSON of a pre-M8 transaction (no `isDrip` key) decodes with `isDrip == false`; (e) a `.dividend` txn round-trips through Codable. Run: `swift test --filter PortfolioDividendTests` — FAILS.
- [ ] **Step 2: Implement.** Run filtered — PASS. Run the full domain suite: `swift test --filter APTradeDomainTests` — PASS.
- [ ] **Step 3: Commit** `feat(domain): dividend transactions — TradeSide.dividend, isDrip, receivingDividend`

---

### Task 4: Swift domain — DividendMath

**Files:**
- Create: `Sources/APTradeDomain/DividendMath.swift`
- Test: `Tests/APTradeDomainTests/DividendMathTests.swift`

**Interfaces:**
- Consumes: `Transaction` (Task 3), `Money`, `Quantity`, `Percentage`.
- Produces:

```swift
/// A dividend event as the Swift domain sees it (bridged from the shared core
/// by infrastructure; pure value here).
public struct DividendEvent: Equatable, Sendable {
    public let symbol: String
    public let exDate: Date
    public let amountPerShare: Money
    public init(symbol: String, exDate: Date, amountPerShare: Money)
}

public enum DividendCadence: Equatable, Sendable { case monthly, quarterly, semiAnnual, annual }

public enum DividendMath {
    /// Shares held STRICTLY BEFORE `date`: sum of buy quantities minus sell
    /// quantities across transactions with `txn.date < date` (dividend entries
    /// contribute nothing; DRIP buys count like any buy).
    public static func sharesHeld(symbol: String, at date: Date,
                                  transactions: [Transaction]) -> Quantity

    /// Sum of event amounts with exDate in (asOf − 365 days, asOf]. Zero when none.
    public static func trailingAnnualPerShare(events: [DividendEvent], asOf: Date) -> Money

    /// Median gap between consecutive ex-dates → cadence. nil when < 2 events.
    /// Buckets (days): ≤45 monthly, ≤135 quarterly, ≤270 semiAnnual, else annual.
    public static func inferredCadence(events: [DividendEvent]) -> DividendCadence?

    /// Last exDate + cadence interval (monthly 30d, quarterly 91d, semiAnnual 182d,
    /// annual 365d), amount = last event's amount. nil when cadence is nil.
    public static func nextProjected(events: [DividendEvent]) -> DividendEvent?

    /// Received dividend cash per calendar month (UTC, "yyyy-MM" keys) from
    /// `.dividend` transactions only.
    public static func monthlyReceived(transactions: [Transaction]) -> [String: Money]

    /// trailingAnnualPerShare × shares, per held symbol, summed. Symbols absent
    /// from `eventsBySymbol` contribute zero.
    public static func projectedAnnualIncome(positions: [Position],
                                             eventsBySymbol: [String: [DividendEvent]],
                                             asOf: Date) -> Money
}
```

- [ ] **Step 1: Failing tests.** (a) sharesHeld: buys/sells before the date net out; a buy AT the ex-date instant is excluded (strictly-before); DRIP buys count; (b) trailingAnnualPerShare sums exactly the last 365 days (event at exactly −365d excluded, at asOf included); (c) cadence: 4 quarterly events → `.quarterly`; 1 event → nil; monthly spacing → `.monthly`; (d) nextProjected = last exDate + 91d for quarterly, amount = last amount; (e) monthlyReceived groups two same-month dividends into one "2026-07" bucket and ignores buys/sells; (f) projectedAnnualIncome multiplies per-symbol trailing sums by position quantities and adds. Run: `swift test --filter DividendMathTests` — FAILS.
- [ ] **Step 2: Implement.** Run — PASS.
- [ ] **Step 3: Commit** `feat(domain): DividendMath — held-shares reconstruction, trailing rate, cadence projection`

---

### Task 5: Swift port + shared-core bridge

**Files:**
- Modify: `Sources/APTradeApplication/Ports.swift`
- Modify: `Sources/APTradeInfrastructure/SharedCoreMarketDataRepository.swift`
- Test: `Tests/APTradeInfrastructureTests/SharedCoreDividendMappingTests.swift`

**Interfaces:**
- Consumes: Swift `DividendEvent` (Task 4); bridged `Shared.FetchDividendEvents` / `Shared.DividendEvent` (Task 2 — use the exact names recorded from the generated header).
- Produces:

```swift
// Ports.swift
/// Supplies historical dividend events for a symbol, ascending by ex-date.
public protocol DividendEventsRepository: Sendable {
    func dividendEvents(for symbol: String, since: Date) async throws -> [DividendEvent]
}

// SharedCoreMarketDataRepository: conforms to DividendEventsRepository.
// Follows the existing closure-seam pattern: a new stored closure
//   fetchDividends: @Sendable (String, Int64) async throws -> [Shared.DividendEvent]
// added to the designated init (convenience init builds it from
// Shared.FetchDividendEvents(repository:)); the protocol method converts
// `since.timeIntervalSince1970` → Int64 epoch seconds, maps each bridged event via
// the existing `mapMoney` (amountText → Decimal, throw AppError.decoding on failure)
// and `Date(timeIntervalSince1970:)`.
```

- [ ] **Step 1: Failing tests.** Construct the repository via its closure-based designated init with a fake `fetchDividends` closure returning two bridged events (build `Shared.Money` the same way existing infrastructure tests do — follow the fixtures in `Tests/APTradeInfrastructureTests`); assert (a) epoch→Date and amountText→Decimal mapping are exact, (b) the `since` Date arrives as the right epoch seconds, (c) a malformed `amountText` throws `AppError.decoding`. Run: `swift test --filter SharedCoreDividendMappingTests` — FAILS.
- [ ] **Step 2: Implement.** Run — PASS.
- [ ] **Step 3: Commit** `feat(infra): bridge shared-core dividend events into DividendEventsRepository`

---

### Task 6: ProcessDueDividends — engine with backfill, DRIP, dedup

**Files:**
- Create: `Sources/APTradeApplication/DividendUseCases.swift`
- Modify: `Sources/APTradeApplication/MarketActivityPlanner.swift` (SchedulerState only)
- Test: `Tests/APTradeApplicationTests/ProcessDueDividendsTests.swift`

**Interfaces:**
- Consumes: `DividendEventsRepository` (Task 5), `DividendMath.sharesHeld` (Task 4), `Portfolio.receivingDividend` / `buying(isDrip:)` (Task 3), `TradeSerializer`, `PortfolioStore`, `SettingsStore`, `SchedulerStateStore`, `MarketDataRepository`, `MarketCalendar`. `AppSettings.dripEnabled` arrives in Task 7 — in THIS task read the toggle through a constructor closure `isDripEnabled: @Sendable () -> Bool` so Task 6 has no dependency on Task 7 (Task 9/13 wire it to settings).
- Produces:

```swift
// SchedulerState gains two optional fields (synthesized Codable — old payloads
// decode them as nil): 
public var lastDividendDay: String?
public var dividendsFirstRunDay: String?
// (extend the memberwise init with `= nil` defaults)

public enum DividendOutcome: Equatable, Sendable {
    case credited(symbol: String, cash: Money)
    case reinvested(symbol: String, cash: Money, shares: Quantity)
}

public struct ProcessDueDividends: Sendable {
    public init(portfolioStore: PortfolioStore, market: MarketDataRepository,
                dividends: DividendEventsRepository, stateStore: SchedulerStateStore,
                calendar: MarketCalendar, serializer: TradeSerializer,
                isDripEnabled: @escaping @Sendable () -> Bool)
    /// Never throws: a failing symbol degrades to no outcomes for that symbol.
    public func callAsFunction(now: Date) async -> [DividendOutcome]
}
```

**Semantics (each numbered rule gets a test):**

1. First-run marker: if `stateStore.load().dividendsFirstRunDay == nil`, set it to `calendar.tradingDay(of: now)` and save BEFORE processing. Events whose ex-date trading day is `< dividendsFirstRunDay` are **backfill → always cash**, regardless of the DRIP toggle. Events on/after it follow `isDripEnabled()` at processing time.
2. Candidate symbols: `portfolioStore.load().positions` with `asset.kind != .crypto`. For each, `dividends.dividendEvents(for:since:)` with `since` = that symbol's earliest transaction date in the ledger (`Date.distantPast` fallback is wrong — a symbol with a position always has at least one buy).
3. Eligibility: `DividendMath.sharesHeld(symbol:at: event.exDate, transactions:) > 0` (strictly-before).
4. Dedup: skip events where a `.dividend` transaction exists with the same symbol and `calendar.tradingDay(of:)` equal to the event's. Applies at candidate filtering AND re-checked on the fresh reload inside the serializer (see 6).
5. DRIP: fetch closes once per run via the daily-history pattern (`market.history(for:timeframe: .oneYear)` keyed by trading day — mirror `fetchClosesByDay` in `PieUseCases.swift`; it is `private`, so replicate the 15-line helper locally in `DividendUseCases.swift`). Reinvest = `receivingDividend` then `buying(asset, quantity: Quantity(cash / close), at: close, on: exDate, isDrip: true)` — quantity unrounded. Missing/non-positive close for that day → **cash fallback** (a `credited` outcome, no buy).
6. Every event applies inside its own `serializer.run` block: reload the portfolio fresh, re-run the rule-4 dedup against the fresh ledger, mutate, `portfolioStore.save` — same crash-safe per-step discipline as `ExecuteDueContributions`.
7. A thrown error while processing one symbol (network, decoding) abandons THAT symbol's remaining events silently; other symbols still process. Events already saved stay saved.

- [ ] **Step 1: Failing tests.** Fakes: in-memory stores, fake `DividendEventsRepository`, fake market with canned daily history. (a) backfill: two past events credit as cash even with DRIP on; `dividendsFirstRunDay` persisted; (b) post-first-run event with DRIP on → `.dividend` + `isDrip` buy at that day's close, fractional shares exact; (c) second run → zero outcomes (dedup); (d) buy on the ex-date itself earns nothing (strictly-before); (e) a sell before ex-date reduces the credited quantity; (f) DRIP on but close missing that day → cash fallback outcome; (g) crypto position ignored; (h) repository throws for symbol A → symbol B's event still credits; (i) DRIP off → plain cash credit for a post-first-run event. Run: `swift test --filter ProcessDueDividendsTests` — FAILS.
- [ ] **Step 2: Implement.** Run — PASS. Also run `swift test --filter MarketActivityPlannerTests` — PASS (SchedulerState change is additive).
- [ ] **Step 3: Commit** `feat(app): ProcessDueDividends — backfill, DRIP, ledger-dedup dividend crediting`

---

### Task 7: Planner event + settings fields

**Files:**
- Modify: `Sources/APTradeApplication/MarketActivityPlanner.swift`
- Modify: `Sources/APTradeDomain/AppSettings.swift`
- Test: `Tests/APTradeApplicationTests/MarketActivityPlannerTests.swift` (extend)
- Test: `Tests/APTradeDomainTests/AppSettingsLanguageTests.swift` (or the existing settings-decode test file — extend)

**Interfaces — produces:**

```swift
// ScheduledNotification gains:
case dividendCheckDue

// AppSettings gains (with lenient-decode rows exactly like the existing fields):
public var dripEnabled: Bool          // default false
public var dividendNotifications: Bool // default true
// (extend init parameter list + CodingKeys + decodeIfPresent block)

// Planner: a fourth market-open block, mirroring the contribution block BUT
// UNGATED by any setting — dividend crediting is bookkeeping truth, always on:
//   if status == .open, state.lastDividendDay != day {
//       events.append(.dividendCheckDue); newState.lastDividendDay = day
//   }
```

- [ ] **Step 1: Failing tests.** (a) planner fires `.dividendCheckDue` once on the first open tick of a trading day and not again that day; (b) it fires even when every notification toggle is false (ungated); (c) `AppSettings` decodes an old payload without the two new keys to defaults (false / true); round-trips new values. Run: `swift test --filter MarketActivityPlannerTests` — FAILS.
- [ ] **Step 2: Implement.** Run — PASS.
- [ ] **Step 3: Commit** `feat(app): dividendCheckDue planner event + DRIP/notification settings`

---

### Task 8: L10n keys (EN/DE/IT/ES)

**Files:**
- Modify: `Sources/APTradeApp/L10n.swift`
- Test: `Tests/APTradeAppTests/L10nTests.swift` (catalog-completeness test already enforces coverage — just run it)

New `Key` cases (EN raw values shown; add DE/IT/ES `table` rows for each — match the existing translations' register, e.g. DE formal "Sie"):

```swift
// MARK: Income
case incomeSection = "Income"
case incomeProjectedAnnual = "Projected Annual Income"
case incomeReceivedYTD = "Received This Year"
case incomePortfolioYield = "Portfolio Yield"
case incomeYieldOnCost = "Yield on Cost"
case incomeMonthlyTitle = "Monthly Income"
case incomeUpcomingTitle = "Upcoming Dividends"
case incomePerHoldingTitle = "Income by Holding"
case incomeHistoryTitle = "Dividend History"
case incomeEstimatedBadge = "Est."
case incomeReinvestedBadge = "Reinvested"
case incomeNoDividends = "No dividend income yet. Dividends from your holdings will appear here automatically."
case incomeLastPayment = "Last Payment"
case activityDividend = "Dividend"
// MARK: Asset detail
case assetDividendSection = "Dividends"
case assetDividendYield = "Dividend Yield"
case assetDividendRate = "Annual Rate"
case assetNextExDate = "Next Ex-Date (est.)"
// MARK: Settings
case settingsDrip = "Reinvest Dividends (DRIP)"
case settingsDripFooter = "Automatically reinvest dividends into the paying asset. Off: dividends are credited as cash."
case settingsDividendNotif = "Dividend Payments"
// MARK: Notifications
case notifDividendTitle = "Dividend Received"
case notifDividendCashBodyFmt = "%@ paid you %@"
case notifDividendDripBodyFmt = "%@ paid %@ — reinvested"
```

- [ ] **Step 1: Add keys + all four language rows.** Run: `swift test --filter L10nTests` — PASS (completeness test is the failing-test gate here: run it BEFORE adding translations to see it fail, then after to see it pass).
- [ ] **Step 2: Commit** `feat(app): dividend & income L10n keys (EN/DE/IT/ES)`

---

### Task 9: IncomeViewModel

**Files:**
- Create: `Sources/APTradeApp/IncomeViewModel.swift`
- Test: `Tests/APTradeAppTests/IncomeViewModelTests.swift`

**Interfaces:**
- Consumes: `DividendMath` (Task 4), `DividendEventsRepository` (Task 5), `LoadPortfolioUseCase`/`PortfolioStore` and `FetchQuotesUseCase` (existing — follow `PlansViewModel`'s dependency style), L10n keys (Task 8).
- Produces:

```swift
@MainActor
final class IncomeViewModel: ObservableObject {
    struct SummaryCards: Equatable {
        let projectedAnnual: Money
        let receivedYTD: Money
        let portfolioYield: Double      // fraction, 0.031 = 3.1%
        let yieldOnCost: Double
    }
    struct MonthBar: Equatable, Identifiable {
        let id: String                  // "yyyy-MM"
        let amount: Money
        let isProjected: Bool
    }
    struct UpcomingRow: Equatable, Identifiable {
        let id: String                  // symbol
        let symbol: String
        let estimatedExDate: Date
        let estimatedAmount: Money      // amountPerShare × current shares
    }
    struct HoldingRow: Equatable, Identifiable {
        let id: String                  // symbol
        let symbol: String
        let shares: Quantity
        let annualIncome: Money
        let yieldOnCost: Double
        let lastPayment: Money?
    }
    struct HistoryEntry: Equatable, Identifiable {
        let id: UUID                    // transaction id
        let date: Date
        let symbol: String
        let amountPerShare: Money
        let shares: Quantity
        let total: Money
        let wasReinvested: Bool
    }

    @Published private(set) var cards: SummaryCards?
    @Published private(set) var months: [MonthBar]          // last 12 received + up to 3 projected
    @Published private(set) var upcoming: [UpcomingRow]     // sorted by estimatedExDate
    @Published private(set) var holdings: [HoldingRow]      // sorted by annualIncome desc
    @Published private(set) var history: [HistoryEntry]     // newest first
    @Published private(set) var isLoading: Bool

    init(/* injected stores/use cases + now: @escaping () -> Date = Date.init */)
    func load() async
}
```

Semantics: `receivedYTD` sums `.dividend` transactions in the current calendar year (UTC). `wasReinvested` = an `isDrip` buy exists for the same symbol + trading day. `portfolioYield` uses live quotes for market value (fallback cost basis, mirroring `Portfolio.valuation`); a failed quote/event fetch degrades that piece to zero/empty, never blocks the rest. Projected `MonthBar`s come from `DividendMath.nextProjected` per holding, bucketed into future months.

- [ ] **Step 1: Failing tests.** Fakes for stores/repos. (a) cards computed from a ledger with two dividends this year + events fixture (exact Money math); (b) history pairs the DRIP badge correctly; (c) months: 12 buckets, projected bars flagged; (d) upcoming sorted; (e) event-fetch failure → `upcoming` empty but `history`/`cards.receivedYTD` still populated. Run: `swift test --filter IncomeViewModelTests` — FAILS.
- [ ] **Step 2: Implement.** Run — PASS.
- [ ] **Step 3: Commit** `feat(app): IncomeViewModel — cards, monthly bars, upcoming, history`

---

### Task 10: Income UI — section in the Portfolio sub-switcher

**Files:**
- Create: `Sources/APTradeApp/IncomeSection.swift`
- Modify: `Sources/APTradeApp/PortfolioView.swift`
- Test: build-only (ViewModel logic is Task 9; UI is declarative)

**Interfaces:**
- Consumes: `IncomeViewModel` (Task 9), L10n keys (Task 8), `Theme`/`DesignKit` components, `PlansSection.swift` as the structural template (also for how `CompositionRoot` supplies dependencies).

Work:
- `PortfolioView.Section` gains `case income = "Income"` (title `tr(.incomeSection)`), rendered as `IncomeSection()` in the section switch. Place it after `.plans`.
- `IncomeSection`: vertical scroll of (1) a 2×2 grid of summary cards (match the existing dashboard-card styling in `PortfolioView`/`DesignKit`); (2) monthly bar chart — custom `Canvas`/`Rectangle` bars like existing chart code, received bars in `Theme.gold`, projected bars in a muted/40 %-opacity gold with the `tr(.incomeEstimatedBadge)` badge in the legend; (3) upcoming list rows (symbol, est. date formatted with the app's existing date formatting, est. amount, `Est.` badge); (4) per-holding table; (5) history feed rows with the `tr(.incomeReinvestedBadge)` badge when `wasReinvested`. Empty ledger → `tr(.incomeNoDividends)` empty-state (match `PlansSection`'s empty-state styling). All spacing/typography from `Theme`; no hardcoded strings (L10n test will catch); `#if os(iOS)` only if a layout genuinely needs it.

- [ ] **Step 1: Implement the view + switcher case.**
- [ ] **Step 2: Verify:** `DEVELOPER_DIR=/Applications/Xcode.app swift build` — compiles; `swift test --filter L10nTests` — PASS (no hardcoded-string regressions).
- [ ] **Step 3: Commit** `feat(app): Income section — cards, monthly chart, upcoming, per-holding, history`

---

### Task 11: Asset detail — dividend info

**Files:**
- Modify: `Sources/APTradeApp/AssetDetailViewModel.swift`
- Modify: `Sources/APTradeApp/AssetDetailView.swift`
- Test: `Tests/APTradeAppTests/AssetDetailViewModelTests.swift` (extend)

**Interfaces:**
- Consumes: `DividendEventsRepository` (Task 5), `DividendMath` (Task 4), L10n keys (Task 8).
- Produces (on `AssetDetailViewModel`):

```swift
struct DividendInfo: Equatable {
    let trailingAnnualRate: Money       // per share
    let yieldFraction: Double           // trailingAnnualRate / current price
    let nextEstimatedExDate: Date?
    let recentAmounts: [Money]          // last 8 events, oldest first (mini chart)
}
@Published private(set) var dividendInfo: DividendInfo?   // nil = hide section
```

Semantics: loaded alongside the existing detail data for `asset.kind != .crypto`; symbols with zero events in the trailing 2 years → `dividendInfo = nil` (section hidden — non-payers). Fetch failure → nil (hidden, never an error state). View: a `tr(.assetDividendSection)` card showing yield, `tr(.assetDividendRate)`, `tr(.assetNextExDate)` (+ `Est.` badge), and a small bar mini-chart of `recentAmounts` (reuse `Sparkline`-style rendering).

- [ ] **Step 1: Failing tests.** (a) payer: info computed (rate = trailing 365d sum; yield = rate/price); (b) crypto → nil without fetching; (c) no events → nil; (d) fetch failure → nil, other detail state unaffected. Run: `swift test --filter AssetDetailViewModelTests` — FAILS.
- [ ] **Step 2: Implement ViewModel + view section.** Run — PASS.
- [ ] **Step 3: Commit** `feat(app): dividend info on asset detail`

---

### Task 12: Settings UI — DRIP + dividend notifications

**Files:**
- Modify: `Sources/APTradeApp/SettingsViewModel.swift`
- Modify: `Sources/APTradeApp/RootView.swift` (notificationsPage + accountSettingsPage)
- Test: `Tests/APTradeAppTests/SettingsViewModelTests.swift` (extend)

Work: `SettingsViewModel` gains `dripEnabled` and `dividendNotifications` published bindings persisting through the existing save path (mirror `pieContributions` exactly). `notificationsPage` gains a `tr(.settingsDividendNotif)` toggle row next to the Pie contributions row. The DRIP toggle (`tr(.settingsDrip)` + `tr(.settingsDripFooter)` footer text) goes on `accountSettingsPage` — it changes money behavior, not notification delivery.

- [ ] **Step 1: Failing tests.** Toggling each binding persists to the store and survives a reload (copy the existing toggle test shape). Run: `swift test --filter SettingsViewModelTests` — FAILS.
- [ ] **Step 2: Implement.** Run — PASS.
- [ ] **Step 3: Commit** `feat(app): DRIP + dividend notification settings UI`

---

### Task 13: Coordinator wiring + notifier + composition root

**Files:**
- Modify: `Sources/APTradeApplication/Ports.swift` (MarketEventNotifier)
- Modify: `Sources/APTradeInfrastructure/UserNotificationAlertNotifier.swift`
- Modify: `Sources/APTradeApp/MarketActivityCoordinator.swift`
- Modify: `Sources/APTradeApp/CompositionRoot.swift`
- Test: `Tests/APTradeAppTests/MarketActivityCoordinatorTests.swift` (extend)

**Interfaces:**
- Consumes: `ProcessDueDividends` + `DividendOutcome` (Task 6), planner event (Task 7), L10n keys (Task 8), settings fields (Task 7).
- Produces:

```swift
// MarketEventNotifier gains:
func notifyDividend(title: String, body: String) async
// (implement in UserNotificationAlertNotifier exactly like notifyPieContribution;
//  update every test fake that conforms to MarketEventNotifier.)

// MarketActivityCoordinator:
//  - new injected closure, same seam style as executeDueContributions:
//      processDueDividends: @Sendable (Date) async -> [DividendOutcome]
//  - run(): after the contribution launch catch-up, ALWAYS `await notifyDividendsDue()`
//    (crediting is ungated; only the notification inside is gated).
//  - tick(): case .dividendCheckDue: await notifyDividendsDue()
//  - notifyDividendsDue(): runs the closure; for each outcome, IF
//    loadSettings().dividendNotifications, notify:
//      .credited(symbol, cash)    → title tr(.notifDividendTitle),
//                                   body String(format: tr(.notifDividendCashBodyFmt), symbol, cash.formatted)
//      .reinvested(symbol, cash, _) → body String(format: tr(.notifDividendDripBodyFmt), symbol, cash.formatted)

// CompositionRoot.makeMarketActivityCoordinator(): builds ProcessDueDividends with the
// shared pieStore-style singletons (portfolioStore, schedulerStateStore, tradeSerializer,
// makeRepository() for BOTH market and dividends — SharedCoreMarketDataRepository now
// conforms to DividendEventsRepository), isDripEnabled: { LoadSettingsUseCase(store: settingsStore)().dripEnabled }.
```

- [ ] **Step 1: Failing tests.** Coordinator with fake closures: (a) `.dividendCheckDue` tick invokes the closure and notifies per outcome with the formatted bodies; (b) `dividendNotifications == false` → closure STILL invoked (crediting), zero notifications; (c) launch `run()` performs the dividend catch-up once before the tick loop (mirror the existing contribution launch-catch-up test). Run: `swift test --filter MarketActivityCoordinatorTests` — FAILS.
- [ ] **Step 2: Implement.** Run — PASS.
- [ ] **Step 3: Commit** `feat(app): dividend check wired into coordinator + native notifications`

---

### Task 14: Export rows + README + full suites

**Files:**
- Modify: `Sources/APTradeDomain/PortfolioExport.swift`
- Modify: `Sources/APTradeInfrastructure/PortfolioExportRenderer.swift`
- Modify: `Sources/APTradeApplication/ExportUseCases.swift`
- Modify: `README.md`
- Test: `Tests/APTradeDomainTests` + `Tests/APTradeApplicationTests` export tests (extend existing export test files)

Work:
- `PortfolioExport` gains `public let dividendsReceivedYTD: Decimal` and `public let projectedAnnualIncome: Decimal` (extend init; update every construction site the compiler flags). `ExportUseCases` computes them (`.dividend` transactions this calendar year; `DividendMath.projectedAnnualIncome` — pass the events the caller already has, or `0` where events aren't available at export time: the export use case gains an optional `DividendEventsRepository` dependency, nil-safe → zero).
- Renderer: two new summary rows ("Dividends Received (YTD)", "Projected Annual Income") in every export format the renderer emits — follow how `unrealizedPnL` is rendered per format.
- README: add the Dividend & Income Engine to the features section (macOS + iPhone columns of the parity table; M8.2/M8.3 pending); move M8 out of the Roadmap's planned list per the roadmap close-out convention (M8 stays listed until M8.3, but mark M8.1 shipped).

- [ ] **Step 1: Failing tests.** Export use case with a ledger containing dividends → the two Decimals populated; renderer output contains the two localized rows. Run filtered — FAILS, implement, PASS.
- [ ] **Step 2: Full verification.** `DEVELOPER_DIR=/Applications/Xcode.app swift test` — ALL suites pass (expect ≥ 400 pre-existing + the ~45 new tests). `./gradlew :shared:jvmTest` — PASS.
- [ ] **Step 3: Commit** `feat: portfolio export income rows; README M8.1 close-out`

---

## Self-Review Notes

- **Spec coverage:** DRIP toggle (T7/T12), Yahoo events fetch (T1/T2), backfill-always-cash + first-run marker (T6), first-class transactions + isDrip (T3), strictly-before eligibility (T4/T6), dedup by (symbol, ex-date day) (T6), DRIP at ex-date close with cash fallback (T6), planner ungated daily check + launch catch-up (T7/T13), notifications gated by toggle (T13), Income view all five surfaces (T9/T10), asset-detail info hidden for crypto/non-payers (T11), export rows (T14), reset-wipes-dividends is free (same ledger — no task needed), EN/DE/IT/ES (T8), derived trailing rate (T4 — spec amendment honored).
- **Type consistency:** `DividendEvent` (Kotlin: `exDateEpochSeconds Long`; Swift: `exDate Date`) — conversion pinned in T5 only. `DividendOutcome` names match between T6 (producer) and T13 (consumer). `isDripEnabled` closure seam (T6) decouples the engine from the T7 settings field; wired in T13.
- **Ordering:** T8 (L10n) lands before every UI/coordinator consumer (T9–T13). T2 rebuilds the xcframework before T5 consumes bridged names.
