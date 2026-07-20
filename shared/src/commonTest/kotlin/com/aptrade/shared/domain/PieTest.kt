package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Transcribed from `Tests/APTradeDomainTests/PieTests.swift`. Kotlin has no `Codable`, so
 *  the Swift suite's `testCodableRoundTrip` is represented here by the value-equality half
 *  of that contract (see [equalPiesBuiltFromIdenticalFieldsCompareEqual]) — the real
 *  encode/decode round trip is covered by `FilePieStore`'s tests in Task 6. */
class PieTest {

    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)
    private fun pp(s: String): BigDecimal = BigDecimal.parseString(s)

    // -- (a) Construction validation: weights exactly 100 --

    @Test
    fun weightsExactly100Constructs() {
        val slices = listOf(
            PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = pp("60")),
            PieSlice(symbol = "BTC", assetKind = AssetKind.Crypto, targetWeightPP = pp("40")),
        )

        val pie = Pie.create(
            name = "Test Pie",
            slices = slices,
            schedule = null,
            createdDay = "2026-07-20",
        )

        assertEquals("Test Pie", pie.name)
        assertEquals(2, pie.slices.size)
    }

    // -- (b) Weights sum to less than 100 throws InvalidWeights --

    @Test
    fun weightsSumToLessThan100ThrowsInvalidWeights() {
        val slices = listOf(
            PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = pp("60")),
            PieSlice(symbol = "BTC", assetKind = AssetKind.Crypto, targetWeightPP = pp("39")),
        )

        assertFailsWith<PieError.InvalidWeights> {
            Pie.create(name = "Test Pie", slices = slices, schedule = null, createdDay = "2026-07-20")
        }
    }

    // -- (c) Duplicate symbols throws DuplicateSymbols --

    @Test
    fun duplicateSymbolsThrowsDuplicateSymbols() {
        val slices = listOf(
            PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = pp("50")),
            PieSlice(symbol = "AAPL", assetKind = AssetKind.Etf, targetWeightPP = pp("50")),
        )

        assertFailsWith<PieError.DuplicateSymbols> {
            Pie.create(name = "Test Pie", slices = slices, schedule = null, createdDay = "2026-07-20")
        }
    }

    // -- (d) Empty slices throws EmptySlices --

    @Test
    fun emptySlicesThrowsEmptySlices() {
        assertFailsWith<PieError.EmptySlices> {
            Pie.create(name = "Test Pie", slices = emptyList(), schedule = null, createdDay = "2026-07-20")
        }
    }

    // -- (e) Equality / copy round-trip --

    @Test
    fun equalPiesBuiltFromIdenticalFieldsCompareEqual() {
        val schedule = ContributionSchedule(
            amount = Money(BigDecimal.parseString("1000"), "USD"),
            cadence = PieCadence.Monthly,
            anchorDay = "2026-08-20",
            nextDueDay = "2026-08-20",
        )

        val slices = listOf(
            PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = pp("60")),
            PieSlice(symbol = "BTC", assetKind = AssetKind.Crypto, targetWeightPP = pp("40")),
        )

        val ledger = listOf(
            PieLedgerEntry(symbol = "AAPL", quantity = qty("10")),
            PieLedgerEntry(symbol = "BTC", quantity = qty("0.5")),
        )

        val activity = listOf(
            PieActivityEntry(
                id = "activity-1",
                kind = PieActivityKind.Contribution,
                day = "2026-07-20",
                amount = Money(BigDecimal.parseString("1000"), "USD"),
            ),
        )

        val original = Pie.create(
            id = "pie-1",
            name = "Test Pie",
            slices = slices,
            schedule = schedule,
            createdDay = "2026-07-20",
            ledger = ledger,
            activity = activity,
        )

        // "Decoded" side: reconstructed field-by-field, the way a deserializer would (not
        // via `.copy()` — the primary constructor is private, so only the validated
        // `create` factory can produce a Pie).
        val reconstructed = Pie.create(
            id = "pie-1",
            name = "Test Pie",
            slices = slices,
            schedule = schedule,
            createdDay = "2026-07-20",
            ledger = ledger,
            activity = activity,
        )

        assertEquals(original, reconstructed)
        assertEquals("Test Pie", reconstructed.name)
        assertEquals(2, reconstructed.slices.size)
        assertEquals(PieCadence.Monthly, reconstructed.schedule?.cadence)
        assertEquals(2, reconstructed.ledger.size)
        assertEquals(1, reconstructed.activity.size)
    }

    // -- (f) quantityOf: present / absent --

    @Test
    fun quantityOfReturnsPresentEntry() {
        val slices = listOf(PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = pp("100")))
        val ledger = listOf(PieLedgerEntry(symbol = "AAPL", quantity = qty("25")))

        val pie = Pie.create(
            name = "Test Pie",
            slices = slices,
            schedule = null,
            createdDay = "2026-07-20",
            ledger = ledger,
        )

        assertEquals(qty("25"), pie.quantityOf("AAPL"))
    }

    @Test
    fun quantityOfReturnsZeroForAbsentSymbol() {
        val slices = listOf(PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = pp("100")))

        val pie = Pie.create(name = "Test Pie", slices = slices, schedule = null, createdDay = "2026-07-20")

        val quantity = pie.quantityOf("BTC")
        assertTrue(quantity.isZero())
        assertEquals(BigDecimal.ZERO, quantity)
    }
}
