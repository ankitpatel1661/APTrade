import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

// `MutableClock` (a mutable-clock reference type so the `now` closure stays Sendable) is
// declared once in CachingRepositoryTests.swift and reused here.

/// Counts fetch invocations from within the repository's injected `fetchData` seam.
/// Reference type (not a captured `var`) so the closure stays Sendable, mirroring
/// `CachingRepositoryTests.CountingRepo`.
private final class FetchCounter: @unchecked Sendable {
    private(set) var count = 0
    var body: String = #"{"earningsCalendar":[]}"#

    func fetch(_ url: URL) async throws -> Data {
        count += 1
        return body.data(using: .utf8)!
    }
}

final class FinnhubEarningsMapperTests: XCTestCase {
    // Same sample as the Kotlin FinnhubEarningsMapperTest — extra fields (quarter, year,
    // revenueActual, revenueEstimate) are present to prove unknown keys are ignored.
    private let json = """
    {"earningsCalendar":[
      {"date":"2026-07-24","epsActual":null,"epsEstimate":1.52,"hour":"amc","quarter":3,
       "revenueActual":null,"revenueEstimate":90000000000,"symbol":"AAPL","year":2026},
      {"date":"2026-07-21","epsActual":2.11,"epsEstimate":2.05,"hour":"bmo","symbol":"KO"},
      {"date":"2026-07-22","hour":"dmh","symbol":"XYZ"},
      {"hour":"amc","symbol":"NODATE"},
      {"date":"2026-07-23","hour":"weird","symbol":"ODD"}
    ]}
    """.data(using: .utf8)!

    // MARK: - Mapper

    func test_events_mapsFieldsAndSessions() throws {
        let events = try FinnhubEarningsMapper.events(from: json)
        XCTAssertEqual(events.count, 4, "NODATE dropped (no date)")
        let bySymbol = Dictionary(uniqueKeysWithValues: events.map { ($0.symbol, $0) })

        XCTAssertEqual(bySymbol["AAPL"]?.session, .afterClose)
        XCTAssertEqual(bySymbol["AAPL"]?.epsEstimate, 1.52)
        XCTAssertEqual(bySymbol["AAPL"]?.day, "2026-07-24")
        XCTAssertEqual(bySymbol["AAPL"]?.companyName, "", "endpoint carries no name")

        XCTAssertEqual(bySymbol["KO"]?.session, .beforeOpen)
        XCTAssertEqual(bySymbol["KO"]?.epsActual, 2.11)

        XCTAssertEqual(bySymbol["XYZ"]?.session, .duringMarket)
        XCTAssertEqual(bySymbol["ODD"]?.session, .unknown)
        XCTAssertNil(bySymbol["NODATE"])
    }

    func test_events_emptyPayload_mapsToEmpty() throws {
        let events = try FinnhubEarningsMapper.events(from: "{}".data(using: .utf8)!)
        XCTAssertEqual(events, [])
    }

    func test_events_malformedJSON_throwsDecoding() {
        let bad = "not json".data(using: .utf8)!
        XCTAssertThrowsError(try FinnhubEarningsMapper.events(from: bad)) { error in
            XCTAssertEqual(error as? AppError, .decoding)
        }
    }

    // MARK: - Repository / cache

    func test_earnings_secondCallWithinTtl_doesNotRefetch() async throws {
        let counter = FetchCounter()
        let clock = MutableClock(Date(timeIntervalSince1970: 1_000_000))
        let repo = FinnhubEarningsRepository(
            apiKey: "secret-token",
            now: { clock.date },
            ttl: 6 * 60 * 60,
            fetchData: counter.fetch
        )

        _ = try await repo.earnings(fromDay: "2026-07-20", toDay: "2026-07-27")
        _ = try await repo.earnings(fromDay: "2026-07-20", toDay: "2026-07-27")

        XCTAssertEqual(counter.count, 1)
    }

    func test_earnings_callAfterTtlExpiry_refetches() async throws {
        let counter = FetchCounter()
        let clock = MutableClock(Date(timeIntervalSince1970: 1_000_000))
        let repo = FinnhubEarningsRepository(
            apiKey: "secret-token",
            now: { clock.date },
            ttl: 6 * 60 * 60,
            fetchData: counter.fetch
        )

        _ = try await repo.earnings(fromDay: "2026-07-20", toDay: "2026-07-27")
        clock.date = clock.date.addingTimeInterval(6 * 60 * 60 + 1)
        _ = try await repo.earnings(fromDay: "2026-07-20", toDay: "2026-07-27")

        XCTAssertEqual(counter.count, 2)
    }

    func test_earnings_differentRanges_cacheIndependently() async throws {
        let counter = FetchCounter()
        let repo = FinnhubEarningsRepository(
            apiKey: "secret-token",
            now: { Date(timeIntervalSince1970: 1_000_000) },
            ttl: 6 * 60 * 60,
            fetchData: counter.fetch
        )

        _ = try await repo.earnings(fromDay: "2026-07-20", toDay: "2026-07-27")
        _ = try await repo.earnings(fromDay: "2026-08-01", toDay: "2026-08-08")

        XCTAssertEqual(counter.count, 2)
    }

    func test_earnings_assemblesExactUrlAndParams() async throws {
        final class URLCapturingCounter: @unchecked Sendable {
            private(set) var capturedURL: URL?
            func fetch(_ url: URL) async throws -> Data {
                capturedURL = url
                return #"{"earningsCalendar":[]}"#.data(using: .utf8)!
            }
        }
        let capturer = URLCapturingCounter()
        let repo = FinnhubEarningsRepository(
            apiKey: "secret-token",
            now: Date.init,
            ttl: 6 * 60 * 60,
            fetchData: capturer.fetch
        )

        _ = try await repo.earnings(fromDay: "2026-07-20", toDay: "2026-07-27")

        let url = try XCTUnwrap(capturer.capturedURL)
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "https")
        XCTAssertEqual(components.host, "finnhub.io")
        XCTAssertEqual(components.path, "/api/v1/calendar/earnings")
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value) })
        XCTAssertEqual(query["from"] ?? nil, "2026-07-20")
        XCTAssertEqual(query["to"] ?? nil, "2026-07-27")
        XCTAssertEqual(query["token"] ?? nil, "secret-token")
    }

    func test_earnings_translatesCancelledURLErrorToCancellationError() async {
        final class CancellingCounter: @unchecked Sendable {
            func fetch(_ url: URL) async throws -> Data { throw URLError(.cancelled) }
        }
        let repo = FinnhubEarningsRepository(
            apiKey: "secret-token",
            now: Date.init,
            ttl: 6 * 60 * 60,
            fetchData: CancellingCounter().fetch
        )

        do {
            _ = try await repo.earnings(fromDay: "2026-07-20", toDay: "2026-07-27")
            XCTFail("expected CancellationError")
        } catch is CancellationError {
            // expected
        } catch {
            XCTFail("expected CancellationError, got \(error)")
        }
    }

    // MARK: - Empty fallback

    func test_emptyRepository_returnsNoEvents() async throws {
        let repo = EmptyEarningsRepository()
        let events = try await repo.earnings(fromDay: "2026-07-20", toDay: "2026-07-27")
        XCTAssertEqual(events, [])
    }
}
