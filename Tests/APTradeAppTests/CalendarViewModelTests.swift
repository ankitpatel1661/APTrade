import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private func event(
    symbol: String,
    day: String,
    session: EarningsSession = .afterClose
) -> EarningsEvent {
    EarningsEvent(
        symbol: symbol,
        companyName: "\(symbol) Inc.",
        day: day,
        session: session,
        epsEstimate: 1.0,
        epsActual: nil
    )
}

/// Records nothing beyond the fixed events/error it was built with — the ViewModel's
/// contract under test is grouping/keyMissing/failure-degradation, not repository call
/// shape (that's EarningsUseCasesTests' job).
private final class FakeEarningsCalendarRepository: EarningsCalendarRepository, @unchecked Sendable {
    private let events: [EarningsEvent]
    private let error: (any Error)?

    init(events: [EarningsEvent] = [], error: (any Error)? = nil) {
        self.events = events
        self.error = error
    }

    func earnings(fromDay: String, toDay: String) async throws -> [EarningsEvent] {
        if let error { throw error }
        return events
    }
}

@MainActor
final class CalendarViewModelTests: XCTestCase {
    // Monday 2026-11-23 12:00 ET = 17:00 UTC — the same reference instant the shared
    // Kotlin CalendarDaysTest/CalendarViewModelTest use, so a 14-day window starting here
    // is known to contain Thanksgiving (Thu Nov 26) and its half-day (Fri Nov 27).
    private let referenceInstant = Date(timeIntervalSince1970: 1_795_453_200)

    private func makeVM(
        events: [EarningsEvent] = [],
        error: (any Error)? = nil,
        ownSymbols: Set<String> = [],
        keyMissing: Bool = false
    ) -> CalendarViewModel {
        let repo = FakeEarningsCalendarRepository(events: events, error: error)
        return CalendarViewModel(
            fetchEarnings: FetchEarningsCalendarUseCase(repository: repo) { ownSymbols },
            loadOwnSymbols: { ownSymbols },
            keyMissing: keyMissing,
            now: { self.referenceInstant }
        )
    }

    func test_load_groupsHolidayHalfDayAndEventDays_plainDaysCollapse() async {
        let vm = makeVM(events: [event(symbol: "AAPL", day: "2026-11-24")], ownSymbols: ["AAPL"])
        await vm.load()

        XCTAssertFalse(vm.isLoading)
        XCTAssertEqual(vm.ownSymbols, ["AAPL"])
        XCTAssertEqual(vm.days.map(\.day), ["2026-11-24", "2026-11-26", "2026-11-27"])
        XCTAssertEqual(vm.days[0].events.map(\.symbol), ["AAPL"])
        XCTAssertEqual(vm.days[1].holiday, .thanksgiving)
        XCTAssertTrue(vm.days[2].isHalfDay)
    }

    func test_keyMissing_stillRendersHolidayRowsFromAnEmptyFetch() async {
        let vm = makeVM(keyMissing: true)
        await vm.load()

        XCTAssertTrue(vm.keyMissing)
        XCTAssertFalse(vm.isLoading)
        XCTAssertTrue(vm.days.allSatisfy { $0.events.isEmpty })
        XCTAssertTrue(vm.days.contains { $0.day == "2026-11-26" && $0.holiday == .thanksgiving })
    }

    func test_fetchFailure_degradesToHolidayOnlyDaysWithoutCrashing() async {
        struct Boom: Error {}
        let vm = makeVM(error: Boom())
        await vm.load()

        XCTAssertFalse(vm.isLoading)
        XCTAssertTrue(vm.days.allSatisfy { $0.events.isEmpty })
        XCTAssertTrue(vm.days.contains { $0.day == "2026-11-26" && $0.holiday == .thanksgiving })
        XCTAssertTrue(vm.days.contains { $0.day == "2026-11-27" && $0.isHalfDay })
    }

    /// Cooperative cancellation must NOT masquerade as "no earnings" — the amendment
    /// this guards against: `try?` collapsing a cancelled fetch into an empty-but-holiday
    /// day list. On cancellation `load()` returns early, leaving `days` untouched.
    func test_cancellationDuringFetch_leavesDaysUntouched() async {
        let vm = makeVM(error: CancellationError())
        await vm.load()

        XCTAssertFalse(vm.isLoading)
        XCTAssertTrue(vm.days.isEmpty)
    }
}
