package com.aptrade.shared.application

import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioPerformancePoint
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.performanceSeries
import com.aptrade.shared.domain.resampledDaily
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

/** Reconstructs the portfolio's value / unrealized-P&L curve over a timeframe from real
 *  historical prices, fetching each held symbol's history concurrently. */
class FetchPortfolioPerformance(
    private val repository: MarketDataRepository,
    private val store: PortfolioStore,
) {
    @Throws(CancellationException::class)
    suspend fun execute(timeframe: Timeframe, sinceInception: Boolean = false): List<PortfolioPerformancePoint> {
        val portfolio = store.load() ?: Portfolio.starting()
        if (portfolio.positions.isEmpty()) return emptyList()

        val histories: Map<String, List<PricePoint>> = coroutineScope {
            portfolio.positions
                .map { position ->
                    val symbol = position.asset.symbol
                    symbol to async {
                        try {
                            repository.history(symbol, timeframe)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                .associate { (symbol, deferred) -> symbol to deferred.await() }
        }

        var series = portfolio.performanceSeries(histories)
        if (sinceInception) {
            val firstEpoch = portfolio.transactions.minOfOrNull { it.epochSeconds }
            if (firstEpoch != null) {
                val inceptionDay = (firstEpoch / 86_400) * 86_400
                val trimmed = series.filter { it.epochSeconds >= inceptionDay }
                if (trimmed.isNotEmpty()) series = trimmed
            }
        }
        // Resample to one point per UTC day for every timeframe except OneDay, which is
        // meant to show the intraday grid. This kills the overnight/weekend forward-fill
        // flats from performanceSeries' union-of-all-symbols-candles grid (see
        // resampledDaily's KDoc) and — since FetchPerformanceReport's benchmark twin derives
        // its curveDates from these same points — fixes the benchmark staircase too, for
        // free, from this single insertion point.
        if (timeframe != Timeframe.OneDay) series = series.resampledDaily()
        return series
    }
}
