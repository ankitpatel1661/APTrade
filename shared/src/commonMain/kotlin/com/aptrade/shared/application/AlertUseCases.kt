package com.aptrade.shared.application

import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote

/** Loads all persisted price alerts. */
class LoadAlerts(private val store: AlertStore) {
    suspend fun execute(): List<PriceAlert> = store.load()
}

/** Appends a new alert for [symbol]/[condition], persists, and returns the full list. */
class CreatePriceAlert(private val store: AlertStore) {
    suspend fun execute(
        symbol: String,
        condition: AlertCondition,
        createdAtEpochSeconds: Long,
    ): List<PriceAlert> {
        val alerts = store.load() + PriceAlert(
            symbol = symbol,
            condition = condition,
            createdAtEpochSeconds = createdAtEpochSeconds,
        )
        store.save(alerts)
        return alerts
    }
}

/** Removes the alert matching [id], persists, and returns the remaining list. */
class RemovePriceAlert(private val store: AlertStore) {
    suspend fun execute(id: String): List<PriceAlert> {
        val alerts = store.load().filter { it.id != id }
        store.save(alerts)
        return alerts
    }
}

/**
 * Checks every untriggered alert against its symbol's live quote, marks matches as
 * triggered, persists the change, and notifies for each newly-triggered alert.
 *
 * The alert still triggers (so its in-app badge updates) even when the user has turned
 * off price-alert notifications — only the outward push via [notifier] is suppressed.
 * [isNotifyEnabled] is read once per evaluation (matching the macOS
 * `settings.load().priceAlerts` single read before the loop) and gates every
 * newly-triggered notify in this batch.
 *
 * Persist happens before notify: triggering is marked and saved for the whole batch
 * first, and only then are notifications sent one per newly-triggered alert — matching
 * the macOS ordering (mark triggered -> save if anything changed -> notify per alert
 * inside the same loop). Because save completes before any notify runs, a cancellation
 * during notify never leaves a triggered-but-unpersisted alert.
 */
class EvaluateAlerts(
    private val store: AlertStore,
    private val notifier: AlertNotifier,
    private val isNotifyEnabled: suspend () -> Boolean,
) {
    suspend fun execute(quotes: Map<String, Quote>): List<PriceAlert> {
        val alerts = store.load().toMutableList()
        val newlyTriggered = mutableListOf<Pair<PriceAlert, Quote>>()
        var changed = false

        for (index in alerts.indices) {
            val alert = alerts[index]
            val quote = quotes[alert.symbol]
            if (alert.isTriggered || quote == null || !alert.isMet(quote)) continue
            alerts[index] = alert.triggered()
            changed = true
            newlyTriggered += alert to quote
        }

        if (changed) store.save(alerts)

        val notificationsEnabled = isNotifyEnabled()
        for ((alert, quote) in newlyTriggered) {
            if (notificationsEnabled) {
                notifier.notify(alert, quote)
            }
        }

        return alerts
    }
}
