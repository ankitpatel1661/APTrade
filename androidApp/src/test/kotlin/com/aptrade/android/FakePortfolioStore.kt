package com.aptrade.android

import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.domain.Portfolio

/** Lambda-configurable in-memory [PortfolioStore] for ViewModel tests, mirroring
 *  [FakeMarketDataRepository]'s style. By default [load] returns whatever was last
 *  [save]d (null until the first save); override [loadImpl] to inject a fixed portfolio
 *  or simulate a corrupt/missing store returning null. */
class FakePortfolioStore : PortfolioStore {
    var saved: Portfolio? = null
        private set

    var loadImpl: suspend () -> Portfolio? = { saved }
    var saveImpl: suspend (Portfolio) -> Unit = { saved = it }

    var saveCallCount = 0
        private set

    override suspend fun load(): Portfolio? = loadImpl()

    override suspend fun save(portfolio: Portfolio) {
        saveCallCount++
        saveImpl(portfolio)
    }
}
