package com.aptrade.shared.application

import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Discards the current portfolio and persists a fresh [Portfolio.starting] opened at
 *  [startingCash], then clears every goal.
 *
 *  Serialized like [BuyAsset] — under the SAME [portfolioMutex] instance every other
 *  portfolio/pie writer holds (see [BuyAsset]'s co-holder doc). A reset overwriting the portfolio
 *  while a buy/sell/pie mutation is mid-flight would otherwise silently discard it (or vice
 *  versa: an in-flight catch-up day landing after a reset could leave a fresh portfolio saddled
 *  with stale pie ledger claims). There is no load to guard — the whole body runs inside the lock
 *  — mirroring the Swift twin's `ResetPortfolioUseCase`
 *  (`Sources/APTradeApplication/PortfolioUseCases.swift`), which wraps the same save in its
 *  `TradeSerializer.run`.
 *
 *  [goalStore] is REQUIRED, unlike the Swift twin's `goalStore: GoalStore? = nil` (which exists
 *  there only so pre-goals construction sites kept compiling). Carry-notes §4 records a no-op
 *  goal store silently discarding saves as a live hazard: a construction site that forgets to
 *  inject the real store must fail to COMPILE here, not fail silently at runtime. */
class ResetPortfolio(
    private val store: PortfolioStore,
    private val portfolioMutex: Mutex,
    private val goalStore: GoalStore,
) {
    suspend fun execute(startingCash: Money): Portfolio = portfolioMutex.withLock {
        val fresh = Portfolio.starting(startingCash)
        store.save(fresh)
        // A fresh practice run must not inherit targets set against the old balance.
        goalStore.save(emptyList())
        fresh
    }
}
