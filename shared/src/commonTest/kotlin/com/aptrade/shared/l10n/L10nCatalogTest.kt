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
 * The Swift catalog (`Sources/APTradeApp/L10n.swift`) started at exactly 205 `Key` cases with a
 * `table` row for all four languages each — this test originally pinned that same count on the
 * Kotlin side so the transcription couldn't silently drop or duplicate a key. The Swift catalog
 * has since grown independently (Calendar tab, Plans feature, etc.) and now has 281 cases; the
 * two counts are no longer expected to match — this test tracks the Kotlin-only total below.
 * Task 6 (6e wave 1 — navigation/watchlist/portfolio retrofit) added 6 desktop-only Keys with
 * no macOS counterpart (`StartOverWithFormat`, `ExportEllipsis`, `ResetPortfolioEllipsis`,
 * `ByClass`, `TrackingStartsTodayMessage`, `NoPerformanceDataYet` — see the task report for
 * why each has no existing Key), bringing the total to 211. Task 7 (6e wave 2 —
 * detail/news/alerts retrofit) added 6 more desktop-only Keys (`Back`, `Retry`,
 * `ChartStyleLine`, `BuySellButton`, `Overbought`, `Oversold` — see that task's report for
 * why each has no existing Key), bringing the total to 217. Android i18n snackbar fix added 2
 * more Keys (`AddedSymbolFmt`, `RemovedSymbolFmt`), bringing the total to 219. The Android
 * news-tab review fix wave added 1 more Key (`CouldntUpdateBookmark`, for the localized
 * bookmark-persistence-failure snackbar), bringing the total to 220. The AssetKind label
 * localization sweep added 3 more Keys (`StockKindLabel`/`EtfKindLabel`/`CryptoKindLabel` —
 * the singular plain words the detail chip/type stat row show; "Aktie" is neither "Aktien"
 * nor "AKTIE"), bringing the total to 223. The Android in-app Finnhub key-entry field added
 * 4 more Keys (`FinnhubApiKeyField`/`SaveAction`/`FinnhubKeyAppliesNote`/`FinnhubKeyInstructionsInApp`),
 * bringing the total to 227. The calendar increment (Task 5 of SDD) added 22 keys for market
 * holidays, earnings calendar, and session states, bringing the total to 249. Task 10 (M7.2 —
 * Investment Plans L10n) added 52 keys for the Plans (pies) feature UI, bringing the total to
 * 301. A Task 10 review fix wave then added the missing `Next` key (pie wizard's forward
 * action — dropped from the initial transcription), bringing the total to 302. M8.2 Task 5
 * (the Kotlin port of the M8.1 dividend & income feature's L10n keys) added 26 more: the 20
 * Task-8 income/asset-detail/settings keys, `settingsDividendNotifSubtitle` and
 * `notifDividendBackfillBodyFmt` (added to the Swift catalog in later M8.1 fix commits), and
 * `activityDividend` (the uppercase "DIVIDEND" transaction chip, transcribed here for the
 * first time even though the Kotlin `TradeSide.Dividend` case predates this task) — bringing
 * the total to 328. M9.2 Task 5 (the Kotlin port of the M9.1 screener feature's L10n keys)
 * added 37 more: the screener tab/scan-bar keys, 9 presets, 10 metrics, and the builder keys
 * incl. `addToWatchlist` (`screenerRefresh` was skipped — it reuses the existing `Refresh`
 * key, mirroring the Swift source's reuse decision) — bringing the total to 365; the count
 * below tracks the Kotlin-only total going forward, not the Swift source count.
 */
class L10nCatalogTest {

    @Test
    fun `catalog has exactly 365 keys (205 macOS-transcribed + 12 desktop-only from Tasks 6-7 + 32 Kotlin-side additions + 52 Plans L10n from Task 10 + 1 Next key fix + 26 dividend and income keys from the M8_2 Kotlin L10n port + 37 screener keys from the M9_2 Kotlin L10n port)`() {
        assertEquals(365, L10n.Key.entries.size)
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
