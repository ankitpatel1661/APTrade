package com.aptrade.shared.domain

/** Whether the US equity market is in its regular session. Mirrors the macOS
 *  `MarketStatus` (Sources/APTradeDomain/MarketCalendar.swift) exactly. */
enum class MarketStatus {
    OPEN,
    CLOSED,
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L

// US-Eastern standard-time offset from UTC, in seconds (UTC-5, i.e. EST).
private const val EASTERN_STANDARD_OFFSET_SECONDS = -5 * SECONDS_PER_HOUR

// US-Eastern daylight-time offset from UTC, in seconds (UTC-4, i.e. EDT).
private const val EASTERN_DAYLIGHT_OFFSET_SECONDS = -4 * SECONDS_PER_HOUR

/**
 * Pure US-equity regular-session calendar: weekdays 09:30-16:00 America/New_York.
 *
 * Transcribed from Sources/APTradeDomain/MarketCalendar.swift. Holiday-aware: full NYSE
 * closures and half-days (13:00 ET close) are resolved via USMarketHolidays and applied
 * in status() below.
 *
 * DST HANDLING — READ BEFORE CHANGING TIMES:
 * The Swift implementation resolves "America/New_York" via `Foundation.TimeZone`, which
 * consults the IANA tz database and therefore observes US daylight saving time
 * automatically. Kotlin's `commonMain` has no IANA tz database available (no java.time,
 * no kotlinx-datetime dependency in this module — see shared/build.gradle.kts), so this
 * port instead implements the US statutory DST rule directly, in pure Kotlin, using the
 * civil-date math already present below (civilFromDays / isoWeekday):
 *
 *   - Eastern Daylight Time (UTC-4) applies from the 2nd Sunday of March through the
 *     1st Sunday of November, inclusive of those two Sundays.
 *   - Eastern Standard Time (UTC-5) applies otherwise.
 *
 * This is the rule set by the Energy Policy Act of 2005, in effect since 2007; dates
 * before 2007 used different transition dates and are out of scope for this calendar
 * (a paper-trading build has no reason to model pre-2007 market sessions).
 *
 * The rule is applied at DATE granularity rather than instant granularity: DST
 * transitions happen at 2am local time on a Sunday, and the market is closed on
 * Sundays and only open 9:30-16:00 on weekdays, so no market-hours instant this
 * calendar cares about is ever ambiguous or skipped by the transition itself. The
 * offset is decided from the Eastern *civil date*, which is itself derived using a
 * provisional standard-time (UTC-5) offset — the standard way to bootstrap a
 * date-level DST rule without circularity.
 */
class MarketCalendar {

    /** ISO weekday: 1 = Monday ... 7 = Sunday (proleptic Gregorian, matches civil day count). */
    private fun isoWeekday(localEpochDay: Long): Int {
        // Epoch day 0 = 1970-01-01, which was a Thursday (ISO weekday 4).
        val shifted = floorMod(localEpochDay + 3, 7L) // 0 = Monday .. 6 = Sunday
        return (shifted + 1).toInt()
    }

    private fun floorMod(x: Long, y: Long): Long {
        val r = x % y
        return if (r < 0) r + y else r
    }

    private fun floorDiv(x: Long, y: Long): Long {
        val q = x / y
        return if ((x xor y) < 0 && q * y != x) q - 1 else q
    }

    /**
     * Resolves the US-Eastern UTC offset (in seconds) that applies at [atEpochSeconds],
     * per the statutory DST rule documented on the class.
     *
     * The Eastern civil date is first computed using the provisional standard-time
     * (EST, UTC-5) offset, then that date is checked against the DST window
     * [2nd Sunday of March, 1st Sunday of November]. This is safe because, per the
     * class doc, no market-hours instant straddles the transition boundary itself.
     */
    private fun offsetSecondsFor(atEpochSeconds: Long): Long {
        val provisionalLocalSeconds = atEpochSeconds + EASTERN_STANDARD_OFFSET_SECONDS
        val provisionalEpochDay = floorDiv(provisionalLocalSeconds, SECONDS_PER_DAY)
        val (year, _, _) = civilFromDays(provisionalEpochDay)

        val dstStartDay = nthSundayOfMonthAsEpochDay(year, month = 3, n = 2)
        val dstEndDay = nthSundayOfMonthAsEpochDay(year, month = 11, n = 1)

        val isWithinDstWindow = provisionalEpochDay in dstStartDay..dstEndDay
        return if (isWithinDstWindow) EASTERN_DAYLIGHT_OFFSET_SECONDS else EASTERN_STANDARD_OFFSET_SECONDS
    }

    /** Epoch-day (days since 1970-01-01) of the nth Sunday of the given proleptic-Gregorian month. */
    private fun nthSundayOfMonthAsEpochDay(year: Long, month: Int, n: Int): Long {
        val firstOfMonthEpochDay = daysFromCivil(year, month, 1)
        val firstWeekday = isoWeekday(firstOfMonthEpochDay) // 1=Mon .. 7=Sun
        val daysUntilFirstSunday = floorMod((7 - firstWeekday).toLong(), 7L)
        val firstSundayEpochDay = firstOfMonthEpochDay + daysUntilFirstSunday
        return firstSundayEpochDay + (n - 1) * 7L
    }

    // Howard Hinnant's days_from_civil algorithm (proleptic Gregorian) — the inverse of
    // civilFromDays below, needed here to locate "the 1st of March/November" as an
    // epoch day so nthSundayOfMonthAsEpochDay can walk forward to the correct Sunday.
    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = floorDiv(y, 400L)
        val yoe = y - era * 400L // [0, 399]
        val mp = if (m > 2) m - 3 else m + 9 // [0, 11]
        val doy = (153 * mp + 2) / 5 + d - 1 // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
        return era * 146_097L + doe - 719_468L
    }

    fun status(atEpochSeconds: Long): MarketStatus {
        val localSeconds = atEpochSeconds + offsetSecondsFor(atEpochSeconds)
        val localEpochDay = floorDiv(localSeconds, SECONDS_PER_DAY)
        val secondsIntoDay = floorMod(localSeconds, SECONDS_PER_DAY)

        val weekday = isoWeekday(localEpochDay) // 1=Mon ... 7=Sun
        if (weekday !in 1..5) return MarketStatus.CLOSED // weekend

        if (USMarketHolidays.fullHoliday(localEpochDay) != null) return MarketStatus.CLOSED

        val minutesIntoDay = secondsIntoDay / SECONDS_PER_MINUTE
        val openMinute = 9 * 60 + 30
        val closeMinute = if (USMarketHolidays.isHalfDay(localEpochDay)) 13 * 60 else 16 * 60
        return if (minutesIntoDay in openMinute until closeMinute) MarketStatus.OPEN else MarketStatus.CLOSED
    }

    /** `yyyy-MM-dd` in market-local time, used to gate once-per-trading-day work. */
    fun tradingDay(atEpochSeconds: Long): String {
        val localSeconds = atEpochSeconds + offsetSecondsFor(atEpochSeconds)
        val localEpochDay = floorDiv(localSeconds, SECONDS_PER_DAY)
        return formatLocalDate(localEpochDay)
    }

    /** Market-local epoch day for an instant — the key the holiday helpers take. */
    fun localEpochDay(atEpochSeconds: Long): Long {
        val localSeconds = atEpochSeconds + offsetSecondsFor(atEpochSeconds)
        return floorDiv(localSeconds, SECONDS_PER_DAY)
    }

    /** The full-closure holiday on a market-local day, or null on trading days. */
    fun holiday(localEpochDay: Long): USMarketHoliday? = USMarketHolidays.fullHoliday(localEpochDay)

    /** True when the market closes at 13:00 ET on this market-local day. */
    fun isHalfDay(localEpochDay: Long): Boolean = USMarketHolidays.isHalfDay(localEpochDay)

    /** `yyyy-MM-dd` for a market-local epoch day (public face of formatLocalDate). */
    fun dayString(localEpochDay: Long): String = formatLocalDate(localEpochDay)

    private fun formatLocalDate(epochDay: Long): String {
        val (year, month, day) = civilFromDays(epochDay)
        val monthStr = if (month < 10) "0$month" else "$month"
        val dayStr = if (day < 10) "0$day" else "$day"
        val yearStr = year.toString().padStart(4, '0')
        return "$yearStr-$monthStr-$dayStr"
    }

    // Howard Hinnant's civil_from_days algorithm (proleptic Gregorian) — same algorithm
    // already used by shared/infrastructure/UtcDate.kt's formatUtcDate, reused here
    // in local (offset-shifted) days rather than raw UTC days.
    private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097L // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
        val mp = (5 * doy + 2) / 153 // [0, 11]
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt() // [1, 31]
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt() // [1, 12]
        val year = if (m <= 2) y + 1 else y
        return Triple(year, m, d)
    }
}
