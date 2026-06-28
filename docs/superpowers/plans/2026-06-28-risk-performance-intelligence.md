# Risk & Performance Intelligence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Performance segment to the Portfolio tab that reports honest, professionally-correct return/risk/benchmark/concentration analytics computed from the transaction log and historical prices.

**Architecture:** Pure-domain metric functions (returns, risk, diversification) + a trade-aware equity curve that replays the transaction log, orchestrated by one application use case, surfaced through a dedicated `@Observable` view model and a new SwiftUI section. Strict Clean Architecture: domain imports only `Foundation`; presentation owns no business logic.

**Tech Stack:** Swift, SwiftUI, Swift Charts, XCTest. Three SPM modules already exist: `APTradeDomain` → `APTradeApplication` → `APTradeApp` (presentation), with `APTradeInfrastructure` providing concrete repositories.

## Global Constraints

- Domain layer (`Sources/APTradeDomain/`) imports **only `Foundation`** — no networking, no persistence, no framework imports.
- No force-unwraps; no business logic in views; no massive view models (use a dedicated `PerformanceViewModel`).
- `swift test` requires the Xcode toolchain: prefix every test command with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` or XCTest is missing.
- Money amounts are `Decimal`; statistics that need `sqrt`/regression work in `Double`, converting at the boundary via `(decimal as NSDecimalNumber).doubleValue`.
- Risk-free rate default: `0.04` (4% annualized). Annualization factor: `252` trading days.
- Benchmarks: `SPY`, `QQQ`, `VTI`. Default `SPY`.
- Follow existing test style: `private final class ...: Protocol, @unchecked Sendable` stubs in the test file; `@MainActor` on view-model test classes.

---

### Task 1: RiskMetrics (domain)

Pure return/risk statistics + the `PerformanceMetrics` output struct.

**Files:**
- Create: `Sources/APTradeDomain/RiskMetrics.swift`
- Test: `Tests/APTradeDomainTests/RiskMetricsTests.swift`

**Interfaces:**
- Consumes: nothing (pure).
- Produces:
  - `enum RiskMetrics` with statics: `dailyReturns([Double]) -> [Double]`, `totalReturn([Double]) -> Double`, `annualizedReturn([Double], periodsPerYear: Double = 252) -> Double`, `annualizedVolatility([Double], periodsPerYear: Double = 252) -> Double`, `maxDrawdown([Double]) -> Double`, `sharpe(annualizedReturn: Double, annualizedVolatility: Double, riskFree: Double) -> Double?`, `beta(portfolioReturns: [Double], benchmarkReturns: [Double]) -> Double?`, `alpha(annualizedReturn: Double, beta: Double, benchmarkAnnualizedReturn: Double, riskFree: Double) -> Double`
  - `struct PerformanceMetrics: Equatable, Sendable { totalReturn, annualizedReturn, volatility, maxDrawdown: Double; sharpe, beta, alpha: Double? }`

- [ ] **Step 1: Write the failing test**

Create `Tests/APTradeDomainTests/RiskMetricsTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class RiskMetricsTests: XCTestCase {
    func test_dailyReturns_computesSimpleReturns() {
        let r = RiskMetrics.dailyReturns([100, 110, 99])
        XCTAssertEqual(r.count, 2)
        XCTAssertEqual(r[0], 0.10, accuracy: 1e-9)
        XCTAssertEqual(r[1], -0.10, accuracy: 1e-9)
    }

    func test_dailyReturns_shortInput_returnsEmpty() {
        XCTAssertTrue(RiskMetrics.dailyReturns([100]).isEmpty)
        XCTAssertTrue(RiskMetrics.dailyReturns([]).isEmpty)
    }

    func test_totalReturn_isEndOverStart() {
        XCTAssertEqual(RiskMetrics.totalReturn([100, 150]), 0.5, accuracy: 1e-9)
        XCTAssertEqual(RiskMetrics.totalReturn([100]), 0, accuracy: 1e-9)
    }

    func test_annualizedReturn_compoundsToPeriodsPerYear() {
        // 1% over 1 day, annualized over 252 trading days.
        let a = RiskMetrics.annualizedReturn([100, 101], periodsPerYear: 252)
        XCTAssertEqual(a, pow(1.01, 252) - 1, accuracy: 1e-6)
    }

    func test_annualizedVolatility_scalesBySqrtPeriods() {
        let returns = [0.01, -0.01, 0.02, -0.02]
        let mean = returns.reduce(0, +) / 4
        let variance = returns.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / 3
        let expected = (variance).squareRoot() * (252.0).squareRoot()
        XCTAssertEqual(RiskMetrics.annualizedVolatility(returns), expected, accuracy: 1e-9)
    }

    func test_maxDrawdown_findsWorstPeakToTrough() {
        // peak 120 then trough 90 → -25%
        let dd = RiskMetrics.maxDrawdown([100, 120, 90, 110])
        XCTAssertEqual(dd, 90.0 / 120.0 - 1, accuracy: 1e-9)
    }

    func test_sharpe_nilWhenZeroVolatility() {
        XCTAssertNil(RiskMetrics.sharpe(annualizedReturn: 0.1, annualizedVolatility: 0, riskFree: 0.04))
        XCTAssertEqual(RiskMetrics.sharpe(annualizedReturn: 0.10, annualizedVolatility: 0.20, riskFree: 0.04)!,
                       (0.10 - 0.04) / 0.20, accuracy: 1e-9)
    }

    func test_beta_ofIdenticalSeriesIsOne() {
        let r = [0.01, -0.02, 0.03, -0.01]
        XCTAssertEqual(RiskMetrics.beta(portfolioReturns: r, benchmarkReturns: r)!, 1.0, accuracy: 1e-9)
    }

    func test_beta_nilWhenMismatchedOrZeroVariance() {
        XCTAssertNil(RiskMetrics.beta(portfolioReturns: [0.01, 0.02], benchmarkReturns: [0.01]))
        XCTAssertNil(RiskMetrics.beta(portfolioReturns: [0.01, 0.02], benchmarkReturns: [0.0, 0.0]))
    }

    func test_alpha_isZeroWhenReturnMatchesCAPM() {
        // If annualReturn == riskFree + beta*(bench - riskFree), alpha == 0.
        let alpha = RiskMetrics.alpha(annualizedReturn: 0.04 + 1.2 * (0.10 - 0.04),
                                      beta: 1.2, benchmarkAnnualizedReturn: 0.10, riskFree: 0.04)
        XCTAssertEqual(alpha, 0, accuracy: 1e-9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter RiskMetricsTests`
Expected: FAIL — `cannot find 'RiskMetrics' in scope`.

- [ ] **Step 3: Write minimal implementation**

Create `Sources/APTradeDomain/RiskMetrics.swift`:

```swift
import Foundation

/// Pure return/risk statistics over a portfolio's daily equity curve. Deterministic
/// transforms with no I/O. Works in `Double` because the statistics need square roots
/// and regression that `Decimal` can't express; callers convert money to `Double` once.
public enum RiskMetrics {
    /// Day-over-day simple returns: rₜ = vₜ / vₜ₋₁ − 1. Skips non-positive predecessors
    /// to avoid divide-by-zero. Fewer than 2 values → [].
    public static func dailyReturns(_ values: [Double]) -> [Double] {
        guard values.count > 1 else { return [] }
        var out: [Double] = []
        out.reserveCapacity(values.count - 1)
        for i in 1..<values.count {
            let prev = values[i - 1]
            guard prev > 0 else { continue }
            out.append(values[i] / prev - 1)
        }
        return out
    }

    /// Cumulative (time-weighted) return: last / first − 1. Degenerate input → 0.
    public static func totalReturn(_ values: [Double]) -> Double {
        guard values.count > 1, let first = values.first, let last = values.last, first > 0
        else { return 0 }
        return last / first - 1
    }

    /// Compound annual growth rate over the series' span. Degenerate input → 0.
    public static func annualizedReturn(_ values: [Double], periodsPerYear: Double = 252) -> Double {
        let periods = Double(values.count - 1)
        guard periods > 0, let first = values.first, first > 0 else { return 0 }
        return pow(1 + totalReturn(values), periodsPerYear / periods) - 1
    }

    /// Sample standard deviation of `returns`, annualized by √periodsPerYear. <2 → 0.
    public static func annualizedVolatility(_ returns: [Double], periodsPerYear: Double = 252) -> Double {
        guard returns.count > 1 else { return 0 }
        let mean = returns.reduce(0, +) / Double(returns.count)
        let variance = returns.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(returns.count - 1)
        return sqrt(variance) * sqrt(periodsPerYear)
    }

    /// Worst peak-to-trough decline as a negative fraction (−0.25 = −25%). No decline → 0.
    public static func maxDrawdown(_ values: [Double]) -> Double {
        guard values.count > 1 else { return 0 }
        var peak = values[0]
        var worst = 0.0
        for v in values {
            if v > peak { peak = v }
            guard peak > 0 else { continue }
            let dd = v / peak - 1
            if dd < worst { worst = dd }
        }
        return worst
    }

    /// Risk-adjusted excess return. `nil` when volatility is zero (undefined).
    public static func sharpe(annualizedReturn: Double, annualizedVolatility: Double,
                              riskFree: Double) -> Double? {
        guard annualizedVolatility > 0 else { return nil }
        return (annualizedReturn - riskFree) / annualizedVolatility
    }

    /// Slope of portfolio returns regressed on benchmark returns (cov / var). `nil` when
    /// lengths differ, the series is too short, or the benchmark has zero variance.
    public static func beta(portfolioReturns p: [Double], benchmarkReturns b: [Double]) -> Double? {
        guard p.count == b.count, p.count > 1 else { return nil }
        let n = Double(p.count)
        let mp = p.reduce(0, +) / n
        let mb = b.reduce(0, +) / n
        var cov = 0.0, varb = 0.0
        for i in p.indices {
            cov += (p[i] - mp) * (b[i] - mb)
            varb += (b[i] - mb) * (b[i] - mb)
        }
        guard varb > 0 else { return nil }
        return cov / varb
    }

    /// CAPM alpha: actual annualized return minus what beta predicts from the benchmark.
    public static func alpha(annualizedReturn: Double, beta: Double,
                             benchmarkAnnualizedReturn: Double, riskFree: Double) -> Double {
        annualizedReturn - (riskFree + beta * (benchmarkAnnualizedReturn - riskFree))
    }
}

/// The full set of computed statistics for one window. `beta`/`alpha` are nil when no
/// benchmark was available.
public struct PerformanceMetrics: Equatable, Sendable {
    public let totalReturn: Double
    public let annualizedReturn: Double
    public let volatility: Double
    public let maxDrawdown: Double
    public let sharpe: Double?
    public let beta: Double?
    public let alpha: Double?

    public init(totalReturn: Double, annualizedReturn: Double, volatility: Double,
                maxDrawdown: Double, sharpe: Double?, beta: Double?, alpha: Double?) {
        self.totalReturn = totalReturn
        self.annualizedReturn = annualizedReturn
        self.volatility = volatility
        self.maxDrawdown = maxDrawdown
        self.sharpe = sharpe
        self.beta = beta
        self.alpha = alpha
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter RiskMetricsTests`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeDomain/RiskMetrics.swift Tests/APTradeDomainTests/RiskMetricsTests.swift
git commit -m "feat(domain): RiskMetrics — returns, volatility, drawdown, Sharpe, beta, alpha"
```

---

### Task 2: Diversification (domain)

Pure concentration analytics + warning types.

**Files:**
- Create: `Sources/APTradeDomain/Diversification.swift`
- Test: `Tests/APTradeDomainTests/DiversificationTests.swift`

**Interfaces:**
- Consumes: nothing (pure).
- Produces:
  - `struct HoldingWeight: Equatable, Sendable { label: String; kind: String; weight: Double }`
  - `enum ConcentrationWarning: Equatable, Sendable { case singleName(label: String, weight: Double); case assetClass(kind: String, weight: Double) }`
  - `enum Diversification` with statics: `concentration([Double]) -> Double`, `effectiveHoldings([Double]) -> Double`, `warnings([HoldingWeight], singleNameThreshold: Double = 0.25, assetClassThreshold: Double = 0.60) -> [ConcentrationWarning]`

- [ ] **Step 1: Write the failing test**

Create `Tests/APTradeDomainTests/DiversificationTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class DiversificationTests: XCTestCase {
    func test_concentration_hhi() {
        // Two equal holdings: 0.5² + 0.5² = 0.5
        XCTAssertEqual(Diversification.concentration([0.5, 0.5]), 0.5, accuracy: 1e-9)
        // Single holding: 1.0
        XCTAssertEqual(Diversification.concentration([1.0]), 1.0, accuracy: 1e-9)
        XCTAssertEqual(Diversification.concentration([]), 0, accuracy: 1e-9)
    }

    func test_effectiveHoldings_isInverseHHI() {
        XCTAssertEqual(Diversification.effectiveHoldings([0.5, 0.5]), 2.0, accuracy: 1e-9)
        XCTAssertEqual(Diversification.effectiveHoldings([]), 0, accuracy: 1e-9)
    }

    func test_warnings_flagsSingleNameOver25Percent() {
        // Binary-exact weights (multiples of 0.5/0.125) so summed class weights carry no
        // float drift; each class here is 0.5, below the 0.60 class threshold.
        let holdings = [
            HoldingWeight(label: "NVDA", kind: "stock", weight: 0.5),
            HoldingWeight(label: "BTC-USD", kind: "crypto", weight: 0.5)
        ]
        let warnings = Diversification.warnings(holdings)
        // Both exceed the 0.25 single-name threshold; neither class exceeds 0.60.
        XCTAssertTrue(warnings.contains(.singleName(label: "NVDA", weight: 0.5)))
        XCTAssertTrue(warnings.contains(.singleName(label: "BTC-USD", weight: 0.5)))
        XCTAssertFalse(warnings.contains { if case .assetClass = $0 { return true } else { return false } })
    }

    func test_warnings_flagsAssetClassDominance() {
        // Binary-exact weights: stock sums to 0.625 (> 0.60); every single weight is ≤ 0.25
        // (0.25 is not strictly > 0.25), so only the class warning fires.
        let holdings = [
            HoldingWeight(label: "AAPL", kind: "stock", weight: 0.25),
            HoldingWeight(label: "MSFT", kind: "stock", weight: 0.25),
            HoldingWeight(label: "GOOG", kind: "stock", weight: 0.125),
            HoldingWeight(label: "BTC-USD", kind: "crypto", weight: 0.25),
            HoldingWeight(label: "ETH-USD", kind: "crypto", weight: 0.125)
        ]
        let warnings = Diversification.warnings(holdings)
        // stock class = 0.625 > 0.60 → flagged; crypto = 0.375; no single name > 0.25.
        XCTAssertTrue(warnings.contains(.assetClass(kind: "stock", weight: 0.625)))
        XCTAssertFalse(warnings.contains { if case .singleName = $0 { return true } else { return false } })
    }

    func test_warnings_sortedByDescendingWeight() {
        let holdings = [
            HoldingWeight(label: "A", kind: "stock", weight: 0.30),
            HoldingWeight(label: "B", kind: "crypto", weight: 0.70)
        ]
        let warnings = Diversification.warnings(holdings)
        // Assert the emitted warnings are ordered by non-increasing weight without
        // depending on which warning type wins a weight tie (a dominant single name and
        // its sole-member class share the same weight).
        let weights = warnings.map { warning -> Double in
            switch warning {
            case .singleName(_, let w): return w
            case .assetClass(_, let w): return w
            }
        }
        XCTAssertFalse(weights.isEmpty)
        XCTAssertEqual(weights, weights.sorted(by: >))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DiversificationTests`
Expected: FAIL — `cannot find 'Diversification' in scope`.

- [ ] **Step 3: Write minimal implementation**

Create `Sources/APTradeDomain/Diversification.swift`:

```swift
import Foundation

/// One holding's share of total holdings value, for concentration analysis.
public struct HoldingWeight: Equatable, Sendable {
    public let label: String   // display name or symbol
    public let kind: String    // asset-class label (e.g. "stock", "crypto")
    public let weight: Double  // 0...1 fraction of total holdings value
    public init(label: String, kind: String, weight: Double) {
        self.label = label
        self.kind = kind
        self.weight = weight
    }
}

/// A flagged concentration risk.
public enum ConcentrationWarning: Equatable, Sendable {
    case singleName(label: String, weight: Double)
    case assetClass(kind: String, weight: Double)
}

/// Pure portfolio-concentration analytics.
public enum Diversification {
    /// Herfindahl-Hirschman index: Σ wᵢ². 1.0 = everything in one name; → 0 as spread out.
    public static func concentration(_ weights: [Double]) -> Double {
        weights.reduce(0) { $0 + $1 * $1 }
    }

    /// Effective number of equally-weighted holdings = 1 / HHI. Empty → 0.
    public static func effectiveHoldings(_ weights: [Double]) -> Double {
        let hhi = concentration(weights)
        return hhi > 0 ? 1 / hhi : 0
    }

    /// Warnings for single holdings above `singleNameThreshold` (default 25%) and asset
    /// classes above `assetClassThreshold` (default 60%). Sorted by descending weight.
    public static func warnings(_ holdings: [HoldingWeight],
                                singleNameThreshold: Double = 0.25,
                                assetClassThreshold: Double = 0.60) -> [ConcentrationWarning] {
        var result: [ConcentrationWarning] = []
        for h in holdings where h.weight > singleNameThreshold {
            result.append(.singleName(label: h.label, weight: h.weight))
        }
        var byClass: [String: Double] = [:]
        for h in holdings { byClass[h.kind, default: 0] += h.weight }
        for (kind, w) in byClass where w > assetClassThreshold {
            result.append(.assetClass(kind: kind, weight: w))
        }
        return result.sorted { weight(of: $0) > weight(of: $1) }
    }

    private static func weight(of warning: ConcentrationWarning) -> Double {
        switch warning {
        case .singleName(_, let w): return w
        case .assetClass(_, let w): return w
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter DiversificationTests`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeDomain/Diversification.swift Tests/APTradeDomainTests/DiversificationTests.swift
git commit -m "feat(domain): Diversification — HHI concentration + warnings"
```

---

### Task 3: Trade-aware equity curve (domain)

`EquityPoint` + `Portfolio.equitySeries(histories:)` reconstructing true account value by replaying the transaction log.

**Files:**
- Create: `Sources/APTradeDomain/PortfolioEquityCurve.swift`
- Test: `Tests/APTradeDomainTests/PortfolioEquityCurveTests.swift`

**Interfaces:**
- Consumes: `Portfolio`, `Position`, `Transaction`, `PricePoint`, `Money` (existing domain types).
- Produces:
  - `struct EquityPoint: Equatable, Sendable { date: Date; value: Money }`
  - `Portfolio.equitySeries(histories: [String: [PricePoint]]) -> [EquityPoint]`

- [ ] **Step 1: Write the failing test**

Create `Tests/APTradeDomainTests/PortfolioEquityCurveTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class PortfolioEquityCurveTests: XCTestCase {
    private func day(_ n: Int) -> Date { Date(timeIntervalSince1970: TimeInterval(n) * 86_400) }

    func test_equitySeries_emptyHistories_returnsEmpty() {
        let p = Portfolio.starting()
        XCTAssertTrue(p.equitySeries(histories: [:]).isEmpty)
    }

    func test_equitySeries_valuesHoldingsAndCashOverTime() {
        // Start $100k cash, buy 10 AAPL @ $100 on day 1 (cost $1,000 → cash $99,000).
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let p = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100), date: day(1))
        let histories = [
            "AAPL": [
                PricePoint(date: day(1), close: Money(amount: 100)),
                PricePoint(date: day(2), close: Money(amount: 110))
            ]
        ]
        let series = p.equitySeries(histories: histories)
        XCTAssertEqual(series.count, 2)
        // Day 1: cash 99,000 + 10*100 = 100,000.
        XCTAssertEqual(series[0].value, Money(amount: 100_000))
        // Day 2: cash 99,000 + 10*110 = 100,100.
        XCTAssertEqual(series[1].value, Money(amount: 100_100))
    }

    func test_equitySeries_preTradeDate_isAllCash() {
        // History includes a day *before* the buy → that day should be pure starting cash.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let p = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100), date: day(2))
        let histories = [
            "AAPL": [
                PricePoint(date: day(1), close: Money(amount: 90)),
                PricePoint(date: day(2), close: Money(amount: 100))
            ]
        ]
        let series = p.equitySeries(histories: histories)
        XCTAssertEqual(series.count, 2)
        // Day 1 (before the buy): cash reverses the later $1,000 buy → 100,000, no holdings.
        XCTAssertEqual(series[0].value, Money(amount: 100_000))
        // Day 2: cash 99,000 + 10*100 = 100,000.
        XCTAssertEqual(series[1].value, Money(amount: 100_000))
    }

    func test_equitySeries_forwardFillsMissingCloses() {
        // AAPL has no bar on day 2; should forward-fill day-1 close.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)
        var p = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(1)), at: Money(amount: 100), date: day(1))
        p = try! p.buying(btc, quantity: Quantity(Decimal(1)), at: Money(amount: 200), date: day(1))
        let histories = [
            "AAPL": [PricePoint(date: day(1), close: Money(amount: 100))],
            "BTC-USD": [
                PricePoint(date: day(1), close: Money(amount: 200)),
                PricePoint(date: day(2), close: Money(amount: 250))
            ]
        ]
        let series = p.equitySeries(histories: histories)
        XCTAssertEqual(series.count, 2)
        // Day 2: cash = 100,000 - 100 - 200 = 99,700; AAPL forward-filled 100 + BTC 250 = 350.
        XCTAssertEqual(series[1].value, Money(amount: 100_050))  // 99,700 + 350
    }
}
```

> Note: confirm `Portfolio.buying` accepts a `date:` argument (it does — see `Sources/APTradeDomain/Portfolio.swift:43`). `Portfolio.starting()` is the existing factory used elsewhere in tests.

- [ ] **Step 2: Run test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PortfolioEquityCurveTests`
Expected: FAIL — `value of type 'Portfolio' has no member 'equitySeries'`.

- [ ] **Step 3: Write minimal implementation**

Create `Sources/APTradeDomain/PortfolioEquityCurve.swift`:

```swift
import Foundation

/// One point on the reconstructed account-value curve: the true total account value
/// (cash + holdings) at a past date, derived by replaying the transaction log.
public struct EquityPoint: Equatable, Sendable {
    public let date: Date
    public let value: Money
    public init(date: Date, value: Money) {
        self.date = date
        self.value = value
    }
}

public extension Portfolio {
    /// Reconstructs the *true* historical account value by replaying the transaction log.
    /// For each date in the union of supplied histories: computes the holdings and cash the
    /// account actually had at that date, and values holdings against each symbol's
    /// forward-filled close. Anchored on the current `cash` (no external deposits exist) —
    /// cash at a past date adds back the net cash of trades that happened *after* it. Pure.
    func equitySeries(histories: [String: [PricePoint]]) -> [EquityPoint] {
        let code = cash.currencyCode
        let allDates = Set(histories.values.flatMap { $0.map(\.date) }).sorted()
        guard !allDates.isEmpty else { return [] }

        let sortedTxns = transactions.sorted { $0.date < $1.date }
        let sorted = histories.mapValues { $0.sorted { $0.date < $1.date } }

        var result: [EquityPoint] = []
        result.reserveCapacity(allDates.count)

        for date in allDates {
            // Holdings as of `date`: net buys/sells up to and including it.
            var qty: [String: Decimal] = [:]
            for t in sortedTxns where t.date <= date {
                qty[t.symbol, default: 0] += (t.side == .buy ? t.quantity.amount : -t.quantity.amount)
            }
            // Cash as of `date`: current cash with later trade cashflows reversed.
            var cashAt = cash.amount
            for t in sortedTxns where t.date > date {
                let flow = t.price.amount * t.quantity.amount
                cashAt += (t.side == .buy ? flow : -flow)   // undo a later buy (+) / sell (−)
            }

            let hasHoldings = qty.contains { $0.value != 0 }
            var holdings = Decimal(0)
            if hasHoldings {
                var pricedAny = false
                for (symbol, q) in qty where q != 0 {
                    guard let points = sorted[symbol],
                          let close = lastClose(in: points, onOrBefore: date) else { continue }
                    holdings += close * q
                    pricedAny = true
                }
                guard pricedAny else { continue }   // holdings exist but no price yet → skip
            }
            result.append(EquityPoint(date: date,
                                      value: Money(amount: cashAt + holdings, currencyCode: code)))
        }
        return result
    }

    private func lastClose(in points: [PricePoint], onOrBefore date: Date) -> Decimal? {
        var last: Decimal?
        for p in points {
            if p.date <= date { last = p.close.amount } else { break }
        }
        return last
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PortfolioEquityCurveTests`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeDomain/PortfolioEquityCurve.swift Tests/APTradeDomainTests/PortfolioEquityCurveTests.swift
git commit -m "feat(domain): trade-aware equity curve from the transaction log"
```

---

### Task 4: ComputePerformanceMetricsUseCase (application)

Orchestrates portfolio load + parallel history fetch (holdings + benchmark) + domain math into a `PerformanceReport`.

**Files:**
- Create: `Sources/APTradeApplication/PerformanceUseCases.swift`
- Test: `Tests/APTradeApplicationTests/PerformanceUseCasesTests.swift`

**Interfaces:**
- Consumes: `MarketDataRepository`, `PortfolioStore` (existing ports); `RiskMetrics`, `PerformanceMetrics`, `Diversification`, `HoldingWeight`, `ConcentrationWarning`, `EquityPoint`, `Portfolio.equitySeries` (Tasks 1–3).
- Produces:
  - `struct PerformanceReport: Equatable, Sendable { metrics: PerformanceMetrics; equityCurve: [EquityPoint]; benchmarkCurve: [PricePoint]; benchmarkSymbol: String; concentration: Double; effectiveHoldings: Double; warnings: [ConcentrationWarning]; isEmpty: Bool }` + `static let empty`
  - `struct ComputePerformanceMetricsUseCase: Sendable { init(repository:store:riskFreeRate: Double = 0.04); func callAsFunction(timeframe: Timeframe, benchmark: String) async -> PerformanceReport }`

- [ ] **Step 1: Write the failing test**

Create `Tests/APTradeApplicationTests/PerformanceUseCasesTests.swift`:

```swift
import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

/// Returns a rising history for any symbol; a flag lets the benchmark fetch "fail" (empty).
private final class RisingRepo: MarketDataRepository, @unchecked Sendable {
    let benchmarkFails: Bool
    init(benchmarkFails: Bool = false) { self.benchmarkFails = benchmarkFails }
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 110), previousClose: Money(amount: 100))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        if benchmarkFails && symbol == "SPY" { return [] }
        return (0..<10).map { i in
            PricePoint(date: Date(timeIntervalSince1970: TimeInterval(i) * 86_400),
                       close: Money(amount: Decimal(100 + i)))   // 100,101,...,109
        }
    }
}

final class PerformanceUseCasesTests: XCTestCase {
    private func portfolioWithAAPL() -> Portfolio {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        return try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100),
                    on: Date(timeIntervalSince1970: 0))
    }

    func test_emptyPortfolio_returnsEmptyReport() async {
        let useCase = ComputePerformanceMetricsUseCase(repository: RisingRepo(), store: MemoryStore(.starting()))
        let report = await useCase(timeframe: .oneYear, benchmark: "SPY")
        XCTAssertTrue(report.isEmpty)
    }

    func test_computesMetricsAndBenchmark() async {
        let useCase = ComputePerformanceMetricsUseCase(repository: RisingRepo(), store: MemoryStore(portfolioWithAAPL()))
        let report = await useCase(timeframe: .oneYear, benchmark: "SPY")
        XCTAssertFalse(report.isEmpty)
        XCTAssertGreaterThan(report.equityCurve.count, 1)
        XCTAssertGreaterThan(report.metrics.totalReturn, 0)          // rising prices
        XCTAssertNotNil(report.metrics.beta)                          // benchmark available
        XCTAssertEqual(report.benchmarkSymbol, "SPY")
        XCTAssertFalse(report.benchmarkCurve.isEmpty)
        XCTAssertEqual(report.concentration, 1.0, accuracy: 1e-9)     // single holding
    }

    func test_benchmarkFailure_stillReturnsPortfolioMetrics() async {
        let useCase = ComputePerformanceMetricsUseCase(repository: RisingRepo(benchmarkFails: true), store: MemoryStore(portfolioWithAAPL()))
        let report = await useCase(timeframe: .oneYear, benchmark: "SPY")
        XCTAssertFalse(report.isEmpty)
        XCTAssertGreaterThan(report.metrics.totalReturn, 0)
        XCTAssertNil(report.metrics.beta)            // no benchmark → no beta
        XCTAssertNil(report.metrics.alpha)
        XCTAssertTrue(report.benchmarkCurve.isEmpty)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PerformanceUseCasesTests`
Expected: FAIL — `cannot find 'ComputePerformanceMetricsUseCase' in scope`.

- [ ] **Step 3: Write minimal implementation**

Create `Sources/APTradeApplication/PerformanceUseCases.swift`:

```swift
import Foundation
import APTradeDomain

/// Everything the Performance section renders for one (timeframe, benchmark) selection.
public struct PerformanceReport: Equatable, Sendable {
    public let metrics: PerformanceMetrics
    public let equityCurve: [EquityPoint]
    public let benchmarkCurve: [PricePoint]   // empty when the benchmark is unavailable
    public let benchmarkSymbol: String
    public let concentration: Double
    public let effectiveHoldings: Double
    public let warnings: [ConcentrationWarning]
    public let isEmpty: Bool

    public init(metrics: PerformanceMetrics, equityCurve: [EquityPoint],
                benchmarkCurve: [PricePoint], benchmarkSymbol: String,
                concentration: Double, effectiveHoldings: Double,
                warnings: [ConcentrationWarning], isEmpty: Bool) {
        self.metrics = metrics
        self.equityCurve = equityCurve
        self.benchmarkCurve = benchmarkCurve
        self.benchmarkSymbol = benchmarkSymbol
        self.concentration = concentration
        self.effectiveHoldings = effectiveHoldings
        self.warnings = warnings
        self.isEmpty = isEmpty
    }

    public static let empty = PerformanceReport(
        metrics: PerformanceMetrics(totalReturn: 0, annualizedReturn: 0, volatility: 0,
                                    maxDrawdown: 0, sharpe: nil, beta: nil, alpha: nil),
        equityCurve: [], benchmarkCurve: [], benchmarkSymbol: "",
        concentration: 0, effectiveHoldings: 0, warnings: [], isEmpty: true)
}

/// Loads the portfolio, fetches holding + benchmark histories in parallel, builds the
/// trade-aware equity curve, and computes returns / risk / benchmark / concentration.
public struct ComputePerformanceMetricsUseCase: Sendable {
    private let repository: MarketDataRepository
    private let store: PortfolioStore
    private let riskFreeRate: Double

    public init(repository: MarketDataRepository, store: PortfolioStore,
                riskFreeRate: Double = 0.04) {
        self.repository = repository
        self.store = store
        self.riskFreeRate = riskFreeRate
    }

    public func callAsFunction(timeframe: Timeframe, benchmark: String) async -> PerformanceReport {
        let portfolio = store.load()
        guard !portfolio.positions.isEmpty else { return .empty }

        // Holding histories, in parallel.
        var histories: [String: [PricePoint]] = [:]
        await withTaskGroup(of: (String, [PricePoint]).self) { group in
            for position in portfolio.positions {
                let symbol = position.asset.symbol
                let repository = repository
                group.addTask {
                    let points = (try? await repository.history(for: symbol, timeframe: timeframe)) ?? []
                    return (symbol, points)
                }
            }
            for await (symbol, points) in group { histories[symbol] = points }
        }

        let equity = portfolio.equitySeries(histories: histories)
        guard equity.count > 1 else { return .empty }

        let values = equity.map { ($0.value.amount as NSDecimalNumber).doubleValue }
        let returns = RiskMetrics.dailyReturns(values)
        let annual = RiskMetrics.annualizedReturn(values)
        let vol = RiskMetrics.annualizedVolatility(returns)

        // Benchmark (optional — failure must not sink the report).
        let benchmarkCurve = (try? await repository.history(for: benchmark, timeframe: timeframe)) ?? []
        var beta: Double?
        var alpha: Double?
        if benchmarkCurve.count > 1 {
            let bValues = benchmarkCurve.map { ($0.close.amount as NSDecimalNumber).doubleValue }
            let bReturns = RiskMetrics.dailyReturns(bValues)
            let n = min(returns.count, bReturns.count)
            if n > 1, let b = RiskMetrics.beta(portfolioReturns: Array(returns.suffix(n)),
                                               benchmarkReturns: Array(bReturns.suffix(n))) {
                beta = b
                alpha = RiskMetrics.alpha(annualizedReturn: annual, beta: b,
                                          benchmarkAnnualizedReturn: RiskMetrics.annualizedReturn(bValues),
                                          riskFree: riskFreeRate)
            }
        }

        let metrics = PerformanceMetrics(
            totalReturn: RiskMetrics.totalReturn(values),
            annualizedReturn: annual,
            volatility: vol,
            maxDrawdown: RiskMetrics.maxDrawdown(values),
            sharpe: RiskMetrics.sharpe(annualizedReturn: annual, annualizedVolatility: vol, riskFree: riskFreeRate),
            beta: beta, alpha: alpha)

        let weights = currentWeights(portfolio: portfolio, histories: histories)
        let rawWeights = weights.map(\.weight)

        return PerformanceReport(
            metrics: metrics,
            equityCurve: equity,
            benchmarkCurve: benchmarkCurve.count > 1 ? benchmarkCurve : [],
            benchmarkSymbol: benchmark,
            concentration: Diversification.concentration(rawWeights),
            effectiveHoldings: Diversification.effectiveHoldings(rawWeights),
            warnings: Diversification.warnings(weights),
            isEmpty: false)
    }

    /// Current portfolio weights from each symbol's latest available close (cost-basis
    /// fallback). Used for concentration analysis.
    private func currentWeights(portfolio: Portfolio,
                                histories: [String: [PricePoint]]) -> [HoldingWeight] {
        var rows: [(label: String, kind: String, value: Double)] = []
        for position in portfolio.positions {
            let q = (position.quantity.amount as NSDecimalNumber).doubleValue
            let price = histories[position.asset.symbol]?.last?.close.amount ?? position.averageCost.amount
            rows.append((position.asset.name, position.asset.kind.rawValue,
                         (price as NSDecimalNumber).doubleValue * q))
        }
        let total = rows.reduce(0) { $0 + $1.value }
        guard total > 0 else { return [] }
        return rows.map { HoldingWeight(label: $0.label, kind: $0.kind, weight: $0.value / total) }
    }
}
```

> Note: `Asset.kind` is an `AssetKind` with a `rawValue` (used the same way in `PortfolioView`'s allocation colors). Confirm the property name during implementation; if `Asset` exposes `kind.rawValue` it works as written.

- [ ] **Step 4: Run test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PerformanceUseCasesTests`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeApplication/PerformanceUseCases.swift Tests/APTradeApplicationTests/PerformanceUseCasesTests.swift
git commit -m "feat(application): ComputePerformanceMetricsUseCase + PerformanceReport"
```

---

### Task 5: PerformanceViewModel (presentation)

A dedicated `@Observable @MainActor` view model exposing a simple state machine.

**Files:**
- Create: `Sources/APTradeApp/PerformanceViewModel.swift`
- Test: `Tests/APTradeAppTests/PerformanceViewModelTests.swift`

**Interfaces:**
- Consumes: `ComputePerformanceMetricsUseCase`, `PerformanceReport` (Task 4); `Timeframe` (domain).
- Produces:
  - `final class PerformanceViewModel` (`@MainActor @Observable`) with: `enum State: Equatable { case idle, loading, loaded(PerformanceReport), empty }`, `private(set) var state`, `var timeframe: Timeframe`, `var benchmark: String`, `let benchmarks: [String]`, `init(compute:)`, `func onAppear() async`, `func load() async`.

- [ ] **Step 1: Write the failing test**

Create `Tests/APTradeAppTests/PerformanceViewModelTests.swift`:

```swift
import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

private final class RisingRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 110), previousClose: Money(amount: 100))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        (0..<10).map { i in
            PricePoint(date: Date(timeIntervalSince1970: TimeInterval(i) * 86_400),
                       close: Money(amount: Decimal(100 + i)))
        }
    }
}

@MainActor
final class PerformanceViewModelTests: XCTestCase {
    private func vm(_ portfolio: Portfolio) -> PerformanceViewModel {
        PerformanceViewModel(compute: ComputePerformanceMetricsUseCase(
            repository: RisingRepo(), store: MemoryStore(portfolio)))
    }

    func test_load_withHoldings_entersLoaded() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let portfolio = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100),
                    on: Date(timeIntervalSince1970: 0))
        let model = vm(portfolio)
        await model.load()
        guard case .loaded(let report) = model.state else { return XCTFail("expected .loaded") }
        XCTAssertFalse(report.isEmpty)
    }

    func test_load_emptyPortfolio_entersEmpty() async {
        let model = vm(.starting())
        await model.load()
        XCTAssertEqual(model.state, .empty)
    }

    func test_defaultBenchmarkIsSPY() {
        XCTAssertEqual(vm(.starting()).benchmark, "SPY")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PerformanceViewModelTests`
Expected: FAIL — `cannot find 'PerformanceViewModel' in scope`.

- [ ] **Step 3: Write minimal implementation**

Create `Sources/APTradeApp/PerformanceViewModel.swift`:

```swift
import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class PerformanceViewModel {
    enum State: Equatable {
        case idle
        case loading
        case loaded(PerformanceReport)
        case empty
    }

    private(set) var state: State = .idle
    var timeframe: Timeframe = .oneYear { didSet { if oldValue != timeframe { reload() } } }
    var benchmark: String = "SPY" { didSet { if oldValue != benchmark { reload() } } }

    let benchmarks = ["SPY", "QQQ", "VTI"]

    private let compute: ComputePerformanceMetricsUseCase

    init(compute: ComputePerformanceMetricsUseCase) { self.compute = compute }

    /// Loads once on first appearance; no-op if already loaded/loading.
    func onAppear() async {
        if case .idle = state { await load() }
    }

    /// Recomputes the report for the current timeframe/benchmark selection.
    func load() async {
        state = .loading
        let report = await compute(timeframe: timeframe, benchmark: benchmark)
        state = report.isEmpty ? .empty : .loaded(report)
    }

    private func reload() { Task { await load() } }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PerformanceViewModelTests`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeApp/PerformanceViewModel.swift Tests/APTradeAppTests/PerformanceViewModelTests.swift
git commit -m "feat(presentation): PerformanceViewModel state machine"
```

---

### Task 6: Wire into Composition Root + Portfolio tab UI

Add the DI factory, the new `PerformanceSection` view, and the `.performance` picker segment. This task is verified by a clean build plus a manual UI checklist (SwiftUI views aren't unit-tested here).

**Files:**
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (add `makePerformanceViewModel()`)
- Create: `Sources/APTradeApp/PerformanceSection.swift`
- Modify: `Sources/APTradeApp/PortfolioView.swift` (add `.performance` case to `Section`; own a `PerformanceViewModel`; render the section)

**Interfaces:**
- Consumes: `PerformanceViewModel` (Task 5), `ComputePerformanceMetricsUseCase` (Task 4), existing `CompositionRoot.makeRepository()` and `CompositionRoot.portfolioStore`, `Theme`, Swift Charts.
- Produces: `CompositionRoot.makePerformanceViewModel() -> PerformanceViewModel`; `PerformanceSection` view.

- [ ] **Step 1: Add the DI factory**

In `Sources/APTradeApp/CompositionRoot.swift`, add alongside the other `make...ViewModel` factories:

```swift
static func makePerformanceViewModel() -> PerformanceViewModel {
    PerformanceViewModel(
        compute: ComputePerformanceMetricsUseCase(repository: makeRepository(),
                                                  store: portfolioStore))
}
```

- [ ] **Step 2: Create the PerformanceSection view**

Create `Sources/APTradeApp/PerformanceSection.swift`:

```swift
import SwiftUI
import Charts
import APTradeApplication
import APTradeDomain

/// The Portfolio tab's analytics surface: return/risk metric cards, a normalized
/// benchmark-overlay chart, and concentration warnings. All state lives in the view model.
struct PerformanceSection: View {
    @Bindable var viewModel: PerformanceViewModel

    var body: some View {
        Group {
            switch viewModel.state {
            case .idle, .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .empty:
                emptyState
            case .loaded(let report):
                loaded(report)
            }
        }
        .task { await viewModel.onAppear() }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "chart.line.uptrend.xyaxis")
                .font(.system(size: 34)).foregroundStyle(Theme.textSecondary)
            Text("Not enough history yet")
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textPrimary)
            Text("Add holdings to see performance analytics.")
                .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func loaded(_ report: PerformanceReport) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                metricGrid(report.metrics)
                benchmarkPicker
                if !report.benchmarkCurve.isEmpty {
                    overlayChart(report)
                } else {
                    Text("Benchmark unavailable")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                }
                diversification(report)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
    }

    // MARK: Metric cards

    private func metricGrid(_ m: PerformanceMetrics) -> some View {
        let columns = [GridItem(.adaptive(minimum: 150), spacing: 12)]
        return LazyVGrid(columns: columns, spacing: 12) {
            MetricCard(title: "Total Return", value: percent(m.totalReturn), positive: m.totalReturn >= 0)
            MetricCard(title: "Annualized", value: percent(m.annualizedReturn), positive: m.annualizedReturn >= 0)
            MetricCard(title: "Volatility", value: percent(m.volatility), positive: nil)
            MetricCard(title: "Max Drawdown", value: percent(m.maxDrawdown), positive: false)
            MetricCard(title: "Sharpe", value: ratio(m.sharpe), positive: (m.sharpe ?? 0) >= 0)
            MetricCard(title: "Beta", value: ratio(m.beta), positive: nil)
            MetricCard(title: "Alpha", value: m.alpha.map { percent($0) } ?? "—", positive: (m.alpha ?? 0) >= 0)
        }
    }

    // MARK: Benchmark

    private var benchmarkPicker: some View {
        Picker("Benchmark", selection: $viewModel.benchmark) {
            ForEach(viewModel.benchmarks, id: \.self) { Text($0).tag($0) }
        }
        .pickerStyle(.segmented)
    }

    private func overlayChart(_ report: PerformanceReport) -> some View {
        let port = rebased(report.equityCurve.map { ($0.date, ($0.value.amount as NSDecimalNumber).doubleValue) })
        let bench = rebased(report.benchmarkCurve.map { ($0.date, ($0.close.amount as NSDecimalNumber).doubleValue) })
        return Chart {
            ForEach(port, id: \.0) { point in
                LineMark(x: .value("Date", point.0), y: .value("Portfolio", point.1),
                         series: .value("Series", "Portfolio"))
                    .foregroundStyle(Theme.gold)
            }
            ForEach(bench, id: \.0) { point in
                LineMark(x: .value("Date", point.0), y: .value(report.benchmarkSymbol, point.1),
                         series: .value("Series", report.benchmarkSymbol))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .frame(height: 200)
        .chartForegroundStyleScale(["Portfolio": Theme.gold, report.benchmarkSymbol: Theme.textSecondary])
    }

    // MARK: Concentration

    private func diversification(_ report: PerformanceReport) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Diversification")
                .font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.textSecondary)
            Text(String(format: "%.1f effective holdings", report.effectiveHoldings))
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textPrimary)
            ForEach(Array(report.warnings.enumerated()), id: \.offset) { _, warning in
                Label(warningText(warning), systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func warningText(_ w: ConcentrationWarning) -> String {
        switch w {
        case .singleName(let label, let weight):
            return "\(label) is \(percent(weight)) of holdings"
        case .assetClass(let kind, let weight):
            return "\(kind.capitalized) is \(percent(weight)) of holdings"
        }
    }

    // MARK: Formatting helpers

    private func percent(_ v: Double) -> String { String(format: "%.2f%%", v * 100) }
    private func ratio(_ v: Double?) -> String { v.map { String(format: "%.2f", $0) } ?? "—" }

    /// Rebase a value series to 100 at its first point for a like-for-like overlay.
    private func rebased(_ series: [(Date, Double)]) -> [(Date, Double)] {
        guard let base = series.first?.1, base > 0 else { return series }
        return series.map { ($0.0, $0.1 / base * 100) }
    }
}

/// A single labelled metric tile in the performance grid.
private struct MetricCard: View {
    let title: String
    let value: String
    /// nil = neutral (no color), true = positive (gold/green), false = negative (red).
    let positive: Bool?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 11, weight: .medium)).foregroundStyle(Theme.textSecondary)
            Text(value)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.hairline, lineWidth: 1))
    }

    private var color: Color {
        switch positive {
        case .some(true): return Theme.gold
        case .some(false): return .red
        case .none: return Theme.textPrimary
        }
    }
}
```

> Note: `Theme.surface`, `Theme.hairline`, `Theme.gold`, `Theme.textPrimary`, `Theme.textSecondary` are all used elsewhere in the app (e.g. `RootView`, `PortfolioView`). Confirm exact names against `Sources/APTradeApp/Theme.swift` during implementation and adjust if any differ.

- [ ] **Step 3: Add the `.performance` segment to PortfolioView**

In `Sources/APTradeApp/PortfolioView.swift`:

Extend the `Section` enum (the picker is `CaseIterable`, so this also adds the pill automatically):

```swift
enum Section: String, CaseIterable {
    case holdings = "Holdings", allocation = "Allocation", activity = "Activity", performance = "Performance"
}
```

Add a view-model property next to the existing `@State private var viewModel = ...`:

```swift
@State private var performanceVM = CompositionRoot.makePerformanceViewModel()
```

Add the case to the `content` switch:

```swift
switch section {
case .holdings: holdingsList
case .allocation: allocationView
case .activity: activityView
case .performance: PerformanceSection(viewModel: performanceVM)
}
```

- [ ] **Step 4: Build and run the test suite**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build`
Expected: builds with no errors.

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: the full suite passes (prior 117 tests + the new RiskMetrics/Diversification/EquityCurve/UseCase/ViewModel tests).

- [ ] **Step 5: Manual UI verification**

Launch the app (see `aptrade` skill for the run command). In the Portfolio tab with at least one holding:
- A **Performance** pill appears in the picker beside Holdings / Allocation / Activity.
- Selecting it shows the metric grid (Total Return, Annualized, Volatility, Max Drawdown, Sharpe, Beta, Alpha), the SPY/QQQ/VTI benchmark picker, the normalized overlay chart (portfolio gold vs. benchmark grey, both starting at 100), and a diversification line with any concentration warnings.
- Switching the benchmark re-renders the overlay.
- `PerformanceSection`'s own empty state ("Not enough history yet") is reachable when a position exists but its equity curve resolves to fewer than 2 points (e.g. a symbol whose history fetch returns insufficient data) — NOT via Reset, since an all-cash portfolio has no positions and is intercepted earlier by `PortfolioView.content`'s `viewModel.holdings.isEmpty` gate, which hides the section picker entirely rather than reaching `PerformanceSection`.

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeApp/CompositionRoot.swift Sources/APTradeApp/PerformanceSection.swift Sources/APTradeApp/PortfolioView.swift
git commit -m "feat(portfolio): Performance section — metrics, benchmark overlay, concentration"
```

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task |
|---|---|
| Total Return (TWR) + CAGR | Task 1 (`totalReturn`, `annualizedReturn`); shown in Task 6 grid |
| Volatility, Max Drawdown, Sharpe | Task 1; shown Task 6 |
| Benchmark overlay (normalized), Alpha/Beta, selectable SPY/QQQ/VTI | Task 1 (`beta`/`alpha`), Task 4 (benchmark fetch + alignment), Task 5 (`benchmarks`/`benchmark`), Task 6 (overlay + picker) |
| Concentration / diversification warnings | Task 2; shown Task 6 |
| Trade-aware equity curve | Task 3 |
| Use case orchestration, parallel fetch, no new port | Task 4 |
| `.performance` segment in existing picker | Task 6 |
| Dedicated PerformanceViewModel (no massive VM) | Task 5 |
| Error handling: no positions / <2 points → empty; benchmark fail → metrics still render | Task 4 (`.empty`, `benchmarkCurve` guard), Task 5 (`.empty`), Task 6 (empty state + "Benchmark unavailable") |
| Zero volatility → Sharpe "—" | Task 1 (`sharpe` returns nil), Task 6 (`ratio` renders "—") |
| Risk-free 4%, annualization 252 | Task 1 defaults, Task 4 default |
| Tests at each layer | Tasks 1–5 each ship tests |

No gaps.

**2. Placeholder scan:** No TBD/TODO/"handle edge cases" — every code step is complete. The three "Note:" callouts ask the implementer to confirm an existing symbol's exact name (`Asset.kind.rawValue`, `Portfolio.buying(date:)`, `Theme.*`) before relying on it — these are verification reminders, not missing content.

**3. Type consistency:** `PerformanceMetrics`, `PerformanceReport`, `EquityPoint`, `HoldingWeight`, `ConcentrationWarning`, and `ComputePerformanceMetricsUseCase.callAsFunction(timeframe:benchmark:)` signatures match across the tasks that define and consume them. `PerformanceReport` is `Equatable` (required by `PerformanceViewModel.State: Equatable`) — all its fields are `Equatable`. Benchmark default `"SPY"` consistent in Task 5 and tests.

## Out-of-scope (tracked in spec, not built here)

Deposit/withdraw cash (would make IRR real), live risk-free rate, per-position/sector risk contribution.
