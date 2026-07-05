package com.aptrade.desktop.infra

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.shared.application.AlertNotifier
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.TradeSide

// --- Pure formatters -------------------------------------------------------------
//
// Extracted from TrayNotifier so message shapes are unit-testable without a TrayState/
// AWT dependency. Every string here is transcribed to match the macOS source exactly
// (Sources/APTradeInfrastructure/UserNotificationAlertNotifier.swift) — the delivery
// mechanism differs (RECORDED DIVERGENCE below) but the words the user reads do not.

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
    val verb = if (side == TradeSide.Buy) "Bought" else "Sold"
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
 * Delivers alert, order-fill, and market-activity notifications via the OS tray icon.
 *
 * RECORDED DIVERGENCE (scope decision for increment 6d.1): macOS delivers notifications
 * through `UNUserNotificationCenter` (Sources/APTradeInfrastructure/UserNotificationAlertNotifier.swift).
 * Compose Multiplatform for Desktop has no equivalent cross-platform notification-center
 * API; the portable primitive it exposes is `TrayState.sendNotification`, which requires
 * a `Tray` composable (carrying the app icon) mounted in the `ApplicationScope` — see
 * Main.kt. This is a deliberate, recorded scope decision, not an oversight: no
 * `UNUserNotificationCenter`-equivalent exists off-macOS, so desktop notification
 * delivery is Compose Tray notifications for Windows/Linux/desktop-JVM generally.
 *
 * Order-fill and market-event delivery are exposed as plain suspend methods here
 * (not shared-module ports) because :shared has no `OrderFillNotifier`/
 * `MarketEventNotifier` equivalent yet — wiring those call sites into the ViewModels is
 * increment 6d.1 Task 4's job. This class only needs to be ready to receive the calls.
 */
class TrayNotifier(private val trayState: TrayState) : AlertNotifier {

    override suspend fun notify(alert: PriceAlert, quote: Quote) {
        trayState.sendNotification(
            Notification(formatAlertTitle(alert.symbol), formatAlertBody(alert, quote)),
        )
    }

    /**
     * Delivers an order-fill notification for a just-completed trade.
     *
     * The pre-formatted [quantityText]/[amountFormatted] String params (rather than the
     * domain `Quantity`/`Money` types) are a deliberate boundary shape, confirmed by Task
     * 4's real call site (`PortfolioViewModel.notifyFillSafely`): no shared port dictates
     * this signature (there is no `OrderFillNotifier` in :shared), and formatting
     * (`Quantity.toStringExpanded`, `formatMoney`) happens in the pure formatters/
     * ViewModel layer, not here — this class only renders the already-formatted strings
     * into a `Notification`. Kept as-is; the shape works cleanly at the real call site.
     */
    suspend fun notifyFill(side: TradeSide, symbol: String, quantityText: String, amountFormatted: String) {
        trayState.sendNotification(
            Notification(formatOrderFillTitle(), formatOrderFillBody(side, symbol, quantityText, amountFormatted)),
        )
    }

    /**
     * Delivers a market open/close notification.
     *
     * Called from [com.aptrade.desktop.DesktopMarketActivityCoordinator] once per
     * open/close transition (Task 4) — a plain `Boolean`, since `MarketStatus` is a
     * :shared domain enum the coordinator already unwraps via `ScheduledNotification`.
     */
    suspend fun notifyMarketStatus(opened: Boolean) {
        trayState.sendNotification(
            Notification(formatMarketStatusTitle(opened), formatMarketStatusBody(opened)),
        )
    }

    /**
     * Delivers the daily digest notification.
     *
     * [summary] is pre-built plain text (Task 4:
     * `DesktopMarketActivityCoordinator.digestSummary()` — top-3 watchlist movers by
     * `abs(changePercent)`); this class performs no further formatting on it.
     */
    suspend fun notifyDigest(summary: String) {
        trayState.sendNotification(Notification(formatDigestTitle(), summary))
    }
}
