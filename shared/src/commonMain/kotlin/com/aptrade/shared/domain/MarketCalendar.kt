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

/**
 * Pure US-equity regular-session calendar: weekdays 09:30-16:00 America/New_York.
 *
 * Transcribed from Sources/APTradeDomain/MarketCalendar.swift. Holidays and half-days
 * are intentionally not modeled there either — this is a paper-trading build, so a
 * Thanksgiving "market open" notification is an acceptable inaccuracy on both platforms.
 * (Roadmap item on both sides: a real US market holiday calendar.)
 *
 * SIMPLIFICATION BEYOND THE SWIFT SOURCE — READ BEFORE CHANGING TIMES:
 * The Swift implementation resolves "America/New_York" via `Foundation.TimeZone`, which
 * consults the IANA tz database and therefore observes US daylight saving time
 * automatically (UTC-5 in winter, UTC-4 in summer, with the actual DST transition
 * dates). Kotlin's `commonMain` has no IANA tz database available (no java.time, no
 * kotlinx-datetime dependency in this module — see shared/build.gradle.kts), so this
 * port uses a FIXED UTC-5 (US Eastern Standard Time) offset year-round. During US
 * daylight saving time (roughly mid-March to early November) this port's `status(at:)`
 * and open/close instants will read ONE HOUR LATE relative to the real America/New_York
 * wall clock (and relative to what the Swift build reports for the same instant). This
 * is a deliberate, tracked simplification — not a bug — and should be revisited if/when
 * a tz-aware dependency is added to commonMain (kotlinx-datetime + tzdb, or platform
 * `expect`/`actual` tz lookup).
 */
class MarketCalendar(private val utcOffsetSeconds: Long = EASTERN_STANDARD_OFFSET_SECONDS) {

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

    fun status(atEpochSeconds: Long): MarketStatus {
        val localSeconds = atEpochSeconds + utcOffsetSeconds
        val localEpochDay = floorDiv(localSeconds, SECONDS_PER_DAY)
        val secondsIntoDay = floorMod(localSeconds, SECONDS_PER_DAY)

        val weekday = isoWeekday(localEpochDay) // 1=Mon ... 7=Sun
        if (weekday !in 1..5) return MarketStatus.CLOSED // weekend

        val minutesIntoDay = secondsIntoDay / SECONDS_PER_MINUTE
        val openMinute = 9 * 60 + 30
        val closeMinute = 16 * 60
        return if (minutesIntoDay in openMinute until closeMinute) MarketStatus.OPEN else MarketStatus.CLOSED
    }

    /** `yyyy-MM-dd` in market-local time, used to gate once-per-trading-day work. */
    fun tradingDay(atEpochSeconds: Long): String {
        val localSeconds = atEpochSeconds + utcOffsetSeconds
        val localEpochDay = floorDiv(localSeconds, SECONDS_PER_DAY)
        return formatLocalDate(localEpochDay)
    }

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
