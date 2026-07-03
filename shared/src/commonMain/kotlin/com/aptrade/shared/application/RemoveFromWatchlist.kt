package com.aptrade.shared.application

import com.aptrade.shared.domain.WatchlistEntry

class RemoveFromWatchlist(private val store: WatchlistStore) {
    /** Removes `symbol` if present. Returns the resulting list either way. */
    suspend fun execute(symbol: String): List<WatchlistEntry> {
        val current = store.load()
        val updated = current.filterNot { it.symbol == symbol }
        if (updated.size == current.size) return current
        store.save(updated)
        return updated
    }
}
