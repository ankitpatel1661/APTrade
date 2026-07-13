package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epoch-day of a proleptic-Gregorian date — mirror of the production daysFromCivil,
 *  duplicated here so the tests are readable as plain dates. */
private fun day(y: Long, m: Int, d: Int): Long {
    val yy = if (m <= 2) y - 1 else y
    val era = if (yy >= 0) yy / 400 else (yy - 399) / 400
    val yoe = yy - era * 400
    val mp = if (m > 2) m - 3 else m + 9
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

class USMarketHolidaysTest {

    // ---- 2026 (fixed reference year; July 4 2026 is a SATURDAY -> observed Friday July 3) ----

    @Test fun newYears2026() = assertEquals(USMarketHoliday.NewYearsDay, USMarketHolidays.fullHoliday(day(2026, 1, 1)))
    @Test fun mlk2026_thirdMondayJan() = assertEquals(USMarketHoliday.MartinLutherKingDay, USMarketHolidays.fullHoliday(day(2026, 1, 19)))
    @Test fun washington2026_thirdMondayFeb() = assertEquals(USMarketHoliday.WashingtonsBirthday, USMarketHolidays.fullHoliday(day(2026, 2, 16)))
    @Test fun goodFriday2026() = assertEquals(USMarketHoliday.GoodFriday, USMarketHolidays.fullHoliday(day(2026, 4, 3)))
    @Test fun memorial2026_lastMondayMay() = assertEquals(USMarketHoliday.MemorialDay, USMarketHolidays.fullHoliday(day(2026, 5, 25)))
    @Test fun juneteenth2026() = assertEquals(USMarketHoliday.Juneteenth, USMarketHolidays.fullHoliday(day(2026, 6, 19)))
    @Test fun independence2026_observedFridayJul3() {
        assertEquals(USMarketHoliday.IndependenceDay, USMarketHolidays.fullHoliday(day(2026, 7, 3)))
        assertNull(USMarketHolidays.fullHoliday(day(2026, 7, 4))) // the Saturday itself is not a market day anyway
    }
    @Test fun labor2026_firstMondaySep() = assertEquals(USMarketHoliday.LaborDay, USMarketHolidays.fullHoliday(day(2026, 9, 7)))
    @Test fun thanksgiving2026_fourthThursdayNov() = assertEquals(USMarketHoliday.Thanksgiving, USMarketHolidays.fullHoliday(day(2026, 11, 26)))
    @Test fun christmas2026() = assertEquals(USMarketHoliday.Christmas, USMarketHolidays.fullHoliday(day(2026, 12, 25)))

    // ---- 2027 spot checks (rules, not a copied table) ----

    @Test fun newYears2027_fridayUnshifted() = assertEquals(USMarketHoliday.NewYearsDay, USMarketHolidays.fullHoliday(day(2027, 1, 1)))
    @Test fun goodFriday2027() = assertEquals(USMarketHoliday.GoodFriday, USMarketHolidays.fullHoliday(day(2027, 3, 26)))
    @Test fun juneteenth2027_saturdayObservedFriday() {
        assertEquals(USMarketHoliday.Juneteenth, USMarketHolidays.fullHoliday(day(2027, 6, 18)))
        assertNull(USMarketHolidays.fullHoliday(day(2027, 6, 19)))
    }
    @Test fun independence2027_sundayObservedMonday() =
        assertEquals(USMarketHoliday.IndependenceDay, USMarketHolidays.fullHoliday(day(2027, 7, 5)))
    @Test fun christmas2027_saturdayObservedFriday() =
        assertEquals(USMarketHoliday.Christmas, USMarketHolidays.fullHoliday(day(2027, 12, 24)))

    // ---- year-boundary: New Year's observed in the PRIOR calendar year ----

    @Test fun newYears2028_saturdayObservedPriorDec31() {
        // Jan 1 2028 is a Saturday -> NYSE observes it on Friday Dec 31 2027. The observed
        // day lives in civil year 2027 while the rule belongs to year 2028 — the lookup
        // must bridge that boundary (New Year's is the only holiday that can cross it).
        assertEquals(USMarketHoliday.NewYearsDay, USMarketHolidays.fullHoliday(day(2027, 12, 31)))
        assertNull(USMarketHolidays.fullHoliday(day(2028, 1, 1)))
    }

    // ---- Good Friday across years pins the Easter math ----

    @Test fun goodFriday2028() = assertEquals(USMarketHoliday.GoodFriday, USMarketHolidays.fullHoliday(day(2028, 4, 14)))

    // ---- non-holidays ----

    @Test fun plainWednesdayIsNoHoliday() = assertNull(USMarketHolidays.fullHoliday(day(2026, 7, 15)))

    // ---- half-days (13:00 ET close) ----

    @Test fun dayAfterThanksgiving2026IsHalfDay() = assertTrue(USMarketHolidays.isHalfDay(day(2026, 11, 27)))
    @Test fun christmasEve2026_thursdayIsHalfDay() = assertTrue(USMarketHolidays.isHalfDay(day(2026, 12, 24)))
    @Test fun july3_2026_isFullClosureNotHalfDay() {
        // July 4 2026 is Saturday -> observed ON July 3, which therefore is a FULL closure.
        assertFalse(USMarketHolidays.isHalfDay(day(2026, 7, 3)))
    }
    @Test fun july3_2025_wasHalfDay() = assertTrue(USMarketHolidays.isHalfDay(day(2025, 7, 3))) // Thu, Jul 4 2025 = Fri
    @Test fun christmasEve2027_saturdayIsNotHalfDay() = assertFalse(USMarketHolidays.isHalfDay(day(2027, 12, 24).let { it })) // Dec 24 2027 is the OBSERVED Christmas (full closure)
    @Test fun plainDayIsNotHalfDay() = assertFalse(USMarketHolidays.isHalfDay(day(2026, 7, 15)))
}
