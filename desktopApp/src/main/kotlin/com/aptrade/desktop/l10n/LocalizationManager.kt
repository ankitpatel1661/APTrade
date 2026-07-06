package com.aptrade.desktop.l10n

import androidx.compose.runtime.mutableStateOf

/**
 * Holds the active [AppLanguage] as Compose snapshot state — the same pattern as
 * `DK.accent`/`DK.isDark` (see `desktopApp/.../designkit/DK.kt`): every reader of [current]
 * recomposes on a language change with no call-site changes. Defaults to English, the
 * shipped default; persistence wiring arrives with the Language page (Task 5).
 *
 * Extraction-ready: this file and the rest of the `l10n` package depend on nothing but
 * Kotlin and `compose.runtime`.
 */
object LocalizationManager {
    val current = mutableStateOf(AppLanguage.English)
}

/**
 * Resolves [key] against the active language. Reading `LocalizationManager.current.value`
 * makes every composable that calls `tr()` recompose when the language changes.
 */
fun tr(key: L10n.Key): String = L10n.string(key, LocalizationManager.current.value)
