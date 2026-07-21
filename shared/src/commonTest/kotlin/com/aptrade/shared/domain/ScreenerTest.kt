package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transcribed from `Tests/APTradeDomainTests/ScreenerTests.swift` (21 Swift test functions,
 * the shipped M9.1 Swift/macOS reference) — byte-value-equal fixtures, including the strict-
 * boundary pairs (a value exactly at a threshold matches neither `above` nor `below`) and the
 * 10-distinct-values (1.0-10.0) anti-transposition mapping test. Do not "improve" the fixtures.
 *
 * Two Swift tests (`test_e_customScreen_codableRoundTrip`,
 * `test_e_legacyCustomScreenJSON_withoutFutureField_decodes`) exercised `Codable` on
 * `CustomScreen`. Per this milestone's Task 1 precedent (`ScreenerMath.kt`'s header doc) and
 * this task's brief, `CustomScreen` is kept a PLAIN (non-`@Serializable`) data class here —
 * persistence DTOs are deferred to Task 4's store. There is no JSON encoder/decoder at this
 * layer to round-trip through, so those two tests are replaced with
 * [testCustomScreen_dataClassEquality_survivesCopyRoundTrip] and
 * [testCustomScreen_dataClassEquality_differsOnlyByFieldValues] — both exercise the same
 * underlying guarantee the Swift tests protected (value equality/identity of the saved
 * screen shape) using Kotlin data class structural equality instead of JSON.
 */
class ScreenerTest {

    // MARK: - Helpers

    /** A row with every optional metric null and every flag false by default — individual
     *  fields overridden per test so each assertion only varies what it needs. */
    private fun makeRow(
        symbol: String = "TEST",
        name: String = "Test Co",
        close: Double = 100.0,
        dayChangePercent: Double? = null,
        rsi14: Double? = null,
        macd: Double? = null,
        macdSignal: Double? = null,
        macdHistogram: Double? = null,
        sma50: Double? = null,
        sma200: Double? = null,
        ema20: Double? = null,
        pctVsSma50: Double? = null,
        pctVsSma200: Double? = null,
        bollingerPercentB: Double? = null,
        bollingerBandwidth: Double? = null,
        week52High: Double? = null,
        week52Low: Double? = null,
        pctTo52wHigh: Double? = null,
        pctTo52wLow: Double? = null,
        relativeVolume: Double? = null,
        macdCrossedUp: Boolean = false,
        macdCrossedDown: Boolean = false,
        goldenCross: Boolean = false,
        deathCross: Boolean = false,
    ): ScreenerSnapshotRow = ScreenerSnapshotRow(
        symbol = symbol,
        name = name,
        close = close,
        dayChangePercent = dayChangePercent,
        rsi14 = rsi14,
        macd = macd,
        macdSignal = macdSignal,
        macdHistogram = macdHistogram,
        sma50 = sma50,
        sma200 = sma200,
        ema20 = ema20,
        pctVsSma50 = pctVsSma50,
        pctVsSma200 = pctVsSma200,
        bollingerPercentB = bollingerPercentB,
        bollingerBandwidth = bollingerBandwidth,
        week52High = week52High,
        week52Low = week52Low,
        pctTo52wHigh = pctTo52wHigh,
        pctTo52wLow = pctTo52wLow,
        relativeVolume = relativeVolume,
        macdCrossedUp = macdCrossedUp,
        macdCrossedDown = macdCrossedDown,
        goldenCross = goldenCross,
        deathCross = deathCross,
    )

    // MARK: - (a) 9 presets — matching row + boundary near-miss (strict comparisons)

    @Test
    fun testRsiOversold_matchesBelow30_notAtExactly30() {
        val matching = makeRow(rsi14 = 29.0)
        val boundary = makeRow(rsi14 = 30.0)
        assertTrue(PresetScreen.RsiOversold.matches(matching))
        assertFalse(PresetScreen.RsiOversold.matches(boundary))
    }

    @Test
    fun testRsiOverbought_matchesAbove70_notAtExactly70() {
        val matching = makeRow(rsi14 = 71.0)
        val boundary = makeRow(rsi14 = 70.0)
        assertTrue(PresetScreen.RsiOverbought.matches(matching))
        assertFalse(PresetScreen.RsiOverbought.matches(boundary))
    }

    @Test
    fun testMacdBullishCross_matchesFlagTrue_notWhenFalse() {
        val matching = makeRow(macdCrossedUp = true)
        val nearMiss = makeRow(macdCrossedUp = false)
        assertTrue(PresetScreen.MacdBullishCross.matches(matching))
        assertFalse(PresetScreen.MacdBullishCross.matches(nearMiss))
    }

    @Test
    fun testMacdBearishCross_matchesFlagTrue_notWhenFalse() {
        val matching = makeRow(macdCrossedDown = true)
        val nearMiss = makeRow(macdCrossedDown = false)
        assertTrue(PresetScreen.MacdBearishCross.matches(matching))
        assertFalse(PresetScreen.MacdBearishCross.matches(nearMiss))
    }

    @Test
    fun testGoldenCross_matchesFlagTrue_notWhenFalse() {
        val matching = makeRow(goldenCross = true)
        val nearMiss = makeRow(goldenCross = false)
        assertTrue(PresetScreen.GoldenCross.matches(matching))
        assertFalse(PresetScreen.GoldenCross.matches(nearMiss))
    }

    @Test
    fun testDeathCross_matchesFlagTrue_notWhenFalse() {
        val matching = makeRow(deathCross = true)
        val nearMiss = makeRow(deathCross = false)
        assertTrue(PresetScreen.DeathCross.matches(matching))
        assertFalse(PresetScreen.DeathCross.matches(nearMiss))
    }

    @Test
    fun testBollingerSqueeze_matchesBelow0_05_notAtExactly0_05() {
        val matching = makeRow(bollingerBandwidth = 0.04)
        val boundary = makeRow(bollingerBandwidth = 0.05)
        assertTrue(PresetScreen.BollingerSqueeze.matches(matching))
        assertFalse(PresetScreen.BollingerSqueeze.matches(boundary))
    }

    @Test
    fun testNear52wHigh_matchesBelow3_notAtExactly3() {
        val matching = makeRow(pctTo52wHigh = 2.0)
        val boundary = makeRow(pctTo52wHigh = 3.0)
        assertTrue(PresetScreen.Near52wHigh.matches(matching))
        assertFalse(PresetScreen.Near52wHigh.matches(boundary))
    }

    @Test
    fun testNear52wLow_matchesBelow3_notAtExactly3() {
        val matching = makeRow(pctTo52wLow = 2.0)
        val boundary = makeRow(pctTo52wLow = 3.0)
        assertTrue(PresetScreen.Near52wLow.matches(matching))
        assertFalse(PresetScreen.Near52wLow.matches(boundary))
    }

    /** Null-metric presets never crash and never match — same "no data, no match" rule as
     *  [ScreenCondition]. */
    @Test
    fun testPresetsOverNumericMetrics_nullMetric_neverMatchesNeverCrashes() {
        val allNull = makeRow()
        assertFalse(PresetScreen.RsiOversold.matches(allNull))
        assertFalse(PresetScreen.RsiOverbought.matches(allNull))
        assertFalse(PresetScreen.BollingerSqueeze.matches(allNull))
        assertFalse(PresetScreen.Near52wHigh.matches(allNull))
        assertFalse(PresetScreen.Near52wLow.matches(allNull))
    }

    // MARK: - (b) Condition with null metric -> row excluded

    @Test
    fun testConditionOnNullMetric_excludesRow() {
        val row = makeRow(rsi14 = null)
        val condition = ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Below, threshold = 50.0)
        assertFalse(condition.matches(row))
    }

    @Test
    fun testConditionOnPresentMetric_stillEvaluatesStrictly() {
        val row = makeRow(rsi14 = 50.0)
        val above = ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Above, threshold = 50.0)
        val below = ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Below, threshold = 50.0)
        assertFalse(above.matches(row), "50 is not strictly above 50")
        assertFalse(below.matches(row), "50 is not strictly below 50")
    }

    // MARK: - (c) AND semantics — row passing only one of two conditions is excluded

    @Test
    fun testAndSemantics_rowPassingOnlyOneOfTwoConditions_isExcluded() {
        val row = makeRow(dayChangePercent = -1.0, rsi14 = 25.0)
        val passesFirst = ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Below, threshold = 30.0)
        val failsSecond = ScreenCondition(metric = ScreenerMetric.dayChangePercent, comparison = ScreenComparison.Above, threshold = 5.0)
        val screen = CustomScreen(id = "s1", name = "Partial", conditions = listOf(passesFirst, failsSecond))

        assertTrue(passesFirst.matches(row))
        assertFalse(failsSecond.matches(row))
        assertEquals(emptyList(), ScreenSelection.Custom(screen).evaluate(listOf(row)))
    }

    @Test
    fun testAndSemantics_rowPassingBothConditions_isIncluded() {
        val row = makeRow(dayChangePercent = 6.0, rsi14 = 25.0)
        val first = ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Below, threshold = 30.0)
        val second = ScreenCondition(metric = ScreenerMetric.dayChangePercent, comparison = ScreenComparison.Above, threshold = 5.0)
        val screen = CustomScreen(id = "s1", name = "Both", conditions = listOf(first, second))

        assertEquals(listOf(row), ScreenSelection.Custom(screen).evaluate(listOf(row)))
    }

    // MARK: - (d) Empty custom conditions -> no matches

    @Test
    fun testEmptyConditions_matchesNothing() {
        val row = makeRow(close = 9999.0, dayChangePercent = 100.0, rsi14 = 1.0)
        val screen = CustomScreen(id = "empty", name = "Empty", conditions = emptyList())
        assertEquals(emptyList(), ScreenSelection.Custom(screen).evaluate(listOf(row)))
    }

    // MARK: - (e) CustomScreen structural equality (replaces Swift's Codable round-trip pair)

    /** Kotlin equivalent of the Swift codable round-trip test: `copy()` with no changes
     *  yields a structurally equal value, and the fields all survive intact. */
    @Test
    fun testCustomScreen_dataClassEquality_survivesCopyRoundTrip() {
        val screen = CustomScreen(
            id = "abc123",
            name = "My Screen",
            conditions = listOf(
                ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Below, threshold = 30.0),
                ScreenCondition(metric = ScreenerMetric.price, comparison = ScreenComparison.Above, threshold = 10.0),
            ),
        )
        val roundTripped = screen.copy()
        assertEquals(screen, roundTripped)
        assertEquals("abc123", roundTripped.id)
        assertEquals("My Screen", roundTripped.name)
        assertEquals(screen.conditions, roundTripped.conditions)
    }

    /** Kotlin equivalent of the Swift legacy-JSON-tolerance test: a `CustomScreen` built
     *  from only today's known fields (id/name/conditions — nothing hypothetical) is equal
     *  to another built the same way, and unequal when any one field differs. */
    @Test
    fun testCustomScreen_dataClassEquality_differsOnlyByFieldValues() {
        val condition = ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Below, threshold = 30.0)
        val a = CustomScreen(id = "legacy-1", name = "Legacy Screen", conditions = listOf(condition))
        val b = CustomScreen(id = "legacy-1", name = "Legacy Screen", conditions = listOf(condition))
        val differentId = a.copy(id = "legacy-2")

        assertEquals(a, b)
        assertEquals(listOf(condition), a.conditions)
        assertFalse(a == differentId)
    }

    // MARK: - ScreenerMetric.value(row) — one door per case, null-propagating

    @Test
    fun testMetricValue_priceMapsToClose() {
        val row = makeRow(close = 42.5)
        assertEquals(42.5, ScreenerMetric.price.value(row))
    }

    @Test
    fun testMetricValue_allCasesPropagateNullIndependently() {
        val row = makeRow()
        for (metric in ScreenerMetric.entries) {
            if (metric == ScreenerMetric.price) continue
            assertNull(metric.value(row), "$metric should be null when the row field is null")
        }
    }

    /** Every metric-backed field gets its own distinct value (1...10) so a transposition
     *  inside `value(row)` — e.g. swapping the `pctTo52wHigh`/`pctTo52wLow` returns — fails
     *  this test even though it would sail through null-only or single-field checks. If
     *  `.pctTo52wHigh` and `.pctTo52wLow` were swapped in the implementation, the
     *  `.pctTo52wHigh` assertion below would observe 7.0 (pctTo52wLow's value) instead of
     *  the expected 6.0, and fail. */
    @Test
    fun testMetricValue_allTenCases_mapToTheirOwnDistinctField() {
        val row = makeRow(
            close = 1.0,
            dayChangePercent = 2.0,
            rsi14 = 3.0,
            pctVsSma50 = 9.0,
            pctVsSma200 = 10.0,
            bollingerPercentB = 4.0,
            bollingerBandwidth = 5.0,
            pctTo52wHigh = 6.0,
            pctTo52wLow = 7.0,
            relativeVolume = 8.0,
        )

        assertEquals(1.0, ScreenerMetric.price.value(row))
        assertEquals(2.0, ScreenerMetric.dayChangePercent.value(row))
        assertEquals(3.0, ScreenerMetric.rsi14.value(row))
        assertEquals(4.0, ScreenerMetric.bollingerPercentB.value(row))
        assertEquals(5.0, ScreenerMetric.bollingerBandwidth.value(row))
        assertEquals(6.0, ScreenerMetric.pctTo52wHigh.value(row))
        assertEquals(7.0, ScreenerMetric.pctTo52wLow.value(row))
        assertEquals(8.0, ScreenerMetric.relativeVolume.value(row))
        assertEquals(9.0, ScreenerMetric.pctVsSma50.value(row))
        assertEquals(10.0, ScreenerMetric.pctVsSma200.value(row))
    }

    // MARK: - ScreenSelection.evaluate — preset door

    @Test
    fun testScreenSelection_presetDoor_filtersRows() {
        val oversold = makeRow(symbol = "OVER", rsi14 = 20.0)
        val neutral = makeRow(symbol = "NEUT", rsi14 = 50.0)
        val results = ScreenSelection.Preset(PresetScreen.RsiOversold).evaluate(listOf(oversold, neutral))
        assertEquals(listOf("OVER"), results.map { it.symbol })
    }
}
