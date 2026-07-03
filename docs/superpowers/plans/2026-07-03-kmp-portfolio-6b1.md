# KMP Increment 6b.1 — Shared Portfolio Core + Desktop Portfolio Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the macOS paper-trading portfolio domain into `:shared` commonMain (exact-decimal, framework-free) and build the desktop Portfolio tab to macOS parity: holdings, buy/sell, PnL, allocation, performance curve, activity, reset, CSV/JSON export.

**Architecture:** Domain transitions are pure `Portfolio` functions transcribed from the Swift originals; use cases orchestrate over the existing `MarketDataRepository` plus a new `PortfolioStore` port; desktop gets a JSON file store, two ViewModels (portfolio + trade dialog), and the tab UI. Swift and Android are untouched (adoption is 6b.3/6b.4).

**Tech Stack:** Kotlin 2.1.0, ionspin bignum (BigDecimal), kotlinx-serialization, Compose Multiplatform 1.7.3 (unchanged pins).

**Spec:** `docs/superpowers/specs/2026-07-03-kmp-portfolio-6b1-design.md`

## Global Constraints

- Before EVERY `./gradlew`: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`. Never system Gradle. Counts from JUnit XMLs with `--rerun-tasks`.
- Baselines: shared **51**, desktop **55**, android **13**, Swift **193**. Android and Swift sources untouched; `:shared` grows (so Task 7 re-proves xcframework + Swift + iOS).
- **Exact-decimal everywhere Swift uses Decimal** — money AND quantity are `BigDecimal`; `Double` only for allocation fractions (ratios) and chart pixel math. Money to UI only as `amountText` through designkit.
- **Division must pin a DecimalMode** — ionspin BigDecimal throws `ArithmeticException` on non-terminating division without one. Project rule (mirrors Swift `Decimal`'s 38-significant-digit plain rounding): `val MONEY_MATH = DecimalMode(38, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)`; every `/` in portfolio math uses `divide(other, MONEY_MATH)`.
- Semantics transcribed from Swift EXACTLY: validation order in `buying`/`selling`, average-cost merge `(oldAvg×oldQty + cost)/newQty`, realized delta `(price − avgCost)×qty`, valuation cost-basis fallback, dayChange derived `(price − previousClose)×qty` (Kotlin Quote has `previousClose`, not `change`), `realizedPnL` transaction-log replay, `performanceSeries` forward-fill.
- CancellationException rethrown before any domain error in every coroutine catch. Kotlin `Fetch*` use-case naming.
- Deliberate divergence (spec-approved): desktop export formats are CSV + JSON (macOS's PDF/XLSX/DOCX renderers are Apple-platform code; revisit later if wanted). The shared `PortfolioExport` snapshot is format-agnostic.
- Test money/price literals feeding `amountText` assertions must be zero-drop-immune (.25-style); values feeding only BigDecimal comparisons or Double math may be round.
- UI composition ships without unit tests (standing user waiver); ViewModels and domain carry the behavior.
- Commit after every task minimum.

## File Structure (new files)

```
shared/src/commonMain/kotlin/com/aptrade/shared/domain/
  Trade.kt            TradeSide, TradeError, Transaction
  Position.kt         Position
  Portfolio.kt        Portfolio, PortfolioValuation, MONEY_MATH, starting/buying/selling/valuation
  PortfolioAnalytics.kt  realizedPnL replay, AllocationSlice, allocationByHolding/ByKind
  PortfolioPerformance.kt PortfolioPerformancePoint, performanceSeries
  PortfolioExport.kt  PortfolioExport snapshot + renderCsv/renderJson
shared/src/commonMain/kotlin/com/aptrade/shared/application/
  PortfolioStore.kt   port
  FetchPortfolio.kt / BuyAsset.kt / SellAsset.kt / ResetPortfolio.kt / FetchPortfolioPerformance.kt
desktopApp/src/main/kotlin/com/aptrade/desktop/
  infra/FilePortfolioStore.kt
  portfolio/PortfolioViewModel.kt
  portfolio/TradeViewModel.kt
  portfolio/PortfolioPane.kt      (+ TradeDialog inside portfolio/TradeDialog.kt)
  infra/ExportSave.kt             (AWT FileDialog isolated here)
```

---

### Task 1: Shared domain — trade primitives + Portfolio buy/sell math

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Trade.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Position.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Portfolio.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PortfolioTest.kt`

**Interfaces:**
- Consumes: existing `Asset`, `AssetKind`, `Money`, `Quote` (`price`, `previousClose`, `changePercent`).
- Produces (Tasks 2–6 rely on these verbatim):
  - `enum class TradeSide { Buy, Sell }`
  - `sealed class TradeError(message: String) : Exception(message)` with `object InsufficientFunds`, `object InsufficientShares`, `object InvalidQuantity`
  - `data class Transaction(val id: String, val symbol: String, val side: TradeSide, val quantity: BigDecimal, val price: Money, val epochSeconds: Long)`
  - `data class Position(val asset: Asset, val quantity: BigDecimal, val averageCost: Money, val realizedPnL: Money)` with `fun marketValue(at: Money): Money`, `fun unrealizedPnL(at: Money): Money`
  - `data class PortfolioValuation(val cash: Money, val holdingsValue: Money, val totalValue: Money, val unrealizedPnL: Money, val dayChange: Money)`
  - `data class Portfolio(val cash: Money, val positions: List<Position> = emptyList(), val transactions: List<Transaction> = emptyList())` with `companion fun starting(): Portfolio` ($100,000), `fun positionFor(symbol: String): Position?`, `fun buying(asset, quantity, price, epochSeconds, id): Portfolio` (throws TradeError), `fun selling(symbol, quantity, price, epochSeconds, id): Portfolio` (throws TradeError), `fun valuation(quotes: Map<String, Quote>): PortfolioValuation`
  - `val MONEY_MATH: DecimalMode` (38 digits, ROUND_HALF_AWAY_FROM_ZERO)
  - `id` parameters take explicit strings so tests are deterministic; production callers pass `generateTradeId()` — `fun generateTradeId(): String` (time + random suffix, no platform UUID).

- [ ] **Step 1: Write the failing tests** (`PortfolioTest.kt`):

```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
private val btc = Asset("BTC-USD", "Bitcoin USD", AssetKind.Crypto)
private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)
private fun quote(symbol: String, price: String, prevClose: String) =
    Quote(symbol, Money.usd(price), Money.usd(prevClose), 0.0)

class PortfolioTest {

    @Test
    fun startingPortfolioHas100kCashAndNothingElse() {
        val p = Portfolio.starting()
        assertEquals(BigDecimal.parseString("100000"), p.cash.amount)
        assertTrue(p.positions.isEmpty())
        assertTrue(p.transactions.isEmpty())
    }

    @Test
    fun buyingOpensAPositionAtThePaidPrice() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), epochSeconds = 1000, id = "t1")
        val pos = p.positionFor("AAPL")!!
        assertEquals(qty("10"), pos.quantity)
        assertEquals(BigDecimal.parseString("300"), pos.averageCost.amount)
        assertEquals(BigDecimal.parseString("97000"), p.cash.amount)   // 100000 - 3000
        assertEquals(1, p.transactions.size)
        assertEquals(TradeSide.Buy, p.transactions[0].side)
        assertEquals("t1", p.transactions[0].id)
    }

    @Test
    fun buyingMoreAveragesTheCost() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .buying(aapl, qty("10"), Money.usd("400"), 2000, "t2")
        val pos = p.positionFor("AAPL")!!
        assertEquals(qty("20"), pos.quantity)
        assertEquals(BigDecimal.parseString("350"), pos.averageCost.amount) // (300*10+400*10)/20
    }

    @Test
    fun nonTerminatingAverageDoesNotThrow() {
        // avg = (100*1 + 100*2)/3 = 100, then (100*3 + 50*1)/4 ... build a true 1/3 case:
        // buy 1 @ 100, buy 2 @ 250 → avg = (100 + 500)/3 = 200 exactly; instead force 1/3:
        val p = Portfolio.starting()
            .buying(aapl, qty("3"), Money.usd("100"), 1000, "t1")       // avg 100
            .buying(aapl, qty("1"), Money.usd("1"), 2000, "t2")         // (300+1)/4 = 75.25
            .buying(aapl, qty("2"), Money.usd("1"), 3000, "t3")         // (301+2)/6 = 50.5
            .buying(aapl, qty("1"), Money.usd("2"), 4000, "t4")         // (303+2)/7 → non-terminating
        val avg = p.positionFor("AAPL")!!.averageCost.amount
        // 305/7 = 43.571428... — pinned by MONEY_MATH (38 digits, half-away). Assert the prefix.
        assertTrue(avg.toStringExpanded().startsWith("43.5714285714"))
    }

    @Test
    fun buyingZeroQuantityIsInvalid() {
        assertFailsWith<TradeError.InvalidQuantity> {
            Portfolio.starting().buying(aapl, qty("0"), Money.usd("300"), 1000, "t1")
        }
    }

    @Test
    fun buyingBeyondCashIsInsufficientFunds() {
        assertFailsWith<TradeError.InsufficientFunds> {
            Portfolio.starting().buying(aapl, qty("1000"), Money.usd("300"), 1000, "t1") // 300k > 100k
        }
    }

    @Test
    fun validationOrderChecksQuantityBeforeFunds() {
        // zero quantity AND absurd price: must throw InvalidQuantity (Swift checks quantity first)
        assertFailsWith<TradeError.InvalidQuantity> {
            Portfolio.starting().buying(aapl, qty("0"), Money.usd("999999999"), 1000, "t1")
        }
    }

    @Test
    fun sellingRealizesPnLAndFreesCash() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .selling("AAPL", qty("4"), Money.usd("350"), 2000, "t2")
        val pos = p.positionFor("AAPL")!!
        assertEquals(qty("6"), pos.quantity)
        assertEquals(BigDecimal.parseString("300"), pos.averageCost.amount)   // avg unchanged on sell
        assertEquals(BigDecimal.parseString("200"), pos.realizedPnL.amount)   // (350-300)*4
        assertEquals(BigDecimal.parseString("98400"), p.cash.amount)          // 97000 + 1400
        assertEquals(2, p.transactions.size)
    }

    @Test
    fun sellingWholePositionRemovesIt() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .selling("AAPL", qty("10"), Money.usd("310"), 2000, "t2")
        assertNull(p.positionFor("AAPL"))
        assertEquals(BigDecimal.parseString("100100"), p.cash.amount)  // +100 realized
    }

    @Test
    fun sellingMoreThanHeldIsInsufficientShares() {
        val p = Portfolio.starting().buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
        assertFailsWith<TradeError.InsufficientShares> {
            p.selling("AAPL", qty("11"), Money.usd("300"), 2000, "t2")
        }
    }

    @Test
    fun sellingUnknownSymbolIsInsufficientShares() {
        assertFailsWith<TradeError.InsufficientShares> {
            Portfolio.starting().selling("ZZZZ", qty("1"), Money.usd("1"), 1000, "t1")
        }
    }

    @Test
    fun fractionalCryptoQuantitiesStayExact() {
        val p = Portfolio.starting()
            .buying(btc, qty("0.05"), Money.usd("60000"), 1000, "t1")   // cost 3000
        assertEquals(qty("0.05"), p.positionFor("BTC-USD")!!.quantity)
        assertEquals(BigDecimal.parseString("97000"), p.cash.amount)
    }

    @Test
    fun valuationValuesAgainstQuotesWithCostBasisFallback() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .buying(btc, qty("0.1"), Money.usd("60000"), 2000, "t2")    // cost 6000
        val v = p.valuation(mapOf("AAPL" to quote("AAPL", "310", "305")))
        // AAPL priced: 310*10 = 3100; BTC missing → cost basis 60000*0.1 = 6000
        assertEquals(BigDecimal.parseString("9100"), v.holdingsValue.amount)
        assertEquals(BigDecimal.parseString("91000"), v.cash.amount)
        assertEquals(BigDecimal.parseString("100100"), v.totalValue.amount)
        assertEquals(BigDecimal.parseString("100"), v.unrealizedPnL.amount)   // (310-300)*10, BTC excluded
        assertEquals(BigDecimal.parseString("50"), v.dayChange.amount)        // (310-305)*10
    }

    @Test
    fun positionHelpersComputeMarketValueAndUnrealized() {
        val pos = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        assertEquals(BigDecimal.parseString("3100"), pos.marketValue(Money.usd("310")).amount)
        assertEquals(BigDecimal.parseString("100"), pos.unrealizedPnL(Money.usd("310")).amount)
    }

    @Test
    fun generatedTradeIdsAreUnique() {
        val ids = (1..100).map { generateTradeId() }.toSet()
        assertEquals(100, ids.size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest --console=plain
```
Expected: compilation FAILS — `TradeSide`/`Portfolio`/etc unresolved.

- [ ] **Step 3: Implement**

`Trade.kt`:
```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.random.Random

enum class TradeSide { Buy, Sell }

sealed class TradeError(message: String) : Exception(message) {
    object InsufficientFunds : TradeError("Insufficient funds")
    object InsufficientShares : TradeError("Insufficient shares")
    object InvalidQuantity : TradeError("Invalid quantity")
}

/** One executed paper trade. `id` is caller-supplied (deterministic in tests). */
data class Transaction(
    val id: String,
    val symbol: String,
    val side: TradeSide,
    val quantity: BigDecimal,
    val price: Money,
    val epochSeconds: Long,
)

/** Unique-enough trade id without a platform UUID dependency. */
fun generateTradeId(): String =
    "txn-${kotlinx.datetime.Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(100000, 999999)}"
```

NOTE: if `kotlinx.datetime` is not already a `:shared` dependency (it is not, as of 6a.5), do NOT add it — use a monotonic counter + random instead:
```kotlin
private var idCounter = 0L
fun generateTradeId(): String = "txn-${idCounter++}-${Random.nextInt(100000, 999999)}"
```
is unacceptable (not process-stable). Correct minimal version without new deps:
```kotlin
fun generateTradeId(): String =
    "txn-${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"
```
(128 bits of randomness — collision-negligible.) Use this version.

`Position.kt`:
```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

data class Position(
    val asset: Asset,
    val quantity: BigDecimal,
    val averageCost: Money,
    val realizedPnL: Money,
) {
    fun marketValue(at: Money): Money =
        Money(at.amount * quantity, at.currencyCode)

    fun unrealizedPnL(at: Money): Money =
        Money((at.amount - averageCost.amount) * quantity, at.currencyCode)
}
```

`Portfolio.kt`:
```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode

/** Division mode for portfolio math: mirrors Swift Decimal's 38-significant-digit plain
 *  rounding. ionspin BigDecimal THROWS on non-terminating division without a mode. */
val MONEY_MATH = DecimalMode(38, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)

/** A derived snapshot of a portfolio valued against current quotes. Pure. */
data class PortfolioValuation(
    val cash: Money,
    val holdingsValue: Money,
    val totalValue: Money,
    val unrealizedPnL: Money,
    val dayChange: Money,
)

/** A simulated (paper-trading) portfolio: virtual cash plus average-cost positions and a
 *  transaction log. All transitions are pure and return a new Portfolio. Transcribed from
 *  the Swift original (Sources/APTradeDomain/Portfolio.swift) — semantics must not drift. */
data class Portfolio(
    val cash: Money,
    val positions: List<Position> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
) {
    fun positionFor(symbol: String): Position? = positions.firstOrNull { it.asset.symbol == symbol }

    fun buying(
        asset: Asset,
        quantity: BigDecimal,
        price: Money,
        epochSeconds: Long,
        id: String = generateTradeId(),
    ): Portfolio {
        if (quantity.isZero()) throw TradeError.InvalidQuantity
        val cost = price.amount * quantity
        if (cash.amount < cost) throw TradeError.InsufficientFunds

        val index = positions.indexOfFirst { it.asset.symbol == asset.symbol }
        val updated = positions.toMutableList()
        if (index >= 0) {
            val old = positions[index]
            val newQty = old.quantity + quantity
            val newAvg = (old.averageCost.amount * old.quantity + cost).divide(newQty, MONEY_MATH)
            updated[index] = Position(old.asset, newQty, Money(newAvg, price.currencyCode), old.realizedPnL)
        } else {
            updated += Position(asset, quantity, price, Money(BigDecimal.ZERO, price.currencyCode))
        }

        val txn = Transaction(id, asset.symbol, TradeSide.Buy, quantity, price, epochSeconds)
        return Portfolio(
            cash = Money(cash.amount - cost, cash.currencyCode),
            positions = updated,
            transactions = transactions + txn,
        )
    }

    fun selling(
        symbol: String,
        quantity: BigDecimal,
        price: Money,
        epochSeconds: Long,
        id: String = generateTradeId(),
    ): Portfolio {
        if (quantity.isZero()) throw TradeError.InvalidQuantity
        val index = positions.indexOfFirst { it.asset.symbol == symbol }
        if (index < 0 || positions[index].quantity < quantity) throw TradeError.InsufficientShares

        val old = positions[index]
        val proceeds = price.amount * quantity
        val realizedDelta = (price.amount - old.averageCost.amount) * quantity
        val newQty = old.quantity - quantity

        val updated = positions.toMutableList()
        if (newQty.isZero()) {
            updated.removeAt(index)
        } else {
            updated[index] = Position(
                old.asset, newQty, old.averageCost,
                Money(old.realizedPnL.amount + realizedDelta, old.realizedPnL.currencyCode),
            )
        }

        val txn = Transaction(id, symbol, TradeSide.Sell, quantity, price, epochSeconds)
        return Portfolio(
            cash = Money(cash.amount + proceeds, cash.currencyCode),
            positions = updated,
            transactions = transactions + txn,
        )
    }

    /** Values every position against `quotes` (cost-basis fallback when a quote is
     *  missing). Day change per share is derived as price − previousClose. Pure. */
    fun valuation(quotes: Map<String, Quote>): PortfolioValuation {
        var holdings = BigDecimal.ZERO
        var unrealized = BigDecimal.ZERO
        var day = BigDecimal.ZERO
        for (position in positions) {
            val q = position.quantity
            val quote = quotes[position.asset.symbol]
            if (quote != null) {
                holdings += quote.price.amount * q
                unrealized += (quote.price.amount - position.averageCost.amount) * q
                day += (quote.price.amount - quote.previousClose.amount) * q
            } else {
                holdings += position.averageCost.amount * q   // cost-basis fallback
            }
        }
        val code = cash.currencyCode
        return PortfolioValuation(
            cash = cash,
            holdingsValue = Money(holdings, code),
            totalValue = Money(cash.amount + holdings, code),
            unrealizedPnL = Money(unrealized, code),
            dayChange = Money(day, code),
        )
    }

    companion object {
        /** The starting paper portfolio: $100,000 cash, no holdings. */
        fun starting(): Portfolio = Portfolio(Money.usd("100000"))
    }
}
```

(If `BigDecimal.isZero()`/comparison operators differ in the bignum API, use the API's actual members — `compareTo` is proven present since increment 4. Do not weaken any semantic.)

- [ ] **Step 4: Run tests**

```bash
./gradlew :shared:jvmTest --console=plain --rerun-tasks
grep -o 'tests="[0-9]*"' shared/build/test-results/jvmTest/TEST-*.xml | grep -o '[0-9]*' | paste -sd+ - | bc
```
Expected: PASS, count **66** (51 + 15).

- [ ] **Step 5: Commit**

```bash
git add shared/src
git commit -m "feat(shared): Portfolio domain — buy/sell transitions, valuation, pinned decimal division"
```

---

### Task 2: Shared domain — analytics, performance series, export snapshot + renderers

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PortfolioAnalytics.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PortfolioPerformance.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PortfolioExport.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PortfolioAnalyticsTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PortfolioPerformanceTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PortfolioExportTest.kt`

**Interfaces:**
- Consumes: Task 1 types; existing `PricePoint(epochSeconds, close)`, `Quote`.
- Produces (Tasks 3, 5, 6 rely on):
  - `val Portfolio.realizedPnL: Money` (transaction-log replay, average-cost method)
  - `data class AllocationSlice(val id: String, val label: String, val value: Double, val fraction: Double)`
  - `fun Portfolio.allocationByHolding(quotes: Map<String, Quote>): List<AllocationSlice>` (largest first)
  - `fun Portfolio.allocationByKind(quotes: Map<String, Quote>): List<AllocationSlice>` (Stocks/ETFs/Crypto order, zero-value kinds omitted; labels "Stocks"/"ETFs"/"Crypto")
  - `data class PortfolioPerformancePoint(val epochSeconds: Long, val value: Money, val pnl: Money)`
  - `fun Portfolio.performanceSeries(histories: Map<String, List<PricePoint>>): List<PortfolioPerformancePoint>` (forward-fill; skip dates where nothing is priced; cash constant)
  - `data class PortfolioExport(...)` snapshot with nested `Holding` (fields mirroring the Swift original; `allocation: Double` 0..1; holdings ordered by market value desc) + `companion fun from(portfolio, quotes, accountName, generatedAtEpochSeconds): PortfolioExport`
  - `fun PortfolioExport.renderCsv(): String` and `fun PortfolioExport.renderJson(): String`

Transcribe the algorithms from the Swift originals named in each file's doc comment:
`realizedPnL` from `Sources/APTradeDomain/PortfolioAnalytics.swift` (sort by date; buy re-averages, sell realizes against held avg with fallback-to-price when unheld; quantities go negative freely), `performanceSeries` from `Sources/APTradeDomain/PortfolioPerformance.swift` (per-symbol sorted points, union of dates, cursor forward-fill, `priced` gate, cash constant), export from `Sources/APTradeDomain/PortfolioExport.swift` (valuation totals + per-holding rows with cost-basis-fallback price). Allocation ports the two computed properties from `Sources/APTradeApp/PortfolioViewModel.swift:104-125` into the domain (they are pure; the macOS VM will keep its own copy until 6b.3). Divisions use `MONEY_MATH`; allocation fractions convert via `.doubleValue(false)` at the end (ratios, not money).

- [ ] **Step 1: Write the failing tests.** Test cases (write them concretely in the files):

`PortfolioAnalyticsTest.kt` — 5 tests:
- `realizedPnLReplaysBuysAndSells`: buy 10@300 (t1), sell 4@350 (t2) → realized 200.
- `realizedPnLSurvivesFullyClosedPositions`: buy 10@300, sell 10@350 → realized 500 even though positions is empty (compute over a Portfolio reconstructed with those transactions and no positions).
- `realizedPnLAveragesAcrossMultipleBuys`: buy 10@300, buy 10@400, sell 10@400 → realized (400−350)×10 = 500.
- `allocationByHoldingIsLargestFirstWithFractions`: AAPL 10@quote 310 (3100), BTC 0.1@quote 60000 (6000) → slices [BTC 6000 frac 6000/9100, AAPL 3100 frac 3100/9100] (assert fractions with 1e-9 tolerance).
- `allocationByKindGroupsAndOmitsZero`: same portfolio → [Stocks 3100, Crypto 6000] filtered/ordered [Stocks, Crypto] (no ETFs slice).

`PortfolioPerformanceTest.kt` — 4 tests:
- `seriesValuesHoldingsAgainstEachDate`: one position AAPL 10 @avg 300, history [(100, 300.00), (200, 310.00)] → points: (100, value 100000-3000+3000=… careful: cash after the buy — construct Portfolio DIRECTLY with cash 97000, position, no transactions needed) → value at 100 = 97000+3000 = 100000, pnl 0; at 200 = 97000+3100, pnl 100.
- `seriesForwardFillsMissingDates`: two positions with staggered histories — AAPL points at 100/200, BTC point only at 200 → at date 100 only AAPL priced (BTC contributes nothing, point still included), at 200 both.
- `seriesSkipsDatesWherNothingIsPriced` — position with history starting at 200, plus another symbol's history providing date 100 but that symbol has NO position… (mirror the Swift `priced` gate: histories map contains only held symbols, so construct one held symbol with points at 200 and pass an extra history entry for an unheld symbol at 100 — unheld symbols are ignored entirely because iteration is over positions; expect single point at 200).
- `emptyPositionsGiveEmptySeries`.

`PortfolioExportTest.kt` — 3 tests:
- `snapshotOrdersHoldingsByMarketValueAndComputesAllocation` (assert fields incl. costBasis, unrealizedPnL, allocation fractions).
- `csvGoldenString`: a fixed small portfolio/quotes/accountName/generatedAt → assert the exact CSV text (header row + one line per holding + totals section — design the format in the implementation and pin it here; keep it stable, comma-separated, amounts as plain decimal strings).
- `jsonRoundTripsThroughKotlinxSerialization`: renderJson output decodes back (via Json.parseToJsonElement) and key fields match.

- [ ] **Step 2: Verify RED** (compilation failure), **Step 3: implement the three files** (transcribing the named Swift sources; export DTO `@Serializable` with BigDecimal fields carried as `String` in the JSON DTO), **Step 4: GREEN**:

```bash
./gradlew :shared:jvmTest --console=plain --rerun-tasks
```
Expected: count **78** (66 + 12).

- [ ] **Step 5: Commit**

```bash
git add shared/src
git commit -m "feat(shared): portfolio analytics, performance series, export snapshot with CSV/JSON renderers"
```

---

### Task 3: Shared application — PortfolioStore port + use cases

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/PortfolioStore.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchPortfolio.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/BuyAsset.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/SellAsset.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/ResetPortfolio.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchPortfolioPerformance.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/PortfolioUseCasesTest.kt`

**Interfaces:**
- Consumes: Tasks 1–2; existing `MarketDataRepository`, `Timeframe`, `QuoteError`.
- Produces (Tasks 4–6 rely on):
  - `interface PortfolioStore { suspend fun load(): Portfolio?; suspend fun save(portfolio: Portfolio) }` (null = never saved)
  - `class FetchPortfolio(store)` → `suspend fun execute(): Portfolio` (null → `Portfolio.starting()`, NOT persisted — reset/first trade persists)
  - `class BuyAsset(repository, store)` → `suspend fun execute(asset: Asset, quantity: BigDecimal, epochSeconds: Long): Portfolio` — live quote via `repository.quotes(listOf(symbol)).first()` (throws QuoteError.NotFound if empty), `buying`, save, return
  - `class SellAsset(repository, store)` → `suspend fun execute(symbol: String, quantity: BigDecimal, epochSeconds: Long): Portfolio`
  - `class ResetPortfolio(store)` → `suspend fun execute(): Portfolio` (saves and returns `starting()`)
  - `class FetchPortfolioPerformance(repository, store)` → `suspend fun execute(timeframe: Timeframe, sinceInception: Boolean = false): List<PortfolioPerformancePoint>` — concurrent per-symbol `history()` via `coroutineScope { async }` with per-symbol failures → empty list (CancellationException rethrown); inception trim: drop points with `epochSeconds <` the first transaction's epochSeconds truncated to day (`(t / 86_400) * 86_400`), keep the untrimmed series if trimming empties it.
  - All use cases `@Throws(QuoteError::class, TradeError::class, CancellationException::class)` where applicable (Swift bridging in 6b.3 needs it — cheap to add now).

- [ ] **Step 1: Failing tests** (~9, using `FakeMarketDataRepositoryShared`-style anonymous object or the existing commonTest patterns + an in-memory store):
- fetch returns starting() when never saved, without persisting (store.saveCount == 0)
- fetch returns the stored portfolio verbatim
- buy executes at the live quote price and persists (assert cash/position/store state + transaction epochSeconds passed through)
- buy propagates TradeError.InsufficientFunds without saving
- buy propagates QuoteError (repo throws RateLimited) without saving
- sell realizes and persists; sell propagates InsufficientShares without saving
- reset saves and returns starting()
- performance fetches histories for each held symbol concurrently and builds the series (fake returns fixed histories; assert points)
- performance tolerates a per-symbol failure (one symbol throws → treated as empty history, series still built from the other)
- performance sinceInception trims to the first transaction's day (fixture with transactions at epoch 200000; points at 86400×1=86400 and 86400×3; first-txn day = 172800 → first point dropped)

- [ ] **Step 2: RED**, **Step 3: implement** (each use case ~15 lines, mirroring the Swift originals in `Sources/APTradeApplication/PortfolioUseCases.swift`), **Step 4: GREEN**:

```bash
./gradlew :shared:jvmTest --console=plain --rerun-tasks
```
Expected: count **88** (78 + 10).

- [ ] **Step 5: Also prove Apple-target compilation** (commonMain grew — cheap early signal for 6b.3):

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:compileKotlinMacosArm64 --console=plain
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add shared/src
git commit -m "feat(shared): PortfolioStore port and Fetch/Buy/Sell/Reset/Performance use cases"
```

---

### Task 4: Desktop `FilePortfolioStore` + AppGraph wiring

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/FilePortfolioStore.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/infra/FilePortfolioStoreTest.kt`

**Interfaces:**
- Consumes: `PortfolioStore`, `Portfolio`/`Position`/`Transaction`, `resolveConfigDir` (existing).
- Produces: `class FilePortfolioStore(private val file: Path) : PortfolioStore`; `AppGraph` gains `val fetchPortfolio/buyAsset/sellAsset/resetPortfolio/fetchPortfolioPerformance` (store default `FilePortfolioStore(resolveConfigDir().resolve("portfolio.json"))`).

- [ ] **Step 1: Failing tests** (5): round-trip (cash + positions incl. fractional BigDecimal quantity + transactions with side/id/epoch), fresh-instance re-load, missing file → null, corrupt file → null, atomic write leaves no temp file (mirror `FileWatchlistStoreTest` patterns exactly — same temp-dir style).
- [ ] **Step 2: RED.**
- [ ] **Step 3: Implement** — `@Serializable` DTOs (`PortfolioDTO`, `PositionDTO`, `TransactionDTO`, `MoneyDTO(amount: String, currency: String)`), all BigDecimal as `toStringExpanded()` strings, `AssetKind`/`TradeSide` as `.name` strings with unknown-value row-skip like FileWatchlistStore; atomic temp+ATOMIC_MOVE save; `Dispatchers.IO`. Then extend `AppGraph` (constructor keeps its shape; add `portfolioStore: PortfolioStore = FilePortfolioStore(...)` parameter and the five use-case vals).
- [ ] **Step 4: GREEN:**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: count **60** (55 + 5).

- [ ] **Step 5: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): FilePortfolioStore with exact-decimal JSON and AppGraph portfolio wiring"
```

---

### Task 5: Desktop `PortfolioViewModel` + `TradeViewModel`

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModel.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/TradeViewModel.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModelTest.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/TradeViewModelTest.kt`

**Interfaces:**
- Consumes: Task 3 use cases, Task 2 analytics, `FetchMarketQuotes`, designkit `formatPercent` conventions; scope confinement contract (KDoc like the other VMs).
- Produces (Task 6 renders):
  - `PortfolioSpan` enum (`Day/Week/Month/Year/Max` with `timeframe` mapping — Year AND Max → `Timeframe.OneYear` — and `sinceInception` true only for Max; labels "1D"/"1W"/"1M"/"1Y"/"MAX").
  - `data class HoldingRowUi(symbol, name, kindLabel, quantityText, averageCostText, marketValueText, unrealizedText, unrealizedPositive: Boolean?, priceText: String?)`
  - `data class PortfolioUiState(isLoading, totalValueText: String?, dayChangeText, dayChangePositive: Boolean?, cashText, holdingsValueText, unrealizedText, unrealizedPositive: Boolean?, realizedText, realizedPositive: Boolean?, holdings: List<HoldingRowUi>, allocationByHolding: List<AllocationSlice>, allocationByKind: List<AllocationSlice>, transactions: List<TransactionRowUi>, performance: List<Double>, isLoadingPerformance: Boolean, span: PortfolioSpan = Month, error: String?)` — `data class TransactionRowUi(id, symbol, sideLabel, isBuy: Boolean, quantityText, priceText, epochSeconds)`
  - `class PortfolioViewModel(fetchPortfolio, fetchMarketQuotes, buyAsset, sellAsset, resetPortfolio, fetchPortfolioPerformance, scope, tickMillis = 15_000, nowEpochSeconds: () -> Long)`: `start()` (load + poll held quotes 15s), `refresh()`, `setSpan(PortfolioSpan)`, `buy(asset, quantityText)` / `sell(symbol, quantityText)` returning per-call error surface via a `tradeError: String?` in state (cleared on success), `reset()`, `exportCsv(): String` / `exportJson(): String` (renders from current portfolio+quotes via the shared export).
  - Money-to-text conversions all `amountText`; signed PnL texts formatted `+$…`/`−$…` via splitPrice-compatible plain strings — expose raw `amountText` plus sign booleans and let the UI compose signs (keep VM formatting minimal).
  - `class TradeViewModel(side: TradeSide, asset/symbol context, quote provider…)` — SIMPLIFICATION over macOS: the trade dialog state (quantity text, parsed quantity or null, estimated cost text = qty × live price when parseable, canSubmit) is pure-derivable; implement as a small immutable helper `TradeFormState(side, priceText: String?, quantityText)` with `fun parsedQuantity(): BigDecimal?` (rejects ≤0, malformed, >8 fraction digits) and `fun estimateText(price: Money?): String?` — a pure class, unit-tested, no coroutines. The submit action lives on PortfolioViewModel (`buy`/`sell` above). This avoids a second scoped VM for a modal.

- [ ] **Step 1: Failing tests** — `PortfolioViewModelTest` (10): start loads starting-portfolio state (empty holdings, cash 100000 text); buy happy path updates holdings + persists via fake store + clears tradeError; buy with insufficient funds sets tradeError and leaves state unchanged; buy with QuoteError sets tradeError; sell happy + sell insufficient; poll tick refreshes quotes for held symbols only (virtual time); setSpan(Max) fetches performance with sinceInception=true (assert fake repo received OneYear + trim behavior via injected transactions); reset returns to starting and clears performance; export CSV contains a held symbol's line.
  `TradeViewModelTest` (formState, 5): parses integer and fractional quantities; rejects zero/negative/garbage/9-fraction-digits; estimate = qty×price exact text; null price → null estimate; canSubmit only when parseable and price present.
- [ ] **Step 2: RED**, **Step 3: implement** (PortfolioViewModel mirrors WatchlistViewModel's confinement/polling/error patterns; every catch rethrows CancellationException first; TradeError mapped via a `tradeErrorMessage()` in `ui/ErrorMessages.kt` — extend that file with `fun TradeError.userMessage(): String` returning "Insufficient funds." / "Insufficient shares." / "Enter a valid quantity."), **Step 4: GREEN**:

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: count **75** (60 + 15).

- [ ] **Step 5: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): PortfolioViewModel with trading, spans, allocation, export; pure trade form state"
```

---

### Task 6: Desktop Portfolio tab UI — pane, trade dialog, export save

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioPane.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/TradeDialog.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/ExportSave.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (AppRoot: Portfolio tab renders PortfolioPane; wire the VM once in main() beside the watchlist VM)
- Possibly create: designkit `DonutChart` (see targets)

**Interfaces:**
- Consumes: Task 5 state/VM; designkit (SuperscriptPrice, ChangePill, StatTile, TimeframeBar idiom, LineChart, ExpandedValueCard, DK, formatPercent); Task 2 `AllocationSlice`.
- Produces: the working tab. `ExportSave.kt`: `fun saveTextFile(suggestedName: String, content: String)` — AWT `FileDialog` in SAVE mode; the ONLY AWT import in the app; designkit stays clean.

UI composition (no unit tests — standing waiver). Fidelity targets from `Sources/APTradeApp/PortfolioView.swift` + `TradeSheet.swift` (the implementer should read both for anything unspecified):

- **Summary header**: total value `SuperscriptPrice(34sp)`; day-change ChangePill-style signed money + LiveBadge when polling; metric row of `StatTile`s: CASH, HOLDINGS, UNREALIZED P&L (changeColor), REALIZED P&L (changeColor). Reset via a small "Reset portfolio…" text button (textTertiary) → confirmation dialog ("Start over with $100,000?" Confirm/Cancel). Export via two text buttons "Export CSV" / "Export JSON" → `saveTextFile("portfolio.csv"/"portfolio.json", vm.exportCsv()/exportJson())`.
- **P&L chart block**: span bar (TimeframeBar idiom extended with MAX — 5 labels) + `LineChart` of `state.performance` (gold, 260dp height) + gold loading spinner while `isLoadingPerformance`. Deliberately NO ExpandedValueCard here in 6b.1 — that component's headline/delta semantics are percentage-points, and this series is account VALUE; the macOS expandable-value treatment (with money formatting) is a 6b.2 refinement.
- **Section switcher**: KindToggle-idiom segmented pill with three segments Holdings / Allocation / Activity.
- **Holdings**: rows — name (15sp) over symbol+`qty @ avgCost` (12sp textSecondary, tnum); right column market value (SuperscriptPrice 18sp) over unrealized PnL signed text in changeColor; row click opens the existing full-window `DetailScreen`; hover reveals two small text buttons BUY / SELL (gold) opening the TradeDialog. **Buy entry point for NEW symbols (macOS parity — macOS trades from the asset detail sheet): the desktop `DetailScreen` header gains a gold BUY button opening the TradeDialog for that asset.** The Portfolio tab itself only trades held assets (per-row BUY/SELL). Empty state: centered "No holdings yet — open an asset and hit Buy." (textTertiary), with the summary header still showing cash.
- **TradeDialog** (`TradeSheet` parity): modal scrim + 420dp panel (surface, 14dp radius, hairline border): header (asset name/symbol, live price SuperscriptPrice 24sp), Buy/Sell segmented toggle (gold selected), quantity TextField (tnum, plain, validation message in DK.down 12sp under it), estimate row ("Estimated cost/proceeds" + exact amount), Cancel / Confirm buttons (Confirm gold, disabled unless canSubmit); Confirm calls vm.buy/sell and closes on success, shows tradeError inline on failure. Esc closes (dialog-local key handling BEFORE the window's Esc handler — the dialog consumes it; note the Main.kt Esc-priority comment).
- **Allocation**: two blocks — "By holding": rows with symbol, horizontal fraction bar (gold gradient fill proportional to fraction), percent (formatPercent(fraction×100)); "By class": same with Stocks/ETFs/Crypto labels and DK.gold/DK.silver/DK.goldDeep bar colors. (macOS's donut is a nice-to-have; a `DonutChart` designkit Canvas — arcs per slice — MAY be added if time-neutral, else bars only; state the choice in the report.)
- **Activity**: transaction rows newest-first — side chip (BUY in DK.up / SELL in DK.down, 10sp bold), symbol, qty @ price (tnum), relative order preserved; no dates rendered in 6b.1 (epoch formatting needs a date lib — defer; show them in the order given).
- **Wiring**: `PortfolioViewModel` constructed once in `main()` (same appScope), `start()` in the existing LaunchedEffect; Portfolio tab in AppRoot's `when` renders `PortfolioPane(...)` instead of `PlaceholderPane`; DetailScreen gains the BUY button wired to TradeDialog (the dialog lives at AppRoot level so it overlays everything, with state `tradeTarget: Pair<Asset, TradeSide>?` hoisted in main() like the palette).

- [ ] **Step 1: Implement per targets** (read PortfolioView.swift/TradeSheet.swift for unspecified details; nearest-macOS-sibling rule).
- [ ] **Step 2: Suite stays green:**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: count **75** (no test changes).

- [ ] **Step 3: Live check** — run ≥60s on real data with a scripted sanity: after boot, the process must stay alive; then exercise a REAL headless trade by… (not possible without UI driving — instead:) verify `~/Library/Application Support/APTrade/portfolio.json` is ABSENT before first trade (store not written by mere loading — FetchPortfolio does not persist), and note interactive checks (buy/sell/reset/export/spans/sections) for the human gate. Kill cleanly.
- [ ] **Step 4: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): Portfolio tab — summary, P&L chart, holdings with trading, allocation, activity, export"
```

---

### Task 7: Full regression + docs

**Files:**
- Modify: `README.md` (desktop section: portfolio tab paragraph; roadmap: 6b.1 shipped, 6b.2/6b.3/6b.4 remain)
- Modify: `.claude/skills/aptrade/SKILL.md` only if commands changed (they did not — skip unless README contradiction found)

- [ ] **Step 1: Full sweep** (shared changed → full Apple re-proof):

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :desktopApp:test --console=plain --rerun-tasks
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:assembleSharedReleaseXCFramework --console=plain
DEVELOPER_DIR=/Applications/Xcode.app swift test 2>&1 | grep -E "Executed [0-9]+ tests" | tail -1
xcodebuild -scheme APTradeLite-Package -destination 'generic/platform=iOS Simulator' ARCHS=arm64 build -quiet && echo IOS-OK
```
Expected: shared **88**, android **13**, desktop **75**, Swift **193** (untouched sources — if the xcodeproj-shadowing gotcha appears, park/restore `APTrade.xcodeproj`), xcframework 3 slices, IOS-OK.

- [ ] **Step 2: README** portfolio paragraph + roadmap adjustment; commit `docs: document the desktop Portfolio tab (increment 6b.1)`.

---

## Self-review notes (already applied)

- Spec coverage: domain (T1–T2), store+use cases (T3), file store+wiring (T4), VMs (T5), UI incl. trade dialog/export/reset (T6), regression+docs (T7). Deliberate refinements recorded inline: trade form is a pure helper not a scoped VM; the expandable value-card treatment of the P&L chart deferred to 6b.2 (its percent semantics don't fit a value series); Buy entry for new symbols = DetailScreen BUY button; activity rows carry no formatted dates in 6b.1.
- Type consistency: `Portfolio.buying/selling(…, epochSeconds, id)` signatures match T3's use-case calls; `PortfolioStore.load(): Portfolio?` null-semantics consistent between T3 (starting() fallback) and T4 (missing/corrupt → null); `AllocationSlice` defined once in T2, consumed by T5/T6; `MONEY_MATH` defined T1, used T1–T2.
- No placeholders: every test list is concrete; T2/T3 test bodies are specified as behavior + exact fixtures inline where arithmetic matters.
