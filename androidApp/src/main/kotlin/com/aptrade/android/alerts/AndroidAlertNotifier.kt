package com.aptrade.android.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aptrade.android.R
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.formatPercent
import com.aptrade.shared.application.AlertNotifier
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.l10n.L10n

/** The channel every price-alert notification posts to. Created lazily, once, the first
 *  time a notification is about to go out — mirrors [AndroidAlertNotifier]'s own
 *  ensure-then-post ordering, and means a process that never triggers an alert never
 *  touches [NotificationManager] at all. */
internal const val PRICE_ALERTS_CHANNEL_ID = "price_alerts"

/** The channel every order-fill notification posts to (spec A2 — desktop
 *  `AppGraphNotifyOrderFill`/`TrayNotifier.notifyFill` parity). Separate from
 *  [PRICE_ALERTS_CHANNEL_ID] so a user can mute one without muting the other, same
 *  channel-per-notification-kind shape as the price-alerts channel above. Created lazily,
 *  once, the first time an order fill is about to be posted. */
internal const val ORDER_FILLS_CHANNEL_ID = "order_fills"

/** The channel every market open/close notification posts to (Task 8 — desktop
 *  `DesktopMarketActivityCoordinator`/`TrayNotifier.notifyMarketStatus` parity). Separate
 *  channel per notification kind, same reasoning as [ORDER_FILLS_CHANNEL_ID]. */
internal const val MARKET_STATUS_CHANNEL_ID = "market_status"

/** The channel every daily-digest notification posts to (Task 8 — desktop
 *  `TrayNotifier.notifyDigest` parity). */
internal const val DAILY_DIGEST_CHANNEL_ID = "daily_digest"

/** The channel every earnings-today notification posts to (Task 8 — desktop
 *  `TrayNotifier.notifyEarnings` parity). */
internal const val EARNINGS_CHANNEL_ID = "earnings_reports"

/** The channel every pie-contribution (executed/skipped) notification posts to (M7.3
 *  Task 3 — desktop `TrayNotifier.notifyPieContribution` parity). Mechanical twin of
 *  [EARNINGS_CHANNEL_ID]: separate channel per notification kind, same reasoning. */
internal const val PLAN_CONTRIBUTIONS_CHANNEL_ID = "plan_contributions"

// --- Pure formatters -------------------------------------------------------------
//
// Transcribed verbatim from desktop's TrayNotifier (`desktopApp/.../infra/TrayNotifier.kt`),
// which itself transcribes Sources/APTradeInfrastructure/UserNotificationAlertNotifier.swift —
// the delivery mechanism differs per platform (tray notification vs. system notification here)
// but the words the user reads are identical. Kept top-level and pure (no Context/Android
// framework dependency) so they stay unit-testable without Robolectric/instrumentation.

internal fun formatAlertTitle(symbol: String): String = "$symbol alert"

internal fun formatAlertBody(alert: PriceAlert, quote: Quote): String =
    "${alert.condition.summary} — now ${quote.price.formatted} (${formatPercent(quote.changePercent)})"

internal fun formatOrderFillTitle(): String = "Order filled"

internal fun formatOrderFillBody(
    side: TradeSide,
    symbol: String,
    quantityText: String,
    amountFormatted: String,
): String {
    // Dividend never reaches an order-fill notification today (paper trades only fire Buy/
    // Sell fills) — the Dividend branch is minimal neutral staging so this stays exhaustive.
    // M8.3 owns the real dividend notification flow.
    val verb = when (side) {
        TradeSide.Buy -> "Bought"
        TradeSide.Sell -> "Sold"
        TradeSide.Dividend -> "Dividend"
    }
    return "$verb $quantityText ${symbol.uppercase()} for $amountFormatted"
}

internal fun formatMarketStatusTitle(opened: Boolean): String = if (opened) "Market open" else "Market closed"

internal fun formatMarketStatusBody(opened: Boolean): String = if (opened) {
    "US equities are now open for regular trading."
} else {
    "US equities have closed for the day."
}

internal fun formatDigestTitle(): String = "Daily digest"

/**
 * Delivers triggered price alerts as Android system notifications — the Android counterpart
 * to desktop's `TrayNotifier` / macOS's `UserNotificationAlertNotifier`. Implements the shared
 * [AlertNotifier] port so `EvaluateAlerts` (application layer) never imports an Android
 * framework type.
 *
 * Notification-permission handling (Android 13 / API 33+'s `POST_NOTIFICATIONS` runtime
 * permission): [MainActivity][com.aptrade.android.MainActivity] requests the permission once at
 * launch, but a user can still deny it (or the app can run below API 33, where the permission
 * doesn't exist at all). Either way this class checks [ContextCompat.checkSelfPermission] right
 * before posting and silently no-ops on a missing grant — denial is non-fatal: `EvaluateAlerts`
 * still marks the alert triggered and persists it (the in-app badge/list still update), only the
 * outward system notification is skipped. This mirrors how the shared `isNotifyEnabled` gate in
 * `EvaluateAlerts` already suppresses notify without blocking evaluation.
 *
 * [context] should be an application context (the caller — `AppGraph` — passes
 * `context.applicationContext`) since this notifier is expected to outlive any single Activity.
 */
class AndroidAlertNotifier(private val context: Context) : AlertNotifier {

    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(context)
    }

    private var channelCreated = false
    private var orderFillsChannelCreated = false
    private var marketStatusChannelCreated = false
    private var dailyDigestChannelCreated = false
    private var earningsChannelCreated = false
    private var planContributionsChannelCreated = false

    private fun ensureChannel() {
        if (channelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            PRICE_ALERTS_CHANNEL_ID,
            tr(L10n.Key.PriceAlerts),
            NotificationManager.IMPORTANCE_HIGH,
        )
        notificationManager.createNotificationChannel(channel)
        channelCreated = true
    }

    private fun ensureOrderFillsChannel() {
        if (orderFillsChannelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ORDER_FILLS_CHANNEL_ID,
            tr(L10n.Key.OrderFills),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(channel)
        orderFillsChannelCreated = true
    }

    private fun ensureMarketStatusChannel() {
        if (marketStatusChannelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            MARKET_STATUS_CHANNEL_ID,
            tr(L10n.Key.MarketOpenAndClose),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(channel)
        marketStatusChannelCreated = true
    }

    private fun ensureDailyDigestChannel() {
        if (dailyDigestChannelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DAILY_DIGEST_CHANNEL_ID,
            tr(L10n.Key.DailyNewsDigest),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(channel)
        dailyDigestChannelCreated = true
    }

    private fun ensureEarningsChannel() {
        if (earningsChannelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            EARNINGS_CHANNEL_ID,
            tr(L10n.Key.EarningsReportsToggle),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(channel)
        earningsChannelCreated = true
    }

    private fun ensurePlanContributionsChannel() {
        if (planContributionsChannelCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            PLAN_CONTRIBUTIONS_CHANNEL_ID,
            tr(L10n.Key.PieContributionsToggle),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(channel)
        planContributionsChannelCreated = true
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    override suspend fun notify(alert: PriceAlert, quote: Quote) {
        ensureChannel()
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(context, PRICE_ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(formatAlertTitle(alert.symbol))
            .setContentText(formatAlertBody(alert, quote))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(alert.id.hashCode(), notification)
    }

    /**
     * Delivers an order-fill notification for a just-completed trade — the Android
     * counterpart to desktop's `TrayNotifier.notifyFill` (spec A2). Posted to its own
     * [ORDER_FILLS_CHANNEL_ID] channel (not [PRICE_ALERTS_CHANNEL_ID]) so muting one
     * notification kind never mutes the other. Not part of the shared [AlertNotifier]
     * port — mirrors `TrayNotifier`, which also exposes `notifyFill` as a plain extra
     * method rather than a shared-module contract (no `OrderFillNotifier` port exists
     * in :shared). Gated upstream by `AppGraph.buildNotifyOrderFill`'s
     * `settings.orderFills` check; this method itself only handles the runtime
     * notification-permission gate, same as [notify].
     */
    suspend fun notifyFill(side: TradeSide, symbol: String, quantityText: String, amountFormatted: String) {
        ensureOrderFillsChannel()
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(context, ORDER_FILLS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(formatOrderFillTitle())
            .setContentText(formatOrderFillBody(side, symbol, quantityText, amountFormatted))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify("$symbol-$side".hashCode(), notification)
    }

    /**
     * Delivers a market open/close notification (Task 8) — the Android counterpart to
     * desktop's `TrayNotifier.notifyMarketStatus`. [opened] is a plain `Boolean` (the same
     * shape desktop's method takes: `MarketStatus` is already unwrapped to a Boolean by
     * `ScheduledNotification` before this call). Posted to its own
     * [MARKET_STATUS_CHANNEL_ID] channel, same one-channel-per-kind shape as [notifyFill].
     * Called from [com.aptrade.android.alerts.AndroidMarketActivityCoordinator] once per
     * open/close transition.
     */
    suspend fun notifyMarketStatus(opened: Boolean) {
        ensureMarketStatusChannel()
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(context, MARKET_STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(formatMarketStatusTitle(opened))
            .setContentText(formatMarketStatusBody(opened))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(MARKET_STATUS_CHANNEL_ID.hashCode(), notification)
    }

    /**
     * Delivers the daily digest notification (Task 8) — the Android counterpart to
     * desktop's `TrayNotifier.notifyDigest`. [summary] is pre-built plain text
     * (`AndroidMarketActivityCoordinator.digestSummary()` — top-3 watchlist movers by
     * `abs(changePercent)`); this method performs no further formatting on it. A fixed
     * notification id (channel-scoped) so a later digest simply replaces the earlier one
     * rather than stacking multiple digest notifications in the tray.
     */
    suspend fun notifyDigest(summary: String) {
        ensureDailyDigestChannel()
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(context, DAILY_DIGEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(formatDigestTitle())
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(DAILY_DIGEST_CHANNEL_ID.hashCode(), notification)
    }

    /**
     * Delivers an earnings-today notification (Task 8) — the Android counterpart to
     * desktop's `TrayNotifier.notifyEarnings`. Unlike [notifyDigest] (hardcoded English
     * title, plain-text body), both [title] and [body] arrive pre-localized here — the
     * `AppGraph.marketActivityCoordinator` factory resolves `L10n.Key.EarningsTodayTitle`/
     * `EarningsTodayBodyFmt` via `tr`/`trf` before calling in, so this method performs no
     * formatting of its own, just the notification post. The notification id is keyed off
     * [body] (which embeds the symbol) rather than a fixed id, since a single tick can
     * deliver multiple distinct earnings notifications (one per owned event) that must not
     * clobber each other in the tray.
     */
    suspend fun notifyEarnings(title: String, body: String) {
        ensureEarningsChannel()
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(context, EARNINGS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(body.hashCode(), notification)
    }

    /**
     * Delivers a pie-contribution (executed/skipped) notification (M7.3 Task 3) — the
     * Android counterpart to desktop's `TrayNotifier.notifyPieContribution`. Mechanical
     * twin of [notifyEarnings]: both [title] and [body] arrive pre-localized — the
     * `AppGraph.marketActivityCoordinator` factory resolves `L10n.Key.NotifPieExecutedTitle`/
     * `NotifPieExecutedBody` or `NotifPieSkippedTitle`/`NotifPieSkippedBody` via `tr`/`trf`
     * before calling in, so this method performs no formatting of its own, just the
     * notification post. The notification id is keyed off [body] (which embeds the pie
     * name), same reasoning as [notifyEarnings]'s id: a single tick can deliver multiple
     * distinct contribution outcomes (one per due pie) that must not clobber each other
     * in the tray.
     */
    suspend fun notifyPieContribution(title: String, body: String) {
        ensurePlanContributionsChannel()
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(context, PLAN_CONTRIBUTIONS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(body.hashCode(), notification)
    }
}
