package com.aptrade.shared.application

import com.aptrade.shared.domain.WatchlistEntry

class AddToWatchlist(private val store: WatchlistStore) {
    /** Appends `entry` (symbol trimmed/uppercased). Blank or duplicate symbols are
     *  a no-op. Returns the resulting list either way. */
    suspend fun execute(entry: WatchlistEntry): List<WatchlistEntry> {
        val current = store.load()
        val symbol = entry.symbol.trim().uppercase()
        if (symbol.isEmpty() || current.any { it.symbol == symbol }) return current
        val updated = current + entry.copy(symbol = symbol)
        store.save(updated)
        return updated
    }
}
