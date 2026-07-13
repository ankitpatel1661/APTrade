package com.aptrade.desktop.l10n

import com.aptrade.shared.l10n.L10n
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [trf]'s placeholder translation (`%@` → `%s`, `%lld` → `%d`) and its MANDATORY
 * `Locale.ROOT` formatting — a DE/IT/ES default locale would render decimals with a comma
 * (`1,3`) instead of a period (`1.3`), corrupting numbers that must stay locale-invariant even
 * while the surrounding catalog prose is translated. Every case below runs under a simulated
 * non-English active language so a Locale.ROOT regression would actually surface here.
 */
class TrfTest {

    @Test
    fun `percent-s substitutes a string arg via the transcribed percent-at placeholder`() {
        val original = LocalizationManager.current.value
        try {
            LocalizationManager.current.value = AppLanguage.German
            // ConfirmBuyTitleFormat german = "%@ %@ kaufen?" — two %@ string substitutions.
            assertEquals(
                "10 AAPL kaufen?",
                trf(L10n.Key.ConfirmBuyTitleFormat, "10", "AAPL"),
            )
        } finally {
            LocalizationManager.current.value = original
        }
    }

    @Test
    fun `percent-lld and percent-d substitute an integer arg`() {
        val original = LocalizationManager.current.value
        try {
            LocalizationManager.current.value = AppLanguage.Italian
            // AdvancingFormat italian = "%d in rialzo".
            assertEquals("7 in rialzo", trf(L10n.Key.AdvancingFormat, 7))
        } finally {
            LocalizationManager.current.value = original
        }
    }

    @Test
    fun `percent-1f formats a float with a period under Locale ROOT, never a locale comma`() {
        val original = LocalizationManager.current.value
        try {
            // Spanish is a comma-decimal locale in java.util.Locale's own tables (es-ES) — the
            // language most likely to expose a missing Locale.ROOT pin if trf ever regressed
            // to String.format's implicit default-locale overload.
            LocalizationManager.current.value = AppLanguage.Spanish
            // EffectiveHoldingsFormat spanish = "%.1f posiciones efectivas".
            assertEquals(
                "1.3 posiciones efectivas",
                trf(L10n.Key.EffectiveHoldingsFormat, 1.3),
            )
        } finally {
            LocalizationManager.current.value = original
        }
    }

    @Test
    fun `percent-percent survives as a literal percent sign`() {
        val original = LocalizationManager.current.value
        try {
            LocalizationManager.current.value = AppLanguage.English
            // PercentMoveSummaryFormat english = "Moves %@%% in a day" — %@ substitutes the
            // number, %% must collapse to a single literal '%' (Java Formatter semantics),
            // not double up or get consumed by the %@→%s rewrite.
            assertEquals(
                "Moves 5% in a day",
                trf(L10n.Key.PercentMoveSummaryFormat, "5"),
            )
        } finally {
            LocalizationManager.current.value = original
        }
    }

    @Test
    fun `tr resolves the active language and updates when LocalizationManager current changes`() {
        // Moved from L10nCatalogTest (desktopApp/.../l10n/L10nCatalogTest.kt, deleted) when
        // the catalog itself moved to shared: tr()/LocalizationManager are Compose-backed
        // desktop-only types with no commonMain equivalent, so this tr()-layer coverage stays
        // here rather than moving with the catalog-completeness assertions.
        val original = LocalizationManager.current.value
        try {
            LocalizationManager.current.value = AppLanguage.German
            assertEquals("Beobachtungsliste", tr(L10n.Key.Watchlist))

            LocalizationManager.current.value = AppLanguage.Spanish
            assertEquals("Lista de seguimiento", tr(L10n.Key.Watchlist))

            LocalizationManager.current.value = AppLanguage.Italian
            assertEquals("Lista di controllo", tr(L10n.Key.Watchlist))

            LocalizationManager.current.value = AppLanguage.English
            assertEquals("Watchlist", tr(L10n.Key.Watchlist))
        } finally {
            LocalizationManager.current.value = original
        }
    }
}
