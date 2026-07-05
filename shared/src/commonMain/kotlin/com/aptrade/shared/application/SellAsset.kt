package com.aptrade.shared.application

import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.TradeError
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Executes a sell at the live quote price and persists the resulting [Portfolio].
 *
 *  The read-modify-write (load current portfolio -> apply [Portfolio.selling] -> save) runs
 *  under [portfolioMutex]: the portfolio is re-loaded from the store *inside* the lock (never
 *  taken from a possibly-stale caller snapshot), so serialized callers see the previous
 *  caller's write instead of overwriting it (the lost-update race deferred since 6b.4).
 *
 *  *** [portfolioMutex] MUST BE THE SAME [Mutex] INSTANCE THAT [BuyAsset] RECEIVES. *** Both
 *  use cases perform load->validate->save against the SAME [PortfolioStore]; a sell and a buy
 *  in flight at the same time are two RMW cycles over one blob, and only ONE shared lock
 *  serializes them against each other. Two separate `Mutex()` instances (one per class) would
 *  only serialize sell-vs-sell and buy-vs-buy — a concurrent sell+buy could still clobber each
 *  other's write. This is why [AppGraph] / `PortfolioGraph` construct exactly ONE `Mutex` and
 *  hand that SAME instance to both this [SellAsset] and [BuyAsset] (and to every view model
 *  that shares this store) — see the shared commonTest
 *  `twoRacingBuysThroughOneInstanceBothLandNoLostUpdate`,
 *  `racingSellsThroughOneInstanceBothLandNoLostUpdate`, and (the case this note exists for)
 *  `racingBuyAndSellSharingOneMutexBothLandNoLostUpdate`.
 *
 *  The quote fetch stays OUTSIDE the lock — it is network I/O, and a mutex must never guard
 *  a network call. [Portfolio.selling]'s validation (shares-held check) depends on the
 *  *loaded* portfolio, not the quote, so it stays inside the lock alongside the load/save it
 *  validates against. */
class SellAsset(
    private val repository: MarketDataRepository,
    private val store: PortfolioStore,
    private val portfolioMutex: Mutex,
) {
    @Throws(QuoteError::class, TradeError::class, CancellationException::class)
    suspend fun execute(symbol: String, quantity: BigDecimal, epochSeconds: Long): Portfolio {
        val quote = repository.quotes(listOf(symbol)).firstOrNull() ?: throw QuoteError.NotFound
        return portfolioMutex.withLock {
            val current = store.load() ?: Portfolio.starting()
            val updated = current.selling(symbol, quantity, quote.price, epochSeconds)
            store.save(updated)
            updated
        }
    }
}
