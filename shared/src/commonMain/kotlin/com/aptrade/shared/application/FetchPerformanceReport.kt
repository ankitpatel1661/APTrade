package com.aptrade.shared.application

import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioPerformancePoint
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.RiskMetrics
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.benchmarkTwinSeries
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
    ): PerformanceReport {
        val points = fetchPortfolioPerformance.execute(timeframe)
        if (points.isEmpty()) {
            return PerformanceReport(points, null, PerformanceMetrics(0.0, 0.0, 0.0, 0.0, null, null, null))
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
}
