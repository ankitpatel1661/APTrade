# KMP Increment 6b.2 — Portfolio & Detail Fidelity + Intelligence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the desktop Portfolio tab and asset detail screen to macOS parity (money formatting, stat grid + position panel, allocation donut, single Export chooser with PDF) and port the macOS intelligence features (six chart indicators, Performance section with SPY/QQQ/VTI benchmark overlay and seven risk metrics).

**Architecture:** All new math (technical indicators, risk metrics, rebase) lands in `:shared` commonMain as pure Double-domain functions transcribed from the Swift originals and fixture-tested. The desktop grows a PDFBox-backed PDF renderer behind the existing export seam, a designkit money formatter + DonutChart, VM extensions for the performance report, and composition-only UI (standing waiver). Spec: `docs/superpowers/specs/2026-07-03-kmp-portfolio-6b2-design.md`.

**Tech Stack:** Kotlin Multiplatform, Compose Desktop, ionspin BigDecimal (money only), Apache PDFBox 3.0.3 (desktopApp only).

## Global Constraints

- Before EVERY `./gradlew`: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`. Never system Gradle. Counts from JUnit XMLs with `--rerun-tasks`.
- Baselines at branch start: shared **90**, desktop **75**, android **13**, Swift **193**. Android and Swift sources untouched; `:shared` grows → the regression task re-proves xcframework + Swift + iOS.
- New shared math is **Double-domain** (chart/statistics) — indicators and risk metrics take/return Double. Money and quantity remain BigDecimal; money reaches UI only as text; every BigDecimal division uses `MONEY_MATH` (`DecimalMode(38, ROUND_HALF_AWAY_FROM_ZERO)`).
- Transcriptions must match the Swift originals exactly — implementers read the named Swift file BEFORE coding and flag any semantic mismatch with this plan instead of silently choosing: `Sources/APTradeDomain/TechnicalIndicators.swift`, `Sources/APTradeDomain/RiskMetrics.swift`, `Sources/APTradeInfrastructure/PortfolioExportRenderer.swift` (PDF section), `Sources/APTradeApp/PerformanceSection.swift` + `PerformanceViewModel.swift`, `Sources/APTradeApp/AssetDetailView.swift`, `Sources/APTradeApp/PortfolioView.swift` (donut).
- CancellationException rethrown before any domain error in every coroutine catch.
- UI composition ships without unit tests (standing user waiver); ViewModels, shared math, and the PDF renderer carry the behavior.
- Money formatting parity: "$", comma grouping, ALWAYS exactly 2 decimals, HALF_EVEN rounding, minus from the formatter, "+" added by call sites only for strictly positive values (zero unsigned).
- User-mandated divergences from macOS (do not "fix" toward macOS): detail screen gets ONE "BUY / SELL" button (macOS has two); export chooser offers CSV/JSON/PDF (macOS offers PDF/XLSX/DOCX).
- Commit after every task minimum.

## File Structure (new files)

- `shared/src/commonMain/kotlin/com/aptrade/shared/domain/TechnicalIndicators.kt` — pure indicator math
- `shared/src/commonTest/kotlin/com/aptrade/shared/domain/TechnicalIndicatorsTest.kt`
- `shared/src/commonMain/kotlin/com/aptrade/shared/domain/RiskMetrics.kt` — pure risk/return math + rebase
- `shared/src/commonTest/kotlin/com/aptrade/shared/domain/RiskMetricsTest.kt`
- `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchPerformanceReport.kt` — equity curve + benchmark + metrics use case
- `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchPerformanceReportTest.kt`
- `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/MoneyText.kt` — formatMoney/signedMoney
- `desktopApp/src/test/kotlin/com/aptrade/desktop/designkit/MoneyTextTest.kt`
- `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/DonutChart.kt` — Canvas donut
- `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/PdfPortfolioRenderer.kt` — PDFBox renderer
- `desktopApp/src/test/kotlin/com/aptrade/desktop/infra/PdfPortfolioRendererTest.kt`
- `desktopApp/src/main/kotlin/com/aptrade/desktop/detail/IndicatorOverlays.kt` — chips + overlay/pane composition
- `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PerformanceSection.kt` — benchmark chart + metric grid
- `desktopApp/src/main/kotlin/com/aptrade/desktop/ui/AssetKindLabels.kt` — hoisted kind↔label helpers
- Modified: `PortfolioViewModel.kt`, `PortfolioPane.kt`, `TradeDialog.kt` (no change expected — verify), `TradeFormState.kt`, `DetailPane.kt`, `Main.kt`, `ExportSave.kt`, `desktopApp/build.gradle.kts`, `README.md`.

---

### Task 1: Shared `TechnicalIndicators` (pure math + fixture tests)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/TechnicalIndicators.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/TechnicalIndicatorsTest.kt`

**Interfaces:**
- Consumes: nothing (pure Double math). MUST read `Sources/APTradeDomain/TechnicalIndicators.swift` first and transcribe its semantics exactly; if the Swift code disagrees with the code below in ANY observable way (alignment, seeding, null policy), STOP and report the discrepancy instead of picking one.
- Produces (Task 7 renders): `object TechnicalIndicators` with
  `sma(values: List<Double>, period: Int): List<Double?>`,
  `ema(values: List<Double>, period: Int): List<Double?>`,
  `rsi(values: List<Double>, period: Int = 14): List<Double?>`,
  `vwap(highs: List<Double>, lows: List<Double>, closes: List<Double>, volumes: List<Double>): List<Double?>`,
  `bollingerBands(values: List<Double>, period: Int = 20, multiplier: Double = 2.0): List<BollingerBand?>` with `data class BollingerBand(val middle: Double, val upper: Double, val lower: Double)`,
  `macd(values: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): List<MacdPoint?>` with `data class MacdPoint(val macd: Double, val signal: Double?, val histogram: Double?)`.
  All outputs are index-aligned with the input; entries are null until the window/seed is complete.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aptrade.shared.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertClose(expected: Double, actual: Double?, tol: Double = 1e-9) {
    assertTrue(actual != null && abs(expected - actual) < tol, "expected $expected got $actual")
}

class TechnicalIndicatorsTest {
    @Test fun smaAlignsAndAverages() {
        val out = TechnicalIndicators.sma(listOf(1.0, 2.0, 3.0, 4.0), period = 2)
        assertNull(out[0]); assertClose(1.5, out[1]); assertClose(2.5, out[2]); assertClose(3.5, out[3])
    }
    @Test fun smaPeriodLongerThanSeriesIsAllNull() {
        assertTrue(TechnicalIndicators.sma(listOf(1.0, 2.0), 5).all { it == null })
    }
    @Test fun emaSeedsWithSmaThenSmooths() {
        // period 3 → multiplier 0.5; seed at index 2 = SMA(2,2,2)=2; next = (6−2)*0.5+2 = 4
        val out = TechnicalIndicators.ema(listOf(2.0, 2.0, 2.0, 6.0), period = 3)
        assertNull(out[0]); assertNull(out[1]); assertClose(2.0, out[2]); assertClose(4.0, out[3])
    }
    @Test fun rsiAllGainsIs100() {
        val values = (1..16).map { it.toDouble() }
        val out = TechnicalIndicators.rsi(values, period = 14)
        assertNull(out[13]); assertClose(100.0, out[14]); assertClose(100.0, out[15])
    }
    @Test fun rsiBalancedGainsAndLossesIs50() {
        // 15 values alternating +1/−1: 14 deltas = 7 gains of 1, 7 losses of 1 → RS=1 → RSI=50
        val values = MutableList(15) { i -> if (i % 2 == 0) 10.0 else 11.0 }
        assertClose(50.0, TechnicalIndicators.rsi(values, period = 14)[14])
    }
    @Test fun vwapIsNullUntilVolumeThenCumulative() {
        val out = TechnicalIndicators.vwap(
            highs = listOf(12.0, 12.0, 24.0), lows = listOf(8.0, 8.0, 16.0),
            closes = listOf(10.0, 10.0, 20.0), volumes = listOf(0.0, 1.0, 3.0),
        )
        assertNull(out[0])                    // cumulative volume still 0
        assertClose(10.0, out[1])             // typical (12+8+10)/3 = 10
        assertClose(17.5, out[2])             // (10*1 + 20*3) / 4
    }
    @Test fun bollingerConstantSeriesCollapsesBands() {
        val out = TechnicalIndicators.bollingerBands(listOf(5.0, 5.0, 5.0), period = 2)
        assertNull(out[0])
        assertClose(5.0, out[1]?.middle); assertClose(5.0, out[1]?.upper); assertClose(5.0, out[1]?.lower)
    }
    @Test fun bollingerUsesPopulationStddev() {
        // window [1,3]: mean 2, POPULATION stddev 1 → upper 4, lower 0 (sample stddev √2 would give ≈4.828)
        val out = TechnicalIndicators.bollingerBands(listOf(1.0, 3.0), period = 2, multiplier = 2.0)
        assertClose(2.0, out[1]?.middle); assertClose(4.0, out[1]?.upper); assertClose(0.0, out[1]?.lower)
    }
    @Test fun macdConstantSeriesIsZeroEverywhereDefined() {
        val out = TechnicalIndicators.macd(List(40) { 7.0 })
        assertNull(out[24])                                   // slow EMA seeds at index 25
        assertClose(0.0, out[25]?.macd)
        assertNull(out[25]?.signal)                           // signal needs 9 macd values
        assertClose(0.0, out[33]?.signal); assertClose(0.0, out[33]?.histogram)
        assertClose(0.0, out[39]?.macd); assertClose(0.0, out[39]?.signal)
    }
    @Test fun macdNullityBoundaries() {
        val out = TechnicalIndicators.macd(List(40) { it.toDouble() })
        assertTrue((0..24).all { out[it] == null })
        assertTrue(out[25] != null && out[25]?.signal == null)
        assertTrue(out[32]?.signal == null && out[33]?.signal != null)
    }
}
```

- [ ] **Step 2: Run to verify RED**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest --console=plain --tests "com.aptrade.shared.domain.TechnicalIndicatorsTest"
```
Expected: compile failure (`TechnicalIndicators` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package com.aptrade.shared.domain

import kotlin.math.sqrt

data class BollingerBand(val middle: Double, val upper: Double, val lower: Double)
data class MacdPoint(val macd: Double, val signal: Double?, val histogram: Double?)

/** Pure technical-indicator math, transcribed from Sources/APTradeDomain/TechnicalIndicators.swift.
 *  All outputs are index-aligned with the input series; null until the window/seed completes. */
object TechnicalIndicators {

    fun sma(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0 || values.size < period) return List(values.size) { null }
        val out = MutableList<Double?>(values.size) { null }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    fun ema(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0 || values.size < period) return List(values.size) { null }
        val out = MutableList<Double?>(values.size) { null }
        val multiplier = 2.0 / (period + 1)
        var prev = values.take(period).sum() / period          // seed: SMA of first `period`
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = (values[i] - prev) * multiplier + prev
            out[i] = prev
        }
        return out
    }

    fun rsi(values: List<Double>, period: Int = 14): List<Double?> {
        if (period <= 0 || values.size <= period) return List(values.size) { null }
        val out = MutableList<Double?>(values.size) { null }
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val delta = values[i] - values[i - 1]
            if (delta > 0) avgGain += delta else avgLoss -= delta
        }
        avgGain /= period
        avgLoss /= period
        out[period] = rsiValue(avgGain, avgLoss)
        for (i in period + 1 until values.size) {
            val delta = values[i] - values[i - 1]
            val gain = if (delta > 0) delta else 0.0
            val loss = if (delta < 0) -delta else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period   // Wilder's smoothing
            avgLoss = (avgLoss * (period - 1) + loss) / period
            out[i] = rsiValue(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double =
        if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)

    fun vwap(highs: List<Double>, lows: List<Double>, closes: List<Double>, volumes: List<Double>): List<Double?> {
        val n = minOf(highs.size, lows.size, closes.size, volumes.size)
        val out = MutableList<Double?>(n) { null }
        var cumTypVol = 0.0
        var cumVol = 0.0
        for (i in 0 until n) {
            val typical = (highs[i] + lows[i] + closes[i]) / 3.0
            cumTypVol += typical * volumes[i]
            cumVol += volumes[i]
            if (cumVol > 0.0) out[i] = cumTypVol / cumVol
        }
        return out
    }

    fun bollingerBands(values: List<Double>, period: Int = 20, multiplier: Double = 2.0): List<BollingerBand?> {
        if (period <= 0 || values.size < period) return List(values.size) { null }
        val middles = sma(values, period)
        val out = MutableList<BollingerBand?>(values.size) { null }
        for (i in period - 1 until values.size) {
            val mean = middles[i] ?: continue
            var sq = 0.0
            for (j in i - period + 1..i) {
                val d = values[j] - mean
                sq += d * d
            }
            val stddev = sqrt(sq / period)                       // POPULATION stddev (macOS parity)
            out[i] = BollingerBand(middle = mean, upper = mean + multiplier * stddev, lower = mean - multiplier * stddev)
        }
        return out
    }

    fun macd(values: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): List<MacdPoint?> {
        val emaFast = ema(values, fast)
        val emaSlow = ema(values, slow)
        val macdLine = values.indices.map { i ->
            val f = emaFast[i]
            val s = emaSlow[i]
            if (f != null && s != null) f - s else null
        }
        val defined = macdLine.filterNotNull()
        val signalOnDefined = ema(defined, signal)
        val out = MutableList<MacdPoint?>(values.size) { null }
        var d = 0
        for (i in values.indices) {
            val m = macdLine[i] ?: continue
            val sig = signalOnDefined.getOrNull(d)
            out[i] = MacdPoint(macd = m, signal = sig, histogram = sig?.let { m - it })
            d++
        }
        return out
    }
}
```

- [ ] **Step 4: Run to verify GREEN, then full shared suite**

```bash
./gradlew :shared:jvmTest --console=plain --rerun-tasks
```
Expected: **100** tests (90 + 10), 0 failures.

- [ ] **Step 5: Commit**

```bash
git add shared/src
git commit -m "feat(shared): technical indicator math (SMA/EMA/RSI/VWAP/Bollinger/MACD) transcribed from Swift"
```

---

### Task 2: Shared `RiskMetrics` (+ rebase) with fixture tests

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/RiskMetrics.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/RiskMetricsTest.kt`

**Interfaces:**
- Consumes: nothing (pure Double math). MUST read `Sources/APTradeDomain/RiskMetrics.swift` first; on any semantic mismatch with the code below, STOP and report.
- Produces (Task 3/6 consume): `object RiskMetrics` with
  `dailyReturns(values: List<Double>): List<Double>`,
  `totalReturn(values: List<Double>): Double`,
  `annualizedReturn(values: List<Double>): Double` (252 periods/yr CAGR),
  `annualizedVolatility(values: List<Double>): Double` (SAMPLE stddev × √252),
  `maxDrawdown(values: List<Double>): Double`,
  `sharpe(values: List<Double>, riskFree: Double = 0.04): Double?` (null when volatility 0),
  `beta(portfolioValues: List<Double>, benchmarkValues: List<Double>): Double?` (index-paired from the END/recency; null when var==0 or <2 pairs),
  `alpha(portfolioValues: List<Double>, benchmarkValues: List<Double>, riskFree: Double = 0.04): Double?` (CAPM),
  `rebase(values: List<Double>): List<Double>` (value/first×100; empty/zero-first → emptyList()).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aptrade.shared.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertClose(expected: Double, actual: Double?, tol: Double = 1e-9) {
    assertTrue(actual != null && abs(expected - actual) < tol, "expected $expected got $actual")
}

class RiskMetricsTest {
    @Test fun dailyReturnsFromValues() {
        val r = RiskMetrics.dailyReturns(listOf(100.0, 110.0, 99.0))
        assertEquals(2, r.size); assertClose(0.1, r[0]); assertClose(-0.1, r[1])
    }
    @Test fun totalReturnAndDegenerate() {
        assertClose(0.5, RiskMetrics.totalReturn(listOf(100.0, 150.0)))
        assertClose(0.0, RiskMetrics.totalReturn(listOf(100.0)))
    }
    @Test fun flatSeriesHasZeroAnnualizedReturnAndVolatility() {
        val flat = listOf(100.0, 100.0, 100.0)
        assertClose(0.0, RiskMetrics.annualizedReturn(flat))
        assertClose(0.0, RiskMetrics.annualizedVolatility(flat))
    }
    @Test fun constantGrowthHasZeroVolatility() {
        // returns are all exactly 10% → sample stddev 0
        assertClose(0.0, RiskMetrics.annualizedVolatility(listOf(100.0, 110.0, 121.0)))
    }
    @Test fun maxDrawdownWorstPeakToTrough() {
        assertClose(0.5, RiskMetrics.maxDrawdown(listOf(100.0, 120.0, 60.0, 80.0)))
        assertClose(0.0, RiskMetrics.maxDrawdown(listOf(100.0, 110.0, 121.0)))
    }
    @Test fun sharpeIsNullWhenVolatilityZero() {
        assertNull(RiskMetrics.sharpe(listOf(100.0, 110.0, 121.0)))
    }
    @Test fun betaOfDoubledReturnsIsTwo() {
        // portfolio returns exactly 2× benchmark returns → beta 2
        val portfolio = listOf(100.0, 110.0, 99.0)      // returns +0.1, −0.1
        val benchmark = listOf(100.0, 105.0, 99.75)     // returns +0.05, −0.05
        assertClose(2.0, RiskMetrics.beta(portfolio, benchmark))
    }
    @Test fun betaNullOnFlatBenchmark() {
        assertNull(RiskMetrics.beta(listOf(100.0, 110.0, 99.0), listOf(100.0, 100.0, 100.0)))
    }
    @Test fun betaPairsFromTheEndWhenLengthsDiffer() {
        // benchmark has one extra leading point; the trailing returns still pair exactly
        val portfolio = listOf(100.0, 110.0, 99.0)
        val benchmark = listOf(400.0, 100.0, 105.0, 99.75)
        assertClose(2.0, RiskMetrics.beta(portfolio, benchmark))
    }
    @Test fun alphaOfIdenticalSeriesWithZeroRiskFreeIsZero() {
        val v = listOf(100.0, 108.0, 104.0, 111.0)
        assertClose(0.0, RiskMetrics.alpha(v, v, riskFree = 0.0))
    }
    @Test fun rebaseTo100() {
        assertEquals(listOf(100.0, 110.0, 90.0), RiskMetrics.rebase(listOf(50.0, 55.0, 45.0)))
        assertTrue(RiskMetrics.rebase(emptyList()).isEmpty())
    }
}
```

- [ ] **Step 2: RED**

```bash
./gradlew :shared:jvmTest --console=plain --tests "com.aptrade.shared.domain.RiskMetricsTest"
```
Expected: compile failure (`RiskMetrics` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package com.aptrade.shared.domain

import kotlin.math.pow
import kotlin.math.sqrt

/** Pure risk/return statistics, transcribed from Sources/APTradeDomain/RiskMetrics.swift.
 *  Double domain (statistics, not money). 252 trading periods per year. */
object RiskMetrics {
    const val TRADING_DAYS_PER_YEAR = 252

    fun dailyReturns(values: List<Double>): List<Double> {
        if (values.size < 2) return emptyList()
        return (1 until values.size).mapNotNull { i ->
            val prev = values[i - 1]
            if (prev == 0.0) null else values[i] / prev - 1.0
        }
    }

    fun totalReturn(values: List<Double>): Double {
        val first = values.firstOrNull() ?: return 0.0
        val last = values.lastOrNull() ?: return 0.0
        if (values.size < 2 || first <= 0.0) return 0.0
        return last / first - 1.0
    }

    fun annualizedReturn(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val total = totalReturn(values)
        val periods = values.size - 1
        return (1.0 + total).pow(TRADING_DAYS_PER_YEAR.toDouble() / periods) - 1.0
    }

    fun annualizedVolatility(values: List<Double>): Double {
        val returns = dailyReturns(values)
        if (returns.size < 2) return 0.0
        val mean = returns.average()
        val sampleVar = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
        return sqrt(sampleVar) * sqrt(TRADING_DAYS_PER_YEAR.toDouble())
    }

    fun maxDrawdown(values: List<Double>): Double {
        var peak = Double.NEGATIVE_INFINITY
        var worst = 0.0
        for (v in values) {
            if (v > peak) peak = v
            if (peak > 0.0) {
                val dd = (peak - v) / peak
                if (dd > worst) worst = dd
            }
        }
        return worst
    }

    fun sharpe(values: List<Double>, riskFree: Double = 0.04): Double? {
        val vol = annualizedVolatility(values)
        if (vol == 0.0) return null
        return (annualizedReturn(values) - riskFree) / vol
    }

    /** Index-paired from the END (recency pairing — macOS parity; documented calendar
     *  limitation for crypto-heavy portfolios, ported as-is). */
    fun beta(portfolioValues: List<Double>, benchmarkValues: List<Double>): Double? {
        val p = dailyReturns(portfolioValues)
        val b = dailyReturns(benchmarkValues)
        val n = minOf(p.size, b.size)
        if (n < 2) return null
        val pt = p.takeLast(n)
        val bt = b.takeLast(n)
        val pMean = pt.average()
        val bMean = bt.average()
        var cov = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            cov += (pt[i] - pMean) * (bt[i] - bMean)
            varB += (bt[i] - bMean) * (bt[i] - bMean)
        }
        if (varB == 0.0) return null
        return cov / varB
    }

    fun alpha(portfolioValues: List<Double>, benchmarkValues: List<Double>, riskFree: Double = 0.04): Double? {
        val b = beta(portfolioValues, benchmarkValues) ?: return null
        val rp = annualizedReturn(portfolioValues)
        val rb = annualizedReturn(benchmarkValues)
        return rp - (riskFree + b * (rb - riskFree))
    }

    fun rebase(values: List<Double>): List<Double> {
        val first = values.firstOrNull() ?: return emptyList()
        if (first == 0.0) return emptyList()
        return values.map { it / first * 100.0 }
    }
}
```

- [ ] **Step 4: GREEN + full shared suite**

```bash
./gradlew :shared:jvmTest --console=plain --rerun-tasks
```
Expected: **111** tests (100 + 11), 0 failures.

- [ ] **Step 5: Commit**

```bash
git add shared/src
git commit -m "feat(shared): risk metrics (returns, volatility, drawdown, Sharpe, beta, alpha) and rebase"
```

---

### Task 3: `FetchPerformanceReport` use case + vacuous-test upgrade

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchPerformanceReport.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchPerformanceReportTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PortfolioExportTest.kt` (replace the vacuous `zeroAllocationRendersAsZero` test)

**Interfaces:**
- Consumes: `FetchPortfolioPerformance` (existing: `execute(timeframe, sinceInception): List<PortfolioPerformancePoint>`), `MarketDataRepository.history(symbol, timeframe): List<PricePoint>` (existing), `RiskMetrics` (Task 2).
- Produces (Task 6 consumes):
  ```kotlin
  data class PerformanceMetrics(
      val totalReturn: Double, val annualizedReturn: Double, val volatility: Double,
      val maxDrawdown: Double, val sharpe: Double?, val beta: Double?, val alpha: Double?)
  data class PerformanceReport(
      val points: List<PortfolioPerformancePoint>,
      val benchmarkCloses: List<Double>?,          // null = benchmark unavailable
      val metrics: PerformanceMetrics)
  class FetchPerformanceReport(repository, fetchPortfolioPerformance) {
      @Throws(CancellationException::class)
      suspend fun execute(timeframe: Timeframe, benchmark: String, riskFree: Double = 0.04): PerformanceReport
  }
  ```

- [ ] **Step 1: Failing tests** (use the existing fake repository/store patterns from `PortfolioUseCasesTest.kt` — read that file first and mirror its fakes):
  1. `reportComputesMetricsFromEquityCurve` — seed a store/repo so the equity curve is a known series; assert totalReturn/maxDrawdown match `RiskMetrics` on that series (compute expected by calling `RiskMetrics` on the same literal values — the assertion pins the WIRING, the math itself is Task-2-tested).
  2. `benchmarkClosesComeFromRepositoryHistory` — fake repo returns a known benchmark history for symbol "SPY"; assert `benchmarkCloses` equals its closes and beta/alpha are non-null.
  3. `benchmarkFailureIsSwallowed` — fake repo throws for the benchmark symbol only; assert `benchmarkCloses == null`, beta/alpha null, and the portfolio points/other metrics still present (macOS parity: a bad benchmark never sinks the report).
  4. `cancellationRethrownFromBenchmarkFetch` — fake repo throws `CancellationException` for the benchmark; assert it propagates (not swallowed).
  5. `emptyPortfolioYieldsEmptyReport` — no positions → points empty, metrics all zero/null, benchmarkCloses null (do not fetch benchmark when there is no curve).
- [ ] **Step 2: RED** (`./gradlew :shared:jvmTest --tests "...FetchPerformanceReportTest"` — compile failure)
- [ ] **Step 3: Implement**

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.PortfolioPerformancePoint
import com.aptrade.shared.domain.RiskMetrics
import com.aptrade.shared.domain.Timeframe
import kotlin.coroutines.cancellation.CancellationException

data class PerformanceMetrics(
    val totalReturn: Double,
    val annualizedReturn: Double,
    val volatility: Double,
    val maxDrawdown: Double,
    val sharpe: Double?,
    val beta: Double?,
    val alpha: Double?,
)

data class PerformanceReport(
    val points: List<PortfolioPerformancePoint>,
    val benchmarkCloses: List<Double>?,
    val metrics: PerformanceMetrics,
)

/** Portfolio equity curve + benchmark overlay + risk metrics (macOS PerformanceSection parity).
 *  Benchmark fetch failure is swallowed (report survives); CancellationException always rethrows. */
class FetchPerformanceReport(
    private val repository: MarketDataRepository,
    private val fetchPortfolioPerformance: FetchPortfolioPerformance,
) {
    @Throws(CancellationException::class)
    suspend fun execute(timeframe: Timeframe, benchmark: String, riskFree: Double = 0.04): PerformanceReport {
        val points = fetchPortfolioPerformance.execute(timeframe)
        if (points.isEmpty()) {
            return PerformanceReport(points, null, PerformanceMetrics(0.0, 0.0, 0.0, 0.0, null, null, null))
        }
        val values = points.map { it.value.amount.doubleValue(false) }
        val benchmarkCloses: List<Double>? = try {
            repository.history(benchmark, timeframe).map { it.close.amount.doubleValue(false) }.ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val metrics = PerformanceMetrics(
            totalReturn = RiskMetrics.totalReturn(values),
            annualizedReturn = RiskMetrics.annualizedReturn(values),
            volatility = RiskMetrics.annualizedVolatility(values),
            maxDrawdown = RiskMetrics.maxDrawdown(values),
            sharpe = RiskMetrics.sharpe(values, riskFree),
            beta = benchmarkCloses?.let { RiskMetrics.beta(values, it) },
            alpha = benchmarkCloses?.let { RiskMetrics.alpha(values, it, riskFree) },
        )
        return PerformanceReport(points, benchmarkCloses, metrics)
    }
}
```
  NOTE: verify the actual field/name shape of `PricePoint` (`close` vs `price`) against `shared/.../domain/` before coding — mirror `FetchPortfolioPerformance`'s usage; if `PricePoint` carries `Money`, `.amount.doubleValue(false)` is the existing chart-pixel convention.
- [ ] **Step 4: Upgrade the vacuous test** in `PortfolioExportTest.kt`: replace `zeroAllocationRendersAsZero` with a case where holdings are NON-empty but one position's market value is zero (e.g. a quote priced 0 with cost-basis fallback disabled by a zero averageCost), so `renderFraction` actually runs and renders "0.0"/"0.00%" per the existing golden convention. Keep the test name meaningful (`zeroValuedHoldingRendersZeroAllocation`). Read the existing goldens in that file first and follow their exact format.
- [ ] **Step 5: GREEN + full shared suite**

```bash
./gradlew :shared:jvmTest --console=plain --rerun-tasks
```
Expected: **116** tests (111 + 5; the upgraded test replaces an existing one, net 0), 0 failures.
- [ ] **Step 6: Commit**

```bash
git add shared/src
git commit -m "feat(shared): FetchPerformanceReport use case with benchmark overlay and risk metrics"
```

---

### Task 4: designkit `formatMoney`/`signedMoney` + `DonutChart`

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/MoneyText.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/designkit/MoneyTextTest.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/DonutChart.kt` (composition — no unit tests, waiver)

**Interfaces:**
- Consumes: `java.math.BigDecimal`/`RoundingMode` (JVM desktop — allowed here; NOT in shared), existing `DK` colors.
- Produces (Tasks 5/6/7/8 consume):
  `fun formatMoney(amountText: String): String` — parses the exact-decimal string, HALF_EVEN to 2 decimals, comma grouping, "$" after the minus (e.g. `-$1,234.56`).
  `fun signedMoney(amountText: String): String` — `formatMoney` plus a leading "+" ONLY for strictly positive values (zero: no sign).
  `@Composable fun DonutChart(slices: List<DonutSlice>, modifier: Modifier = Modifier)` with `data class DonutSlice(val fraction: Double, val color: Color)` — Canvas arcs: inner radius 64% of outer, 1.5° inset between slices, rounded stroke caps, designed for a 150.dp frame.

- [ ] **Step 1: Failing tests**

```kotlin
package com.aptrade.desktop.designkit

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTextTest {
    @Test fun formatsGroupingAndTwoDecimals() {
        assertEquals("$1,234.50", formatMoney("1234.5"))
        assertEquals("$1,000,000.00", formatMoney("1000000"))
        assertEquals("$0.00", formatMoney("0"))
    }
    @Test fun roundsHalfEven() {
        assertEquals("$2.00", formatMoney("2.005"))     // 0 is even → stays
        assertEquals("$35,040.46", formatMoney("35040.455"))  // 5 is odd → up
        assertEquals("-$31.16", formatMoney("-31.155"))
    }
    @Test fun minusComesFromFormatterPlusFromSignedOnly() {
        assertEquals("-$1.00", formatMoney("-1"))
        assertEquals("+$254.46", signedMoney("254.455"))
        assertEquals("-$31.16", signedMoney("-31.155"))
        assertEquals("$0.00", signedMoney("0"))
    }
}
```

- [ ] **Step 2: RED** (`./gradlew :desktopApp:test --tests "com.aptrade.desktop.designkit.MoneyTextTest"` — compile failure)
- [ ] **Step 3: Implement `MoneyText.kt`**

```kotlin
package com.aptrade.desktop.designkit

import java.math.BigDecimal
import java.math.RoundingMode

/** macOS Money.formatted parity: en_US currency — "$", comma grouping, exactly 2 decimals,
 *  HALF_EVEN (NSNumberFormatter default), minus before the "$". */
fun formatMoney(amountText: String): String {
    val value = BigDecimal(amountText).setScale(2, RoundingMode.HALF_EVEN)
    val negative = value.signum() < 0
    val abs = value.abs().toPlainString()
    val whole = abs.substringBefore('.')
    val fraction = abs.substringAfter('.')
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-$" else "$") + grouped + "." + fraction
}

/** "+" only for strictly positive values (call-site convention on macOS); zero unsigned. */
fun signedMoney(amountText: String): String {
    val formatted = formatMoney(amountText)
    return if (BigDecimal(amountText).signum() > 0) "+$formatted" else formatted
}
```

- [ ] **Step 4: Implement `DonutChart.kt`** (composition, waiver — targets from `PortfolioView.allocationDonut`): Canvas in a 150.dp default frame; ring thickness = (1 − 0.64) × radius drawn as `drawArc(style = Stroke(width = ringWidth, cap = StrokeCap.Round))` on the mid-ring radius; slice sweep = fraction × 360 minus a 1.5° angular inset on each side; start at −90° (12 o'clock); skip slices with fraction ≤ 0; content slot (e.g. `content: @Composable BoxScope.() -> Unit`) so callers can center the "HOLDINGS" + value overlay inside the hole.
- [ ] **Step 5: GREEN + full desktop suite**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: **78** tests (75 + 3), 0 failures.
- [ ] **Step 6: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): designkit money formatter (macOS parity) and DonutChart"
```

---

### Task 5: PDF renderer (PDFBox) + export filename helper

**Files:**
- Modify: `desktopApp/build.gradle.kts` (add `implementation("org.apache.pdfbox:pdfbox:3.0.3")`)
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/PdfPortfolioRenderer.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/infra/PdfPortfolioRendererTest.kt`

**Interfaces:**
- Consumes: shared `PortfolioExport` snapshot (existing — read `shared/.../domain/PortfolioExport.kt` for the exact field names before coding; the CSV/JSON renderers in that file show which fields exist), `formatMoney`/`signedMoney` (Task 4).
- Produces (Task 8 consumes):
  `fun renderPortfolioPdf(export: PortfolioExport): ByteArray`
  `fun exportFileName(extension: String, epochSeconds: Long): String` → `APTrade-Portfolio-yyyy-MM-dd.<ext>` (UTC date).

- [ ] **Step 1: Failing tests** (PDFBox's `PDFTextStripper` extracts text from the rendered bytes — assert content, not pixels):
  1. `pdfContainsStatementHeaderAndSummary` — build a snapshot with one holding; extract text; assert it contains "Portfolio Statement", "Total Value", "Cash", "Holdings Value", "Unrealized P&L", and the holding's symbol.
  2. `pdfEmptyHoldingsMessage` — all-cash snapshot; assert text contains "No holdings — the account is all cash."
  3. `pdfTableHeaderColumns` — assert text contains "SYMBOL", "QTY", "AVG COST", "MKT VALUE", "ALLOC".
  4. `exportFileNameIsDateStamped` — `exportFileName("pdf", epochFor(2026-07-03))` == "APTrade-Portfolio-2026-07-03.pdf".
- [ ] **Step 2: RED** (compile failure)
- [ ] **Step 3: Implement.** Landscape US Letter = `PDRectangle(792f, 612f)`, 40pt margins. Content order per macOS `PDFExportRenderer`: account title (bold ~22), "Portfolio Statement · <generated>" line, summary block (label: value pairs — Total Value, Cash, Holdings Value, Day P&L signed, Unrealized P&L signed), holdings table with fixed column x-offsets (SYMBOL, NAME truncated to 26 chars, QTY, AVG COST, LAST, MKT VALUE, UNREAL P&L, ALLOC %.1f), new page when y runs past the bottom margin. Use PDFBox `PDPageContentStream` with `PDType1Font.HELVETICA`/`HELVETICA_BOLD`; money text via `formatMoney`/`signedMoney`; UNREAL P&L colored via `setNonStrokingColor` green/red to match macOS. Timestamp format "MMMM d, yyyy h:mm" via `java.time` (en_US locale).
- [ ] **Step 4: GREEN + full desktop suite**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: **82** tests (78 + 4), 0 failures.
- [ ] **Step 5: Commit**

```bash
git add desktopApp/build.gradle.kts desktopApp/src
git commit -m "feat(desktop): PDFBox portfolio PDF renderer with macOS statement layout"
```

---

### Task 6: `PortfolioViewModel` — formatting, performance report, quote merge, lazy parse

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModel.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/TradeFormState.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt` (expose `FetchPerformanceReport`)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (pass the new use case into the VM)
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModelTest.kt`, `TradeFormStateTest.kt`

**Interfaces:**
- Consumes: `FetchPerformanceReport` (Task 3), `formatMoney`/`signedMoney` (Task 4), `RiskMetrics.rebase` (Task 2).
- Produces (Task 8 renders):
  - `PortfolioUiState` gains: `benchmark: String = "SPY"`, `benchmarks: List<String> = listOf("SPY","QQQ","VTI")`, `performanceRebased: List<Double>`, `benchmarkRebased: List<Double>?` (null = unavailable), `metrics: MetricTexts?` where `data class MetricTexts(totalReturn, annualizedReturn, volatility, maxDrawdown, sharpe, beta, alpha: String)` (percent metrics via existing `formatPercent`; sharpe/beta/alpha 2-decimal plain, "—" when null).
  - `fun setBenchmark(symbol: String)` — refetches the report.
  - All money texts in state (`totalValueText`, `cashText`, `holdingsValueText`, `unrealizedText`, `realizedText`, `dayChangeText`, `HoldingRowUi.averageCostText/marketValueText/unrealizedText/priceText`, `TransactionRowUi.priceText`) now pass through `formatMoney`/`signedMoney` (signed for P&L/day-change texts, plain for the rest).
- Behavior changes (each with a test):
  1. Money texts formatted (update EVERY existing assertion that expected raw `amountText` — e.g. `"100000"` → `"$100,000.00"`; keep the zero-drop-immune literals, they still exercise real cents).
  2. `refreshQuotes` merges per-symbol instead of wholesale replace: `quotes = quotes + fetched` keyed by symbol — a poll returning a SUBSET keeps the last-good quote for missing symbols. Test: two-tick fake — tick 1 returns quotes for A and B, tick 2 only A; assert B's row still prices from tick 1 (not averageCost fallback).
  3. Performance report loads with the span AND benchmark: state carries rebased portfolio + benchmark series (`RiskMetrics.rebase`) and `MetricTexts`. Tests: success populates both series + metrics; repo failing ONLY on the benchmark symbol → `benchmarkRebased == null`, beta/alpha "—", portfolio series still present; `setBenchmark("QQQ")` triggers a refetch (fake counts calls).
  4. `TradeFormState`: parse once — `private val parsed: BigDecimal? by lazy { ... }`, `parsedQuantity()` returns it (behavior identical; existing tests must stay green unchanged).
- [ ] **Step 1: Write/adjust failing tests** for behaviors 1–3 (behavior 4 is covered by existing tests staying green).
- [ ] **Step 2: RED** — new tests fail, updated assertions fail against current raw texts.
- [ ] **Step 3: Implement** (mirror the existing confinement/polling patterns; CancellationException-first in every new catch; performance fetch stays one-shot per span/benchmark change like 6b.1, NOT on every poll tick).
- [ ] **Step 4: GREEN + full desktop suite**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: **87** tests (82 + 5 new: 1 merge + 3 report + 1 formatting-specific; existing tests updated in place), 0 failures. Report the actual measured count if a step legitimately lands a different number, with the delta explained.
- [ ] **Step 5: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): formatted money state, benchmark performance report, per-symbol quote merge"
```

---

### Task 7: Detail screen — stat grid, position panel, BUY/SELL, indicators UI

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/detail/DetailPane.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/detail/IndicatorOverlays.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/ui/AssetKindLabels.kt` (hoist `assetKindFromLabel` from Main.kt + DetailPane.kt; both call sites switch to it)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (use hoisted helper)

**Interfaces:**
- Consumes: `TechnicalIndicators` (Task 1), existing detail state (quote, candles, chart mode), existing `TradeDialog`/`tradeTarget` wiring from 6b.1, designkit (`StatTile`/card idioms, `formatMoney`/`signedMoney`, `formatPercent`, DK colors).
- Produces: the finished detail screen. UI composition — no unit tests (waiver). Fidelity source: `Sources/APTradeApp/AssetDetailView.swift` (read it for anything unspecified).

Targets:
- **BUY / SELL button** (user-mandated divergence): the single gold header button's label becomes "BUY / SELL"; it opens the existing TradeDialog with Buy preselected (dialog's internal toggle covers sell). No second button.
- **Indicator chips**: horizontally scrolling row under the existing chart controls; six multi-select chips, none on by default: "SMA 20", "EMA 12", "VWAP", "BB 20", "RSI 14", "MACD 12·26·9" — each with a leading 6dp colored dot. Selection is local UI state (macOS uses view @State). Colors: SMA=DK.gold; EMA=Color(0.30f,0.74f,0.86f); VWAP=DK.silver; BB=Color(0.38f,0.56f,0.95f); RSI=Color(0.65f,0.49f,0.92f); MACD=Color(0.90f,0.58f,0.26f); MACD signal=Color(0.84f,0.45f,0.67f).
- **Series source**: indicators compute from the candle series the pane already fetches for candle mode (closes for SMA/EMA/RSI/BB/MACD; H/L/C + volume for VWAP), via `remember(candles, selection)` — ensure the candle series is fetched even in Line mode when any indicator is active (reuse the existing fetch path; do NOT add a second data path).
- **Overlays** on the price chart (extend the existing chart canvas/composition): SMA/EMA solid 1.5dp polylines; VWAP dashed (PathEffect dash [5,3]); Bollinger: translucent fill (band color at 0.07 alpha) between upper/lower + solid upper/lower 1dp + middle dashed [3,3] at 0.6 alpha. Y-domain: pad data extremes 12% and widen to include BB extremes when active (match the existing chart's domain logic style).
- **RSI pane** (only when toggled): 90dp tall, below the chart; y-domain 0..100; dashed guide lines at 30/70 with "Oversold"/"Overbought" 9sp labels; RSI polyline in its color; header "RSI 14"; no x-axis.
- **MACD pane** (only when toggled): 100dp tall; histogram bars (DK.up/DK.down at 0.5 alpha, zero-centered), MACD line + signal line, small dot legend ("MACD", "Signal"); no x-axis.
- **KEY STATS card** (replaces the current 4-cell footer): 2-column grid in a card (surface at 0.5 alpha, 14dp radius, hairline border, 20dp padding, header "KEY STATS" with wide tracking): Last (`formatMoney`), Previous close (`formatMoney`), Day change (signedMoney of price−previousClose, changeColor), Day change % (`formatPercent`, changeColor), Symbol, Type (Stock/ETF/Crypto label).
- **YOUR POSITION card** (only when the symbol is held — the detail screen needs read access to the portfolio holding: pass the held-position row (or null) into DetailScreen from Main.kt via the existing PortfolioViewModel state; do NOT create a second store read path): Shares (plain quantity text), Average cost (`formatMoney`), Market value (`formatMoney`), Unrealized P&L (`signedMoney`, pnl color).
- Suite must stay green (no test changes): `./gradlew :desktopApp:test --console=plain --rerun-tasks` → **87**.
- Live check: run the app ≥60s; toggle each indicator on a real symbol; confirm no crash and overlays/panes render; kill cleanly.
- [ ] **Step 1: Implement per targets** (read AssetDetailView.swift first; nearest-macOS-sibling rule).
- [ ] **Step 2: Suite green (87), live check.**
- [ ] **Step 3: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): detail-screen indicators, key stats, position panel, BUY/SELL entry"
```

---

### Task 8: Portfolio tab — donut, Performance section, export chooser, polish

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioPane.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PerformanceSection.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/ExportSave.kt` (add `saveBinaryFile(suggestedName, bytes)` beside the existing text save)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (only if the export chooser state is hoisted like other dialogs — follow the TradeDialog pattern)

**Interfaces:**
- Consumes: `DonutChart` (Task 4), `renderPortfolioPdf`/`exportFileName` (Task 5), VM state additions (Task 6), existing `SpanBar`/`LineChart`/section-switcher composition.
- Produces: the finished Portfolio tab. UI composition — no unit tests (waiver). Fidelity sources: `Sources/APTradeApp/PortfolioView.swift` (donut), `Sources/APTradeApp/PerformanceSection.swift` (benchmark UI), `Sources/APTradeApp/RootView.swift` (export dialog).

Targets:
- **Export**: replace the two text buttons with ONE "Export…" text button opening a DK-styled chooser (small popup panel or DropdownMenu idiom consistent with the app): three entries "CSV", "JSON", "PDF" + implicit dismiss. CSV/JSON call the existing paths with their existing filenames; PDF calls `saveBinaryFile(exportFileName("pdf", now), renderPortfolioPdf(vm portfolio snapshot via existing export path))`. Chooser closes on selection and on Esc (consume BEFORE the window chain, TradeDialog pattern).
- **Allocation section**: donut row ABOVE the existing bars — `DonutChart` (150dp) with slices from `allocationByKind` in Stock/ETF/Crypto order (zero slices omitted; colors DK.gold/DK.goldDeep/DK.silver), center overlay "HOLDINGS" (8sp bold, wide tracking, textTertiary) over total holdings value (14sp bold, tnum, `formatMoney`); manual legend to the right: 9dp dot + class label + right-aligned percent (1 decimal). Existing "BY HOLDING" bars stay below.
- **Performance section**: below the existing P&L chart block, header "PERFORMANCE": segmented benchmark picker (SPY/QQQ/VTI, gold selected — KindToggle idiom), 200dp overlay chart with TWO polylines from state (`performanceRebased` gold, `benchmarkRebased` DK.silver/secondary), "Benchmark unavailable" (textTertiary) replacing the chart when `benchmarkRebased == null`; metric grid of 7 StatTiles: TOTAL RETURN, ANNUALIZED, VOLATILITY, MAX DRAWDOWN, SHARPE, BETA, ALPHA from `MetricTexts` (dashes render as "—").
- **MAX day-one message**: in the P&L chart block, when `span == Max`, transactions non-empty, and the performance series has <2 points: "Tracking starts today — performance appears after your first market day." (textTertiary) instead of the generic empty message.
- **ResetConfirmDialog** consumes Esc (same `onPreviewKeyEvent` pattern as TradeDialog; update the Main.kt Esc-ownership comment if it enumerates dialogs).
- Suite stays green (no test changes): **87**. Live check ≥60s: open Portfolio, switch benchmark, export a PDF to a temp location and confirm the file opens (non-zero bytes), toggle sections; kill cleanly.
- [ ] **Step 1: Implement per targets.**
- [ ] **Step 2: Suite green (87), live check.**
- [ ] **Step 3: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): allocation donut, performance section with benchmark, unified export with PDF"
```

---

### Task 9: Full regression + docs

**Files:**
- Modify: `README.md` (desktop section: indicators/performance/export/donut sentences; roadmap: 6b.2 shipped/pruned, 6b.3/6b.4 remain)

- [ ] **Step 1: Full sweep** (`:shared` changed → full Apple re-proof):

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :desktopApp:test --console=plain --rerun-tasks
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:assembleSharedReleaseXCFramework --console=plain
DEVELOPER_DIR=/Applications/Xcode.app swift test 2>&1 | grep -E "Executed [0-9]+ tests" | tail -1
xcodebuild -scheme APTradeLite-Package -destination 'generic/platform=iOS Simulator' ARCHS=arm64 build -quiet && echo IOS-OK
```
Expected: shared **116**, android **13**, desktop **87**, Swift **193** (untouched — park/restore `APTrade.xcodeproj` if the shadowing gotcha appears), xcframework 3 slices, IOS-OK. Report MEASURED numbers.
- [ ] **Step 2: README** — feature sentences accurate to what shipped (six indicators with macOS parameters; Performance section with SPY/QQQ/VTI benchmark + 7 risk metrics; single Export with CSV/JSON/PDF; allocation donut; formatted money); roadmap PRUNED (6b.2 out; 6b.3 macOS adoption, 6b.4 Android portfolio remain). Commit `docs: document desktop portfolio intelligence and fidelity pass (increment 6b.2)`.

---

## Self-review notes (applied)

- Spec coverage: R1→T7 (BUY/SELL); R2→T1+T7; R3→T7; R4→T5+T8; R5→T8 (message); R6→T2+T3+T6+T8; R7→T4+T8; R8→T4+T6 (+T7/T8 call sites); deferred-minor batch→T6 (merge, lazy), T7 (hoist), T8 (Esc), T3 (vacuous test). Regression/docs→T9.
- Type consistency: `TechnicalIndicators`/`BollingerBand`/`MacdPoint` (T1) consumed by T7; `RiskMetrics.rebase` (T2) consumed by T6; `PerformanceReport`/`PerformanceMetrics` (T3) consumed by T6; `formatMoney`/`signedMoney` (T4) consumed by T5/T6/T7/T8; `renderPortfolioPdf`/`exportFileName` (T5) consumed by T8; `MetricTexts`/`setBenchmark`/`performanceRebased`/`benchmarkRebased` (T6) consumed by T8.
- Known unknowns delegated with guardrails, not placeholders: `PricePoint` field shape (T3 — mirror FetchPortfolioPerformance), PortfolioExport field names (T5 — read the file), candle-fetch reuse in Line mode (T7 — explicit no-second-data-path rule).
- Counts: shared 90→100→111→116; desktop 75→78→82→87. Two tests replaced/updated in place (T3 vacuous upgrade, T6 assertion updates) — counts stated per task; implementers report measured numbers.
