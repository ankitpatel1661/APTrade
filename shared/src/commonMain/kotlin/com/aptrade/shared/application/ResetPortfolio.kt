package com.aptrade.shared.application

import com.aptrade.shared.domain.Portfolio

class ResetPortfolio(private val store: PortfolioStore) {
    /** Discards the current portfolio and persists a fresh `Portfolio.starting()`. */
    suspend fun execute(): Portfolio {
        val fresh = Portfolio.starting()
        store.save(fresh)
        return fresh
    }
}
