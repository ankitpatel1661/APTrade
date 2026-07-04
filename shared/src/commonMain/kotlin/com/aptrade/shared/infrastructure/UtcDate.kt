package com.aptrade.shared.infrastructure

private const val SECONDS_PER_DAY_UTC = 86_400L

/**
 * Formats a Unix epoch-seconds instant as a UTC calendar date `yyyy-MM-dd`, with no
 * java.time / kotlinx-datetime dependency (commonMain has neither available).
 *
 * Day -> (year, month, day) conversion is Howard Hinnant's `civil_from_days` algorithm
 * (proleptic Gregorian, exact for the full int range, including leap years) —
 * http://howardhinnant.github.io/date_algorithms.html#civil_from_days
 */
fun formatUtcDate(epochSeconds: Long): String {
    val days = floorDiv(epochSeconds, SECONDS_PER_DAY_UTC)
    val (year, month, day) = civilFromDays(days)
    val monthStr = if (month < 10) "0$month" else "$month"
    val dayStr = if (day < 10) "0$day" else "$day"
    val yearStr = year.toString().padStart(4, '0')
    return "$yearStr-$monthStr-$dayStr"
}

/** Floor division (rounds toward negative infinity), unlike Kotlin's truncating `/`. */
private fun floorDiv(x: Long, y: Long): Long {
    val q = x / y
    return if ((x xor y) < 0 && q * y != x) q - 1 else q
}

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
