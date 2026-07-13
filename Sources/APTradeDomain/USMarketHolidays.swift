import Foundation

/// The ten NYSE full-closure holidays. Twin of shared/commonMain USMarketHolidays.kt —
/// keep the rule tables in lockstep.
public enum USMarketHoliday: Sendable, Equatable {
    case newYearsDay, martinLutherKingDay, washingtonsBirthday, goodFriday, memorialDay
    case juneteenth, independenceDay, laborDay, thanksgiving, christmas
}

/// Pure, computed US equity market holiday rules — valid for any year, nothing expires.
/// Inputs are civil dates in market-local (ET) wall-clock terms; `MarketCalendar` supplies
/// those from a `Date` via `Calendar.dateComponents`. Uses the same Hinnant civil-date
/// algorithms as the Kotlin twin (`shared/commonMain/.../USMarketHolidays.kt`) — pure
/// integer math throughout, no floating point in the date math.
///
/// Rules (NYSE):
///  - Fixed-date holidays (New Year's, Juneteenth, July 4, Christmas) observe
///    Saturday -> preceding Friday, Sunday -> following Monday.
///  - Floating: MLK = 3rd Mon Jan; Washington's Birthday = 3rd Mon Feb; Memorial = last
///    Mon May; Labor = 1st Mon Sep; Thanksgiving = 4th Thu Nov.
///  - Good Friday = Easter Sunday (anonymous Gregorian algorithm) minus 2 days.
///  - Half-days (13:00 ET close): day after Thanksgiving; July 3 and December 24 when
///    they are weekdays NOT already consumed as the observed July 4 / Christmas.
///
/// CACHING: deliberately none, unlike the Kotlin twin's per-year `HashMap` cache. That
/// cache's safety rests on single-thread confinement documented in its KDoc; under Swift 6
/// strict concurrency a static mutable cache would need real synchronization (an actor or
/// a lock) to stay `Sendable`-safe, and the per-year table is only 10 entries of integer
/// math — cheap enough to recompute on every call. Simpler and still correct.
public enum USMarketHolidays {

    /// The full-closure holiday on this civil date, or nil on trading days.
    public static func fullHoliday(year: Int, month: Int, day: Int) -> USMarketHoliday? {
        let epochDay = daysFromCivil(year, month, day)
        // Year+1's table is consulted too: when Jan 1 of year+1 falls on a Saturday, its
        // observed closure is Dec 31 of THIS year (e.g. Fri 2027-12-31 for Jan 1 2028).
        // New Year's is the only holiday whose observed day can cross a year boundary,
        // so the shifted New Year's entry is the only year+1 entry that can match here.
        return holidays(year: year)[epochDay] ?? holidays(year: year + 1)[epochDay]
    }

    /// True when the market closes at 13:00 ET on this civil date.
    public static func isHalfDay(year: Int, month: Int, day: Int) -> Bool {
        // Route through fullHoliday (not holidays(year:) directly) so the cross-year
        // observed New Year's on Dec 31 is suppressed here exactly as it is reported there.
        if fullHoliday(year: year, month: month, day: day) != nil { return false }
        let epochDay = daysFromCivil(year, month, day)
        let weekday = isoWeekday(epochDay)
        guard (1...5).contains(weekday) else { return false }
        let dayAfterThanksgiving = nthWeekday(year: year, month: 11, isoWeekday: 4, n: 4) + 1
        if epochDay == dayAfterThanksgiving { return true }
        return epochDay == daysFromCivil(year, 7, 3) || epochDay == daysFromCivil(year, 12, 24)
    }

    private static func holidays(year: Int) -> [Int: USMarketHoliday] {
        var map: [Int: USMarketHoliday] = [:]
        map[observed(daysFromCivil(year, 1, 1))] = .newYearsDay
        map[nthWeekday(year: year, month: 1, isoWeekday: 1, n: 3)] = .martinLutherKingDay
        map[nthWeekday(year: year, month: 2, isoWeekday: 1, n: 3)] = .washingtonsBirthday
        map[easterSunday(year) - 2] = .goodFriday
        map[lastWeekday(year: year, month: 5, isoWeekday: 1)] = .memorialDay
        map[observed(daysFromCivil(year, 6, 19))] = .juneteenth
        map[observed(daysFromCivil(year, 7, 4))] = .independenceDay
        map[nthWeekday(year: year, month: 9, isoWeekday: 1, n: 1)] = .laborDay
        map[nthWeekday(year: year, month: 11, isoWeekday: 4, n: 4)] = .thanksgiving
        map[observed(daysFromCivil(year, 12, 25))] = .christmas
        return map
    }

    /// Saturday -> Friday before; Sunday -> Monday after; weekday unchanged.
    private static func observed(_ epochDay: Int) -> Int {
        switch isoWeekday(epochDay) {
        case 6: return epochDay - 1
        case 7: return epochDay + 1
        default: return epochDay
        }
    }

    /// Epoch day of the nth <isoWeekday> (1=Mon..7=Sun) of the month.
    private static func nthWeekday(year: Int, month: Int, isoWeekday target: Int, n: Int) -> Int {
        let first = daysFromCivil(year, month, 1)
        let delta = mod(target - isoWeekday(first), 7)
        return first + delta + (n - 1) * 7
    }

    /// Epoch day of the last <isoWeekday> of the month.
    private static func lastWeekday(year: Int, month: Int, isoWeekday target: Int) -> Int {
        let nextFirst = month == 12 ? daysFromCivil(year + 1, 1, 1) : daysFromCivil(year, month + 1, 1)
        let last = nextFirst - 1
        return last - mod(isoWeekday(last) - target, 7)
    }

    /// Anonymous Gregorian Easter algorithm -> epoch day of Easter Sunday.
    private static func easterSunday(_ year: Int) -> Int {
        let a = year % 19, b = year / 100, c = year % 100
        let d = b / 4, e = b % 4
        let f = (b + 8) / 25, g = (b - f + 1) / 3
        let h = (19 * a + b - d - g + 15) % 30
        let i = c / 4, k = c % 4
        let l = (32 + 2 * e + 2 * i - h - k) % 7
        let m = (a + 11 * h + 22 * l) / 451
        let month = (h + l - 7 * m + 114) / 31
        let day = (h + l - 7 * m + 114) % 31 + 1
        return daysFromCivil(year, month, day)
    }

    private static func mod(_ a: Int, _ b: Int) -> Int { ((a % b) + b) % b }

    // --- Hinnant civil-date math (private copy, pure Int — no floating point). ---

    private static func daysFromCivil(_ y0: Int, _ m: Int, _ d: Int) -> Int {
        let y = m <= 2 ? y0 - 1 : y0
        let era = y >= 0 ? y / 400 : (y - 399) / 400
        let yoe = y - era * 400
        let mp = m > 2 ? m - 3 : m + 9
        let doy = (153 * mp + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private static func isoWeekday(_ epochDay: Int) -> Int { mod(epochDay + 3, 7) + 1 }
}
