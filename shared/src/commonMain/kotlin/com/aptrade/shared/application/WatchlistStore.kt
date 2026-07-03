package com.aptrade.shared.application

import com.aptrade.shared.domain.WatchlistEntry

/** Persistence port for the user's ordered watchlist. Implementations live per
 *  platform (JSON file on desktop). Load returns an empty list when nothing was
 *  ever saved. */
interface WatchlistStore {
    suspend fun load(): List<WatchlistEntry>
    suspend fun save(entries: List<WatchlistEntry>)
}
