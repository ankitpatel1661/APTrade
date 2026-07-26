package com.aptrade.shared.l10n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
 * key, mirroring the Swift source's reuse decision) — bringing the total to 365. M10.2 Task 1
 * (the Kotlin desktop transcription of the Swift M10.1 IA-restructure's L10n keys) added 24
 * more: Swift T1's 25-key Home/Markets/Invest/Alerts-center block minus `quickNewsFmt` (dead)
 * and `earningsSessionAfterClose`/`earningsSessionBeforeOpen` (reused the existing
 * `SessionAfterClose`/`SessionBeforeOpen` family instead of adding a near-duplicate pair),
 * plus `SidebarSearch`/`SidebarSettings` (Swift M10.1 Task 6) — bringing the total to 389; the
 * count below tracks the Kotlin-only total going forward, not the Swift source count.
 * M11.2 Task 8 (goals, dividend calendar, income forecast, configurable starting balance) added
 * 21 more: the calendar/forecast/goal block, the two reset-sheet strings, the starting-balance
 * range hint, and `SinceInception` (Kotlin-first — no Swift counterpart, it names the metric
 * introduced by M11.2 kickoff decision 4a.1). Swift's `estimatedShort` was NOT transcribed — the
 * existing `IncomeEstimatedBadge` already carries exactly that word in all four languages, so it
 * is reused rather than near-duplicated. That brings the total to 410.
 * M11.2 Task 12 (desktop Income UI) added 1 more: `ForecastPricesEstimatedCaption`. Task 11 added
 * `State.forecastPricesAreEstimated` (a Kotlin-only signal — Swift has no equivalent) to flag when
 * a total quote-fetch failure forces the income forecast's DRIP compounding to fall back to
 * cost-basis pricing; Task 12's brief predates that flag and has no caption copy for it, and no
 * existing key fit (`EstimatedCost`/`EstimatedProceeds`/`IncomeEstimatedBadge` are all short badge
 * words, not a sentence explaining a price fallback). Bringing the total to 411.
 */
class L10nCatalogTest {

    @Test
    fun `catalog has exactly 411 keys (410 pre-Task_12 + 1 ForecastPricesEstimatedCaption key added by M11_2 Task 12)`() {
        assertEquals(411, L10n.Key.entries.size)
    }

    @Test
    fun `every key has a non-blank table row for every non-English language`() {
        // Asserts against L10n.table directly, NOT L10n.string(...) — mirrors
        // Tests/APTradeAppTests/L10nTests.swift:7-18, which reads its table directly for the
        // same reason: string()/tr()'s own English fallback would silently mask a missing or
        // blank row, so going through it here would make this test unable to fail no matter
        // what is (or isn't) in the table. English has no table row by design (it resolves via
        // Key.english — see the KDoc above `table`), so only the translated languages are
        // checked here.
        for (key in L10n.Key.entries) {
            for (language in AppLanguage.entries) {
                if (language == AppLanguage.English) continue
                val row = assertNotNull(L10n.table[language], "missing table for $language")
                val value = assertNotNull(row[key], "missing $language translation for $key")
                assertFalse(value.isBlank(), "blank $language translation for $key")
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
