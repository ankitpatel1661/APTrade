package com.aptrade.shared.application

import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.TradeError
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.coroutines.cancellation.CancellationException

class SellAsset(
    private val repository: MarketDataRepository,
    private val store: PortfolioStore,
) {
    @Throws(QuoteError::class, TradeError::class, CancellationException::class)
    suspend fun execute(symbol: String, quantity: BigDecimal, epochSeconds: Long): Portfolio {
        val quote = repository.quotes(listOf(symbol)).firstOrNull() ?: throw QuoteError.NotFound
        val current = store.load() ?: Portfolio.starting()
        val updated = current.selling(symbol, quantity, quote.price, epochSeconds)
        store.save(updated)
        return updated
    }
}
