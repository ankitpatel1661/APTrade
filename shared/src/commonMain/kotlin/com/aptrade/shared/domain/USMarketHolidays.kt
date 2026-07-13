package com.aptrade.shared.domain

/** The ten NYSE full-closure holidays. Cases map 1:1 to L10n keys (Task 5). */
enum class USMarketHoliday {
    NewYearsDay, MartinLutherKingDay, WashingtonsBirthday, GoodFriday, MemorialDay,
    Juneteenth, IndependenceDay, LaborDay, Thanksgiving, Christmas,
}

/**
 * Pure, computed US equity market holiday rules — valid for any year, nothing expires.
 * All inputs/outputs are market-local epoch days (days since 1970-01-01 in the wall-clock
 * date already localized by MarketCalendar). Uses the same Hinnant civil-date algorithms
 * as MarketCalendar (private copies — this object must not reach into that class).
 *
 * Rules (NYSE):
 *  - Fixed-date holidays (New Year's, Juneteenth, July 4, Christmas) observe
 *    Saturday -> preceding Friday, Sunday -> following Monday.
 *  - Floating: MLK = 3rd Mon Jan; Washington's Birthday = 3rd Mon Feb; Memorial = last
 *    Mon May; Labor = 1st Mon Sep; Thanksgiving = 4th Thu Nov.
 *  - Good Friday = Easter Sunday (anonymous Gregorian algorithm) minus 2 days.
 *  - Half-days (13:00 ET close): day after Thanksgiving; July 3 and December 24 when
 *    they are weekdays NOT already consumed as the observed July 4 / Christmas.
 */
object USMarketHolidays {

    fun fullHoliday(localEpochDay: Long): USMarketHoliday? {
        val (year, _, _) = civilFromDays(localEpochDay)
        return holidaysFor(year)[localEpochDay]
    }

    fun isHalfDay(localEpochDay: Long): Boolean {
        val (year, _, _) = civilFromDays(localEpochDay)
        if (holidaysFor(year).containsKey(localEpochDay)) return false
        val weekday = isoWeekday(localEpochDay)
        if (weekday !in 1..5) return false
        val dayAfterThanksgiving = nthWeekdayOfMonth(year, month = 11, isoWeekday = 4, n = 4) + 1
        if (localEpochDay == dayAfterThanksgiving) return true
        val july3 = daysFromCivil(year, 7, 3)
        val christmasEve = daysFromCivil(year, 12, 24)
        return localEpochDay == july3 || localEpochDay == christmasEve
    }

    // Cached per-year tables — the calendar screen queries 14 consecutive days.
    private val cache = HashMap<Long, Map<Long, USMarketHoliday>>()

    private fun holidaysFor(year: Long): Map<Long, USMarketHoliday> = cache.getOrPut(year) {
        buildMap {
            put(observed(daysFromCivil(year, 1, 1)), USMarketHoliday.NewYearsDay)
            put(nthWeekdayOfMonth(year, 1, isoWeekday = 1, n = 3), USMarketHoliday.MartinLutherKingDay)
            put(nthWeekdayOfMonth(year, 2, isoWeekday = 1, n = 3), USMarketHoliday.WashingtonsBirthday)
            put(easterSunday(year) - 2, USMarketHoliday.GoodFriday)
            put(lastWeekdayOfMonth(year, 5, isoWeekday = 1), USMarketHoliday.MemorialDay)
            put(observed(daysFromCivil(year, 6, 19)), USMarketHoliday.Juneteenth)
            put(observed(daysFromCivil(year, 7, 4)), USMarketHoliday.IndependenceDay)
            put(nthWeekdayOfMonth(year, 9, isoWeekday = 1, n = 1), USMarketHoliday.LaborDay)
            put(nthWeekdayOfMonth(year, 11, isoWeekday = 4, n = 4), USMarketHoliday.Thanksgiving)
            put(observed(daysFromCivil(year, 12, 25)), USMarketHoliday.Christmas)
        }
    }

    /** Saturday -> Friday before; Sunday -> Monday after; weekday unchanged. */
    private fun observed(epochDay: Long): Long = when (isoWeekday(epochDay)) {
        6 -> epochDay - 1
        7 -> epochDay + 1
        else -> epochDay
    }

    /** Epoch day of the nth <isoWeekday> (1=Mon..7=Sun) of the month. */
    private fun nthWeekdayOfMonth(year: Long, month: Int, isoWeekday: Int, n: Int): Long {
        val first = daysFromCivil(year, month, 1)
        val delta = floorMod((isoWeekday - isoWeekday(first)).toLong(), 7L)
        return first + delta + (n - 1) * 7L
    }

    /** Epoch day of the last <isoWeekday> of the month. */
    private fun lastWeekdayOfMonth(year: Long, month: Int, isoWeekday: Int): Long {
        val nextMonthFirst = if (month == 12) daysFromCivil(year + 1, 1, 1) else daysFromCivil(year, month + 1, 1)
        val last = nextMonthFirst - 1
        val delta = floorMod((isoWeekday(last) - isoWeekday).toLong(), 7L)
        return last - delta
    }

    /** Anonymous Gregorian Easter algorithm -> epoch day of Easter Sunday. */
    private fun easterSunday(year: Long): Long {
        val y = year
        val a = y % 19
        val b = y / 100
        val c = y % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = ((h + l - 7 * m + 114) / 31).toInt()
        val day = ((h + l - 7 * m + 114) % 31 + 1).toInt()
        return daysFromCivil(y, month, day)
    }

    // --- Hinnant civil-date math (private copies, pure Kotlin — identical to
    // MarketCalendar's; commonMain has no java.lang.Math, so this cannot delegate to it). ---

    private fun floorMod(x: Long, y: Long): Long {
        val r = x % y
        return if (r < 0) r + y else r
    }

    private fun floorDiv(x: Long, y: Long): Long {
        val q = x / y
        return if ((x xor y) < 0 && q * y != x) q - 1 else q
    }

    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = floorDiv(y, 400L)
        val yoe = y - era * 400L
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = if (m <= 2) y + 1 else y
        return Triple(year, m, d)
    }

    private fun isoWeekday(epochDay: Long): Int = (floorMod(epochDay + 3, 7L) + 1).toInt()
}
