package com.aptrade.shared.application

import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioPerformancePoint
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.performanceSeries
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
        return series
    }
}
