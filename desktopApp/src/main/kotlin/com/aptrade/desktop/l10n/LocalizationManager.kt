package com.aptrade.desktop.l10n

import androidx.compose.runtime.mutableStateOf
import com.aptrade.shared.l10n.L10n

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

/**
 * Formats [key]'s resolved string against [args], translating the Swift-style placeholders
 * transcribed verbatim into the catalog (`%@` for a string arg, `%lld` for an integer) into
 * `java.util.Formatter` equivalents (`%s`, `%d`) before delegating to [String.format].
 *
 * [java.util.Locale.ROOT] is MANDATORY here, not cosmetic: `String.format` without an explicit
 * locale uses the JVM default locale, and a DE/IT/ES default would reformat `%.2f`-style
 * decimals with a comma instead of a period (`1,25` instead of `1.25`), corrupting numbers
 * that are supposed to stay locale-invariant (prices, percentages) even while the surrounding
 * prose is translated. Locale.ROOT pins the formatting conventions regardless of which
 * [AppLanguage] is active — this is a JVM-only desktopApp file, not commonMain, so
 * `java.util.Locale`/`String.format` are safe to use directly.
 */
fun trf(key: L10n.Key, vararg args: Any?): String {
    val template = tr(key)
        .replace("%@", "%s")
        .replace("%lld", "%d")
    return String.format(java.util.Locale.ROOT, template, *args)
}
