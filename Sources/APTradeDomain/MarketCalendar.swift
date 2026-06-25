import Foundation

/// Whether the US equity market is in its regular session.
public enum MarketStatus: String, Codable, Sendable {
    case open, closed
}

/// Pure US-equity regular-session calendar: weekdays 09:30–16:00 America/New_York.
/// Holidays and half-days are intentionally not modeled — this is a paper-trading
/// build, so a Thanksgiving "market open" notification is an acceptable inaccuracy.
public struct MarketCalendar: Sendable {
    private let timeZone: TimeZone

    public init(timeZone: TimeZone = TimeZone(identifier: "America/New_York") ?? .gmt) {
        self.timeZone = timeZone
    }

    private var calendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return cal
    }

    public func status(at date: Date) -> MarketStatus {
        let comps = calendar.dateComponents([.weekday, .hour, .minute], from: date)
        guard let weekday = comps.weekday, (2...6).contains(weekday) else { return .closed } // 1=Sun … 7=Sat
        let minutes = (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
        let openMinute = 9 * 60 + 30
        let closeMinute = 16 * 60
        return (minutes >= openMinute && minutes < closeMinute) ? .open : .closed
    }

    /// `yyyy-MM-dd` in market-local time, used to gate once-per-trading-day work.
    public func tradingDay(of date: Date) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}
