package com.aptrade.shared.l10n

/**
 * The app's interface language. Pure value type — the actual translated strings live in
 * the [L10n] catalog (the way `AccentTheme` is domain but its color ramp is presentation).
 * Only the endonym `displayName` is carried here, for the picker.
 *
 * Transcribed verbatim from `Sources/APTradeDomain/AppLanguage.swift`.
 */
enum class AppLanguage(val code: String, val displayName: String) {
    English(code = "en", displayName = "English"),
    German(code = "de", displayName = "Deutsch"),
    Italian(code = "it", displayName = "Italiano"),
    Spanish(code = "es", displayName = "Español"),
}
