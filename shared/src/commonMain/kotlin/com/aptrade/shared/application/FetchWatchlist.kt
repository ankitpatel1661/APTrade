package com.aptrade.shared.application

import com.aptrade.shared.domain.WatchlistEntry

class FetchWatchlist(
    private val store: WatchlistStore,
    private val defaults: List<WatchlistEntry>,
) {
    /** First launch (empty store) seeds — and persists — the default watchlist. */
    suspend fun execute(): List<WatchlistEntry> {
        val stored = store.load()
        if (stored.isNotEmpty()) return stored
        store.save(defaults)
        return defaults
    }
}
