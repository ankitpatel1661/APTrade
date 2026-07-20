package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityEntry
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieLedgerEntry
import com.aptrade.shared.domain.PieSlice
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilePieStoreTest {

    private fun tempFile() = createTempDirectory("aptrade-pies-test").resolve("pies.json")

    private fun pp(s: String): BigDecimal = BigDecimal.parseString(s)
    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

    private val fullPie = Pie.create(
        id = "pie-1",
        name = "Core Growth",
        slices = listOf(
            PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = pp("60")),
            PieSlice(symbol = "BTC", assetKind = AssetKind.Crypto, targetWeightPP = pp("40")),
        ),
        schedule = ContributionSchedule(
            amount = Money(BigDecimal.parseString("1000.00"), "USD"),
            cadence = PieCadence.Monthly,
            anchorDay = "2026-01-31",
            nextDueDay = "2026-03-31",
        ),
        createdDay = "2026-01-31",
        ledger = listOf(
            PieLedgerEntry(symbol = "AAPL", quantity = qty("10.5")),
            PieLedgerEntry(symbol = "BTC", quantity = qty("0.25")),
        ),
        activity = listOf(
            PieActivityEntry(
                id = "activity-1",
                kind = PieActivityKind.Contribution,
                day = "2026-01-31",
                amount = Money(BigDecimal.parseString("1000.00"), "USD"),
            ),
            PieActivityEntry(
                id = "activity-2",
                kind = PieActivityKind.MissedInsufficientCash,
                day = "2026-02-28",
                amount = null,
            ),
        ),
    )

    @Test
    fun missingFileLoadsEmptyList() = runTest {
        val file = tempFile()
        assertEquals(emptyList(), FilePieStore(file).load())
    }

    @Test
    fun roundTripsAllFieldsIncludingScheduleWithAnchorDayLedgerAndActivity() = runTest {
        val file = tempFile()
        val store = FilePieStore(file)

        store.save(listOf(fullPie))
        assertTrue(file.exists())

        val loaded = store.load()
        assertEquals(listOf(fullPie), loaded)

        // Fresh instance, same file — proves it's the persisted bytes, not in-memory state.
        assertEquals(listOf(fullPie), FilePieStore(file).load())
    }

    @Test
    fun roundTripsAPieWithNoSchedule() = runTest {
        val file = tempFile()
        val store = FilePieStore(file)
        val pie = Pie.create(
            id = "pie-2",
            name = "No Schedule",
            slices = listOf(PieSlice(symbol = "SPY", assetKind = AssetKind.Etf, targetWeightPP = pp("100"))),
            schedule = null,
            createdDay = "2026-07-20",
        )

        store.save(listOf(pie))

        assertEquals(listOf(pie), store.load())
    }

    @Test
    fun roundTripsMultiplePiesPreservingOrder() = runTest {
        val file = tempFile()
        val store = FilePieStore(file)
        val pieA = Pie.create(
            id = "pie-a",
            name = "A",
            slices = listOf(PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = pp("100"))),
            schedule = null,
            createdDay = "2026-07-20",
        )
        val pieB = Pie.create(
            id = "pie-b",
            name = "B",
            slices = listOf(PieSlice(symbol = "ETH", assetKind = AssetKind.Crypto, targetWeightPP = pp("100"))),
            schedule = null,
            createdDay = "2026-07-20",
        )

        store.save(listOf(pieA, pieB))

        assertEquals(listOf(pieA, pieB), store.load())
    }

    @Test
    fun emptyListRoundTripsToEmptyList() = runTest {
        val file = tempFile()
        val store = FilePieStore(file)
        store.save(emptyList())
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun missingFileThenSaveThenLoadWorksAfterDirectoriesAreCreated() = runTest {
        val dir = createTempDirectory("aptrade-pies-nested")
        val file = dir.resolve("nested/dir/pies.json")
        val store = FilePieStore(file)

        store.save(listOf(fullPie))

        assertTrue(file.exists())
        assertEquals(listOf(fullPie), store.load())
    }

    @Test
    fun corruptJsonLoadsEmptyListAndLeavesFileBytesUntouched() = runTest {
        val file = tempFile()
        file.writeText("{ not valid json at all ")
        val originalBytes = file.readBytes()

        assertEquals(emptyList(), FilePieStore(file).load())

        assertContentEquals(originalBytes, file.readBytes())
    }

    @Test
    fun aPieWithInvalidWeightsInHandWrittenJsonIsTreatedAsCorruptAndLoadsEmptyList() = runTest {
        // Weights sum to 99, not 100 — Pie.create throws PieError.InvalidWeights, and the
        // WHOLE load (not just this pie) is discarded, mirroring the whole-blob philosophy.
        val file = tempFile()
        file.writeText(
            """
            [
              {
                "id": "pie-bad",
                "name": "Bad Weights",
                "slices": [
                  {"symbol": "AAPL", "assetKind": "Stock", "targetWeight": {"value": "60"}},
                  {"symbol": "BTC", "assetKind": "Crypto", "targetWeight": {"value": "39"}}
                ],
                "schedule": null,
                "createdDay": "2026-07-20",
                "ledger": [],
                "activity": []
              }
            ]
            """.trimIndent(),
        )
        val originalBytes = file.readBytes()

        assertEquals(emptyList(), FilePieStore(file).load())

        assertContentEquals(originalBytes, file.readBytes())
    }

    @Test
    fun scheduleMissingAnchorDayKeyFallsBackToNextDueDay() = runTest {
        // Legacy data written before `anchorDay` existed — Swift's Codable fallback is
        // `anchorDay = nextDueDay` (see `ContributionSchedule.init(from:)`); transcribed
        // here for the Kotlin DTO decode path.
        val file = tempFile()
        file.writeText(
            """
            [
              {
                "id": "pie-legacy",
                "name": "Legacy Schedule",
                "slices": [
                  {"symbol": "AAPL", "assetKind": "Stock", "targetWeight": {"value": "100"}}
                ],
                "schedule": {
                  "amount": {"amount": "500.00", "currencyCode": "USD"},
                  "cadence": "weekly",
                  "nextDueDay": "2026-08-01"
                },
                "createdDay": "2026-07-20",
                "ledger": [],
                "activity": []
              }
            ]
            """.trimIndent(),
        )

        val loaded = FilePieStore(file).load()

        assertEquals(1, loaded.size)
        assertEquals("2026-08-01", loaded[0].schedule?.anchorDay)
        assertEquals("2026-08-01", loaded[0].schedule?.nextDueDay)
    }

    @Test
    fun fieldNamesConvergeWithSwiftCodableShapes() = runTest {
        // Spot-checks the serialized JSON text for the Swift-convergent field names named
        // in the plan's Global Constraints (spec §C): top-level array, slice
        // symbol/assetKind/targetWeight.value, schedule amount/cadence/anchorDay/
        // nextDueDay, ledger symbol/quantity.amount, activity id/kind/day/amount, and the
        // exact lowercase enum raw strings ("monthly", "contribution",
        // "missedInsufficientCash").
        val file = tempFile()
        val store = FilePieStore(file)

        store.save(listOf(fullPie))
        val text = file.readText()

        assertTrue(text.trimStart().startsWith("["), "top-level JSON must be a bare array")
        for (needle in listOf(
            "\"id\"", "\"name\"", "\"slices\"", "\"schedule\"", "\"createdDay\"", "\"ledger\"", "\"activity\"",
            "\"symbol\"", "\"assetKind\"", "\"targetWeight\"", "\"value\"",
            "\"amount\"", "\"cadence\"", "\"anchorDay\"", "\"nextDueDay\"", "\"currencyCode\"",
            "\"quantity\"", "\"kind\"", "\"day\"",
            "\"monthly\"", "\"contribution\"", "\"missedInsufficientCash\"",
        )) {
            assertTrue(text.contains(needle), "expected $needle in serialized pies.json:\n$text")
        }
    }
}
