import XCTest
@testable import APTradeApplication
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

private final class FakeEarningsRepository: EarningsCalendarRepository, @unchecked Sendable {
    private let events: [EarningsEvent]
    private(set) var calls = 0

    init(_ events: [EarningsEvent]) {
        self.events = events
    }

    func earnings(fromDay: String, toDay: String) async throws -> [EarningsEvent] {
        calls += 1
        return events
    }
}

private struct FailingEarningsRepository: EarningsCalendarRepository {
    func earnings(fromDay: String, toDay: String) async throws -> [EarningsEvent] {
        throw AppError.network
    }
}

final class EarningsUseCasesTests: XCTestCase {

    func test_filtersToIndexPlusOwnSymbols() async {
        let repo = FakeEarningsRepository([
            event(symbol: "AAPL", day: "2026-07-20"),      // in index
            event(symbol: "TINYCO", day: "2026-07-20"),    // not index, not owned -> dropped
            event(symbol: "MYPENNY", day: "2026-07-21"),   // not index, but OWNED -> kept
        ])
        let fetch = FetchEarningsCalendarUseCase(repository: repo) { ["MYPENNY"] }
        let out = await fetch.execute(fromDay: "2026-07-20", toDay: "2026-07-27")
        XCTAssertEqual(out.map(\.symbol), ["AAPL", "MYPENNY"])
    }

    func test_ownSymbolsSortFirstWithinADay() async {
        let repo = FakeEarningsRepository([
            event(symbol: "AAPL", day: "2026-07-20"),
            event(symbol: "ZTS", day: "2026-07-20"),
            event(symbol: "MSFT", day: "2026-07-20"),
        ])
        let fetch = FetchEarningsCalendarUseCase(repository: repo) { ["ZTS"] }
        let out = await fetch.execute(fromDay: "2026-07-20", toDay: "2026-07-27")
        XCTAssertEqual(out.map(\.symbol), ["ZTS", "AAPL", "MSFT"]) // owned pinned, rest alphabetical
    }

    func test_nextEarningsPicksEarliestForSymbol() async {
        let repo = FakeEarningsRepository([
            event(symbol: "AAPL", day: "2026-07-24"),
            event(symbol: "AAPL", day: "2026-10-22"),
            event(symbol: "MSFT", day: "2026-07-21"),
        ])
        let fetch = FetchEarningsCalendarUseCase(repository: repo) { [] }
        let next = await fetch.nextEarnings(symbol: "AAPL", fromDay: "2026-07-13", toDay: "2026-08-12")
        XCTAssertEqual(next?.day, "2026-07-24")
    }

    func test_nextEarningsNullWhenAbsent() async {
        let fetch = FetchEarningsCalendarUseCase(repository: FakeEarningsRepository([])) { [] }
        let next = await fetch.nextEarnings(symbol: "AAPL", fromDay: "2026-07-13", toDay: "2026-08-12")
        XCTAssertNil(next)
    }

    func test_repositoryFailureDegradesToEmpty() async {
        let fetch = FetchEarningsCalendarUseCase(repository: FailingEarningsRepository()) { [] }
        let out = await fetch.execute(fromDay: "2026-07-20", toDay: "2026-07-27")
        XCTAssertEqual(out, [])
    }

    func test_dotClassTickersMatchDashForm() async {
        // Finnhub reports BRK.B as "BRK.B"; a user may hold "BRK-B". Normalization makes them meet.
        let repo = FakeEarningsRepository([event(symbol: "BRK.B", day: "2026-07-20")])
        let fetch = FetchEarningsCalendarUseCase(repository: repo) { ["BRK-B"] }
        let out = await fetch.execute(fromDay: "2026-07-20", toDay: "2026-07-27")
        XCTAssertEqual(out.map(\.symbol), ["BRK.B"])
    }

    func test_ownedTodayReturnsOnlyOwnedSymbolsForTheDay() async {
        let repo = FakeEarningsRepository([
            event(symbol: "MYPENNY", day: "2026-07-20"),  // owned
            event(symbol: "AAPL", day: "2026-07-20"),     // index-only, not owned
        ])
        let fetch = FetchEarningsCalendarUseCase(repository: repo) { ["MYPENNY"] }
        let out = await fetch.ownedToday(day: "2026-07-20")
        XCTAssertEqual(out.map(\.symbol), ["MYPENNY"])
    }
}
