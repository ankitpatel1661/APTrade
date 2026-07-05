package com.aptrade.shared.domain

/**
 * Trims [items] to the exact rolling window of [windowDurationSeconds], anchored to the
 * newest item's timestamp rather than wall-clock now — Yahoo's own ranges count trading
 * days, not calendar time, so anchoring to "now" would leak stale data outside market
 * hours, on weekends, or after holidays. Anchoring to the newest bar always yields the
 * latest window.
 */
fun <T> clampToWindow(items: List<T>, windowDurationSeconds: Long, epochSeconds: (T) -> Long): List<T> {
    val newest = items.maxOfOrNull(epochSeconds) ?: return items
    val cutoff = newest - windowDurationSeconds
    return items.filter { epochSeconds(it) >= cutoff }
}
