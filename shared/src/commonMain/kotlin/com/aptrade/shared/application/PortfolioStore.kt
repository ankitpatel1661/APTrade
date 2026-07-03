package com.aptrade.shared.application

import com.aptrade.shared.domain.Portfolio

/** Persistence port for the user's paper-trading portfolio. Implementations live per
 *  platform (JSON file on desktop). Load returns null when nothing was ever saved —
 *  callers treat that as the starting portfolio without persisting it. */
interface PortfolioStore {
    suspend fun load(): Portfolio?
    suspend fun save(portfolio: Portfolio)
}
