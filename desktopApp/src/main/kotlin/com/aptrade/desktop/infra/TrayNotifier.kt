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
    // Dividend never reaches an order-fill notification in practice (paper trades only fire
    // Buy/Sell fills; a dividend credit surfaces through notifyDividend instead — see
    // DesktopMarketActivityCoordinator's notifyDividendsDue). The exhaustive `when` (Kotlin
    // twin of Swift review fix 3c0ac79) still names a real verb for this branch rather than
    // silently reusing "Bought", so an exhaustive match here can never accidentally mislabel
    // an order fill.
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

    /**
     * Delivers an earnings-today notification.
     *
     * Unlike [notifyDigest] (hardcoded English title, plain-text body), both [title] and
     * [body] arrive pre-localized here — Main.kt resolves `L10n.Key.EarningsTodayTitle`/
     * `EarningsTodayBodyFmt` via `tr`/`trf` before calling in, so this class (like
     * [notifyFill]) performs no formatting of its own, just the `sendNotification` call.
     */
    suspend fun notifyEarnings(title: String, body: String) {
        trayState.sendNotification(Notification(title, body))
    }

    /**
     * Delivers a pie-contribution notification (executed or skipped for insufficient cash).
     *
     * Mechanical twin of [notifyEarnings]: both [title] and [body] arrive pre-localized —
     * `Main.kt` resolves `L10n.Key.NotifPieExecutedTitle`/`NotifPieExecutedBody` or
     * `NotifPieSkippedTitle`/`NotifPieSkippedBody` via `tr`/`trf` before calling in, so this
     * class performs no formatting of its own here either.
     */
    suspend fun notifyPieContribution(title: String, body: String) {
        trayState.sendNotification(Notification(title, body))
    }

    /**
     * Delivers a dividend notification (cash credit, DRIP reinvestment, or a collapsed
     * backfill summary).
     *
     * Mechanical twin of [notifyPieContribution]/[notifyEarnings]: both [title] and [body]
     * arrive pre-localized — `Main.kt` resolves `L10n.Key.NotifDividendTitle` and one of
     * `NotifDividendCashBodyFmt`/`NotifDividendDripBodyFmt`/`NotifDividendBackfillBodyFmt` via
     * `tr`/`trf` before calling in, so this class performs no formatting of its own here
     * either.
     */
    suspend fun notifyDividend(title: String, body: String) {
        trayState.sendNotification(Notification(title, body))
    }
}
