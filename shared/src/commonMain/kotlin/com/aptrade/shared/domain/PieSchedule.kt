package com.aptrade.shared.domain

/**
 * Cadence-based contribution due-day generation for [Pie] schedules, holiday-aware via
 * [MarketCalendar]. Pure object: every result here is a deterministic function of its
 * parameters — no wall-clock reads anywhere, so callers pass "now" in explicitly and every
 * result stays testable.
 *
 * Transcribed from `Sources/APTradeDomain/PieSchedule.swift` (the shipped M7.1 Swift/macOS
 * reference), AS-BUILT — including the M7.1 review corrections already baked into that file
 * (window checks against the *rolled* due day, `nextDueDay`'s step-0-unrolled/step-N-rolled
 * split). Kotlin has no Foundation `Calendar`/`Date`; [MarketCalendar] here works purely in
 * epoch-day (days since 1970-01-01, market-local) arithmetic, so day strings are parsed
 * straight to epoch days ([parseDay]) instead of through a `Date` intermediate, and monthly
 * cadence stepping walks civil (year, month, day) triples directly rather than delegating to
 * `Calendar.date(byAdding:)`. The resulting semantics are identical; only the representation
 * differs.
 *
 * Day strings are always "yyyy-MM-dd" in market-local (ET) terms. Comparisons between them
 * are plain `String` comparisons, which sort correctly because the ISO shape is
 * lexicographically ordered the same as chronological order.
 */
object PieSchedule {

    /**
     * Defensive iteration cap for the step-search loops below. Cadence steps are strictly
     * monotonic in real usage, so these loops terminate almost immediately; the cap only
     * guards against a pathological/malformed window.
     */
    private const val MAX_STEPS = 10_000

    /**
     * Parses "yyyy-MM-dd" into a market-local epoch day (days since 1970-01-01); `null` on
     * malformed input (wrong shape, non-numeric components, out-of-range values, or a civil
     * date that would silently normalize, e.g. "2026-02-30" -> "2026-03-02"). Strict about
     * shape: exactly 10 characters, dashes at positions 4 and 7, digits everywhere else — no
     * single-digit month/day shorthand.
     *
     * Deliberately kept on [PieSchedule] rather than [MarketCalendar]: it is the inverse of
     * [MarketCalendar.dayString] but has no need for calendar/holiday state, and the two
     * callers that need it (this object's own [rollToTradingDay]/[dueDays]/[nextDueDay]) are
     * both here.
     */
    fun parseDay(day: String): Long? {
        if (day.length != 10 || day[4] != '-' || day[7] != '-') return null

        val yearPart = day.substring(0, 4)
        val monthPart = day.substring(5, 7)
        val dayPart = day.substring(8, 10)
        if (!yearPart.all(Char::isDigit) || !monthPart.all(Char::isDigit) || !dayPart.all(Char::isDigit)) {
            return null
        }

        val year = yearPart.toLong()
        val month = monthPart.toInt()
        val dayOfMonth = dayPart.toInt()
        if (year !in 1..9999 || month !in 1..12 || dayOfMonth !in 1..31) return null

        val epochDay = daysFromCivil(year, month, dayOfMonth)

        // Reject inputs that don't round-trip (e.g. Feb 30 -> Mar 2) rather than silently
        // normalizing them — mirrors the Swift reference's re-parse check.
        val (reYear, reMonth, reDay) = civilFromDays(epochDay)
        if (reYear != year || reMonth != month || reDay != dayOfMonth) return null

        return epochDay
    }

    /**
     * First trading day >= [day] (skips weekends and [MarketCalendar.holiday] days).
     * Half-days are trading days and are never skipped. On malformed input, or if the cap is
     * exhausted (unreachable with real calendars — no run of 10+ consecutive non-trading
     * days exists), returns [day] unchanged.
     */
    fun rollToTradingDay(day: String, calendar: MarketCalendar): String {
        var current = parseDay(day) ?: return day

        repeat(10) {
            val weekday = isoWeekday(current)
            val isWeekend = weekday == 6 || weekday == 7
            if (!isWeekend && calendar.holiday(current) == null) {
                return calendar.dayString(current)
            }
            current += 1
        }
        return day
    }

    /**
     * All contribution days in (`afterDay`, `throughDay`], stepping [cadence] from
     * [anchorDay] (weekly +7d, biweekly +14d, monthly +1 month clamped — day-of-month
     * clamped to the target month's length, e.g. Jan 31 -> Feb 28, computed from civil
     * (year, month, day) math rather than hand-rolled day counting; each monthly step is
     * `anchor + n months`, computed from the original anchor every time, never chained off
     * a previous result, so there is no cumulative drift). The `(afterDay, throughDay]`
     * window check is against each cadence step's *rolled* value — the actual due day money
     * moves on — not the unrolled calendar step. Checking the unrolled step instead would
     * let a step whose roll pushes it past `throughDay` through, and could drop a step
     * forever: a step landing just before `afterDay` unrolled but rolling to just after it
     * would fail both this window's lower bound and the *next* window's (since by then it's
     * already <= that window's `afterDay` too). Because roll only ever moves a date forward
     * and cadence steps are spaced at least a week apart, rolled values are monotonic
     * non-decreasing in step order, so breaking the loop the first time a rolled candidate
     * exceeds `throughDay` is safe. Stepping starts at `anchor + 1*cadence` — the bare
     * anchor itself is never a [dueDays] candidate; [nextDueDay] is the entry point that
     * treats the anchor as eligible when it's the very first due day of a fresh schedule.
     * Sorted ascending, deduped. Malformed [anchorDay] silently falls back to an empty list
     * (mirrors [rollToTradingDay]'s return-input fallback on malformed input) rather than
     * throwing.
     */
    fun dueDays(
        anchorDay: String,
        cadence: PieCadence,
        afterDay: String,
        throughDay: String,
        calendar: MarketCalendar,
    ): List<String> {
        if (afterDay >= throughDay) return emptyList()
        val anchor = parseDay(anchorDay) ?: return emptyList()

        val results = mutableListOf<String>()
        var step = 1
        while (step < MAX_STEPS) {
            val candidate = candidateEpochDay(anchor, cadence, step)
            val unrolledDay = calendar.dayString(candidate)
            val rolledDay = rollToTradingDay(unrolledDay, calendar)
            if (rolledDay > throughDay) break
            if (rolledDay > afterDay) {
                results.add(rolledDay)
            }
            step += 1
        }
        return results.toSortedSet().toList()
    }

    /**
     * The single next contribution day strictly after [afterDay], stepping [cadence] from
     * [anchorDay], returning the first *rolled* candidate (the actual trading day money
     * moves on) that is strictly after [afterDay]. Falls back to [afterDay] on malformed
     * [anchorDay] or if the cap is exhausted — both unreachable with real calendars, since
     * cadence steps are strictly monotonic.
     *
     * Step 0 (the anchor itself) is eligible, unlike [dueDays], since this is the entry
     * point that answers "what's the very first due day of a fresh schedule" — its
     * eligibility is judged against the *unrolled* anchor day: the anchor is the
     * user-configured reference date, and whether that reference has already elapsed is
     * naturally judged in calendar terms, not by where it happens to land once rolled onto
     * a trading day. Every later step (>= 1) is judged against its *rolled* value instead,
     * since what actually matters for "has this occurrence already happened" is the real
     * trading day the contribution fires on. Comparing a later step's unrolled value would
     * reproduce the same bug [dueDays] had: a step landing just before [afterDay] unrolled
     * but rolling to just after it would be wrongly skipped, silently jumping to the
     * following cadence step instead.
     */
    fun nextDueDay(
        anchorDay: String,
        cadence: PieCadence,
        afterDay: String,
        calendar: MarketCalendar,
    ): String {
        val anchor = parseDay(anchorDay) ?: return afterDay

        var step = 0
        while (step < MAX_STEPS) {
            val candidate = candidateEpochDay(anchor, cadence, step)
            val unrolledDay = calendar.dayString(candidate)
            if (step == 0) {
                if (unrolledDay > afterDay) return rollToTradingDay(unrolledDay, calendar)
            } else {
                val rolledDay = rollToTradingDay(unrolledDay, calendar)
                if (rolledDay > afterDay) return rolledDay
            }
            step += 1
        }
        return afterDay
    }

    /** The [step]th cadence-spaced epoch day from [anchor] (step 0 is the anchor itself). */
    private fun candidateEpochDay(anchor: Long, cadence: PieCadence, step: Int): Long =
        when (cadence) {
            PieCadence.Weekly -> anchor + 7L * step
            PieCadence.Biweekly -> anchor + 14L * step
            PieCadence.Monthly -> addMonthsClamped(anchor, step)
        }

    /**
     * [anchor] plus [months] calendar months, with the day-of-month clamped to the target
     * month's length when it overflows (e.g. Jan 31 + 1 month -> Feb 28, not Mar 3/2/1).
     * Mirrors Foundation's `Calendar.date(byAdding: .month, ...)` clamping behavior, which
     * the Swift reference relies on (see the file doc comment) — reproduced here by hand
     * since Kotlin `commonMain` has no such calendar API.
     */
    private fun addMonthsClamped(anchor: Long, months: Int): Long {
        val (year, month, day) = civilFromDays(anchor)
        val totalMonths = year * 12 + (month - 1) + months
        val newYear = floorDiv(totalMonths, 12L)
        val newMonth = (floorMod(totalMonths, 12L) + 1).toInt()
        val clampedDay = minOf(day, daysInMonth(newYear, newMonth))
        return daysFromCivil(newYear, newMonth, clampedDay)
    }

    /** Number of days in [month] of [year] (proleptic Gregorian). */
    private fun daysInMonth(year: Long, month: Int): Int {
        val thisMonthFirst = daysFromCivil(year, month, 1)
        val nextMonthFirst = if (month == 12) daysFromCivil(year + 1, 1, 1) else daysFromCivil(year, month + 1, 1)
        return (nextMonthFirst - thisMonthFirst).toInt()
    }

    // --- Hinnant civil-date math (private copies, pure Kotlin). MarketCalendar.kt and
    // USMarketHolidays.kt each keep their own private copy of this same algorithm rather
    // than exposing it more broadly (see USMarketHolidays' file doc: "this object must not
    // reach into that class") — this file follows that established precedent instead of
    // widening MarketCalendar's private members to `internal`, keeping each type's date
    // math self-contained and independently reviewable. ---

    private fun floorMod(x: Long, y: Long): Long {
        val r = x % y
        return if (r < 0) r + y else r
    }

    private fun floorDiv(x: Long, y: Long): Long {
        val q = x / y
        return if ((x xor y) < 0 && q * y != x) q - 1 else q
    }

    /** ISO weekday: 1 = Monday ... 7 = Sunday (proleptic Gregorian, matches civil day count). */
    private fun isoWeekday(localEpochDay: Long): Int {
        // Epoch day 0 = 1970-01-01, which was a Thursday (ISO weekday 4).
        val shifted = floorMod(localEpochDay + 3, 7L) // 0 = Monday .. 6 = Sunday
        return (shifted + 1).toInt()
    }

    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = floorDiv(y, 400L)
        val yoe = y - era * 400L // [0, 399]
        val mp = if (m > 2) m - 3 else m + 9 // [0, 11]
        val doy = (153 * mp + 2) / 5 + d - 1 // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
        return era * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097L // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
        val mp = (5 * doy + 2) / 153 // [0, 11]
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt() // [1, 31]
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt() // [1, 12]
        val year = if (m <= 2) y + 1 else y
        return Triple(year, m, d)
    }
}
