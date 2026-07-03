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

    /** NEGATIVE fraction: worst v/peak − 1 (Swift parity — e.g. −0.25 for a 25% drawdown). */
    fun maxDrawdown(values: List<Double>): Double {
        var peak = Double.NEGATIVE_INFINITY
        var worst = 0.0
        for (v in values) {
            if (v > peak) peak = v
            if (peak > 0.0) {
                val dd = v / peak - 1.0
                if (dd < worst) worst = dd
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
        if (first <= 0.0) return values                       // Swift parity: degenerate base → unchanged
        return values.map { it / first * 100.0 }
    }
}
