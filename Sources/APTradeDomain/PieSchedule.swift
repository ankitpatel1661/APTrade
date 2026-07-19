import Foundation

/// Cadence-based contribution due-day generation for `Pie` schedules, holiday-aware via
/// `MarketCalendar`. Pure namespace: no `Date()` calls anywhere — time flows in as
/// parameters, so every result here is deterministic and testable.
///
/// Day strings are always "yyyy-MM-dd" in market-local (ET) terms. Comparisons between
/// them are plain `String` comparisons, which sort correctly because the ISO shape is
/// lexicographically ordered the same as chronological order.
public enum PieSchedule {

    /// A dedicated Gregorian/ET calendar for parsing day strings into `Date`s. Kept
    /// separate from `MarketCalendar`'s own (private) calendar since this type has no
    /// access to it — the two are independently constructed but agree on the timezone,
    /// so results (via `MarketCalendar.tradingDay(of:)`) stay in lockstep.
    private static var parsingCalendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        return cal
    }

    /// Defensive iteration cap for the step-search loops below. Cadence steps are
    /// strictly monotonic in real usage, so these loops terminate almost immediately;
    /// the cap only guards against a pathological/malformed window.
    private static let maxSteps = 10_000

    /// Parses "yyyy-MM-dd" at noon ET; nil on malformed input (wrong shape, non-numeric
    /// components, out-of-range values, or a civil date Foundation would silently
    /// normalize, e.g. "2026-02-30").
    public static func date(fromDay day: String, calendar: MarketCalendar) -> Date? {
        let parts = day.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let year = Int(parts[0]), let month = Int(parts[1]), let dayOfMonth = Int(parts[2]),
              (1...9999).contains(year), (1...12).contains(month), (1...31).contains(dayOfMonth)
        else { return nil }

        let components = DateComponents(year: year, month: month, day: dayOfMonth, hour: 12, minute: 0)
        guard let result = parsingCalendar.date(from: components) else { return nil }

        // Reject inputs Foundation silently normalized (e.g. Feb 30 -> Mar 2) rather than
        // rejected outright.
        let reparsed = parsingCalendar.dateComponents([.year, .month, .day], from: result)
        guard reparsed.year == year, reparsed.month == month, reparsed.day == dayOfMonth else {
            return nil
        }
        return result
    }

    /// First trading day >= `day` (skips weekends and `calendar.holiday(on:)` days).
    /// Half-days are trading days and are never skipped. On malformed input, or if the
    /// cap is exhausted (unreachable with real calendars — no run of 10+ consecutive
    /// non-trading days exists), returns `day` unchanged.
    public static func rollToTradingDay(_ day: String, calendar: MarketCalendar) -> String {
        guard var current = date(fromDay: day, calendar: calendar) else { return day }

        for _ in 0..<10 {
            let weekday = parsingCalendar.component(.weekday, from: current) // 1=Sun...7=Sat
            let isWeekend = weekday == 1 || weekday == 7
            if !isWeekend && calendar.holiday(on: current) == nil {
                return calendar.tradingDay(of: current)
            }
            guard let next = parsingCalendar.date(byAdding: .day, value: 1, to: current) else {
                return day
            }
            current = next
        }
        return day
    }

    /// All contribution days in (`afterDay`, `throughDay`], stepping `cadence` from
    /// `anchorDay` (weekly +7d, biweekly +14d, monthly +1 month clamped — via
    /// `DateComponents(month:)` so overflow, e.g. Jan 31 -> Feb 28, comes from
    /// Foundation, not hand math). The window check is against each *unrolled* cadence
    /// step; the result is then rolled forward to the nearest trading day, so a step
    /// that lands exactly on a holiday is still captured and reported at its rolled
    /// date. Sorted ascending, deduped.
    public static func dueDays(
        anchorDay: String,
        cadence: PieCadence,
        afterDay: String,
        throughDay: String,
        calendar: MarketCalendar
    ) -> [String] {
        guard afterDay < throughDay else { return [] }
        guard let anchor = date(fromDay: anchorDay, calendar: calendar) else { return [] }

        var results: [String] = []
        var step = 0
        while step < maxSteps {
            guard let candidate = candidateDate(anchor: anchor, cadence: cadence, step: step) else {
                break
            }
            let candidateDay = calendar.tradingDay(of: candidate)
            if candidateDay > throughDay { break }
            if candidateDay > afterDay {
                results.append(rollToTradingDay(candidateDay, calendar: calendar))
            }
            step += 1
        }
        return Array(Set(results)).sorted()
    }

    /// The single next contribution day strictly after `afterDay`, stepping `cadence`
    /// from `anchorDay` (the anchor day itself is eligible when it falls after
    /// `afterDay`), rolled forward to the nearest trading day. Falls back to `afterDay`
    /// on malformed `anchorDay` or if the cap is exhausted — both unreachable with real
    /// calendars, since cadence steps are strictly monotonic.
    public static func nextDueDay(
        anchorDay: String,
        cadence: PieCadence,
        afterDay: String,
        calendar: MarketCalendar
    ) -> String {
        guard let anchor = date(fromDay: anchorDay, calendar: calendar) else { return afterDay }

        var step = 0
        while step < maxSteps {
            guard let candidate = candidateDate(anchor: anchor, cadence: cadence, step: step) else {
                return afterDay
            }
            let candidateDay = calendar.tradingDay(of: candidate)
            if candidateDay > afterDay {
                return rollToTradingDay(candidateDay, calendar: calendar)
            }
            step += 1
        }
        return afterDay
    }

    /// The `step`th cadence-spaced date from `anchor` (step 0 is the anchor itself).
    private static func candidateDate(anchor: Date, cadence: PieCadence, step: Int) -> Date? {
        switch cadence {
        case .weekly:
            return parsingCalendar.date(byAdding: .day, value: 7 * step, to: anchor)
        case .biweekly:
            return parsingCalendar.date(byAdding: .day, value: 14 * step, to: anchor)
        case .monthly:
            return parsingCalendar.date(byAdding: .month, value: step, to: anchor)
        }
    }
}
