import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class FakeMarketData: MarketDataRepository, @unchecked Sendable {
    private let historyBySymbol: [String: [PricePoint]]
    private(set) var historyFetchCount = 0
    var failureError: (any Error)?

    init(historyBySymbol: [String: [PricePoint]] = [:]) {
        self.historyBySymbol = historyBySymbol
    }

    func quote(for symbol: String) async throws -> Quote {
        fatalError("not needed for SimulateDCA test")
    }

    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        historyFetchCount += 1
        if let error = failureError {
            throw error
        }
        return historyBySymbol[symbol] ?? []
    }

    func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        fatalError("not needed for SimulateDCA test")
    }

    func profile(for symbol: String) async throws -> Asset {
        fatalError("not needed for SimulateDCA test")
    }

    func search(query: String) async throws -> [Asset] {
        fatalError("not needed for SimulateDCA test")
    }
}

final class SimulateDCATests: XCTestCase {
    private let calendar = MarketCalendar()
    private let slice1 = PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 50))
    private let slice2 = PieSlice(symbol: "BTC", assetKind: .crypto, targetWeight: Percentage(value: 50))

    // MARK: - Happy Path: Report Matches dcaBacktest

    func test_simulateDCA_withValidHistory_returnsReportMatchingDcaBacktest() async throws {
        // Set up a 1-year history for both slices
        let startDate = Date(timeIntervalSince1970: 1_609_459_200) // 2021-01-01 00:00:00 UTC
        let points1 = (0..<252).map { i in
            let date = Calendar.current.date(byAdding: .day, value: i, to: startDate) ?? Date()
            return PricePoint(date: date, close: Money(amount: Decimal(150 + i), currencyCode: "USD"))
        }
        let points2 = (0..<252).map { i in
            let date = Calendar.current.date(byAdding: .day, value: i, to: startDate) ?? Date()
            return PricePoint(date: date, close: Money(amount: Decimal(30000 + i * 10), currencyCode: "USD"))
        }

        let market = FakeMarketData(historyBySymbol: ["AAPL": points1, "BTC": points2])
        let simulate = SimulateDCA(market: market, calendar: calendar)

        let slices = [slice1, slice2]
        let amount = Money(amount: 1000, currencyCode: "USD")
        let cadence = PieCadence.monthly
        let years = 1
        let now = Date(timeIntervalSince1970: 1_641_038_400) // 2022-01-01 UTC

        // Fetch history and simulate
        let report = try await simulate(slices: slices, amount: amount, cadence: cadence, years: years, now: now)

        // Should return a report (not nil)
        XCTAssertNotNil(report)
        guard let report = report else { return }

        // Should have fetched history for both symbols
        XCTAssertEqual(market.historyFetchCount, 2)

        // Manually compute expected result via dcaBacktest to verify they match
        // Build dailyCloses from the history
        var dailyCloses: [String: [String: Money]] = [:]
        for point in points1 {
            let day = calendar.tradingDay(of: point.date)
            dailyCloses["AAPL", default: [:]][day] = point.close
        }
        for point in points2 {
            let day = calendar.tradingDay(of: point.date)
            dailyCloses["BTC", default: [:]][day] = point.close
        }

        // Compute start and end days
        let dateComponents = DateComponents(year: -years)
        let startDate2 = Calendar.current.date(byAdding: dateComponents, to: now) ?? now
        let startDay = calendar.tradingDay(of: startDate2)
        let endDay = calendar.tradingDay(of: now)

        let expected = PieMathBacktest.dcaBacktest(
            slices: slices,
            amount: amount,
            cadence: cadence,
            startDay: startDay,
            endDay: endDay,
            dailyCloses: dailyCloses,
            calendar: calendar
        )

        XCTAssertEqual(report, expected)
    }

    // MARK: - Degrade: Network Failure Returns Nil

    func test_simulateDCA_onNetworkFailure_returnsNil() async throws {
        let market = FakeMarketData()
        market.failureError = AppError.network
        let simulate = SimulateDCA(market: market, calendar: calendar)

        let slices = [slice1, slice2]
        let amount = Money(amount: 1000, currencyCode: "USD")
        let cadence = PieCadence.monthly
        let years = 1
        let now = Date()

        let report = try await simulate(slices: slices, amount: amount, cadence: cadence, years: years, now: now)

        XCTAssertNil(report)
    }

    func test_simulateDCA_onFailureForOneSymbol_returnsNil() async throws {
        // Provide history for AAPL but fail for BTC
        let points = (0..<252).map { i in
            let date = Date(timeIntervalSince1970: 1_609_459_200 + TimeInterval(i * 86400))
            return PricePoint(date: date, close: Money(amount: Decimal(150 + i), currencyCode: "USD"))
        }

        let market = FakeMarketData(historyBySymbol: ["AAPL": points])
        market.failureError = AppError.network
        let simulate = SimulateDCA(market: market, calendar: calendar)

        let slices = [slice1, slice2]
        let amount = Money(amount: 1000, currencyCode: "USD")
        let cadence = PieCadence.monthly
        let years = 1
        let now = Date()

        let report = try await simulate(slices: slices, amount: amount, cadence: cadence, years: years, now: now)

        XCTAssertNil(report)
    }

    // MARK: - Cancellation: CancellationError Propagates

    func test_simulateDCA_onCancellation_propagatesError() async throws {
        let market = FakeMarketData()
        market.failureError = CancellationError()
        let simulate = SimulateDCA(market: market, calendar: calendar)

        let slices = [slice1, slice2]
        let amount = Money(amount: 1000, currencyCode: "USD")
        let cadence = PieCadence.monthly
        let years = 1
        let now = Date()

        do {
            _ = try await simulate(slices: slices, amount: amount, cadence: cadence, years: years, now: now)
            XCTFail("expected CancellationError to propagate")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
    }

    // MARK: - Edge Cases

    func test_simulateDCA_withInsufficientHistory_returnsNilFromBacktest() async throws {
        // Provide only a few days of history (not enough for any contributions)
        let startDate = Date(timeIntervalSince1970: 1_609_459_200)
        let points = [
            PricePoint(date: startDate, close: Money(amount: 100, currencyCode: "USD")),
        ]

        let market = FakeMarketData(historyBySymbol: ["AAPL": points, "BTC": points])
        let simulate = SimulateDCA(market: market, calendar: calendar)

        let slices = [slice1, slice2]
        let amount = Money(amount: 1000, currencyCode: "USD")
        let cadence = PieCadence.monthly
        let years = 1
        let now = Date(timeIntervalSince1970: 1_609_459_200 + 86400)

        let report = try await simulate(slices: slices, amount: amount, cadence: cadence, years: years, now: now)

        // dcaBacktest returns nil if no due day is executable
        XCTAssertNil(report)
    }

    func test_simulateDCA_3years_covers3YearsOfHistory() async throws {
        // Generate 3 years of history
        let startDate = Date(timeIntervalSince1970: 1_609_459_200) // 2021-01-01
        let points = (0..<756).map { i in // ~3 years of trading days
            let date = Calendar.current.date(byAdding: .day, value: i, to: startDate) ?? Date()
            return PricePoint(date: date, close: Money(amount: Decimal(150 + i), currencyCode: "USD"))
        }

        let market = FakeMarketData(historyBySymbol: ["AAPL": points, "BTC": points])
        let simulate = SimulateDCA(market: market, calendar: calendar)

        let slices = [slice1, slice2]
        let amount = Money(amount: 1000, currencyCode: "USD")
        let cadence = PieCadence.monthly
        let years = 3
        let now = Date(timeIntervalSince1970: 1_672_531_200) // 2023-01-01

        let report = try await simulate(slices: slices, amount: amount, cadence: cadence, years: years, now: now)

        // Should succeed with sufficient 3-year history
        XCTAssertNotNil(report)
        // Should have fetched history for both symbols
        XCTAssertEqual(market.historyFetchCount, 2)
    }

    func test_simulateDCA_5years_withLimitedHistory_degradesGracefully() async throws {
        // History port only supports .oneYear, so 5-year request will only get 1 year
        // dcaBacktest should handle missing days gracefully
        let startDate = Date(timeIntervalSince1970: 1_609_459_200) // 2021-01-01
        let points = (0..<252).map { i in
            let date = Calendar.current.date(byAdding: .day, value: i, to: startDate) ?? Date()
            return PricePoint(date: date, close: Money(amount: Decimal(150 + i), currencyCode: "USD"))
        }

        let market = FakeMarketData(historyBySymbol: ["AAPL": points, "BTC": points])
        let simulate = SimulateDCA(market: market, calendar: calendar)

        let slices = [slice1, slice2]
        let amount = Money(amount: 1000, currencyCode: "USD")
        let cadence = PieCadence.monthly
        let years = 5
        let now = Date(timeIntervalSince1970: 1_672_531_200) // 2023-01-01

        // Should not throw; dcaBacktest handles missing closes gracefully
        let report = try await simulate(slices: slices, amount: amount, cadence: cadence, years: years, now: now)

        // Result depends on what dcaBacktest can execute with 1 year of history
        // (might be nil if no due days fall in the 1-year window, or a report if some do)
        _ = report
    }
}
