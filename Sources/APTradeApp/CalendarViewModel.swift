import Foundation
import APTradeApplication
import APTradeDomain

/// Fourteen days of holidays + S&P 500 earnings. Holiday/half-day banners never depend on
/// the fetch succeeding: a failed or keyless fetch still renders the local calendar rows,
/// since `MarketCalendar`/`USMarketHolidays` are pure and computed offline.
@MainActor
@Observable
final class CalendarViewModel {
    struct DayGroup: Identifiable {
        let day: String
        let date: Date
        let holiday: USMarketHoliday?
        let isHalfDay: Bool
        let events: [EarningsEvent]
        var id: String { day }
    }

    private(set) var days: [DayGroup] = []
    private(set) var ownSymbols: Set<String> = []
    private(set) var isLoading = false
    let keyMissing: Bool

    private let fetchEarnings: FetchEarningsCalendarUseCase
    private let calendar: MarketCalendar
    private let loadOwnSymbols: @Sendable () async -> Set<String>
    private let now: () -> Date

    init(fetchEarnings: FetchEarningsCalendarUseCase,
         calendar: MarketCalendar = MarketCalendar(),
         loadOwnSymbols: @escaping @Sendable () async -> Set<String>,
         keyMissing: Bool,
         now: @escaping () -> Date = Date.init) {
        self.fetchEarnings = fetchEarnings
        self.calendar = calendar
        self.loadOwnSymbols = loadOwnSymbols
        self.keyMissing = keyMissing
        self.now = now
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        let start = now()
        // The 86_400-second stride is safe here because the day KEY comes from
        // `calendar.tradingDay(of:)` (ET wall clock) each step — a DST hour shift cannot
        // skip or double a calendar day over a 14-day window.
        let window = (0..<14).map { start.addingTimeInterval(Double($0) * 86_400) }
        do {
            let events = try await fetchEarnings.execute(
                fromDay: calendar.tradingDay(of: window[0]),
                toDay: calendar.tradingDay(of: window[13]))
            let byDay = Dictionary(grouping: events, by: \.day)
            ownSymbols = await loadOwnSymbols()
            days = window.compactMap { date in
                let day = calendar.tradingDay(of: date)
                let holiday = calendar.holiday(on: date)
                let half = calendar.isHalfDay(on: date)
                let dayEvents = byDay[day] ?? []
                guard holiday != nil || half || !dayEvents.isEmpty else { return nil }
                return DayGroup(day: day, date: date, holiday: holiday, isHalfDay: half, events: dayEvents)
            }
        } catch is CancellationError {
            // Cooperative cancellation is the one exception to the use case's
            // degrade-to-empty rule — leave `days`/`ownSymbols` untouched rather than
            // masquerading a cancelled fetch as "no earnings this window".
            return
        } catch {
            // Unreachable: `FetchEarningsCalendarUseCase.execute` only ever throws
            // `CancellationError` (every other repository failure is degraded to `[]`
            // inside the use case itself).
        }
    }
}
