package com.aptrade.shared.application

import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioPerformancePoint
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.RiskMetrics
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.benchmarkTwinSeries
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.coroutines.cancellation.CancellationException

data class PerformanceMetrics(
    val totalReturn: Double,
    val annualizedReturn: Double,
    val volatility: Double,
    val maxDrawdown: Double,
    val sharpe: Double?,
    val beta: Double?,
    val alpha: Double?,
    /** Total return measured from the portfolio's ACTUAL opening balance
     *  ([Portfolio.startingCash]) to its latest curve value, rather than from the curve's own
     *  first point — the consumer that makes `startingCash` earn its place (M11.2 kickoff
     *  decision 4a.1, carry-notes §2.1). Swift currently carries the field with no reader; this
     *  is a BACKPORT CANDIDATE.
     *
     *  Span-INDEPENDENT by construction: it reads the latest point's value, which is "now"
     *  regardless of which timeframe produced the curve. `null` when there is no curve to read a
     *  latest value from, or when the opening balance is non-positive (division would be
     *  meaningless, and a fabricated number here is exactly what carry-notes §2.3 forbids).
     *
     *  REQUIRED, deliberately not defaulted: every construction site must decide what it means. */
    val sinceInceptionReturn: Double?,
)

data class PerformanceReport(
    val points: List<PortfolioPerformancePoint>,
    val benchmarkCloses: List<Double>?,
    val metrics: PerformanceMetrics,
    /** Cash-flow replay twin: what the same trades would be worth in the benchmark instead
     *  of the current holdings (see `benchmarkTwinSeries` KDoc for the exact semantics).
     *  Null when there's no benchmark history to replay against. Aligned 1:1 with [points]
     *  when present. */
    val benchmarkTwinValues: List<Money>? = null,
)

/** Portfolio equity curve + benchmark overlay + risk metrics (macOS PerformanceSection parity).
 *  Benchmark fetch failure is swallowed (report survives); CancellationException always rethrows.
 *
 *  The caller supplies the [Portfolio] whose transactions/cash source the benchmark twin — it is
 *  a required argument to [execute], compile-enforcing a real portfolio (no silently-null twin).
 *  Presentation callers pass the portfolio they already own; save-then-return coherence
 *  guarantees it matches disk at report time. */
class FetchPerformanceReport(
    private val repository: MarketDataRepository,
    private val fetchPortfolioPerformance: FetchPortfolioPerformance,
) {
    @Throws(CancellationException::class)
    suspend fun execute(
        timeframe: Timeframe,
        benchmark: String,
        portfolio: Portfolio,
        riskFree: Double = 0.04,
        /** Forwarded to [FetchPortfolioPerformance]: trims the curve to the account's first
         *  transaction day. Opt-in, so omitting it preserves the previous behaviour exactly. */
        sinceInception: Boolean = false,
    ): PerformanceReport {
        val points = fetchPortfolioPerformance.execute(timeframe, sinceInception)
        val sinceInceptionReturn = sinceInceptionReturn(portfolio, points)
        if (points.isEmpty()) {
            return PerformanceReport(
                points,
                null,
                PerformanceMetrics(0.0, 0.0, 0.0, 0.0, null, null, null, sinceInceptionReturn),
            )
        }
        val values = points.map { it.value.amount.doubleValue(false) }
        // Align the benchmark window to the (post-gate) portfolio curve start so the overlay
        // and beta/alpha describe the same span of time.
        val curveStart = points.first().epochSeconds
        val benchmarkPoints: List<PricePoint>? = try {
            repository.history(benchmark, timeframe)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val benchmarkCloses: List<Double>? = benchmarkPoints
            ?.filter { it.epochSeconds >= curveStart }
            ?.map { it.close.amount.doubleValue(false) }
            ?.ifEmpty { null }
        val metrics = PerformanceMetrics(
            totalReturn = RiskMetrics.totalReturn(values),
            annualizedReturn = RiskMetrics.annualizedReturn(values),
            volatility = RiskMetrics.annualizedVolatility(values),
            maxDrawdown = RiskMetrics.maxDrawdown(values),
            sharpe = RiskMetrics.sharpe(values, riskFree),
            beta = benchmarkCloses?.let { RiskMetrics.beta(values, it) },
            alpha = benchmarkCloses?.let { RiskMetrics.alpha(values, it, riskFree) },
            sinceInceptionReturn = sinceInceptionReturn,
        )
        // The twin needs the UNTRIMMED benchmark points (trades may predate the portfolio
        // curve's start), unlike benchmarkCloses above which is head-trimmed to curveStart.
        val benchmarkTwinValues: List<Money>? = if (benchmarkPoints.isNullOrEmpty()) {
            null
        } else {
            benchmarkTwinSeries(
                transactions = portfolio.transactions,
                benchmarkPoints = benchmarkPoints,
                cash = portfolio.cash,
                curveDates = points.map { point -> point.epochSeconds },
            )
        }
        return PerformanceReport(points, benchmarkCloses, metrics, benchmarkTwinValues)
    }

    /** See [PerformanceMetrics.sinceInceptionReturn]. */
    private fun sinceInceptionReturn(
        portfolio: Portfolio,
        points: List<PortfolioPerformancePoint>,
    ): Double? {
        val opening = portfolio.startingCash.amount
        if (opening <= BigDecimal.ZERO) return null
        val latest = points.lastOrNull()?.value?.amount ?: return null
        return latest.divide(opening, MONEY_MATH).doubleValue(false) - 1.0
    }
}
