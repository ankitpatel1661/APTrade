package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent

/** Supplies earnings-calendar events in a day range (inclusive, `yyyy-MM-dd`). */
interface EarningsCalendarRepository {
    suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent>
}

/** No-key fallback: the Calendar tab renders its needs-key state, holidays still show. */
object EmptyEarningsRepository : EarningsCalendarRepository {
    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> = emptyList()
}
