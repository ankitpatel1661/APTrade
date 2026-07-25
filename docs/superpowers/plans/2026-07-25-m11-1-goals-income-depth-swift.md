# M11.1 — Goals & Income Depth (Swift: macOS + iPhone) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship three features on the Swift platforms — a configurable portfolio starting balance, portfolio-level value/income goals, and a dividend calendar plus multi-year income forecast — all on data the app already fetches.

**Architecture:** Pure math lands in `APTradeDomain` first (caseless `enum` namespaces of `static func`s: extended `DividendMath`, new `GoalMath`), then ports/use cases in `APTradeApplication`, then a `UserDefaults` adapter in `APTradeInfrastructure`, then `@Published`/`@Observable` state on the existing `IncomeViewModel` / `PerformanceViewModel`, then cards inside the existing `IncomeSection` / `PerformanceSection`. macOS and iPhone share every view file in `APTradeApp`; platform differences are `#if os(...)` branches inside them.

**Tech Stack:** Swift 5.9+, SwiftUI, Swift Charts, XCTest, `UserDefaults`-backed JSON stores, Kotlin `Shared` xcframework behind `SharedCoreMarketDataRepository`.

**Spec:** `docs/superpowers/specs/2026-07-25-goals-income-depth-design.md` (approved 2026-07-25).

## Global Constraints

- **No paid APIs or new dependencies.** Only data already fetched (Yahoo quotes/history/dividend events via the shared core) may be used.
- **USD only.** No multi-currency work anywhere in this milestone.
- **Pure-math house style:** domain math is a caseless `public enum` of `public static func`s, Foundation-only, no I/O — matching `DividendMath`, `PieMath`, `PieMathBacktest`, `RiskMetrics`.
- **Every new L10n key must supply all four languages** — `.english`, `.german`, `.italian`, `.spanish` — in `L10n.table`. `Tests/APTradeAppTests/L10nTests.swift` enforces completeness and will fail otherwise.
- **Lenient decoding:** any new persisted field uses the `AppSettings.init(from:)` `decodeIfPresent(...) ?? default` idiom so existing user payloads never reset.
- **Test command (the `DEVELOPER_DIR` prefix is required):**
  `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
  Filtered: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter <TestClassName>`
- **Growth clamps (spec-mandated, exact values):** per-symbol dividend growth clamped to `-0.20 ... 0.25` per year; portfolio value-goal growth rate clamped to `-0.50 ... 1.00` per year; any projection longer than 30 years displays as "> 30 yrs".
- **Forecast horizons:** exactly 5 / 10 / 20 / 30 years, presented as pills (no free slider).
- **Dividend calendar rows are projections and must be labeled "est."** — Yahoo exposes no future declared dividend dates.
- **DRIP is a single global flag** (`AppSettings.dripEnabled`), never per-holding.
- **Commit after every task** using the message given in that task's final step.

## File Structure

**Domain (`Sources/APTradeDomain/`)**
- `Portfolio.swift` — MODIFY: add stored `startingCash` + lenient `init(from:)`.
- `AppSettings.swift` — MODIFY: add `defaultStartingCash`.
- `DividendMath.swift` — MODIFY: add `dividendGrowthRate`, `incomeForecast`, `projectedSchedule` + result structs.
- `PortfolioGoal.swift` — CREATE: `PortfolioGoal` value type + `GoalKind`.
- `GoalMath.swift` — CREATE: progress + projection math.

**Application (`Sources/APTradeApplication/`)**
- `Ports.swift` — MODIFY: add `GoalStore` port.
- `PortfolioUseCases.swift` — MODIFY: `ResetPortfolioUseCase` takes a starting-cash amount.
- `GoalUseCases.swift` — CREATE: load / save / remove goals.

**Infrastructure (`Sources/APTradeInfrastructure/`)**
- `UserDefaultsGoalStore.swift` — CREATE.
- `UserDefaultsPortfolioStore.swift` — MODIFY: seed with the configured starting cash.

**App (`Sources/APTradeApp/`)**
- `IncomeViewModel.swift` — MODIFY: calendar, forecast, income-goal state.
- `IncomeSection.swift` — MODIFY: calendar card, forecast chart, income-goal card.
- `PerformanceViewModel.swift` / `PerformanceSection.swift` — MODIFY: value-goal card.
- `PortfolioViewModel.swift` / `PortfolioView.swift` — MODIFY: reset-with-amount flow.
- `CompositionRoot.swift` — MODIFY: wire the goal store and settings reads.
- `L10n.swift` — MODIFY: new keys, all four languages.

**Tests** — `Tests/APTradeDomainTests/{GoalMathTests,DividendMathForecastTests,PortfolioStartingCashTests}.swift`, `Tests/APTradeApplicationTests/GoalUseCasesTests.swift`, `Tests/APTradeInfrastructureTests/UserDefaultsGoalStoreTests.swift`, `Tests/APTradeAppTests/{IncomeViewModelTests,PortfolioViewModelTests}.swift`.

---

## Task 1: Portfolio records its starting cash

**Files:**
- Modify: `Sources/APTradeDomain/Portfolio.swift:23-37`
- Test: `Tests/APTradeDomainTests/PortfolioStartingCashTests.swift` (create)

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `Portfolio.startingCash: Money` stored property; `Portfolio.init(cash:positions:transactions:startingCash:)` where `startingCash` defaults to `cash`; `Portfolio.starting(cash:)` unchanged in signature but now records the amount. Old persisted payloads without the key decode with `startingCash == cash`.

- [ ] **Step 1: Write the failing tests**

Create `Tests/APTradeDomainTests/PortfolioStartingCashTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class PortfolioStartingCashTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }

    func test_starting_recordsChosenCashAsStartingCash() {
        let p = Portfolio.starting(cash: usd("25000"))
        XCTAssertEqual(p.cash, usd("25000"))
        XCTAssertEqual(p.startingCash, usd("25000"))
    }

    func test_starting_defaultsToOneHundredThousand() {
        XCTAssertEqual(Portfolio.starting().startingCash, usd("100000"))
    }

    func test_init_defaultsStartingCashToOpeningCash() {
        let p = Portfolio(cash: usd("5000"))
        XCTAssertEqual(p.startingCash, usd("5000"))
    }

    func test_decode_legacyPayloadWithoutStartingCash_fallsBackToCash() throws {
        let legacy = #"{"cash":{"amount":42000,"currencyCode":"USD"},"positions":[],"transactions":[]}"#
        let decoded = try JSONDecoder().decode(Portfolio.self, from: Data(legacy.utf8))
        XCTAssertEqual(decoded.startingCash, decoded.cash)
        XCTAssertEqual(decoded.startingCash, usd("42000"))
    }

    func test_roundTrip_preservesStartingCashIndependentOfCurrentCash() throws {
        let original = Portfolio(cash: usd("31000"), positions: [], transactions: [],
                                 startingCash: usd("50000"))
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(Portfolio.self, from: data)
        XCTAssertEqual(decoded.startingCash, usd("50000"))
        XCTAssertEqual(decoded.cash, usd("31000"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PortfolioStartingCashTests`
Expected: FAIL — compile error, `value of type 'Portfolio' has no member 'startingCash'`.

- [ ] **Step 3: Add the stored property and lenient decode**

In `Sources/APTradeDomain/Portfolio.swift`, replace the property block, initializer, and factory (lines 24–37) with:

```swift
    public let cash: Money
    public let positions: [Position]
    public let transactions: [Transaction]
    /// The cash the portfolio opened with. Recorded so performance baselines and the
    /// reset flow never assume a fixed amount. Defaults to the opening cash.
    public let startingCash: Money

    public init(cash: Money, positions: [Position] = [], transactions: [Transaction] = [],
                startingCash: Money? = nil) {
        self.cash = cash
        self.positions = positions
        self.transactions = transactions
        self.startingCash = startingCash ?? cash
    }

    /// Lenient decode: payloads written before `startingCash` existed fall back to the
    /// recorded cash, so upgrading never invalidates a saved portfolio.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        cash = try c.decode(Money.self, forKey: .cash)
        positions = try c.decodeIfPresent([Position].self, forKey: .positions) ?? []
        transactions = try c.decodeIfPresent([Transaction].self, forKey: .transactions) ?? []
        startingCash = try c.decodeIfPresent(Money.self, forKey: .startingCash) ?? cash
    }

    /// The starting paper portfolio: the chosen cash amount, no holdings.
    public static func starting(cash: Money = Money(amount: 100_000)) -> Portfolio {
        Portfolio(cash: cash, startingCash: cash)
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PortfolioStartingCashTests`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full suite to catch construction-site breakage**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: PASS. Every `Portfolio(...)` call site still compiles because `startingCash` is an optional trailing parameter. If any test asserted `Portfolio` equality across a mutation, note that `startingCash` now participates in `Equatable` — a portfolio built by `buying`/`selling` must carry the same `startingCash` as its predecessor. Fix any transition method in `Portfolio.swift` that reconstructs `Portfolio(cash:positions:transactions:)` by passing `startingCash: startingCash` explicitly.

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeDomain/Portfolio.swift Tests/APTradeDomainTests/PortfolioStartingCashTests.swift
git commit -m "feat(domain): record starting cash on Portfolio with lenient decode"
```

---

## Task 2: `AppSettings.defaultStartingCash` preference

**Files:**
- Modify: `Sources/APTradeDomain/AppSettings.swift:15-90`
- Test: `Tests/APTradeDomainTests/AppSettingsTests.swift` (create if absent, else extend)

**Interfaces:**
- Consumes: `Money` from Task 1's domain (unchanged type).
- Produces: `AppSettings.defaultStartingCash: Money`, defaulting to `Money(amount: 100_000)`; remembered across resets by the reset flow in Task 3.

- [ ] **Step 1: Write the failing tests**

Create (or append to) `Tests/APTradeDomainTests/AppSettingsTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class AppSettingsStartingCashTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }

    func test_default_isOneHundredThousand() {
        XCTAssertEqual(AppSettings.default.defaultStartingCash, usd("100000"))
    }

    func test_decode_legacyPayloadWithoutKey_usesDefault() throws {
        let legacy = #"{"dripEnabled":true}"#
        let decoded = try JSONDecoder().decode(AppSettings.self, from: Data(legacy.utf8))
        XCTAssertEqual(decoded.defaultStartingCash, usd("100000"))
        XCTAssertTrue(decoded.dripEnabled)
    }

    func test_roundTrip_preservesCustomAmount() throws {
        var s = AppSettings.default
        s.defaultStartingCash = usd("250000")
        let decoded = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(s))
        XCTAssertEqual(decoded.defaultStartingCash, usd("250000"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter AppSettingsStartingCashTests`
Expected: FAIL — `value of type 'AppSettings' has no member 'defaultStartingCash'`.

- [ ] **Step 3: Add the property following the `dripEnabled` pattern**

In `Sources/APTradeDomain/AppSettings.swift` make four edits:

1. After the `dripEnabled` property (line 16), add:
```swift
    /// The cash a freshly reset portfolio opens with. Remembered between resets.
    public var defaultStartingCash: Money
```
2. In the memberwise `init`, after `dripEnabled: Bool = false,` add:
```swift
        defaultStartingCash: Money = Money(amount: 100_000),
```
3. In the init body, after `self.dripEnabled = dripEnabled` add:
```swift
        self.defaultStartingCash = defaultStartingCash
```
4. In `init(from:)`, after the `dripEnabled` line add:
```swift
        defaultStartingCash = try c.decodeIfPresent(Money.self, forKey: .defaultStartingCash) ?? d.defaultStartingCash
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter AppSettingsStartingCashTests`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeDomain/AppSettings.swift Tests/APTradeDomainTests/AppSettingsTests.swift
git commit -m "feat(domain): add defaultStartingCash preference"
```

---

## Task 3: Reset accepts a starting amount

**Files:**
- Modify: `Sources/APTradeApplication/PortfolioUseCases.swift:53-72`
- Modify: `Sources/APTradeInfrastructure/UserDefaultsPortfolioStore.swift:10-30`
- Modify: `Sources/APTradeApp/PortfolioViewModel.swift` (the `reset()` method, ~line 186)
- Test: `Tests/APTradeApplicationTests/PortfolioUseCasesTests.swift` (extend)

**Interfaces:**
- Consumes: `Portfolio.starting(cash:)` (Task 1), `AppSettings.defaultStartingCash` (Task 2).
- Produces: `ResetPortfolioUseCase.callAsFunction(startingCash: Money) async -> Portfolio`; `PortfolioViewModel.reset(startingCash: Money) async`. Task 4 (UI) calls the ViewModel method; Task 10 extends the same use case to clear goals.

- [ ] **Step 1: Write the failing test**

Append to `Tests/APTradeApplicationTests/PortfolioUseCasesTests.swift` (inside the existing test class, reusing its existing in-memory store double — if that double is named differently, match the file's existing helper):

```swift
    func test_reset_opensPortfolioAtRequestedStartingCash() async {
        let store = InMemoryPortfolioStore(seed: Portfolio.starting())
        let sut = ResetPortfolioUseCase(store: store, serializer: TradeSerializer())
        let fresh = await sut(startingCash: Money(amount: 25_000))
        XCTAssertEqual(fresh.cash, Money(amount: 25_000))
        XCTAssertEqual(fresh.startingCash, Money(amount: 25_000))
        XCTAssertTrue(fresh.positions.isEmpty)
        XCTAssertEqual(store.load().cash, Money(amount: 25_000))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PortfolioUseCasesTests`
Expected: FAIL — `extra argument 'startingCash' in call`.

- [ ] **Step 3: Thread the amount through the use case**

In `Sources/APTradeApplication/PortfolioUseCases.swift`, replace `ResetPortfolioUseCase.callAsFunction`:

```swift
    public func callAsFunction(startingCash: Money) async -> Portfolio {
        await serializer.run {
            let fresh = Portfolio.starting(cash: startingCash)
            self.store.save(fresh)
            return fresh
        }
    }
```

- [ ] **Step 4: Update the ViewModel**

In `Sources/APTradeApp/PortfolioViewModel.swift`, change `reset()` to take the amount and pass it through (keep the rest of the body exactly as-is):

```swift
    func reset(startingCash: Money) async {
        portfolio = await resetPortfolio(startingCash: startingCash)
        quotes = [:]
        clearHistoryUseCase()
        history = []
        performance = []
    }
```

- [ ] **Step 5: Update the store seed sites**

In `Sources/APTradeInfrastructure/UserDefaultsPortfolioStore.swift`, the two `Portfolio.starting()` sites (first-launch seed at line ~16 and the undecodable-data fallback at line ~24) must open at the user's configured default rather than a constant. Add a settings-backed seed closure to the initializer:

```swift
    private let seedCash: @Sendable () -> Money

    public init(defaults: UserDefaults = .standard,
                key: String = "portfolio",
                seedCash: @escaping @Sendable () -> Money = { Money(amount: 100_000) }) {
        self.defaults = defaults
        self.key = key
        self.seedCash = seedCash
    }
```

Then replace both `Portfolio.starting()` calls in that file with `Portfolio.starting(cash: seedCash())`.

In `Sources/APTradeApp/CompositionRoot.swift`, where `portfolioStore` is constructed, pass the settings-backed closure (mirroring how `isDripEnabled` is wired for the activity coordinator):

```swift
        UserDefaultsPortfolioStore(seedCash: { LoadSettingsUseCase(store: settingsStore)().defaultStartingCash })
```

- [ ] **Step 6: Run the full suite**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: PASS. Any other caller of `reset()` or `resetPortfolio()` must be updated to pass an amount — the compiler will point at each one.

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApplication/PortfolioUseCases.swift Sources/APTradeInfrastructure/UserDefaultsPortfolioStore.swift Sources/APTradeApp/PortfolioViewModel.swift Sources/APTradeApp/CompositionRoot.swift Tests/APTradeApplicationTests/PortfolioUseCasesTests.swift
git commit -m "feat: reset portfolio at a caller-supplied starting balance"
```

---

## Task 4: Reset sheet with a validated starting-balance field

**Files:**
- Modify: `Sources/APTradeApp/PortfolioView.swift` (`PortfolioSummaryHeader`: `showResetConfirm` state ~line 216, `.confirmationDialog` ~lines 255-259, `resetMenu` ~lines 410-424)
- Modify: `Sources/APTradeApp/L10n.swift` (`Key` enum ~line 38, `table` ~line 448+, `resetPortfolioConfirm` row ~line 697)
- Test: `Tests/APTradeAppTests/StartingBalanceInputTests.swift` (create)

**Interfaces:**
- Consumes: `PortfolioViewModel.reset(startingCash:)` (Task 3), `AppSettings.defaultStartingCash` (Task 2).
- Produces: `enum StartingBalanceInput { static func parse(_ text: String, locale: Locale) -> Money? }` — used only here; validation range $1,000…$10,000,000 inclusive.

- [ ] **Step 1: Write the failing parser tests**

Create `Tests/APTradeAppTests/StartingBalanceInputTests.swift`:

```swift
import XCTest
import APTradeDomain
@testable import APTradeApp

final class StartingBalanceInputTests: XCTestCase {
    private let us = Locale(identifier: "en_US")
    private let de = Locale(identifier: "de_DE")

    func test_parse_plainAmount() {
        XCTAssertEqual(StartingBalanceInput.parse("25000", locale: us), Money(amount: 25_000))
    }

    func test_parse_groupedAmount_usLocale() {
        XCTAssertEqual(StartingBalanceInput.parse("1,250,000", locale: us), Money(amount: 1_250_000))
    }

    func test_parse_germanDecimalComma() {
        XCTAssertEqual(StartingBalanceInput.parse("25.000,50", locale: de),
                       Money(amount: Decimal(string: "25000.50") ?? 0))
    }

    func test_parse_rejectsBelowMinimum() {
        XCTAssertNil(StartingBalanceInput.parse("999", locale: us))
    }

    func test_parse_rejectsAboveMaximum() {
        XCTAssertNil(StartingBalanceInput.parse("10000001", locale: us))
    }

    func test_parse_acceptsInclusiveBounds() {
        XCTAssertEqual(StartingBalanceInput.parse("1000", locale: us), Money(amount: 1_000))
        XCTAssertEqual(StartingBalanceInput.parse("10000000", locale: us), Money(amount: 10_000_000))
    }

    func test_parse_rejectsGarbageAndEmpty() {
        XCTAssertNil(StartingBalanceInput.parse("", locale: us))
        XCTAssertNil(StartingBalanceInput.parse("abc", locale: us))
        XCTAssertNil(StartingBalanceInput.parse("-5000", locale: us))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter StartingBalanceInputTests`
Expected: FAIL — `cannot find 'StartingBalanceInput' in scope`.

- [ ] **Step 3: Implement the parser**

Create `Sources/APTradeApp/StartingBalanceInput.swift`:

```swift
import Foundation
import APTradeDomain

/// Parses and range-validates the starting-balance field on the reset flow.
/// Locale-aware: accepts grouping separators and the locale's decimal separator.
enum StartingBalanceInput {
    static let minimum = Decimal(1_000)
    static let maximum = Decimal(10_000_000)

    static func parse(_ text: String, locale: Locale = .current) -> Money? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }

        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.generatesDecimalNumbers = true

        let amount: Decimal
        if let number = formatter.number(from: trimmed) as? NSDecimalNumber {
            amount = number.decimalValue
        } else if let plain = Decimal(string: trimmed, locale: locale) {
            amount = plain
        } else {
            return nil
        }

        guard amount >= minimum, amount <= maximum else { return nil }
        return Money(amount: amount)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter StartingBalanceInputTests`
Expected: PASS (7 tests).

- [ ] **Step 5: Add the L10n keys (all four languages)**

In `Sources/APTradeApp/L10n.swift`, add these cases to `Key` (note `startingBalance` already exists at line 38 — do not duplicate it, just ensure it has a `table` row):

```swift
        case startingBalanceRange = "Between $1,000 and $10,000,000"
        case resetPortfolioTitle = "Reset Portfolio"
        case resetPortfolioBody = "This clears all holdings and history, then opens a fresh portfolio with the cash below."
```

Add these rows to `table`, and replace the existing `.resetPortfolioConfirm` row (line ~697) — it currently hardcodes "$100,000", which is now wrong:

```swift
        .startingBalance: [.english: "Starting Balance", .german: "Startkapital",
                           .italian: "Capitale iniziale", .spanish: "Capital inicial"],
        .startingBalanceRange: [.english: "Between $1,000 and $10,000,000",
                                .german: "Zwischen 1.000 $ und 10.000.000 $",
                                .italian: "Tra 1.000 $ e 10.000.000 $",
                                .spanish: "Entre 1.000 $ y 10.000.000 $"],
        .resetPortfolioTitle: [.english: "Reset Portfolio", .german: "Portfolio zurücksetzen",
                               .italian: "Reimposta portafoglio", .spanish: "Restablecer cartera"],
        .resetPortfolioBody: [.english: "This clears all holdings and history, then opens a fresh portfolio with the cash below.",
                              .german: "Dadurch werden alle Bestände und der Verlauf gelöscht und ein neues Portfolio mit dem unten angegebenen Bargeld eröffnet.",
                              .italian: "Questa operazione cancella tutte le posizioni e lo storico, quindi apre un nuovo portafoglio con la liquidità indicata di seguito.",
                              .spanish: "Esto borra todas las posiciones y el historial, y abre una cartera nueva con el efectivo indicado abajo."],
        .resetPortfolioConfirm: [.english: "Reset portfolio and clear all holdings?",
                                 .german: "Portfolio zurücksetzen und alle Bestände löschen?",
                                 .italian: "Reimpostare il portafoglio e cancellare tutte le posizioni?",
                                 .spanish: "¿Restablecer la cartera y borrar todas las posiciones?"],
```

- [ ] **Step 6: Replace the confirmation dialog with a sheet**

In `Sources/APTradeApp/PortfolioView.swift`, inside `PortfolioSummaryHeader`, replace the `showResetConfirm` state and its `.confirmationDialog` with a sheet carrying the amount field. Keep `resetMenu` unchanged — it still sets the flag.

Replace the `@State private var showResetConfirm = false` line with:

```swift
    @State private var showResetSheet = false
    @State private var resetAmountText = ""
```

Replace the `.confirmationDialog(...)` block (lines ~255-259) with:

```swift
        .sheet(isPresented: $showResetSheet) {
            ResetPortfolioSheet(amountText: $resetAmountText) { amount in
                Task { await viewModel.reset(startingCash: amount) }
            }
        }
```

In `resetMenu`, change the button action from `showResetConfirm = true` to:

```swift
                resetAmountText = Self.plainAmountText(settingsVM.settings.defaultStartingCash)
                showResetSheet = true
```

Add this helper inside `PortfolioSummaryHeader` (formats without grouping so the field round-trips through the parser):

```swift
    private static func plainAmountText(_ money: Money) -> String {
        NSDecimalNumber(decimal: money.amount).stringValue
    }
```

If `PortfolioSummaryHeader` does not already receive the settings view model, thread it in from `PortfolioView` the same way `IncomeSection` receives `dripEnabled` — a `@Bindable var settingsVM: SettingsViewModel` property passed at the construction site.

- [ ] **Step 7: Add the sheet view**

Create `Sources/APTradeApp/ResetPortfolioSheet.swift`:

```swift
import SwiftUI
import APTradeDomain

/// Destructive reset flow with a validated starting-balance field.
struct ResetPortfolioSheet: View {
    @Binding var amountText: String
    let onConfirm: (Money) -> Void
    @Environment(\.dismiss) private var dismiss

    private var parsed: Money? { StartingBalanceInput.parse(amountText) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(tr(.resetPortfolioTitle))
                .font(.title3.weight(.semibold))
            Text(tr(.resetPortfolioBody))
                .font(.callout)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 6) {
                Text(tr(.startingBalance))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                TextField("", text: $amountText)
                    .textFieldStyle(.roundedBorder)
                Text(tr(.startingBalanceRange))
                    .font(.caption2)
                    .foregroundStyle(parsed == nil && !amountText.isEmpty ? Color.red : .secondary)
            }

            HStack {
                Spacer()
                Button(tr(.cancel)) { dismiss() }
                Button(tr(.reset), role: .destructive) {
                    guard let amount = parsed else { return }
                    onConfirm(amount)
                    dismiss()
                }
                .disabled(parsed == nil)
            }
        }
        .padding(20)
        .frame(minWidth: 320)
    }
}
```

- [ ] **Step 8: Persist the chosen amount as the new default**

In the sheet's confirm handler at the `PortfolioView` call site, also write the amount back to settings so the next reset remembers it:

```swift
            ResetPortfolioSheet(amountText: $resetAmountText) { amount in
                settingsVM.settings.defaultStartingCash = amount
                Task { await viewModel.reset(startingCash: amount) }
            }
```

(`SettingsViewModel` already persists on mutation — follow whatever save path `dripEnabled` uses in that class; if it exposes an explicit `save()`, call it here.)

- [ ] **Step 9: Verify build, tests, and L10n completeness**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: PASS, including `L10nTests` (proves all four languages are present for every new key).

- [ ] **Step 10: Commit**

```bash
git add Sources/APTradeApp/StartingBalanceInput.swift Sources/APTradeApp/ResetPortfolioSheet.swift Sources/APTradeApp/PortfolioView.swift Sources/APTradeApp/L10n.swift Tests/APTradeAppTests/StartingBalanceInputTests.swift
git commit -m "feat(app): choose the starting balance when resetting the portfolio"
```

---

## Task 5: Per-symbol dividend growth rate

**Files:**
- Modify: `Sources/APTradeDomain/DividendMath.swift` (append to the existing `public enum DividendMath`)
- Test: `Tests/APTradeDomainTests/DividendMathForecastTests.swift` (create)

**Interfaces:**
- Consumes: existing `DividendMath.DividendEvent` (`symbol`, `exDate`, `amountPerShare`) and `DividendMath.trailingAnnualPerShare(events:asOf:)`.
- Produces: `DividendMath.dividendGrowthRate(events: [DividendEvent], asOf: Date) -> Decimal` — annual growth as a fraction (`0.06` = +6%/yr), clamped to `-0.20 ... 0.25`, returning `0` when history is insufficient. Task 6 and Task 9 both call it.

- [ ] **Step 1: Write the failing tests**

Create `Tests/APTradeDomainTests/DividendMathForecastTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class DividendMathForecastTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func date(_ y: Int, _ m: Int, _ d: Int) -> Date {
        var c = DateComponents(); c.year = y; c.month = m; c.day = d
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: c)!
    }
    /// Quarterly events for `years` years ending before `asOf`, growing `perYear` annually.
    private func quarterly(symbol: String, startYear: Int, years: Int,
                           startAmount: Decimal, perYear: Decimal) -> [DividendMath.DividendEvent] {
        var out: [DividendMath.DividendEvent] = []
        var amount = startAmount
        for y in 0..<years {
            for q in 0..<4 {
                out.append(.init(symbol: symbol,
                                 exDate: date(startYear + y, 1 + q * 3, 15),
                                 amountPerShare: Money(amount: amount)))
            }
            amount = amount * (1 + perYear)
        }
        return out
    }

    func test_growthRate_flatHistory_isZero() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!, perYear: 0)
        let r = DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1))
        XCTAssertEqual(r, 0)
    }

    func test_growthRate_recoversTenPercentGrowth() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!,
                               perYear: Decimal(string: "0.10")!)
        let r = DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1))
        let delta = abs((r - Decimal(string: "0.10")!) as Decimal)
        XCTAssertLessThan(delta, Decimal(string: "0.015")!, "expected ~10%, got \(r)")
    }

    func test_growthRate_clampsHighGrowthToTwentyFivePercent() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.10")!,
                               perYear: Decimal(string: "0.90")!)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1)),
                       Decimal(string: "0.25")!)
    }

    func test_growthRate_clampsCollapseToMinusTwentyPercent() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "1.00")!,
                               perYear: Decimal(string: "-0.60")!)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1)),
                       Decimal(string: "-0.20")!)
    }

    func test_growthRate_insufficientHistory_isZero() {
        let oneYear = quarterly(symbol: "AAA", startYear: 2024, years: 1,
                                startAmount: Decimal(string: "0.25")!, perYear: 0)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: oneYear, asOf: date(2025, 1, 1)), 0)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: [], asOf: date(2025, 1, 1)), 0)
    }

    func test_growthRate_ignoresHistoryOlderThanFiveYears() {
        var events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!, perYear: 0)
        // A tiny ancient payment must not inflate the measured growth.
        events.insert(.init(symbol: "AAA", exDate: date(2005, 3, 1),
                            amountPerShare: usd("0.01")), at: 0)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1)), 0)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DividendMathForecastTests`
Expected: FAIL — `type 'DividendMath' has no member 'dividendGrowthRate'`.

- [ ] **Step 3: Implement the growth rate**

Append inside `public enum DividendMath` in `Sources/APTradeDomain/DividendMath.swift`:

```swift
    /// Lower bound on per-symbol annual dividend growth used by forecasts.
    public static let minDividendGrowth = Decimal(string: "-0.20")!
    /// Upper bound on per-symbol annual dividend growth used by forecasts.
    public static let maxDividendGrowth = Decimal(string: "0.25")!

    /// Annualized growth of a symbol's dividend, measured over at most the last five
    /// years of history and clamped to `minDividendGrowth ... maxDividendGrowth`.
    /// Returns `0` when there is too little history to measure honestly
    /// (fewer than two years spanned, or fewer than two payments).
    public static func dividendGrowthRate(events: [DividendEvent], asOf: Date) -> Decimal {
        let window = events
            .filter { $0.exDate <= asOf && $0.exDate >= asOf.addingTimeInterval(-5 * 365.25 * 86_400) }
            .sorted { $0.exDate < $1.exDate }
        guard window.count >= 2 else { return 0 }

        let years = window[window.count - 1].exDate.timeIntervalSince(window[0].exDate) / (365.25 * 86_400)
        guard years >= 2 else { return 0 }

        // Compare the trailing-year rate at each end of the window so cadence changes
        // (e.g. quarterly -> monthly) don't read as growth.
        let early = trailingAnnualPerShare(events: window,
                                           asOf: window[0].exDate.addingTimeInterval(365.25 * 86_400))
        let late = trailingAnnualPerShare(events: window, asOf: asOf)
        guard early.amount > 0, late.amount > 0 else { return 0 }

        let spanYears = years - 1
        guard spanYears >= 1 else { return 0 }

        let ratio = NSDecimalNumber(decimal: late.amount / early.amount).doubleValue
        guard ratio > 0 else { return 0 }
        let rate = pow(ratio, 1.0 / spanYears) - 1.0
        guard rate.isFinite else { return 0 }

        let asDecimal = Decimal(rate)
        return min(max(asDecimal, minDividendGrowth), maxDividendGrowth)
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DividendMathForecastTests`
Expected: PASS (6 tests). If `test_growthRate_recoversTenPercentGrowth` lands outside tolerance, the window arithmetic is off — do not widen the tolerance, fix the span calculation.

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeDomain/DividendMath.swift Tests/APTradeDomainTests/DividendMathForecastTests.swift
git commit -m "feat(domain): per-symbol dividend growth rate with clamps"
```

---

## Task 6: Multi-year income forecast with DRIP compounding

**Files:**
- Modify: `Sources/APTradeDomain/DividendMath.swift`
- Test: `Tests/APTradeDomainTests/DividendMathForecastTests.swift` (extend)

**Interfaces:**
- Consumes: `dividendGrowthRate(events:asOf:)` (Task 5), `trailingAnnualPerShare(events:asOf:)`, `Position` (`asset.symbol`, `quantity`, `averageCost`).
- Produces:
  - `public struct ForecastYear: Equatable, Sendable { public let yearOffset: Int; public let income: Money }` (`yearOffset` 1 = next year).
  - `DividendMath.incomeForecast(positions:eventsBySymbol:years:dripEnabled:asOf:) -> [ForecastYear]`.
  Task 9 (`GoalMath` income projection) and Task 11 (`IncomeViewModel`) both consume this.

- [ ] **Step 1: Write the failing tests**

Append to `Tests/APTradeDomainTests/DividendMathForecastTests.swift`:

```swift
    private func position(_ symbol: String, shares: String, price: String) -> Position {
        Position(asset: Asset(symbol: symbol, name: symbol, kind: .stock),
                 quantity: Quantity(Decimal(string: shares) ?? 0),
                 averageCost: usd(price),
                 realizedPnL: Money(amount: 0))
    }

    func test_forecast_flatDividend_noDrip_isConstant() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!, perYear: 0)
        let out = DividendMath.incomeForecast(positions: [position("AAA", shares: "100", price: "50")],
                                              eventsBySymbol: ["AAA": events],
                                              years: 3, dripEnabled: false, asOf: date(2025, 1, 1))
        XCTAssertEqual(out.count, 3)
        XCTAssertEqual(out.map(\.yearOffset), [1, 2, 3])
        // 100 shares x $1.00/yr trailing
        XCTAssertEqual(out[0].income, usd("100"))
        XCTAssertEqual(out[1].income, usd("100"))
        XCTAssertEqual(out[2].income, usd("100"))
    }

    func test_forecast_growingDividend_compoundsGrowth() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!,
                               perYear: Decimal(string: "0.10")!)
        let out = DividendMath.incomeForecast(positions: [position("AAA", shares: "100", price: "50")],
                                              eventsBySymbol: ["AAA": events],
                                              years: 2, dripEnabled: false, asOf: date(2025, 1, 1))
        XCTAssertGreaterThan(out[1].income.amount, out[0].income.amount)
    }

    func test_forecast_dripProducesMoreIncomeThanCash() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!, perYear: 0)
        let positions = [position("AAA", shares: "100", price: "50")]
        let cash = DividendMath.incomeForecast(positions: positions, eventsBySymbol: ["AAA": events],
                                               years: 5, dripEnabled: false, asOf: date(2025, 1, 1))
        let drip = DividendMath.incomeForecast(positions: positions, eventsBySymbol: ["AAA": events],
                                               years: 5, dripEnabled: true, asOf: date(2025, 1, 1))
        XCTAssertEqual(drip[0].income, cash[0].income, "year 1 is identical — reinvestment only helps later")
        XCTAssertGreaterThan(drip[4].income.amount, cash[4].income.amount)
    }

    func test_forecast_nonPayerContributesNothing() {
        let out = DividendMath.incomeForecast(positions: [position("NOPAY", shares: "10", price: "20")],
                                              eventsBySymbol: [:],
                                              years: 2, dripEnabled: true, asOf: date(2025, 1, 1))
        XCTAssertEqual(out.map(\.income), [Money(amount: 0), Money(amount: 0)])
    }

    func test_forecast_sumsAcrossHoldings() {
        let a = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                          startAmount: Decimal(string: "0.25")!, perYear: 0)
        let b = quarterly(symbol: "BBB", startYear: 2021, years: 4,
                          startAmount: Decimal(string: "0.50")!, perYear: 0)
        let out = DividendMath.incomeForecast(
            positions: [position("AAA", shares: "100", price: "50"),
                        position("BBB", shares: "10", price: "80")],
            eventsBySymbol: ["AAA": a, "BBB": b],
            years: 1, dripEnabled: false, asOf: date(2025, 1, 1))
        XCTAssertEqual(out[0].income, usd("120")) // 100 + 20
    }

    func test_forecast_zeroOrNegativeYears_isEmpty() {
        XCTAssertTrue(DividendMath.incomeForecast(positions: [], eventsBySymbol: [:], years: 0,
                                                  dripEnabled: false, asOf: date(2025, 1, 1)).isEmpty)
        XCTAssertTrue(DividendMath.incomeForecast(positions: [], eventsBySymbol: [:], years: -3,
                                                  dripEnabled: false, asOf: date(2025, 1, 1)).isEmpty)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DividendMathForecastTests`
Expected: FAIL — `type 'DividendMath' has no member 'incomeForecast'`. If `Asset(symbol:name:kind:)` does not match the real initializer, correct the helper to the actual signature in `Sources/APTradeDomain/Asset.swift` before proceeding.

- [ ] **Step 3: Implement the forecast**

Append inside `public enum DividendMath`:

```swift
    /// One projected year of dividend income. `yearOffset` 1 is the next twelve months.
    public struct ForecastYear: Equatable, Sendable {
        public let yearOffset: Int
        public let income: Money
        public init(yearOffset: Int, income: Money) {
            self.yearOffset = yearOffset
            self.income = income
        }
    }

    /// Projects annual dividend income forward, per holding, summed.
    ///
    /// Each symbol grows at its clamped historical dividend growth rate. With DRIP on,
    /// each year's dividends buy more shares at a price assumed to grow at that same
    /// rate — a stated simplification, surfaced to the user as a caption.
    public static func incomeForecast(positions: [Position],
                                      eventsBySymbol: [String: [DividendEvent]],
                                      years: Int,
                                      dripEnabled: Bool,
                                      asOf: Date) -> [ForecastYear] {
        guard years > 0 else { return [] }

        struct Projection {
            var shares: Decimal
            var perShare: Decimal
            var price: Decimal
            let growth: Decimal
        }

        var projections: [Projection] = []
        for position in positions {
            let events = eventsBySymbol[position.asset.symbol] ?? []
            let perShare = trailingAnnualPerShare(events: events, asOf: asOf).amount
            guard perShare > 0, position.quantity.amount > 0 else { continue }
            projections.append(Projection(shares: position.quantity.amount,
                                          perShare: perShare,
                                          price: position.averageCost.amount,
                                          growth: dividendGrowthRate(events: events, asOf: asOf)))
        }

        var out: [ForecastYear] = []
        for offset in 1...years {
            var total: Decimal = 0
            for index in projections.indices {
                if offset > 1 {
                    projections[index].perShare *= (1 + projections[index].growth)
                    projections[index].price *= (1 + projections[index].growth)
                }
                let income = projections[index].shares * projections[index].perShare
                total += income
                if dripEnabled, projections[index].price > 0 {
                    projections[index].shares += income / projections[index].price
                }
            }
            out.append(ForecastYear(yearOffset: offset, income: Money(amount: total)))
        }
        return out
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DividendMathForecastTests`
Expected: PASS (12 tests total in this file).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeDomain/DividendMath.swift Tests/APTradeDomainTests/DividendMathForecastTests.swift
git commit -m "feat(domain): multi-year dividend income forecast with DRIP compounding"
```

---

## Task 7: Twelve-month projected dividend schedule (calendar data)

**Files:**
- Modify: `Sources/APTradeDomain/DividendMath.swift`
- Test: `Tests/APTradeDomainTests/DividendScheduleTests.swift` (create)

**Interfaces:**
- Consumes: `inferredCadence(events:)`, `nextProjected(events:)`, existing `DividendEvent`.
- Produces: `DividendMath.projectedSchedule(positions:eventsBySymbol:through:asOf:) -> [ScheduledDividend]` where
  `public struct ScheduledDividend: Equatable, Sendable { public let symbol: String; public let exDate: Date; public let perShare: Money; public let estimatedAmount: Money }`,
  sorted ascending by `exDate`. Every row is an estimate — the UI labels them "est." (Task 12).

- [ ] **Step 1: Write the failing tests**

Create `Tests/APTradeDomainTests/DividendScheduleTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class DividendScheduleTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func date(_ y: Int, _ m: Int, _ d: Int) -> Date {
        var c = DateComponents(); c.year = y; c.month = m; c.day = d
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: c)!
    }
    private func position(_ symbol: String, shares: String) -> Position {
        Position(asset: Asset(symbol: symbol, name: symbol, kind: .stock),
                 quantity: Quantity(Decimal(string: shares) ?? 0),
                 averageCost: usd("50"), realizedPnL: Money(amount: 0))
    }
    /// Four quarterly payments through 2024.
    private func quarterlyHistory(_ symbol: String, amount: String) -> [DividendMath.DividendEvent] {
        [3, 6, 9, 12].map {
            DividendMath.DividendEvent(symbol: symbol, exDate: date(2024, $0, 10),
                                       amountPerShare: usd(amount))
        }
    }

    func test_schedule_projectsQuarterlyEventsForwardOneYear() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("AAA", shares: "100")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50")],
            through: date(2026, 1, 1), asOf: date(2025, 1, 1))
        XCTAssertEqual(rows.count, 4)
        XCTAssertEqual(rows.map(\.symbol), ["AAA", "AAA", "AAA", "AAA"])
        XCTAssertEqual(rows[0].estimatedAmount, usd("50")) // 100 shares x $0.50
        XCTAssertEqual(rows[0].perShare, usd("0.50"))
    }

    func test_schedule_isSortedAscendingAcrossSymbols() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("AAA", shares: "10"), position("BBB", shares: "10")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50"),
                             "BBB": quarterlyHistory("BBB", amount: "0.25")],
            through: date(2026, 1, 1), asOf: date(2025, 1, 1))
        XCTAssertEqual(rows, rows.sorted { $0.exDate < $1.exDate })
        XCTAssertEqual(Set(rows.map(\.symbol)), ["AAA", "BBB"])
    }

    func test_schedule_neverEmitsDatesInThePast() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("AAA", shares: "100")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50")],
            through: date(2026, 1, 1), asOf: date(2025, 6, 1))
        XCTAssertTrue(rows.allSatisfy { $0.exDate > date(2025, 6, 1) },
                      "stale projections must be rolled forward, not shown in the past")
    }

    func test_schedule_skipsNonPayersAndZeroShareHoldings() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("NOPAY", shares: "100"), position("AAA", shares: "0")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50")],
            through: date(2026, 1, 1), asOf: date(2025, 1, 1))
        XCTAssertTrue(rows.isEmpty)
    }

    func test_schedule_respectsHorizon() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("AAA", shares: "100")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50")],
            through: date(2025, 7, 1), asOf: date(2025, 1, 1))
        XCTAssertTrue(rows.allSatisfy { $0.exDate <= date(2025, 7, 1) })
        XCTAssertLessThan(rows.count, 4)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DividendScheduleTests`
Expected: FAIL — `type 'DividendMath' has no member 'projectedSchedule'`.

- [ ] **Step 3: Implement the schedule**

Append inside `public enum DividendMath`:

```swift
    /// One projected dividend payment for a held symbol. Estimated, never declared —
    /// the source data contains historical ex-dates only.
    public struct ScheduledDividend: Equatable, Sendable {
        public let symbol: String
        public let exDate: Date
        public let perShare: Money
        public let estimatedAmount: Money
        public init(symbol: String, exDate: Date, perShare: Money, estimatedAmount: Money) {
            self.symbol = symbol
            self.exDate = exDate
            self.perShare = perShare
            self.estimatedAmount = estimatedAmount
        }
    }

    /// Rolls each holding's inferred payment cadence forward from its last real event,
    /// emitting estimated payments in `(asOf, through]`, ascending by date.
    public static func projectedSchedule(positions: [Position],
                                         eventsBySymbol: [String: [DividendEvent]],
                                         through: Date,
                                         asOf: Date) -> [ScheduledDividend] {
        var out: [ScheduledDividend] = []
        for position in positions {
            let shares = position.quantity.amount
            guard shares > 0 else { continue }
            let events = eventsBySymbol[position.asset.symbol] ?? []
            guard let seed = nextProjected(events: events),
                  let cadence = inferredCadence(events: events) else { continue }

            let step = cadenceInterval(cadence)
            var next = seed.exDate
            // Roll a stale projection forward until it is genuinely in the future.
            while next <= asOf { next = next.addingTimeInterval(step) }

            while next <= through {
                out.append(ScheduledDividend(
                    symbol: position.asset.symbol,
                    exDate: next,
                    perShare: seed.amountPerShare,
                    estimatedAmount: Money(amount: shares * seed.amountPerShare.amount)))
                next = next.addingTimeInterval(step)
            }
        }
        return out.sorted { $0.exDate < $1.exDate }
    }

    private static func cadenceInterval(_ cadence: DividendCadence) -> TimeInterval {
        switch cadence {
        case .monthly: return 30 * 86_400
        case .quarterly: return 91 * 86_400
        case .semiAnnual: return 182 * 86_400
        case .annual: return 365 * 86_400
        }
    }
```

If `DividendMath` already defines a private cadence-interval helper (M8's `nextProjected` uses the same 30/91/182/365-day steps), reuse it instead of adding a second copy — do not duplicate the constants.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DividendScheduleTests`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full domain suite**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter APTradeDomainTests`
Expected: PASS — confirms the shared cadence helper refactor (if any) broke nothing in M8's paths.

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeDomain/DividendMath.swift Tests/APTradeDomainTests/DividendScheduleTests.swift
git commit -m "feat(domain): twelve-month projected dividend schedule"
```

---

## Task 8: `PortfolioGoal` and value-goal math

**Files:**
- Create: `Sources/APTradeDomain/PortfolioGoal.swift`
- Create: `Sources/APTradeDomain/GoalMath.swift`
- Test: `Tests/APTradeDomainTests/GoalMathTests.swift` (create)

**Interfaces:**
- Consumes: `Money`, `EquityPoint` (from `PortfolioEquityCurve.swift`: `public let date: Date`, `public let value: Money`).
- Produces:
  - `public struct PortfolioGoal: Equatable, Codable, Sendable { public let kind: GoalKind; public let target: Money; public let createdAt: Date }`
  - `public enum GoalKind: String, Codable, Sendable { case value, income }`
  - `public enum GoalProjection: Equatable, Sendable { case reached; case years(Double); case beyondHorizon; case notOnTrack; case insufficientHistory }`
  - `GoalMath.progress(current:target:) -> Double`
  - `GoalMath.annualGrowthRate(curve:) -> Decimal?`
  - `GoalMath.valueProjection(current:target:curve:) -> GoalProjection`
  Task 9 adds the income projection; Task 10 persists `PortfolioGoal`; Tasks 11–13 render both.

- [ ] **Step 1: Write the failing tests**

Create `Tests/APTradeDomainTests/GoalMathTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class GoalMathTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func day(_ offset: Int) -> Date { Date(timeIntervalSince1970: 1_700_000_000 + Double(offset) * 86_400) }
    /// An equity curve of `days` points growing at a constant daily rate.
    private func curve(start: Decimal, days: Int, dailyRate: Double) -> [EquityPoint] {
        (0..<days).map { i in
            let factor = Decimal(pow(1 + dailyRate, Double(i)))
            return EquityPoint(date: day(i), value: Money(amount: start * factor))
        }
    }

    func test_progress_isFractionOfTarget() {
        XCTAssertEqual(GoalMath.progress(current: usd("25000"), target: usd("100000")), 0.25, accuracy: 0.0001)
    }

    func test_progress_exceedsOneWhenGoalBeaten() {
        XCTAssertEqual(GoalMath.progress(current: usd("112000"), target: usd("100000")), 1.12, accuracy: 0.0001)
    }

    func test_progress_zeroTargetIsZero() {
        XCTAssertEqual(GoalMath.progress(current: usd("500"), target: Money(amount: 0)), 0)
    }

    func test_annualGrowthRate_needsThirtyDaysOfHistory() {
        XCTAssertNil(GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 20, dailyRate: 0.0005)))
        XCTAssertNotNil(GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 90, dailyRate: 0.0005)))
    }

    func test_annualGrowthRate_clampsExtremeGrowth() {
        let rate = GoalMath.annualGrowthRate(curve: curve(start: 10_000, days: 200, dailyRate: 0.02))
        XCTAssertEqual(rate, Decimal(1.0)) // clamped to +100%/yr
    }

    func test_annualGrowthRate_clampsCollapse() {
        let rate = GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 200, dailyRate: -0.01))
        XCTAssertEqual(rate, Decimal(-0.5)) // clamped to -50%/yr
    }

    func test_valueProjection_reachedWhenCurrentMeetsTarget() {
        XCTAssertEqual(GoalMath.valueProjection(current: usd("100000"), target: usd("100000"),
                                                curve: curve(start: 100_000, days: 90, dailyRate: 0.0005)),
                       .reached)
    }

    func test_valueProjection_insufficientHistory() {
        XCTAssertEqual(GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                curve: curve(start: 50_000, days: 10, dailyRate: 0.0005)),
                       .insufficientHistory)
    }

    func test_valueProjection_flatOrShrinkingIsNotOnTrack() {
        XCTAssertEqual(GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                curve: curve(start: 50_000, days: 90, dailyRate: 0)),
                       .notOnTrack)
        XCTAssertEqual(GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                curve: curve(start: 60_000, days: 90, dailyRate: -0.001)),
                       .notOnTrack)
    }

    func test_valueProjection_returnsYearsForAchievableTarget() {
        // ~0.05%/day compounds to roughly +20%/yr; doubling then takes ~3.8 years.
        let projection = GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                  curve: curve(start: 50_000, days: 120, dailyRate: 0.0005))
        guard case let .years(y) = projection else { return XCTFail("expected .years, got \(projection)") }
        XCTAssertGreaterThan(y, 3.0)
        XCTAssertLessThan(y, 5.0)
    }

    func test_valueProjection_beyondThirtyYearsIsBeyondHorizon() {
        let projection = GoalMath.valueProjection(current: usd("1000"), target: usd("10000000"),
                                                  curve: curve(start: 1_000, days: 90, dailyRate: 0.00005))
        XCTAssertEqual(projection, .beyondHorizon)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter GoalMathTests`
Expected: FAIL — `cannot find 'GoalMath' in scope`.

- [ ] **Step 3: Create the goal value type**

Create `Sources/APTradeDomain/PortfolioGoal.swift`:

```swift
import Foundation

/// What a goal measures.
public enum GoalKind: String, Codable, Sendable {
    /// Total portfolio value (holdings + cash).
    case value
    /// Projected annual dividend income.
    case income
}

/// A user-set target for the whole portfolio. At most one per `GoalKind`.
/// The user sets an amount; the app projects when it will be reached.
public struct PortfolioGoal: Equatable, Codable, Sendable {
    public let kind: GoalKind
    public let target: Money
    public let createdAt: Date

    public init(kind: GoalKind, target: Money, createdAt: Date = Date()) {
        self.kind = kind
        self.target = target
        self.createdAt = createdAt
    }
}
```

- [ ] **Step 4: Create the math**

Create `Sources/APTradeDomain/GoalMath.swift`:

```swift
import Foundation

/// How a goal is tracking. Never fabricates an ETA it cannot support.
public enum GoalProjection: Equatable, Sendable {
    case reached
    case years(Double)
    case beyondHorizon
    case notOnTrack
    case insufficientHistory
}

/// Progress and honest time-to-target math for portfolio goals. Pure.
public enum GoalMath {
    /// Minimum equity-curve points before a growth rate is trustworthy.
    public static let minimumHistoryDays = 30
    /// Projections longer than this report `.beyondHorizon`.
    public static let horizonYears = 30.0
    public static let minAnnualGrowth = Decimal(-0.5)
    public static let maxAnnualGrowth = Decimal(1.0)

    /// Fraction of the target achieved. May exceed 1. Zero target yields 0.
    public static func progress(current: Money, target: Money) -> Double {
        guard target.amount > 0 else { return 0 }
        return NSDecimalNumber(decimal: current.amount / target.amount).doubleValue
    }

    /// Annualized growth of the equity curve, clamped to
    /// `minAnnualGrowth ... maxAnnualGrowth`. `nil` when history is too short.
    public static func annualGrowthRate(curve: [EquityPoint]) -> Decimal? {
        let sorted = curve.sorted { $0.date < $1.date }
        guard let first = sorted.first, let last = sorted.last else { return nil }
        let days = last.date.timeIntervalSince(first.date) / 86_400
        guard days >= Double(minimumHistoryDays) else { return nil }
        guard first.value.amount > 0, last.value.amount > 0 else { return nil }

        let ratio = NSDecimalNumber(decimal: last.value.amount / first.value.amount).doubleValue
        guard ratio > 0 else { return nil }
        let rate = pow(ratio, 365.25 / days) - 1.0
        guard rate.isFinite else { return nil }
        return min(max(Decimal(rate), minAnnualGrowth), maxAnnualGrowth)
    }

    /// When the portfolio's value reaches `target` at its historical growth rate.
    public static func valueProjection(current: Money, target: Money,
                                       curve: [EquityPoint]) -> GoalProjection {
        guard current.amount < target.amount else { return .reached }
        guard let rate = annualGrowthRate(curve: curve) else { return .insufficientHistory }
        return yearsToTarget(current: current.amount, target: target.amount, annualRate: rate)
    }

    /// Solves `current * (1 + rate)^t >= target`, honestly.
    static func yearsToTarget(current: Decimal, target: Decimal, annualRate: Decimal) -> GoalProjection {
        guard current > 0 else { return .notOnTrack }
        let rate = NSDecimalNumber(decimal: annualRate).doubleValue
        guard rate > 0 else { return .notOnTrack }
        let ratio = NSDecimalNumber(decimal: target / current).doubleValue
        guard ratio > 0 else { return .notOnTrack }
        let years = log(ratio) / log(1 + rate)
        guard years.isFinite, years > 0 else { return .notOnTrack }
        return years > horizonYears ? .beyondHorizon : .years(years)
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter GoalMathTests`
Expected: PASS (11 tests). If `EquityPoint`'s initializer differs from `EquityPoint(date:value:)`, correct the test helper to the real signature in `Sources/APTradeDomain/PortfolioEquityCurve.swift` — do not change the production code to fit the test.

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeDomain/PortfolioGoal.swift Sources/APTradeDomain/GoalMath.swift Tests/APTradeDomainTests/GoalMathTests.swift
git commit -m "feat(domain): portfolio goals with value projection math"
```

---

## Task 9: Income-goal projection (depends on Task 6)

**Files:**
- Modify: `Sources/APTradeDomain/GoalMath.swift`
- Test: `Tests/APTradeDomainTests/GoalMathTests.swift` (extend)

**Interfaces:**
- Consumes: `DividendMath.ForecastYear` and `DividendMath.incomeForecast(...)` (Task 6), `GoalProjection` (Task 8).
- Produces: `GoalMath.incomeProjection(current: Money, target: Money, forecast: [DividendMath.ForecastYear]) -> GoalProjection` — the first forecast year whose income meets the target. Task 11 calls it with a 30-year forecast.

- [ ] **Step 1: Write the failing tests**

Append to `Tests/APTradeDomainTests/GoalMathTests.swift`:

```swift
    private func forecast(_ amounts: [String]) -> [DividendMath.ForecastYear] {
        amounts.enumerated().map { .init(yearOffset: $0.offset + 1, income: usd($0.element)) }
    }

    func test_incomeProjection_reachedWhenCurrentMeetsTarget() {
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("5000"), target: usd("5000"),
                                                 forecast: forecast(["5200", "5400"])),
                       .reached)
    }

    func test_incomeProjection_returnsCrossingYear() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("3000"),
                                                   forecast: forecast(["1500", "2200", "3100", "4000"]))
        XCTAssertEqual(projection, .years(3))
    }

    func test_incomeProjection_neverCrossesWithinForecast_isBeyondHorizon() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("99999"),
                                                   forecast: forecast(["1500", "2200", "3100"]))
        XCTAssertEqual(projection, .beyondHorizon)
    }

    func test_incomeProjection_flatForecastBelowTarget_isNotOnTrack() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(["1000", "1000", "1000"]))
        XCTAssertEqual(projection, .notOnTrack)
    }

    func test_incomeProjection_emptyForecast_isInsufficientHistory() {
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("100"), target: usd("5000"), forecast: []),
                       .insufficientHistory)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter GoalMathTests`
Expected: FAIL — `type 'GoalMath' has no member 'incomeProjection'`.

- [ ] **Step 3: Implement it**

Append inside `public enum GoalMath` in `Sources/APTradeDomain/GoalMath.swift`:

```swift
    /// When projected annual income reaches `target`, read off the forecast curve.
    /// A forecast that never grows reports `.notOnTrack` rather than a fake ETA.
    public static func incomeProjection(current: Money, target: Money,
                                        forecast: [DividendMath.ForecastYear]) -> GoalProjection {
        guard current.amount < target.amount else { return .reached }
        guard let last = forecast.last else { return .insufficientHistory }
        if let crossing = forecast.first(where: { $0.income.amount >= target.amount }) {
            return .years(Double(crossing.yearOffset))
        }
        return last.income.amount > current.amount ? .beyondHorizon : .notOnTrack
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter GoalMathTests`
Expected: PASS (16 tests in this file).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeDomain/GoalMath.swift Tests/APTradeDomainTests/GoalMathTests.swift
git commit -m "feat(domain): income-goal projection from the dividend forecast"
```

---

## Task 10: Goal persistence, use cases, and reset clearing

**Files:**
- Modify: `Sources/APTradeApplication/Ports.swift` (add the port near `PortfolioStore`, line ~42)
- Create: `Sources/APTradeApplication/GoalUseCases.swift`
- Create: `Sources/APTradeInfrastructure/UserDefaultsGoalStore.swift`
- Modify: `Sources/APTradeApplication/PortfolioUseCases.swift` (`ResetPortfolioUseCase`)
- Test: `Tests/APTradeInfrastructureTests/UserDefaultsGoalStoreTests.swift` (create), `Tests/APTradeApplicationTests/GoalUseCasesTests.swift` (create)

**Interfaces:**
- Consumes: `PortfolioGoal`, `GoalKind` (Task 8).
- Produces:
  - `public protocol GoalStore: Sendable { func load() -> [PortfolioGoal]; func save(_ goals: [PortfolioGoal]) }`
  - `LoadGoalsUseCase` → `callAsFunction() -> [PortfolioGoal]`
  - `SaveGoalUseCase` → `callAsFunction(_ goal: PortfolioGoal)` (replaces any existing goal of the same kind)
  - `RemoveGoalUseCase` → `callAsFunction(kind: GoalKind)`
  - `ResetPortfolioUseCase.init(store:serializer:goalStore:)` — reset now clears goals.
  Tasks 11 and 13 consume the use cases via `CompositionRoot`.

- [ ] **Step 1: Write the failing store test**

Create `Tests/APTradeInfrastructureTests/UserDefaultsGoalStoreTests.swift`:

```swift
import XCTest
import APTradeDomain
@testable import APTradeInfrastructure

final class UserDefaultsGoalStoreTests: XCTestCase {
    private func makeDefaults() -> UserDefaults {
        let suite = "goal-store-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    func test_load_emptyByDefault() {
        XCTAssertTrue(UserDefaultsGoalStore(defaults: makeDefaults()).load().isEmpty)
    }

    func test_saveThenLoad_roundTrips() {
        let store = UserDefaultsGoalStore(defaults: makeDefaults())
        let goals = [PortfolioGoal(kind: .value, target: Money(amount: 250_000),
                                   createdAt: Date(timeIntervalSince1970: 1_700_000_000)),
                     PortfolioGoal(kind: .income, target: Money(amount: 5_000),
                                   createdAt: Date(timeIntervalSince1970: 1_700_000_000))]
        store.save(goals)
        XCTAssertEqual(store.load(), goals)
    }

    func test_load_corruptPayload_returnsEmpty() {
        let defaults = makeDefaults()
        defaults.set(Data("not json".utf8), forKey: "portfolioGoals")
        XCTAssertTrue(UserDefaultsGoalStore(defaults: defaults).load().isEmpty)
    }
}
```

- [ ] **Step 2: Write the failing use-case tests**

Create `Tests/APTradeApplicationTests/GoalUseCasesTests.swift`:

```swift
import XCTest
import APTradeDomain
@testable import APTradeApplication

private final class InMemoryGoalStore: GoalStore, @unchecked Sendable {
    private var goals: [PortfolioGoal]
    init(_ goals: [PortfolioGoal] = []) { self.goals = goals }
    func load() -> [PortfolioGoal] { goals }
    func save(_ goals: [PortfolioGoal]) { self.goals = goals }
}

final class GoalUseCasesTests: XCTestCase {
    private let epoch = Date(timeIntervalSince1970: 1_700_000_000)

    func test_save_addsGoal() {
        let store = InMemoryGoalStore()
        SaveGoalUseCase(store: store)(PortfolioGoal(kind: .value, target: Money(amount: 100), createdAt: epoch))
        XCTAssertEqual(LoadGoalsUseCase(store: store)().count, 1)
    }

    func test_save_replacesExistingGoalOfSameKind() {
        let store = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 100), createdAt: epoch)])
        SaveGoalUseCase(store: store)(PortfolioGoal(kind: .value, target: Money(amount: 900), createdAt: epoch))
        let goals = LoadGoalsUseCase(store: store)()
        XCTAssertEqual(goals.count, 1)
        XCTAssertEqual(goals.first?.target, Money(amount: 900))
    }

    func test_save_keepsGoalsOfOtherKinds() {
        let store = InMemoryGoalStore([PortfolioGoal(kind: .income, target: Money(amount: 50), createdAt: epoch)])
        SaveGoalUseCase(store: store)(PortfolioGoal(kind: .value, target: Money(amount: 900), createdAt: epoch))
        XCTAssertEqual(Set(LoadGoalsUseCase(store: store)().map(\.kind)), [.value, .income])
    }

    func test_remove_deletesOnlyRequestedKind() {
        let store = InMemoryGoalStore([
            PortfolioGoal(kind: .value, target: Money(amount: 100), createdAt: epoch),
            PortfolioGoal(kind: .income, target: Money(amount: 50), createdAt: epoch)])
        RemoveGoalUseCase(store: store)(kind: .value)
        XCTAssertEqual(LoadGoalsUseCase(store: store)().map(\.kind), [.income])
    }

    func test_reset_clearsGoals() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 100), createdAt: epoch)])
        let portfolioStore = InMemoryPortfolioStore(seed: Portfolio.starting())
        let sut = ResetPortfolioUseCase(store: portfolioStore, serializer: TradeSerializer(), goalStore: goalStore)
        _ = await sut(startingCash: Money(amount: 10_000))
        XCTAssertTrue(goalStore.load().isEmpty, "a fresh practice run must not inherit old goals")
    }
}
```

- [ ] **Step 3: Run both test files to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter GoalUseCasesTests`
Expected: FAIL — `cannot find type 'GoalStore' in scope`.

- [ ] **Step 4: Add the port**

In `Sources/APTradeApplication/Ports.swift`, below the `PortfolioStore` declaration:

```swift
/// Persists the user's portfolio goals. At most one goal per `GoalKind` is stored;
/// enforcing that is the use case's job, not the adapter's.
public protocol GoalStore: Sendable {
    func load() -> [PortfolioGoal]
    func save(_ goals: [PortfolioGoal])
}
```

- [ ] **Step 5: Add the use cases**

Create `Sources/APTradeApplication/GoalUseCases.swift`:

```swift
import APTradeDomain

public struct LoadGoalsUseCase: Sendable {
    private let store: GoalStore
    public init(store: GoalStore) { self.store = store }
    public func callAsFunction() -> [PortfolioGoal] { store.load() }
}

public struct SaveGoalUseCase: Sendable {
    private let store: GoalStore
    public init(store: GoalStore) { self.store = store }
    /// Upserts by kind — one value goal and one income goal at most.
    public func callAsFunction(_ goal: PortfolioGoal) {
        var goals = store.load().filter { $0.kind != goal.kind }
        goals.append(goal)
        store.save(goals)
    }
}

public struct RemoveGoalUseCase: Sendable {
    private let store: GoalStore
    public init(store: GoalStore) { self.store = store }
    public func callAsFunction(kind: GoalKind) {
        store.save(store.load().filter { $0.kind != kind })
    }
}
```

- [ ] **Step 6: Add the adapter**

Create `Sources/APTradeInfrastructure/UserDefaultsGoalStore.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

/// `UserDefaults`-backed goal persistence: one JSON array under a single key.
public final class UserDefaultsGoalStore: GoalStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "portfolioGoals") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> [PortfolioGoal] {
        guard let data = defaults.data(forKey: key),
              let goals = try? JSONDecoder().decode([PortfolioGoal].self, from: data) else { return [] }
        return goals
    }

    public func save(_ goals: [PortfolioGoal]) {
        guard let data = try? JSONEncoder().encode(goals) else { return }
        defaults.set(data, forKey: key)
    }
}
```

- [ ] **Step 7: Make reset clear goals**

In `Sources/APTradeApplication/PortfolioUseCases.swift`, extend `ResetPortfolioUseCase` — add the stored property, an initializer parameter (defaulted to `nil` so existing construction sites keep compiling), and the clearing call:

```swift
public struct ResetPortfolioUseCase: Sendable {
    private let store: PortfolioStore
    private let serializer: TradeSerializer
    private let goalStore: GoalStore?

    public init(store: PortfolioStore, serializer: TradeSerializer, goalStore: GoalStore? = nil) {
        self.store = store
        self.serializer = serializer
        self.goalStore = goalStore
    }

    public func callAsFunction(startingCash: Money) async -> Portfolio {
        await serializer.run {
            let fresh = Portfolio.starting(cash: startingCash)
            self.store.save(fresh)
            self.goalStore?.save([])
            return fresh
        }
    }
}
```

- [ ] **Step 8: Wire the store in the composition root**

In `Sources/APTradeApp/CompositionRoot.swift`, add a shared singleton alongside `portfolioStore` and pass it into the reset use case:

```swift
    static let goalStore: GoalStore = UserDefaultsGoalStore()
```

Then update the `ResetPortfolioUseCase(...)` construction site to pass `goalStore: goalStore`.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter GoalUseCasesTests`
Then: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter UserDefaultsGoalStoreTests`
Expected: PASS (5 and 3 tests respectively).

- [ ] **Step 10: Commit**

```bash
git add Sources/APTradeApplication/Ports.swift Sources/APTradeApplication/GoalUseCases.swift Sources/APTradeApplication/PortfolioUseCases.swift Sources/APTradeInfrastructure/UserDefaultsGoalStore.swift Sources/APTradeApp/CompositionRoot.swift Tests/APTradeApplicationTests/GoalUseCasesTests.swift Tests/APTradeInfrastructureTests/UserDefaultsGoalStoreTests.swift
git commit -m "feat: persist portfolio goals and clear them on reset"
```

---

## Task 11: Income view model — calendar, forecast, income goal

**Files:**
- Modify: `Sources/APTradeApp/IncomeViewModel.swift`
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (`makeIncomeViewModel()`)
- Test: `Tests/APTradeAppTests/IncomeViewModelTests.swift` (extend; create if absent)

**Interfaces:**
- Consumes: `DividendMath.projectedSchedule(...)` (Task 7), `DividendMath.incomeForecast(...)` (Task 6), `GoalMath.incomeProjection(...)` (Task 9), `LoadGoalsUseCase` / `SaveGoalUseCase` / `RemoveGoalUseCase` (Task 10), `AppSettings.dripEnabled`.
- Produces on `IncomeViewModel`:
  - `enum ForecastHorizon: Int, CaseIterable, Identifiable { case five = 5, ten = 10, twenty = 20, thirty = 30 }`
  - `struct CalendarMonth: Identifiable, Equatable { let id: String; let title: String; let rows: [DividendMath.ScheduledDividend]; let total: Money }`
  - `@Published private(set) var calendarMonths: [CalendarMonth]`
  - `@Published private(set) var forecast: [DividendMath.ForecastYear]`
  - `@Published var horizon: ForecastHorizon` (triggers a forecast rebuild on change)
  - `@Published private(set) var incomeGoal: PortfolioGoal?`
  - `@Published private(set) var incomeGoalProjection: GoalProjection?`
  - `func setIncomeGoal(_ target: Money)` / `func removeIncomeGoal()`
  Task 12 renders all of it.

- [ ] **Step 1: Write the failing tests**

Append to `Tests/APTradeAppTests/IncomeViewModelTests.swift` (match the file's existing fakes for `FetchPortfolioUseCase` / `FetchQuotesUseCase` / `DividendEventsRepository`; if the file does not exist, create it modeling the doubles on `Tests/APTradeAppTests/PortfolioViewModelTests.swift`):

```swift
    @MainActor
    func test_load_buildsCalendarMonthsFromProjectedSchedule() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        XCTAssertFalse(vm.calendarMonths.isEmpty)
        XCTAssertTrue(vm.calendarMonths.allSatisfy { !$0.rows.isEmpty })
        let totals = vm.calendarMonths.map(\.total.amount)
        XCTAssertTrue(totals.allSatisfy { $0 > 0 })
    }

    @MainActor
    func test_load_defaultHorizonIsTenYears() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        XCTAssertEqual(vm.horizon, .ten)
        XCTAssertEqual(vm.forecast.count, 10)
    }

    @MainActor
    func test_changingHorizon_rebuildsForecastLength() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        vm.horizon = .thirty
        XCTAssertEqual(vm.forecast.count, 30)
    }

    @MainActor
    func test_setIncomeGoal_persistsAndProjects() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        vm.setIncomeGoal(Money(amount: 5_000))
        XCTAssertEqual(vm.incomeGoal?.target, Money(amount: 5_000))
        XCTAssertNotNil(vm.incomeGoalProjection)
    }

    @MainActor
    func test_removeIncomeGoal_clearsGoalAndProjection() async {
        let vm = makeSUT(holdings: [("AAA", "100")], events: ["AAA": quarterlyHistory("AAA", "0.50")])
        await vm.load()
        vm.setIncomeGoal(Money(amount: 5_000))
        vm.removeIncomeGoal()
        XCTAssertNil(vm.incomeGoal)
        XCTAssertNil(vm.incomeGoalProjection)
    }

    @MainActor
    func test_load_emptyLedger_hasNoCalendarOrForecast() async {
        let vm = makeSUT(holdings: [], events: [:])
        await vm.load()
        XCTAssertTrue(vm.calendarMonths.isEmpty)
        XCTAssertTrue(vm.forecast.allSatisfy { $0.income.amount == 0 })
    }
```

Add these helpers to the test class (adapting `makeSUT` to the file's existing construction helper if one already exists):

```swift
    private func quarterlyHistory(_ symbol: String, _ amount: String) -> [DividendMath.DividendEvent] {
        [3, 6, 9, 12].map {
            DividendMath.DividendEvent(symbol: symbol,
                                       exDate: fixedDate(2024, $0, 10),
                                       amountPerShare: Money(amount: Decimal(string: amount) ?? 0))
        }
    }
    private func fixedDate(_ y: Int, _ m: Int, _ d: Int) -> Date {
        var c = DateComponents(); c.year = y; c.month = m; c.day = d
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: c)!
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter IncomeViewModelTests`
Expected: FAIL — `value of type 'IncomeViewModel' has no member 'calendarMonths'`.

- [ ] **Step 3: Add the state and builders**

In `Sources/APTradeApp/IncomeViewModel.swift`, extend the class. Add the nested types near the existing row structs:

```swift
    enum ForecastHorizon: Int, CaseIterable, Identifiable {
        case five = 5, ten = 10, twenty = 20, thirty = 30
        var id: Int { rawValue }
        var label: String { "\(rawValue)y" }
    }

    struct CalendarMonth: Identifiable, Equatable {
        let id: String
        let title: String
        let rows: [DividendMath.ScheduledDividend]
        let total: Money
    }
```

Add the published state alongside the existing `@Published private(set)` properties:

```swift
    @Published private(set) var calendarMonths: [CalendarMonth] = []
    @Published private(set) var forecast: [DividendMath.ForecastYear] = []
    @Published private(set) var incomeGoal: PortfolioGoal?
    @Published private(set) var incomeGoalProjection: GoalProjection?
    @Published var horizon: ForecastHorizon = .ten {
        didSet { rebuildForecast() }
    }
```

Add the dependencies to the initializer (defaulted so existing test construction keeps working):

```swift
    private let loadGoals: LoadGoalsUseCase
    private let saveGoal: SaveGoalUseCase
    private let removeGoal: RemoveGoalUseCase
    private let isDripEnabled: @Sendable () -> Bool
```

with matching `init` parameters `loadGoals:`, `saveGoal:`, `removeGoal:`, `isDripEnabled:` assigned in the body.

Cache what the rebuild needs — add private storage populated during `load()`:

```swift
    private var lastPositions: [Position] = []
    private var lastEventsBySymbol: [String: [DividendMath.DividendEvent]] = [:]
```

At the end of `load()` (after the existing builders run, using the same fetched portfolio and events the method already has in scope), add:

```swift
        lastPositions = portfolio.positions
        lastEventsBySymbol = eventsBySymbol
        calendarMonths = Self.buildCalendar(positions: portfolio.positions,
                                            eventsBySymbol: eventsBySymbol,
                                            now: now())
        incomeGoal = loadGoals().first { $0.kind == .income }
        rebuildForecast()
```

Add the builders and goal mutators:

```swift
    private func rebuildForecast() {
        forecast = DividendMath.incomeForecast(positions: lastPositions,
                                               eventsBySymbol: lastEventsBySymbol,
                                               years: horizon.rawValue,
                                               dripEnabled: isDripEnabled(),
                                               asOf: now())
        refreshGoalProjection()
    }

    private func refreshGoalProjection() {
        guard let goal = incomeGoal else { incomeGoalProjection = nil; return }
        // Always project against a full 30-year curve so the answer does not change
        // when the user flips the chart's horizon pills.
        let full = DividendMath.incomeForecast(positions: lastPositions,
                                               eventsBySymbol: lastEventsBySymbol,
                                               years: 30,
                                               dripEnabled: isDripEnabled(),
                                               asOf: now())
        let current = DividendMath.projectedAnnualIncome(positions: lastPositions,
                                                         eventsBySymbol: lastEventsBySymbol,
                                                         asOf: now())
        incomeGoalProjection = GoalMath.incomeProjection(current: current,
                                                         target: goal.target,
                                                         forecast: full)
    }

    func setIncomeGoal(_ target: Money) {
        let goal = PortfolioGoal(kind: .income, target: target, createdAt: now())
        saveGoal(goal)
        incomeGoal = goal
        refreshGoalProjection()
    }

    func removeIncomeGoal() {
        removeGoal(kind: .income)
        incomeGoal = nil
        incomeGoalProjection = nil
    }

    private static func buildCalendar(positions: [Position],
                                      eventsBySymbol: [String: [DividendMath.DividendEvent]],
                                      now: Date) -> [CalendarMonth] {
        let horizon = now.addingTimeInterval(365 * 86_400)
        let scheduled = DividendMath.projectedSchedule(positions: positions,
                                                       eventsBySymbol: eventsBySymbol,
                                                       through: horizon, asOf: now)
        guard !scheduled.isEmpty else { return [] }

        let keyFormatter = DateFormatter()
        keyFormatter.dateFormat = "yyyy-MM"
        let titleFormatter = DateFormatter()
        titleFormatter.setLocalizedDateFormatFromTemplate("MMMM yyyy")

        var order: [String] = []
        var grouped: [String: [DividendMath.ScheduledDividend]] = [:]
        for row in scheduled {
            let key = keyFormatter.string(from: row.exDate)
            if grouped[key] == nil { order.append(key) }
            grouped[key, default: []].append(row)
        }
        return order.map { key in
            let rows = grouped[key] ?? []
            let total = rows.reduce(Decimal(0)) { $0 + $1.estimatedAmount.amount }
            return CalendarMonth(id: key,
                                 title: titleFormatter.string(from: rows[0].exDate),
                                 rows: rows,
                                 total: Money(amount: total))
        }
    }
```

- [ ] **Step 4: Wire the composition root**

In `Sources/APTradeApp/CompositionRoot.swift`, `makeIncomeViewModel()` passes the new dependencies:

```swift
            loadGoals: LoadGoalsUseCase(store: goalStore),
            saveGoal: SaveGoalUseCase(store: goalStore),
            removeGoal: RemoveGoalUseCase(store: goalStore),
            isDripEnabled: { LoadSettingsUseCase(store: settingsStore)().dripEnabled }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter IncomeViewModelTests`
Expected: PASS (6 new tests plus the file's existing ones).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeApp/IncomeViewModel.swift Sources/APTradeApp/CompositionRoot.swift Tests/APTradeAppTests/IncomeViewModelTests.swift
git commit -m "feat(app): income view model gains calendar, forecast, and income goal"
```

---

## Task 12: Income UI — calendar card, forecast chart, income-goal card

**Files:**
- Modify: `Sources/APTradeApp/IncomeSection.swift`
- Modify: `Sources/APTradeApp/L10n.swift`
- Create: `Sources/APTradeApp/GoalCard.swift` (shared by this task and Task 13)

**Interfaces:**
- Consumes: every published property added in Task 11; `GoalProjection` (Task 8).
- Produces: `struct GoalCard: View` with initializer
  `init(title: String, current: Money, goal: PortfolioGoal?, projection: GoalProjection?, onSet: @escaping (Money) -> Void, onRemove: @escaping () -> Void)` — Task 13 reuses it verbatim for the value goal.

- [ ] **Step 1: Add the L10n keys (all four languages)**

In `Sources/APTradeApp/L10n.swift`, add to `Key`:

```swift
        case upcomingDividends = "Upcoming Dividends"
        case estimatedShort = "est."
        case incomeForecast = "Income Forecast"
        case forecastCaption = "Assumes historical dividend growth continues; DRIP compounding where enabled."
        case incomeGoal = "Income Goal"
        case valueGoal = "Value Goal"
        case setGoal = "Set a goal"
        case editGoal = "Edit goal"
        case removeGoal = "Remove goal"
        case goalTarget = "Target"
        case goalReached = "Goal reached"
        case goalNotOnTrack = "Not on track at current rate"
        case goalNeedsHistory = "Tracking — needs more history"
        case goalBeyondHorizon = "More than 30 yrs at this rate"
        case goalYearsFormat = "About %@ yrs at this rate"
        case noUpcomingDividends = "No dividend payers held yet."
```

And the matching `table` rows (every key needs all four languages):

```swift
        .upcomingDividends: [.english: "Upcoming Dividends", .german: "Kommende Dividenden",
                             .italian: "Prossimi dividendi", .spanish: "Próximos dividendos"],
        .estimatedShort: [.english: "est.", .german: "geschätzt", .italian: "stim.", .spanish: "est."],
        .incomeForecast: [.english: "Income Forecast", .german: "Einkommensprognose",
                          .italian: "Previsione di reddito", .spanish: "Previsión de ingresos"],
        .forecastCaption: [.english: "Assumes historical dividend growth continues; DRIP compounding where enabled.",
                           .german: "Annahme: historisches Dividendenwachstum setzt sich fort; DRIP-Verzinsung wo aktiviert.",
                           .italian: "Presuppone che la crescita storica dei dividendi continui; capitalizzazione DRIP dove attiva.",
                           .spanish: "Supone que continúa el crecimiento histórico de dividendos; capitalización DRIP donde esté activada."],
        .incomeGoal: [.english: "Income Goal", .german: "Einkommensziel",
                      .italian: "Obiettivo di reddito", .spanish: "Objetivo de ingresos"],
        .valueGoal: [.english: "Value Goal", .german: "Wertziel",
                     .italian: "Obiettivo di valore", .spanish: "Objetivo de valor"],
        .setGoal: [.english: "Set a goal", .german: "Ziel festlegen",
                   .italian: "Imposta un obiettivo", .spanish: "Fijar un objetivo"],
        .editGoal: [.english: "Edit goal", .german: "Ziel bearbeiten",
                    .italian: "Modifica obiettivo", .spanish: "Editar objetivo"],
        .removeGoal: [.english: "Remove goal", .german: "Ziel entfernen",
                      .italian: "Rimuovi obiettivo", .spanish: "Eliminar objetivo"],
        .goalTarget: [.english: "Target", .german: "Ziel", .italian: "Obiettivo", .spanish: "Objetivo"],
        .goalReached: [.english: "Goal reached", .german: "Ziel erreicht",
                       .italian: "Obiettivo raggiunto", .spanish: "Objetivo alcanzado"],
        .goalNotOnTrack: [.english: "Not on track at current rate",
                          .german: "Beim aktuellen Tempo nicht erreichbar",
                          .italian: "Non in linea al ritmo attuale",
                          .spanish: "Fuera de camino al ritmo actual"],
        .goalNeedsHistory: [.english: "Tracking — needs more history",
                            .german: "Wird erfasst – benötigt mehr Verlauf",
                            .italian: "In monitoraggio — serve più storico",
                            .spanish: "En seguimiento: falta historial"],
        .goalBeyondHorizon: [.english: "More than 30 yrs at this rate",
                             .german: "Mehr als 30 Jahre bei diesem Tempo",
                             .italian: "Più di 30 anni a questo ritmo",
                             .spanish: "Más de 30 años a este ritmo"],
        .goalYearsFormat: [.english: "About %@ yrs at this rate",
                           .german: "Etwa %@ Jahre bei diesem Tempo",
                           .italian: "Circa %@ anni a questo ritmo",
                           .spanish: "Unos %@ años a este ritmo"],
        .noUpcomingDividends: [.english: "No dividend payers held yet.",
                               .german: "Noch keine Dividendenzahler im Bestand.",
                               .italian: "Nessun titolo con dividendi in portafoglio.",
                               .spanish: "Aún no tienes valores con dividendos."],
```

- [ ] **Step 2: Build the shared goal card**

Create `Sources/APTradeApp/GoalCard.swift`:

```swift
import SwiftUI
import APTradeDomain

/// Progress + honest projection for one portfolio goal. Shared by Performance (value)
/// and Income (income). Empty state offers a quiet "Set a goal" affordance.
struct GoalCard: View {
    let title: String
    let current: Money
    let goal: PortfolioGoal?
    let projection: GoalProjection?
    let onSet: (Money) -> Void
    let onRemove: () -> Void

    @State private var isEditing = false
    @State private var targetText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title)
                    .font(.headline)
                Spacer()
                if goal != nil {
                    Menu {
                        Button(tr(.editGoal)) { beginEditing() }
                        Button(tr(.removeGoal), role: .destructive) { onRemove() }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }
            }

            if let goal {
                let fraction = GoalMath.progress(current: current, target: goal.target)
                ProgressView(value: min(fraction, 1.0))
                HStack {
                    Text(current.formatted())
                    Text("/")
                        .foregroundStyle(.secondary)
                    Text(goal.target.formatted())
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("\(Int((fraction * 100).rounded()))%")
                        .monospacedDigit()
                }
                .font(.callout)
                Text(Self.projectionText(projection))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Button(tr(.setGoal)) { beginEditing() }
                    .buttonStyle(.borderless)
                    .font(.callout)
            }
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .stroke(Theme.hairline, lineWidth: 1))
        .sheet(isPresented: $isEditing) {
            GoalEditSheet(title: title, targetText: $targetText) { amount in onSet(amount) }
        }
    }

    private func beginEditing() {
        targetText = goal.map { NSDecimalNumber(decimal: $0.target.amount).stringValue } ?? ""
        isEditing = true
    }

    static func projectionText(_ projection: GoalProjection?) -> String {
        switch projection {
        case .reached: return tr(.goalReached)
        case .notOnTrack: return tr(.goalNotOnTrack)
        case .insufficientHistory, .none: return tr(.goalNeedsHistory)
        case .beyondHorizon: return tr(.goalBeyondHorizon)
        case let .years(y):
            let rounded = y < 10 ? String(format: "%.1f", y) : String(Int(y.rounded()))
            return String(format: tr(.goalYearsFormat), rounded)
        }
    }
}

/// Amount entry for a goal target. Reuses the starting-balance range and parser.
struct GoalEditSheet: View {
    let title: String
    @Binding var targetText: String
    let onSave: (Money) -> Void
    @Environment(\.dismiss) private var dismiss

    private var parsed: Money? { StartingBalanceInput.parse(targetText) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.title3.weight(.semibold))
            VStack(alignment: .leading, spacing: 6) {
                Text(tr(.goalTarget))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                TextField("", text: $targetText)
                    .textFieldStyle(.roundedBorder)
                Text(tr(.startingBalanceRange))
                    .font(.caption2)
                    .foregroundStyle(parsed == nil && !targetText.isEmpty ? Color.red : .secondary)
            }
            HStack {
                Spacer()
                Button(tr(.cancel)) { dismiss() }
                Button(tr(.save)) {
                    guard let amount = parsed else { return }
                    onSave(amount)
                    dismiss()
                }
                .disabled(parsed == nil)
            }
        }
        .padding(20)
        .frame(minWidth: 320)
    }
}
```

If `Money` has no `formatted()` helper, use whatever formatting helper `IncomeSection` already applies to its card values — match the file, do not invent a formatter. If `.save` / `.cancel` L10n keys do not exist, add them with all four languages.

- [ ] **Step 3: Add the three Income cards**

In `Sources/APTradeApp/IncomeSection.swift`, add these private computed properties and insert them into `ledger` in this order: existing summary grid → `incomeGoalCard` → existing monthly chart → `upcomingCalendarCard` → `forecastCard` → existing upcoming/holdings/history cards.

```swift
    private var incomeGoalCard: some View {
        GoalCard(title: tr(.incomeGoal),
                 current: viewModel.cards?.projectedAnnual ?? Money(amount: 0),
                 goal: viewModel.incomeGoal,
                 projection: viewModel.incomeGoalProjection,
                 onSet: { viewModel.setIncomeGoal($0) },
                 onRemove: { viewModel.removeIncomeGoal() })
    }

    private var upcomingCalendarCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(tr(.upcomingDividends)).font(.headline)
                Spacer()
                Text(tr(.estimatedShort))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if viewModel.calendarMonths.isEmpty {
                Text(tr(.noUpcomingDividends))
                    .font(.callout)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(viewModel.calendarMonths) { month in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(month.title)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                            Spacer()
                            Text(month.total.formatted())
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        ForEach(month.rows, id: \.exDate) { row in
                            HStack {
                                Text(row.symbol).font(.callout.weight(.medium))
                                Spacer()
                                Text(row.estimatedAmount.formatted())
                                    .font(.callout.monospacedDigit())
                            }
                        }
                    }
                }
            }
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .stroke(Theme.hairline, lineWidth: 1))
    }

    private var forecastCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(tr(.incomeForecast)).font(.headline)
                Spacer()
                Picker("", selection: $viewModel.horizon) {
                    ForEach(IncomeViewModel.ForecastHorizon.allCases) { horizon in
                        Text(horizon.label).tag(horizon)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
            }
            Chart(viewModel.forecast, id: \.yearOffset) { year in
                AreaMark(x: .value("Year", year.yearOffset),
                         y: .value("Income", NSDecimalNumber(decimal: year.income.amount).doubleValue))
            }
            .frame(height: 180)
            Text(tr(.forecastCaption))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .stroke(Theme.hairline, lineWidth: 1))
    }
```

Add `import Charts` at the top of `IncomeSection.swift` (it currently hand-rolls its bar chart and does not import it). Because `IncomeViewModel` is an `ObservableObject` held via `@StateObject`, `$viewModel.horizon` binds directly — no extra plumbing.

If `viewModel.cards?.projectedAnnual` is not the exact property name on the existing `SummaryCards` struct, use whichever field holds projected annual income — check the struct before writing the line.

- [ ] **Step 4: Build and run the app tests**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build`
Then: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: build succeeds, all tests pass including `L10nTests`.

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeApp/GoalCard.swift Sources/APTradeApp/IncomeSection.swift Sources/APTradeApp/L10n.swift
git commit -m "feat(app): dividend calendar, income forecast chart, and income goal card"
```

---

## Task 13: Value-goal card in Performance

**Files:**
- Modify: `Sources/APTradeApp/PerformanceViewModel.swift`
- Modify: `Sources/APTradeApp/PerformanceSection.swift` (insert into `loaded(_:)`)
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (`makePerformanceViewModel()`)
- Test: `Tests/APTradeAppTests/PerformanceViewModelTests.swift` (extend; create if absent)

**Interfaces:**
- Consumes: `GoalCard` (Task 12), `GoalMath.valueProjection(current:target:curve:)` (Task 8), goal use cases (Task 10), `EquityPoint`.
- Produces on `PerformanceViewModel`: `private(set) var valueGoal: PortfolioGoal?`, `private(set) var valueGoalProjection: GoalProjection?`, `private(set) var currentValue: Money`, `func setValueGoal(_ target: Money)`, `func removeValueGoal()`.

- [ ] **Step 1: Write the failing tests**

Append to `Tests/APTradeAppTests/PerformanceViewModelTests.swift` (model the doubles on the file's existing `ComputePerformanceMetricsUseCase` fake; if the file does not exist, create it following `PortfolioViewModelTests.swift`):

```swift
    @MainActor
    func test_load_readsPersistedValueGoal() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 250_000),
                                                         createdAt: Date(timeIntervalSince1970: 1))])
        let vm = makeSUT(goalStore: goalStore)
        await vm.load()
        XCTAssertEqual(vm.valueGoal?.target, Money(amount: 250_000))
        XCTAssertNotNil(vm.valueGoalProjection)
    }

    @MainActor
    func test_setValueGoal_persistsAndProjects() async {
        let goalStore = InMemoryGoalStore()
        let vm = makeSUT(goalStore: goalStore)
        await vm.load()
        vm.setValueGoal(Money(amount: 500_000))
        XCTAssertEqual(vm.valueGoal?.target, Money(amount: 500_000))
        XCTAssertEqual(goalStore.load().count, 1)
        XCTAssertNotNil(vm.valueGoalProjection)
    }

    @MainActor
    func test_removeValueGoal_clearsStateAndStore() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .value, target: Money(amount: 250_000),
                                                         createdAt: Date(timeIntervalSince1970: 1))])
        let vm = makeSUT(goalStore: goalStore)
        await vm.load()
        vm.removeValueGoal()
        XCTAssertNil(vm.valueGoal)
        XCTAssertNil(vm.valueGoalProjection)
        XCTAssertTrue(goalStore.load().isEmpty)
    }

    @MainActor
    func test_setValueGoal_doesNotDisturbIncomeGoal() async {
        let goalStore = InMemoryGoalStore([PortfolioGoal(kind: .income, target: Money(amount: 5_000),
                                                         createdAt: Date(timeIntervalSince1970: 1))])
        let vm = makeSUT(goalStore: goalStore)
        await vm.load()
        vm.setValueGoal(Money(amount: 500_000))
        XCTAssertEqual(Set(goalStore.load().map(\.kind)), [.value, .income])
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PerformanceViewModelTests`
Expected: FAIL — `value of type 'PerformanceViewModel' has no member 'valueGoal'`.

- [ ] **Step 3: Extend the view model**

In `Sources/APTradeApp/PerformanceViewModel.swift`, add the dependencies to `init` (defaulted where the existing tests construct it directly):

```swift
    private let loadGoals: LoadGoalsUseCase
    private let saveGoal: SaveGoalUseCase
    private let removeGoal: RemoveGoalUseCase
    private let now: () -> Date
```

Add the state (plain properties — the class is `@Observable`):

```swift
    private(set) var valueGoal: PortfolioGoal?
    private(set) var valueGoalProjection: GoalProjection?
    private(set) var currentValue: Money = Money(amount: 0)
    private var equityCurve: [EquityPoint] = []
```

In `load()`, after the report is computed and assigned, capture what the projection needs and refresh it. The equity curve and current value come from the loaded `PerformanceReport` — use the report's existing series and latest value fields rather than adding a second data path:

```swift
        equityCurve = report.equityCurve
        currentValue = report.currentValue
        valueGoal = loadGoals().first { $0.kind == .value }
        refreshValueProjection()
```

If `PerformanceReport` exposes those under different names, use the real ones; if it carries no equity series at all, pass the series `PerformanceSection`'s overlay chart already renders into the view model rather than recomputing it.

Add the mutators:

```swift
    private func refreshValueProjection() {
        guard let goal = valueGoal else { valueGoalProjection = nil; return }
        valueGoalProjection = GoalMath.valueProjection(current: currentValue,
                                                       target: goal.target,
                                                       curve: equityCurve)
    }

    func setValueGoal(_ target: Money) {
        let goal = PortfolioGoal(kind: .value, target: target, createdAt: now())
        saveGoal(goal)
        valueGoal = goal
        refreshValueProjection()
    }

    func removeValueGoal() {
        removeGoal(kind: .value)
        valueGoal = nil
        valueGoalProjection = nil
    }
```

- [ ] **Step 4: Wire the composition root**

In `makePerformanceViewModel()`, pass:

```swift
            loadGoals: LoadGoalsUseCase(store: goalStore),
            saveGoal: SaveGoalUseCase(store: goalStore),
            removeGoal: RemoveGoalUseCase(store: goalStore)
```

- [ ] **Step 5: Add the card to the view**

In `Sources/APTradeApp/PerformanceSection.swift`, inside `loaded(_:)`, insert the card directly below `metricGrid` and above `benchmarkPicker`:

```swift
                GoalCard(title: tr(.valueGoal),
                         current: viewModel.currentValue,
                         goal: viewModel.valueGoal,
                         projection: viewModel.valueGoalProjection,
                         onSet: { viewModel.setValueGoal($0) },
                         onRemove: { viewModel.removeValueGoal() })
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PerformanceViewModelTests`
Expected: PASS (4 new tests plus existing).

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/PerformanceViewModel.swift Sources/APTradeApp/PerformanceSection.swift Sources/APTradeApp/CompositionRoot.swift Tests/APTradeAppTests/PerformanceViewModelTests.swift
git commit -m "feat(app): value goal card on Portfolio Performance"
```

---

## Task 14: Whole-increment verification and documentation

**Files:**
- Modify: `README.md` (feature list / roadmap sections)
- Test: full suite

**Interfaces:**
- Consumes: everything above.
- Produces: a verified, documented M11.1 increment ready for review and merge.

- [ ] **Step 1: Prove no hardcoded starting balance survives**

Run:

```bash
grep -rn "100_000\|100000" Sources/ | grep -v "AppSettings.swift" | grep -v "Portfolio.swift:35"
```

Expected: no output other than unrelated numeric constants. Any surviving reference in a calculation path is a bug — fix it and note which file in the commit message. (`Portfolio.starting`'s default and the `AppSettings` default are the only two permitted literals.)

- [ ] **Step 2: Run the entire suite**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: PASS with zero failures. Record the final test count in the commit message.

- [ ] **Step 3: Manual smoke checklist (macOS)**

Launch the macOS app and confirm, in order:

1. Portfolio → "⋯" → Reset opens the sheet; typing `abc` disables Reset; typing `500` disables it; `25000` enables it. Confirm reset → cash reads $25,000.
2. Reopen the reset sheet → it pre-fills `25000` (the remembered default).
3. Portfolio · Performance → "Set a goal" → enter `50000` → card shows a progress bar, a percentage, and a projection line (not a blank or a crash).
4. Invest · Income → income-goal card, an "Upcoming Dividends" list whose header shows the "est." label, and a forecast chart whose 5/10/20/30 pills change the curve.
5. Reset the portfolio again → both goal cards return to their "Set a goal" empty state.

- [ ] **Step 4: Manual smoke checklist (iPhone simulator)**

Repeat steps 1–5 above in the iPhone simulator, confirming the sheets are reachable and readable at phone width and that the forecast pills are not clipped.

- [ ] **Step 5: Update the README**

Add the three features to the README's feature list (matching the existing entry style) — configurable starting balance, portfolio goals, dividend calendar and income forecast — and per the project's close-out convention, remove any roadmap lines these features have now delivered. Mark the entries as Swift-only until M11.2 lands the Kotlin wave.

- [ ] **Step 6: Commit**

```bash
git add README.md
git commit -m "docs: README — M11.1 goals and income depth (Swift wave)"
```

---

## Plan self-review notes

- **Spec coverage:** F1 → Tasks 1–4; F2 → Tasks 8, 9, 10, 11, 12, 13; F3 → Tasks 5, 6, 7, 11, 12. Cross-cutting L10n → Tasks 4, 12 (plus the enforcement test run in every task's suite step). Acceptance criteria 1–4 → Task 14 steps 1–4.
- **Ordering constraint honored:** `DividendMath.incomeForecast` (Task 6) precedes `GoalMath.incomeProjection` (Task 9), which precedes the view model that calls both (Task 11).
- **Known naming risks flagged inline** (each has an explicit "check the real signature" instruction rather than a guess): `Asset` initializer (Task 6), `EquityPoint` initializer (Task 8), `SummaryCards.projectedAnnual` (Task 12), `PerformanceReport.equityCurve` / `.currentValue` (Task 13), `Money.formatted()` (Task 12), the existing cadence-interval helper (Task 7), and `InMemoryPortfolioStore` (Tasks 3, 10).
