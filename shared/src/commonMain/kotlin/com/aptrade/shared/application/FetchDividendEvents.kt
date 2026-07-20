package com.aptrade.shared.application

import com.aptrade.shared.domain.DividendEvent
import kotlin.coroutines.cancellation.CancellationException

/**
 * Dividend events for [symbol] on/after a given ex-date, delegated straight to the
 * repository. Mirrors [FetchCandles]'s structure: a thin use-case wrapper the Swift
 * layer calls through the shared-core bridge.
 */
class FetchDividendEvents(private val repository: MarketDataRepository) {
    @Throws(QuoteError::class, CancellationException::class)
    suspend fun execute(symbol: String, fromEpochSeconds: Long): List<DividendEvent> =
        repository.dividendEvents(symbol, fromEpochSeconds)
}
