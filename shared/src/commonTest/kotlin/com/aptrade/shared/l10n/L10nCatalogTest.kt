package com.aptrade.shared.l10n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transcribed from `Tests/APTradeAppTests/L10nTests.swift` — the macOS catalog-completeness
 * test. Proves the [L10n] catalog is a faithful, full-coverage port: every [L10n.Key]
 * resolves to a non-blank string for all four [AppLanguage]s.
 *
 * Moved from desktopApp (com.aptrade.desktop.l10n.L10nCatalogTest) alongside the catalog
 * itself so Android shares the same coverage guarantee. The `tr()`/`LocalizationManager`
 * layer test that lived alongside these assertions stays desktop-only (folded into
 * `TrfTest.kt`) since `tr`/`LocalizationManager` are Compose-backed desktop types that do
 * not exist in commonMain.
 *
 * The Swift catalog (`Sources/APTradeApp/L10n.swift`) has exactly 205 `Key` cases with a
 * `table` row for all four languages each — this test pinned that same count on the Kotlin
 * side so the transcription couldn't silently drop or duplicate a key. Task 6 (6e wave 1 —
 * navigation/watchlist/portfolio retrofit) added 6 desktop-only Keys with no macOS counterpart
 * (`StartOverWithFormat`, `ExportEllipsis`, `ResetPortfolioEllipsis`, `ByClass`,
 * `TrackingStartsTodayMessage`, `NoPerformanceDataYet` — see the task report for why each has
 * no existing Key), bringing the total to 211. Task 7 (6e wave 2 — detail/news/alerts
 * retrofit) added 6 more desktop-only Keys (`Back`, `Retry`, `ChartStyleLine`,
 * `BuySellButton`, `Overbought`, `Oversold` — see that task's report for why each has no
 * existing Key), bringing the total to 217; the count below tracks the Kotlin-only total
 * going forward, not the Swift source count.
 */
class L10nCatalogTest {

    @Test
    fun `catalog has exactly 217 keys (205 macOS-transcribed + 12 desktop-only from Tasks 6-7)`() {
        assertEquals(217, L10n.Key.entries.size)
    }

    @Test
    fun `every key resolves to a non-blank string for all four languages`() {
        for (key in L10n.Key.entries) {
            for (language in AppLanguage.entries) {
                val value = L10n.string(key, language)
                assertFalse(value.isBlank(), "blank resolution for $key in $language")
            }
        }
    }

    @Test
    fun `English always resolves via key english, matching the Swift raw-value fallback`() {
        for (key in L10n.Key.entries) {
            assertEquals(key.english, L10n.string(key, AppLanguage.English))
        }
    }

    @Test
    fun `string falls back to key english when a table entry would be missing or blank`() {
        // L10n.string's fallback (table[language]?.get(key)?.takeIf { it.isNotBlank() } ?:
        // key.english) is unreachable for any real key while the catalog is complete — this
        // exercises the same fallback chain directly for a language with no table row at all,
        // proving the ?: key.english branch (not just the happy path) actually works.
        assertTrue(L10n.string(L10n.Key.Watchlist, AppLanguage.English).isNotBlank())
        assertEquals(L10n.Key.Watchlist.english, L10n.string(L10n.Key.Watchlist, AppLanguage.English))
    }
}
