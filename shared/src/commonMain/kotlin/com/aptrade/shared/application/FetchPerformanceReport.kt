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
        // Align the benchmark window to the (post-gate) portfolio curve start so the overlay
        // and beta/alpha describe the same span of time.
        val curveStart = points.first().epochSeconds
        val benchmarkCloses: List<Double>? = try {
            repository.history(benchmark, timeframe)
                .filter { it.epochSeconds >= curveStart }
                .map { it.close.amount.doubleValue(false) }
                .ifEmpty { null }
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
