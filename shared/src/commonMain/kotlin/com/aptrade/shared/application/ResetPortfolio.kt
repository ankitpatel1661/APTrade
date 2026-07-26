package com.aptrade.shared.application

import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Discards the current portfolio and persists a fresh [Portfolio.starting] opened at
 *  [startingCash]. Goals are deliberately NOT touched.
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
 *  This use case took a [GoalStore] until M11.1 UAT F1 and cleared every goal on reset. The user
 *  ruled that wrong on 2026-07-27: resetting starting capital is "start over with more money",
 *  not "abandon my plan" — a $120,000 value goal survives a reset to $1,000,000 and simply reads
 *  as reached. The dependency is REMOVED rather than left unused so the clearing cannot be
 *  re-armed by a later edit; `ResetPortfolioTest.resetLeavesEveryGoalIntact` is the behavioural
 *  record. The Swift twin (`Sources/APTradeApplication/PortfolioUseCases.swift`) dropped its own
 *  `goalStore` parameter in the same change. */
class ResetPortfolio(
    private val store: PortfolioStore,
    private val portfolioMutex: Mutex,
) {
    suspend fun execute(startingCash: Money): Portfolio = portfolioMutex.withLock {
        val fresh = Portfolio.starting(startingCash)
        store.save(fresh)
        fresh
    }
}
