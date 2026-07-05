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
 *  under a [Mutex]: the portfolio is re-loaded from the store *inside* the lock (never taken
 *  from a possibly-stale caller snapshot), so a single shared [SellAsset] instance serializes
 *  concurrent sells against the same store — the second caller sees the first's write instead
 *  of overwriting it (the lost-update race deferred since 6b.4). This is why [AppGraph] /
 *  `PortfolioGraph` hand the same instance to every view model.
 *
 *  KNOWN LIMITATION: this mutex is private to [SellAsset] and is a SEPARATE lock from
 *  [BuyAsset]'s own private mutex, even though both act on the same [PortfolioStore]. A sell
 *  and a buy in flight concurrently are therefore NOT serialized against each other and can
 *  still lose an update (recorded for a future task; out of scope here per the 6d.2 Task 4
 *  brief, which specifies "BuyAsset and SellAsset EACH gain a private Mutex").
 *
 *  The quote fetch stays OUTSIDE the lock — it is network I/O, and a mutex must never guard
 *  a network call. [Portfolio.selling]'s validation (shares-held check) depends on the
 *  *loaded* portfolio, not the quote, so it stays inside the lock alongside the load/save it
 *  validates against. */
class SellAsset(
    private val repository: MarketDataRepository,
    private val store: PortfolioStore,
) {
    private val mutex = Mutex()

    @Throws(QuoteError::class, TradeError::class, CancellationException::class)
    suspend fun execute(symbol: String, quantity: BigDecimal, epochSeconds: Long): Portfolio {
        val quote = repository.quotes(listOf(symbol)).firstOrNull() ?: throw QuoteError.NotFound
        return mutex.withLock {
            val current = store.load() ?: Portfolio.starting()
            val updated = current.selling(symbol, quantity, quote.price, epochSeconds)
            store.save(updated)
            updated
        }
    }
}
