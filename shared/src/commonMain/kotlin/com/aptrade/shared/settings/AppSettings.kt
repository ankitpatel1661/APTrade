package com.aptrade.shared.settings

import com.aptrade.shared.l10n.AppLanguage

/** The app's persisted preferences, framework-free. The DTO carries defaults so future
 *  fields can be added without breaking old files.
 *
 *  Notification flags (increment 6d.1) mirror macOS's `AppSettings` defaults exactly:
 *  price alerts and order fills on by default, market open/close off (noisy for most
 *  users), the daily digest on, and email notifications off. [emailNotifications] is
 *  persisted-but-unwired by design — same as macOS, where no email delivery pipeline
 *  exists yet; the toggle exists for settings-screen parity only.
 *
 *  [isDarkMode] (increment 6d.2) defaults to true — dark is the shipped identity, and an
 *  old settings file written before this field existed must still load dark rather than
 *  flash into an unrequested light mode.
 *
 *  Security/privacy fields (increment 6d.2 Task 3) mirror macOS `AppSettings` exactly:
 *  [biometricLogin], [requireAuthOnLaunch], and [confirmTrades] default to true (matching
 *  the Swift `AppSettings`'s security posture), [analyticsSharing] defaults to false. HONEST
 *  PARITY (recorded): only [confirmTrades] is functional on desktop — it gates the
 *  in-dialog trade confirmation layer (see `TradeConfirm.kt` / `TradeDialog.kt`).
 *  [biometricLogin], [requireAuthOnLaunch], and [analyticsSharing] persist but drive nothing
 *  yet, same as macOS's SecurityPage rows for those three toggles are simulated-app-only.
 *
 *  [language] (increment 6e Task 5) defaults to [AppLanguage.English] — the shipped default;
 *  an old settings file written before this field existed must still load English rather than
 *  an unrequested language. */
data class AppSettings(
    val accent: AccentTheme = AccentTheme.ChampagneGold,
    val priceAlerts: Boolean = true,
    val orderFills: Boolean = true,
    val marketOpenClose: Boolean = false,
    val newsDigest: Boolean = true,
    val emailNotifications: Boolean = false,
    val isDarkMode: Boolean = true,
    val biometricLogin: Boolean = true,
    val requireAuthOnLaunch: Boolean = true,
    val confirmTrades: Boolean = true,
    val analyticsSharing: Boolean = false,
    val language: AppLanguage = AppLanguage.English,
)
