package com.aptrade.shared.domain

/** One saved watchlist row. Name and kind are captured at add time (search results
 *  carry both) so the UI never needs a per-symbol profile fetch on launch. */
data class WatchlistEntry(
    val symbol: String,
    val name: String,
    val kind: AssetKind,
)
