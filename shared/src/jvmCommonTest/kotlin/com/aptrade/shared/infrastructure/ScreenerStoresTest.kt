package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.ScreenComparison
import com.aptrade.shared.domain.ScreenCondition
import com.aptrade.shared.domain.ScreenerMetric
import com.aptrade.shared.domain.ScreenerSnapshot
import com.aptrade.shared.domain.ScreenerSnapshotRow
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transcribed from `Tests/APTradeInfrastructureTests/ScreenerStoresTests.swift` (the shipped
 * Swift/macOS reference's 6 scenarios), adapted to this codebase's file-backed house pattern
 * (`FilePieStore`/`FilePortfolioStore`): an injected [java.nio.file.Path], atomic
 * temp-file-then-rename writes, and a whole-blob corrupt/missing -> null/empty result that
 * NEVER overwrites the bytes already on disk. Both stores implement the NON-suspend
 * `ScreenerSnapshotStore`/`ScreenStore` ports (Task 3) — a deliberate divergence from every
 * other file store in this package, which are all `suspend`.
 *
 * Adds a 7th scenario beyond the Swift 6: an explicit enum-wire-value round trip, proving
 * [ScreenComparison] and [ScreenerMetric] are (de)serialized via EXPLICIT string mappings
 * matching the Swift raw values, never Kotlin's `.name` — critical for `ScreenComparison`,
 * whose Kotlin case names (`Above`/`Below`) are PascalCase while the Swift/wire values are
 * lowerCamelCase (`above`/`below`); `.name` would silently break the wire contract.
 */
class ScreenerStoresTest {

    private fun tempDir() = createTempDirectory("aptrade-screener-test")

    // MARK: - FileScreenerSnapshotStore

    private fun makeRow() = ScreenerSnapshotRow(
        symbol = "AAPL",
        name = "Apple Inc.",
        close = 150.0,
        dayChangePercent = 1.2,
        rsi14 = 55.0,
        macd = 0.5,
        macdSignal = 0.3,
        macdHistogram = 0.2,
        sma50 = 145.0,
        sma200 = 140.0,
        ema20 = 148.0,
        pctVsSma50 = 3.4,
        pctVsSma200 = 7.1,
        bollingerPercentB = 0.6,
        bollingerBandwidth = 0.1,
        week52High = 180.0,
        week52Low = 120.0,
        pctTo52wHigh = 16.7,
        pctTo52wLow = 25.0,
        relativeVolume = 1.1,
        macdCrossedUp = true,
        macdCrossedDown = false,
        goldenCross = true,
        deathCross = false,
    )

    private fun makeSnapshot() = ScreenerSnapshot(
        tradingDay = "2026-07-21",
        scannedAtEpochSeconds = 1_753_100_000L,
        rows = listOf(makeRow()),
        failedSymbols = listOf("TSLA"),
    )

    // (a) snapshot round-trip via temp directory
    @Test
    fun snapshotStore_roundTripsViaTempDirectory() {
        val file = tempDir().resolve("screener-snapshot.json")
        val store = FileScreenerSnapshotStore(file)
        val snapshot = makeSnapshot()

        store.save(snapshot)
        val loaded = store.load()

        assertEquals(snapshot, loaded)
        // Fresh instance, same file — proves it's the persisted bytes, not in-memory state.
        assertEquals(snapshot, FileScreenerSnapshotStore(file).load())
    }

    // (b) corrupt file -> null, bytes untouched
    @Test
    fun snapshotStore_corruptFile_returnsNull_withoutOverwriting() {
        val file = tempDir().resolve("screener-snapshot.json")
        val store = FileScreenerSnapshotStore(file)
        store.save(makeSnapshot())

        file.writeText("not valid json at all")
        val corruptBytes = file.readBytes()

        val loaded = store.load()
        assertNull(loaded)

        assertContentEquals(corruptBytes, file.readBytes())
    }

    // (c) missing file -> null
    @Test
    fun snapshotStore_missingFile_returnsNull() {
        val file = tempDir().resolve("screener-snapshot.json")
        assertNull(FileScreenerSnapshotStore(file).load())
    }

    // (e) hand-written legacy snapshot JSON missing a nullable metric field decodes
    @Test
    fun snapshotStore_legacyJsonMissingNullableField_decodes() {
        val file = tempDir().resolve("screener-snapshot.json")

        // Hand-written legacy payload: relativeVolume key entirely absent from the row.
        file.writeText(
            """
            {
                "tradingDay": "2026-07-20",
                "scannedAtEpochSeconds": 1753000000,
                "rows": [
                    {
                        "symbol": "MSFT",
                        "name": "Microsoft Corp.",
                        "close": 300.0,
                        "dayChangePercent": 0.5,
                        "rsi14": 60.0,
                        "macd": 0.1,
                        "macdSignal": 0.05,
                        "macdHistogram": 0.05,
                        "sma50": 295.0,
                        "sma200": 290.0,
                        "ema20": 298.0,
                        "pctVsSma50": 1.7,
                        "pctVsSma200": 3.4,
                        "bollingerPercentB": 0.5,
                        "bollingerBandwidth": 0.08,
                        "week52High": 320.0,
                        "week52Low": 250.0,
                        "pctTo52wHigh": 6.25,
                        "pctTo52wLow": 20.0,
                        "macdCrossedUp": false,
                        "macdCrossedDown": false,
                        "goldenCross": false,
                        "deathCross": false
                    }
                ],
                "failedSymbols": []
            }
            """.trimIndent(),
        )

        val loaded = requireNotNull(FileScreenerSnapshotStore(file).load())

        assertEquals(1, loaded.rows.size)
        assertNull(loaded.rows[0].relativeVolume)
        assertEquals("MSFT", loaded.rows[0].symbol)
    }

    // MARK: - FileScreenStore

    private fun makeScreen(id: String = "screen-1", name: String = "My Screen") = CustomScreen(
        id = id,
        name = name,
        conditions = listOf(ScreenCondition(ScreenerMetric.rsi14, ScreenComparison.Below, 30.0)),
    )

    // (d) screens round-trip + missing -> []
    @Test
    fun screenStore_roundTrips() {
        val file = tempDir().resolve("screens.json")
        val store = FileScreenStore(file)
        assertEquals(emptyList(), store.load())

        val screen = makeScreen()
        store.save(listOf(screen))
        val loaded = store.load()

        assertEquals(listOf(screen), loaded)
    }

    // (d) corrupt -> [], bytes untouched
    @Test
    fun screenStore_corruptData_returnsEmpty_withoutOverwriting() {
        val file = tempDir().resolve("screens.json")
        val store = FileScreenStore(file)
        store.save(listOf(makeScreen()))

        file.writeText("not valid json at all")
        val corruptBytes = file.readBytes()

        val loaded = store.load()
        assertEquals(emptyList(), loaded)

        assertContentEquals(corruptBytes, file.readBytes())
    }

    // (f) explicit enum-wire-value test: hand-written JSON using the Swift-matching raw
    // strings ("below"/"above", metric names) must decode to the right Kotlin case — proves
    // the mapping is explicit, not `.name` (which would require "Below"/"Above").
    @Test
    fun screenStore_explicitEnumWireValues_roundTripFromHandWrittenJson() {
        val file = tempDir().resolve("screens.json")
        file.writeText(
            """
            [
                {
                    "id": "screen-legacy",
                    "name": "Legacy Screen",
                    "conditions": [
                        {"metric": "rsi14", "comparison": "below", "threshold": 30.0},
                        {"metric": "pctVsSma200", "comparison": "above", "threshold": 5.0}
                    ]
                }
            ]
            """.trimIndent(),
        )

        val loaded = FileScreenStore(file).load()

        assertEquals(1, loaded.size)
        assertEquals("screen-legacy", loaded[0].id)
        assertEquals(2, loaded[0].conditions.size)
        assertEquals(ScreenerMetric.rsi14, loaded[0].conditions[0].metric)
        assertEquals(ScreenComparison.Below, loaded[0].conditions[0].comparison)
        assertEquals(ScreenerMetric.pctVsSma200, loaded[0].conditions[1].metric)
        assertEquals(ScreenComparison.Above, loaded[0].conditions[1].comparison)

        // Round trip through save/load, then spot-check the RAW text uses the lowerCamelCase
        // wire values (never Kotlin's PascalCase `.name` for ScreenComparison).
        val store = FileScreenStore(file)
        store.save(loaded)
        val text = file.readText()
        assertTrue(text.contains("\"below\""), "expected lowercase \"below\" in:\n$text")
        assertTrue(text.contains("\"above\""), "expected lowercase \"above\" in:\n$text")
        assertTrue(!text.contains("\"Below\"") && !text.contains("\"Above\""), "must not use Kotlin .name casing:\n$text")

        assertEquals(loaded, store.load())
    }

    // (all-or-nothing, house precedent from FilePieStore): a well-formed JSON array whose
    // 2nd of 3 screens has an unrecognized metric raw value discards the WHOLE load, not
    // just that one screen -- and never touches the file, since load() never writes.
    @Test
    fun screenStore_unrecognizedMetricMidList_discardsWholeLoad_withoutTouchingFile() {
        val file = tempDir().resolve("screens.json")
        file.writeText(
            """
            [
                {"id": "screen-1", "name": "Fine", "conditions": [{"metric": "rsi14", "comparison": "below", "threshold": 30.0}]},
                {"id": "screen-2", "name": "Bad Metric", "conditions": [{"metric": "notARealMetric", "comparison": "above", "threshold": 1.0}]},
                {"id": "screen-3", "name": "Also Fine", "conditions": [{"metric": "price", "comparison": "above", "threshold": 100.0}]}
            ]
            """.trimIndent(),
        )
        val originalBytes = file.readBytes()

        val loaded = FileScreenStore(file).load()

        assertEquals(emptyList(), loaded)
        assertContentEquals(originalBytes, file.readBytes())
    }
}
