package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Transcribed from `Tests/APTradeDomainTests/PieScheduleTests.swift`, byte-value-equal (19
 * fixtures). Swift's `date(fromDay:calendar:)` returns a `Date`; the Kotlin equivalent
 * ([PieSchedule.parseDay]) returns a market-local epoch day `Long`, so the two "parses
 * ok"/"formats back to the same day string" assertions below use [MarketCalendar.dayString]
 * in place of Swift's `calendar.tradingDay(of:)` round-trip.
 */
class PieScheduleTest {
    private val calendar = MarketCalendar()

    // MARK: - parseDay (Swift: date(fromDay:))

    @Test
    fun parseDay_parsesValidDay() {
        val epochDay = PieSchedule.parseDay("2026-07-15")
        assertNotNull(epochDay)
        assertEquals("2026-07-15", calendar.dayString(epochDay))
    }

    @Test
    fun parseDay_malformedInput_returnsNull() {
        assertNull(PieSchedule.parseDay("not-a-date"))
        assertNull(PieSchedule.parseDay("2026-13-01"))
        assertNull(PieSchedule.parseDay("2026-02-30"))
        assertNull(PieSchedule.parseDay("2026-07"))
    }

    // MARK: - rollToTradingDay (Step 1a)

    @Test
    fun rollToTradingDay_thanksgiving_rollsToHalfDayFriday() {
        // 2026-11-26 is Thanksgiving (full holiday); 2026-11-27 is the half-day-after,
        // which IS a trading day, so the roll lands there.
        assertEquals("2026-11-27", PieSchedule.rollToTradingDay("2026-11-26", calendar))
    }

    @Test
    fun rollToTradingDay_july4thObserved_rollsPastWeekend() {
        // July 4 2026 is a Saturday, so July 3 (Friday) is the observed holiday.
        // Roll from Jul 3 skips the holiday itself, then Sat Jul 4 and Sun Jul 5.
        assertEquals("2026-07-06", PieSchedule.rollToTradingDay("2026-07-03", calendar))
    }

    @Test
    fun rollToTradingDay_saturday_rollsToMonday() {
        assertEquals("2026-07-27", PieSchedule.rollToTradingDay("2026-07-25", calendar))
    }

    @Test
    fun rollToTradingDay_alreadyTradingDay_isUnchanged() {
        assertEquals("2026-07-15", PieSchedule.rollToTradingDay("2026-07-15", calendar))
    }

    // MARK: - dueDays: weekly (Step 1b)

    @Test
    fun dueDays_weekly_stepsSevenDaysAndStaysOnMondays() {
        val days = PieSchedule.dueDays(
            anchorDay = "2026-07-06",
            cadence = PieCadence.Weekly,
            afterDay = "2026-07-06",
            throughDay = "2026-07-27",
            calendar = calendar,
        )
        assertEquals(listOf("2026-07-13", "2026-07-20", "2026-07-27"), days)
    }

    // MARK: - dueDays: monthly with clamp (Step 1c) -- transcribed as nextDueDay per Swift

    @Test
    fun nextDueDay_monthlyFromJan31_clampsToFebAndRollsWeekend() {
        // Jan 31 + 1 month clamps to Feb 28 (day-of-month clamp), which is a Saturday;
        // rolling forward crosses Sun Mar 1 and lands on Mon Mar 2.
        val next = PieSchedule.nextDueDay(
            anchorDay = "2026-01-31",
            cadence = PieCadence.Monthly,
            afterDay = "2026-01-31",
            calendar = calendar,
        )
        assertEquals("2026-03-02", next)
    }

    // MARK: - dueDays: holiday roll inside the window (Step 1d)

    @Test
    fun dueDays_acrossThanksgivingWeek_rollsTheHolidayHit() {
        // Anchor is a Thursday; the first weekly step lands exactly on Thanksgiving.
        val days = PieSchedule.dueDays(
            anchorDay = "2026-11-19",
            cadence = PieCadence.Weekly,
            afterDay = "2026-11-19",
            throughDay = "2026-12-01",
            calendar = calendar,
        )
        assertEquals(listOf("2026-11-27"), days)
    }

    // MARK: - dueDays: empty window (Step 1e)

    @Test
    fun dueDays_afterEqualsThrough_isEmpty() {
        val days = PieSchedule.dueDays(
            anchorDay = "2026-07-06",
            cadence = PieCadence.Weekly,
            afterDay = "2026-07-20",
            throughDay = "2026-07-20",
            calendar = calendar,
        )
        assertEquals(emptyList(), days)
    }

    @Test
    fun dueDays_afterAfterThrough_isEmpty() {
        val days = PieSchedule.dueDays(
            anchorDay = "2026-07-06",
            cadence = PieCadence.Weekly,
            afterDay = "2026-07-27",
            throughDay = "2026-07-20",
            calendar = calendar,
        )
        assertEquals(emptyList(), days)
    }

    // MARK: - dueDays: biweekly

    @Test
    fun dueDays_biweekly_stepsFourteenDays() {
        val days = PieSchedule.dueDays(
            anchorDay = "2026-07-06",
            cadence = PieCadence.Biweekly,
            afterDay = "2026-07-06",
            throughDay = "2026-08-10",
            calendar = calendar,
        )
        assertEquals(listOf("2026-07-20", "2026-08-03"), days)
    }

    // MARK: - dueDays: window bounds test the ROLLED due day (fix-wave regression)

    @Test
    fun dueDays_regressionA_rolledCandidateMustNotExceedThroughDay() {
        // Reviewer's exact repro. Anchor is a Saturday; the only cadence step in this
        // window unrolls to Sat 2026-02-07, which rolls to Mon 2026-02-09 -- past
        // throughDay. Checking the window against the unrolled value let it through;
        // checking it against the rolled value (the actual due day) correctly excludes
        // it, so the window is empty.
        val days = PieSchedule.dueDays(
            anchorDay = "2026-01-31",
            cadence = PieCadence.Weekly,
            afterDay = "2026-01-31",
            throughDay = "2026-02-07",
            calendar = calendar,
        )
        assertEquals(emptyList(), days)
    }

    @Test
    fun dueDays_regressionB_rolledCandidateIsNotDroppedForever() {
        // Same anchor/cadence as regression A, shifted one window later. The Sat
        // 2026-02-07 step's rolled value (Mon 2026-02-09) correctly lands inside THIS
        // window and must be reported -- proving the step isn't silently dropped
        // forever by an unrolled-vs-afterDay comparison that would exclude it from both
        // the (…, Feb 7] window (rolled value is past it) and the (Feb 8, …] window
        // (unrolled value is before it).
        val days = PieSchedule.dueDays(
            anchorDay = "2026-01-31",
            cadence = PieCadence.Weekly,
            afterDay = "2026-02-08",
            throughDay = "2026-02-15",
            calendar = calendar,
        )
        assertEquals(listOf("2026-02-09"), days)
    }

    @Test
    fun dueDays_monthly_multiStepAntiDrift_stepsFromAnchorNotPriorResult() {
        // Anchor 2026-01-31, monthly. Step 1 clamps to Feb 28 (rolled to Mon Mar 2).
        // Step 2 must clamp from the ORIGINAL anchor (Jan 31 + 2 months = Mar 31), not
        // from the rolled Mar 2 result of step 1 -- proving there's no cumulative drift
        // from chaining off a prior rolled value. Mar 31 2026 is a plain Tuesday trading
        // day, so it appears unrolled.
        val days = PieSchedule.dueDays(
            anchorDay = "2026-01-31",
            cadence = PieCadence.Monthly,
            afterDay = "2026-01-31",
            throughDay = "2026-04-01",
            calendar = calendar,
        )
        assertEquals(listOf("2026-03-02", "2026-03-31"), days)
    }

    // MARK: - nextDueDay: anchor itself is the first due day

    @Test
    fun nextDueDay_beforeAnchor_returnsAnchorRolled() {
        // afterDay precedes the anchor entirely, so the very first due day is the
        // (rolled) anchor day itself.
        val next = PieSchedule.nextDueDay(
            anchorDay = "2026-07-06",
            cadence = PieCadence.Weekly,
            afterDay = "2026-07-01",
            calendar = calendar,
        )
        assertEquals("2026-07-06", next)
    }

    // MARK: - nextDueDay: compares the ROLLED candidate, not the unrolled one (fix-wave)

    @Test
    fun nextDueDay_regressionA_comparesRolledCandidate_notUnrolled() {
        // Sat 2026-02-07 candidate rolls to Mon 2026-02-09, which IS > afterDay
        // 2026-02-08 -- the correct next due day. Comparing the unrolled candidate
        // (2026-02-07, which is NOT > 2026-02-08) would wrongly skip straight past it to
        // the 2026-02-14 candidate, whose roll (across the Presidents Day holiday on
        // 2026-02-16) lands on 2026-02-17.
        val next = PieSchedule.nextDueDay(
            anchorDay = "2026-01-31",
            cadence = PieCadence.Weekly,
            afterDay = "2026-02-08",
            calendar = calendar,
        )
        assertEquals("2026-02-09", next)
    }

    @Test
    fun nextDueDay_afterDayExactlyOnRolledDueDay_returnsTheNextOne() {
        // afterDay is itself a previously-reported rolled due day (2026-02-09); the
        // comparison is strict >, so that same day is not returned again. The next
        // candidate (Sat 2026-02-14) rolls across Presidents Day (2026-02-16) to
        // 2026-02-17.
        val next = PieSchedule.nextDueDay(
            anchorDay = "2026-01-31",
            cadence = PieCadence.Weekly,
            afterDay = "2026-02-09",
            calendar = calendar,
        )
        assertEquals("2026-02-17", next)
    }

    // MARK: - dueDays: sorted ascending, deduped

    @Test
    fun dueDays_resultsAreSortedAscending() {
        val days = PieSchedule.dueDays(
            anchorDay = "2026-07-06",
            cadence = PieCadence.Weekly,
            afterDay = "2026-07-06",
            throughDay = "2026-08-10",
            calendar = calendar,
        )
        assertEquals(days.sorted(), days)
        assertEquals(days.toSet().sorted(), days)
    }
}
