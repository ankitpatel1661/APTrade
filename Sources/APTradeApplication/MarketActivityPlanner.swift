import Foundation
import APTradeDomain

/// A scheduled notification the app should deliver this tick. `digestDue` carries no
/// content — the caller builds the digest body from live data.
public enum ScheduledNotification: Equatable, Sendable {
    case marketOpened
    case marketClosed
    case digestDue
}

/// Persisted markers so scheduled notifications fire once per event rather than every
/// poll, and survive relaunches (no duplicate digest after restarting mid-day).
public struct SchedulerState: Codable, Equatable, Sendable {
    public var lastStatus: MarketStatus?
    public var lastDigestDay: String?

    public init(lastStatus: MarketStatus? = nil, lastDigestDay: String? = nil) {
        self.lastStatus = lastStatus
        self.lastDigestDay = lastDigestDay
    }
}

/// Pure decision policy for time-based notifications. Given the current instant, the
/// last persisted state, and the user's preferences, it returns which notifications are
/// due and the state to persist. No clocks, timers, or I/O — fully testable.
public struct MarketActivityPlanner: Sendable {
    private let calendar: MarketCalendar

    public init(calendar: MarketCalendar = MarketCalendar()) {
        self.calendar = calendar
    }

    public func plan(now: Date, state: SchedulerState, settings: AppSettings)
        -> (events: [ScheduledNotification], state: SchedulerState) {
        var newState = state
        var events: [ScheduledNotification] = []
        let status = calendar.status(at: now)

        // Open/close transition. The first observation only seeds the baseline (no event),
        // so launching mid-session never fires a spurious "market opened".
        if let last = state.lastStatus, last != status, settings.marketOpenClose {
            events.append(status == .open ? .marketOpened : .marketClosed)
        }
        newState.lastStatus = status

        // One digest per trading day, the first tick we observe the market open. The
        // day marker only advances when we actually fire, so enabling the toggle later
        // in the day still delivers that day's digest.
        if status == .open {
            let day = calendar.tradingDay(of: now)
            if state.lastDigestDay != day, settings.newsDigest {
                events.append(.digestDue)
                newState.lastDigestDay = day
            }
        }

        return (events, newState)
    }
}
