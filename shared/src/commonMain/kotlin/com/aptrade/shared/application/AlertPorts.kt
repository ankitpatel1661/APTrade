package com.aptrade.shared.application

import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote

/** Persistence port for the user's price alerts. Implementations live per platform
 *  (JSON file on desktop). Load returns an empty list when nothing was ever saved. */
interface AlertStore {
    suspend fun load(): List<PriceAlert>
    suspend fun save(alerts: List<PriceAlert>)
}

/** Delivers a triggered alert outside the app's own state — e.g. a native
 *  notification. Kept as a port so the application layer never imports a
 *  platform notification framework. */
interface AlertNotifier {
    suspend fun notify(alert: PriceAlert, quote: Quote)
}
