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
    /// Dot/dash-`normalized` form (see `APTradeApplication`'s `normalized(_:)`) so the owned-dot
    /// check at render time (`viewModel.ownSymbols.contains(normalized(event.symbol))`) matches
    /// a watched "BRK-B" against Finnhub's "BRK.B" event.
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
        // The 86_400-second stride is a fixed-duration step, not calendar-day arithmetic, so a
        // US DST transition inside the 14-day window shifts every subsequent step's ET wall-clock
        // time by up to ~1 hour. That can only re-map a step's `tradingDay(of:)` key onto the
        // adjacent calendar day when the step lands within that ~1h of ET midnight — and US DST
        // transitions always fall in the small hours of a Sunday, which never has holiday,
        // half-day, or earnings content of its own (`buildCalendarDays`'s Kotlin twin drops
        // content-less days the same way). So the one day whose key could wobble is always
        // dropped by the `guard ... else { return nil }` below regardless, making the drift
        // unobservable in `days`.
        let window = (0..<14).map { start.addingTimeInterval(Double($0) * 86_400) }
        do {
            let events = try await fetchEarnings.execute(
                fromDay: calendar.tradingDay(of: window[0]),
                toDay: calendar.tradingDay(of: window[13]))
            let byDay = Dictionary(grouping: events, by: \.day)
            ownSymbols = Set(await loadOwnSymbols().map(normalized))
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
