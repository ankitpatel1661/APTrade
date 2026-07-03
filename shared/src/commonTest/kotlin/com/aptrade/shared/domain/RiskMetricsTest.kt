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
    @Test fun maxDrawdownWorstPeakToTroughIsNegative() {
        // Swift convention: worst v/peak − 1 (negative). 60/120 − 1 = −0.5
        assertClose(-0.5, RiskMetrics.maxDrawdown(listOf(100.0, 120.0, 60.0, 80.0)))
        // Swift's own test fixture: 90/120 − 1 = −0.25
        assertClose(-0.25, RiskMetrics.maxDrawdown(listOf(100.0, 120.0, 90.0, 110.0)))
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
        // Element-wise tolerance: 55.0/50.0*100.0 is 110.00000000000001 in IEEE-754,
        // so exact list equality would fail on representation noise, not semantics.
        val rebased = RiskMetrics.rebase(listOf(50.0, 55.0, 45.0))
        assertEquals(3, rebased.size)
        assertClose(100.0, rebased[0]); assertClose(110.0, rebased[1]); assertClose(90.0, rebased[2])
        assertTrue(RiskMetrics.rebase(emptyList()).isEmpty())
        // Swift parity: non-positive base → series returned unchanged
        assertEquals(listOf(0.0, 5.0), RiskMetrics.rebase(listOf(0.0, 5.0)))
    }
}
