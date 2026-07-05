package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.TradeError
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Executes a buy at the live quote price and persists the resulting [Portfolio].
 *
 *  The read-modify-write (load current portfolio -> apply [Portfolio.buying] -> save) runs
 *  under [portfolioMutex]: the portfolio is re-loaded from the store *inside* the lock (never
 *  taken from a possibly-stale caller snapshot), so serialized callers see the previous
 *  caller's write instead of overwriting it (the lost-update race deferred since 6b.4).
 *
 *  *** [portfolioMutex] MUST BE THE SAME [Mutex] INSTANCE THAT [SellAsset] RECEIVES. *** Both
 *  use cases perform load->validate->save against the SAME [PortfolioStore]; a buy and a sell
 *  in flight at the same time are two RMW cycles over one blob, and only ONE shared lock
 *  serializes them against each other. Two separate `Mutex()` instances (one per class) would
 *  only serialize buy-vs-buy and sell-vs-sell — a concurrent buy+sell could still clobber each
 *  other's write. This is why [AppGraph] / `PortfolioGraph` construct exactly ONE `Mutex` and
 *  hand that SAME instance to both this [BuyAsset] and [SellAsset] (and to every view model
 *  that shares this store) — see the shared commonTest
 *  `twoRacingBuysThroughOneInstanceBothLandNoLostUpdate`,
 *  `racingSellsThroughOneInstanceBothLandNoLostUpdate`, and (the case this note exists for)
 *  `racingBuyAndSellSharingOneMutexBothLandNoLostUpdate`.
 *
 *  The quote fetch stays OUTSIDE the lock — it is network I/O, and a mutex must never guard
 *  a network call. [Portfolio.buying]'s validation (funds check) depends on the *loaded*
 *  portfolio, not the quote, so it stays inside the lock alongside the load/save it validates
 *  against. */
class BuyAsset(
    private val repository: MarketDataRepository,
    private val store: PortfolioStore,
    private val portfolioMutex: Mutex,
) {
    @Throws(QuoteError::class, TradeError::class, CancellationException::class)
    suspend fun execute(asset: Asset, quantity: BigDecimal, epochSeconds: Long): Portfolio {
        val quote = repository.quotes(listOf(asset.symbol)).firstOrNull() ?: throw QuoteError.NotFound
        return portfolioMutex.withLock {
            val current = store.load() ?: Portfolio.starting()
            val updated = current.buying(asset, quantity, quote.price, epochSeconds)
            store.save(updated)
            updated
        }
    }
}
