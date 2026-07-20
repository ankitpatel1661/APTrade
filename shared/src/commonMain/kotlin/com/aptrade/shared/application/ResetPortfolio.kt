package com.aptrade.shared.application

import com.aptrade.shared.domain.Portfolio
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Discards the current portfolio and persists a fresh [Portfolio.starting].
 *
 *  Serialized like [BuyAsset] — under the SAME [portfolioMutex] instance every other
 *  portfolio/pie writer holds (see [BuyAsset]'s co-holder doc). A reset overwriting the
 *  portfolio while a buy/sell/pie mutation is mid-flight would otherwise silently discard it
 *  (or vice versa: an in-flight catch-up day landing after a reset could leave a fresh
 *  portfolio saddled with stale pie ledger claims). There is no load to guard — the whole
 *  body runs inside the lock — mirroring the Swift twin's `ResetPortfolioUseCase`
 *  (`Sources/APTradeApplication/PortfolioUseCases.swift`), which wraps the same save in its
 *  `TradeSerializer.run`. */
class ResetPortfolio(
    private val store: PortfolioStore,
    private val portfolioMutex: Mutex,
) {
    suspend fun execute(): Portfolio = portfolioMutex.withLock {
        val fresh = Portfolio.starting()
        store.save(fresh)
        fresh
    }
}
