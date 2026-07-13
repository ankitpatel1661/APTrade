package com.aptrade.android.l10n

import androidx.compose.runtime.mutableStateOf
import com.aptrade.shared.l10n.AppLanguage
import com.aptrade.shared.l10n.L10n

/** Active language as Compose snapshot state — every tr() reader recomposes on change.
 *  Mirrors desktopApp's LocalizationManager over the same shared catalog. Persistence
 *  wiring arrives with the Language settings page (Task 7). */
object LocalizationManager {
    val current = mutableStateOf(AppLanguage.English)
}

/**
 * Resolves [key] against the active language. Reading `LocalizationManager.current.value`
 * makes every composable that calls [tr] recompose when the language changes.
 */
fun tr(key: L10n.Key): String = L10n.string(key, LocalizationManager.current.value)

/** Swift-style placeholders (%@, %lld) → java.util.Formatter, Locale.ROOT pinned so
 *  numeric formatting stays locale-invariant while prose translates (same rationale as
 *  the desktop trf()). */
fun trf(key: L10n.Key, vararg args: Any?): String {
    val template = tr(key).replace("%@", "%s").replace("%lld", "%d")
    return String.format(java.util.Locale.ROOT, template, *args)
}
