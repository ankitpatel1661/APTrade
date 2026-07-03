package com.aptrade.shared.application

import com.aptrade.shared.domain.Portfolio

class FetchPortfolio(private val store: PortfolioStore) {
    /** Returns the stored portfolio, or a fresh `Portfolio.starting()` when nothing was
     *  ever saved. The starting portfolio is NOT persisted here — the first trade or an
     *  explicit reset is what actually persists it. */
    suspend fun execute(): Portfolio = store.load() ?: Portfolio.starting()
}
