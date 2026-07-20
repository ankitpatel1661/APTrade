import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class FakePortfolioStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    var saveCallCount = 0
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) {
        self.portfolio = portfolio
        saveCallCount += 1
    }
}

/// Fake market data source supporting both live quotes (for today's due day) and a
/// canned daily-close table (for historical due days), indexed by `yyyy-MM-dd` day
/// string exactly as `ExecuteDueContributions` indexes `history(for:timeframe:)`.
private final class FakeCatchUpRepo: MarketDataRepository, @unchecked Sendable {
    var quotes: [String: Quote] = [:]
    /// dailyCloses[symbol][day] = close
    var dailyCloses: [String: [String: Money]] = [:]
    var historyError: Error?
    private let calendar = MarketCalendar()

    func quote(for symbol: String) async throws -> Quote {
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }

    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        if let historyError { throw historyError }
        let closes = dailyCloses[symbol] ?? [:]
        return closes.compactMap { day, close in
            guard let date = PieSchedule.date(fromDay: day, calendar: calendar) else { return nil }
            return PricePoint(date: date, close: close)
        }
    }
}

final class ExecuteDueContributionsTests: XCTestCase {
    private let sliceA = PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50))
    private let sliceB = PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))
    private let calendar = MarketCalendar()

    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }

    /// Noon ET on `day`, used as `now` for tests that don't need today's due day itself
    /// (i.e. `now`'s trading day is always strictly after every due day under test).
    private func date(_ day: String) -> Date {
        PieSchedule.date(fromDay: day, calendar: calendar) ?? Date()
    }

    private func makeRepo(closesA: [String: String], closesB: [String: String]) -> FakeCatchUpRepo {
        let repo = FakeCatchUpRepo()
        repo.dailyCloses["A"] = closesA.mapValues { usd($0) }
        repo.dailyCloses["B"] = closesB.mapValues { usd($0) }
        return repo
    }

    // MARK: (a) Two missed monthly days execute in ascending order, each at its own close

    func test_twoMissedMonthlyDays_executeInAscendingOrderAtEachDaysClose() async throws {
        let schedule = ContributionSchedule(amount: usd("100"), cadence: .monthly, nextDueDay: "2025-04-01")
        let pie = try Pie(id: "pie-a", name: "DCA Pie", slices: [sliceA, sliceB],
                          schedule: schedule, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolioStore = FakePortfolioStore(.starting())
        let repo = makeRepo(
            closesA: ["2025-04-01": "10", "2025-05-01": "20"],
            closesB: ["2025-04-01": "10", "2025-05-01": "20"]
        )

        let sut = ExecuteDueContributions(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, calendar: calendar)
        let results = await sut(now: date("2025-05-15"))

        XCTAssertEqual(results.count, 1)
        let (resultPie, outcomes) = results[0]
        XCTAssertEqual(outcomes.count, 2)

        guard case let .executed(portfolioAfterDay1, _) = outcomes[0] else {
            XCTFail("day 1 expected .executed"); return
        }
        guard case let .executed(portfolioAfterDay2, _) = outcomes[1] else {
            XCTFail("day 2 expected .executed"); return
        }

        // Day 1 (April 1) at close $10: $50 -> A (5 sh), $50 -> B (5 sh).
        XCTAssertEqual(portfolioAfterDay1.position(for: "A")?.quantity, Quantity(Decimal(5)))
        XCTAssertEqual(portfolioAfterDay1.position(for: "B")?.quantity, Quantity(Decimal(5)))
        XCTAssertEqual(portfolioAfterDay1.cash, usd("99900"))

        // Day 2 (May 1) at close $20: $50 -> A (2.5 sh more), $50 -> B (2.5 sh more).
        XCTAssertEqual(portfolioAfterDay2.position(for: "A")?.quantity, Quantity(Decimal(7.5)))
        XCTAssertEqual(portfolioAfterDay2.position(for: "B")?.quantity, Quantity(Decimal(7.5)))
        XCTAssertEqual(portfolioAfterDay2.cash, usd("99800"))

        // Final persisted state reflects both contributions.
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("99800"))
        XCTAssertEqual(resultPie.quantity(of: "A"), Quantity(Decimal(7.5)))
        XCTAssertEqual(resultPie.quantity(of: "B"), Quantity(Decimal(7.5)))
        XCTAssertEqual(resultPie.activity.filter { $0.kind == .contribution }.map(\.day), ["2025-04-01", "2025-05-01"])
    }

    // MARK: (b) nextDueDay advanced to the first due day after now

    func test_nextDueDay_advancedToFirstDueDayAfterNow() async throws {
        let schedule = ContributionSchedule(amount: usd("100"), cadence: .monthly, nextDueDay: "2025-04-01")
        let pie = try Pie(id: "pie-b", name: "DCA Pie", slices: [sliceA, sliceB],
                          schedule: schedule, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolioStore = FakePortfolioStore(.starting())
        let repo = makeRepo(
            closesA: ["2025-04-01": "10", "2025-05-01": "20"],
            closesB: ["2025-04-01": "10", "2025-05-01": "20"]
        )

        let sut = ExecuteDueContributions(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, calendar: calendar)
        let results = await sut(now: date("2025-05-15"))

        // June 1 2025 is a Sunday -> rolls to Monday June 2.
        XCTAssertEqual(results[0].pie.schedule?.nextDueDay, "2025-06-02")
        XCTAssertEqual(pieStore.pies.first?.schedule?.nextDueDay, "2025-06-02")
    }

    // MARK: (c) Insufficient cash on the second day -> first executes, second recorded missed

    func test_insufficientCashOnSecondDay_firstExecutesSecondRecordedMissed() async throws {
        let schedule = ContributionSchedule(amount: usd("100"), cadence: .monthly, nextDueDay: "2025-04-01")
        let pie = try Pie(id: "pie-c", name: "DCA Pie", slices: [sliceA, sliceB],
                          schedule: schedule, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        // Exactly enough cash for one $100 contribution, not two.
        let portfolioStore = FakePortfolioStore(.starting(cash: usd("150")))
        let repo = makeRepo(
            closesA: ["2025-04-01": "10", "2025-05-01": "20"],
            closesB: ["2025-04-01": "10", "2025-05-01": "20"]
        )

        let sut = ExecuteDueContributions(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, calendar: calendar)
        let results = await sut(now: date("2025-05-15"))

        XCTAssertEqual(results.count, 1)
        let (resultPie, outcomes) = results[0]
        XCTAssertEqual(outcomes.count, 2)

        guard case .executed = outcomes[0] else { XCTFail("day 1 expected .executed"); return }
        guard case let .skippedInsufficientCash(missedPie) = outcomes[1] else {
            XCTFail("day 2 expected .skippedInsufficientCash"); return
        }

        XCTAssertEqual(portfolioStore.portfolio.cash, usd("50"))
        XCTAssertEqual(resultPie.quantity(of: "A"), Quantity(Decimal(5))) // only day 1's buy
        XCTAssertEqual(resultPie.quantity(of: "B"), Quantity(Decimal(5)))
        XCTAssertEqual(resultPie.activity.map(\.kind), [.contribution, .missedInsufficientCash])
        XCTAssertEqual(missedPie.activity.last?.day, "2025-05-01")
    }

    // MARK: (d) Missing close for one symbol on day 1 -> day 1 skipped silently, day 2 executes

    func test_missingCloseForOneSymbolOnDay1_skipsDay1SilentlyExecutesDay2() async throws {
        let schedule = ContributionSchedule(amount: usd("100"), cadence: .monthly, nextDueDay: "2025-04-01")
        let pie = try Pie(id: "pie-d", name: "DCA Pie", slices: [sliceA, sliceB],
                          schedule: schedule, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolioStore = FakePortfolioStore(.starting())
        // B has no close on April 1 -> day 1 must be skipped entirely.
        let repo = makeRepo(
            closesA: ["2025-04-01": "10", "2025-05-01": "20"],
            closesB: ["2025-05-01": "20"]
        )

        let sut = ExecuteDueContributions(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, calendar: calendar)
        let results = await sut(now: date("2025-05-15"))

        XCTAssertEqual(results.count, 1)
        let (resultPie, outcomes) = results[0]

        // Only day 2's outcome is recorded -- day 1 leaves no trace at all.
        XCTAssertEqual(outcomes.count, 1)
        guard case .executed = outcomes[0] else { XCTFail("day 2 expected .executed"); return }

        XCTAssertEqual(resultPie.activity.count, 1)
        XCTAssertEqual(resultPie.activity.first?.day, "2025-05-01")
        // Day 2 ($100 @ $20 close, 50/50, no prior holdings): 2.5 shares each.
        XCTAssertEqual(resultPie.quantity(of: "A"), Quantity(Decimal(2.5)))
        XCTAssertEqual(resultPie.quantity(of: "B"), Quantity(Decimal(2.5)))
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("99900"))
    }

    // MARK: (e) Pies without schedules are untouched

    func test_pieWithoutSchedule_untouched() async throws {
        let pie = try Pie(id: "pie-e", name: "No Schedule Pie", slices: [sliceA, sliceB],
                          schedule: nil, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolioStore = FakePortfolioStore(.starting())
        let repo = makeRepo(closesA: [:], closesB: [:])

        let sut = ExecuteDueContributions(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, calendar: calendar)
        let results = await sut(now: date("2025-05-15"))

        XCTAssertTrue(results.isEmpty)
        XCTAssertEqual(pieStore.pies, [pie])
        XCTAssertEqual(portfolioStore.portfolio, .starting())
        XCTAssertEqual(portfolioStore.saveCallCount, 0)
    }
}
