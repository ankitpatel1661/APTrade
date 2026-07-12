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
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.formatPercent
import com.aptrade.shared.application.AlertNotifier
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.l10n.L10n

/** The channel every price-alert notification posts to. Created lazily, once, the first
 *  time a notification is about to go out — mirrors [AndroidAlertNotifier]'s own
 *  ensure-then-post ordering, and means a process that never triggers an alert never
 *  touches [NotificationManager] at all. */
internal const val PRICE_ALERTS_CHANNEL_ID = "price_alerts"

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

    override suspend fun notify(alert: PriceAlert, quote: Quote) {
        ensureChannel()

        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val notification = NotificationCompat.Builder(context, PRICE_ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(formatAlertTitle(alert.symbol))
            .setContentText(formatAlertBody(alert, quote))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(alert.id.hashCode(), notification)
    }
}
