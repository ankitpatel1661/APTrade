import Foundation
import APTradeApplication
import APTradeDomain

/// Live earnings source backed by Finnhub's `/calendar/earnings`. Error handling mirrors
/// `FinnhubNewsRepository`: 429 → `.rateLimited`, non-2xx → `.network`, decode/other →
/// mapped to `AppError`.
///
/// Responses are cached in-memory per (fromDay, toDay) for `ttl` (default 6h — earnings
/// dates don't move intraday) so the calendar screen, every detail screen, and the daily
/// notification check share one network call. `now` is the same injectable clock seam
/// `CachingMarketDataRepository` uses, defaulting to the real wall clock. Since this type
/// is a class (not an actor), the cache dictionary is guarded by an explicit lock rather
/// than actor isolation.
///
/// Cancellation translation (review note from Task 11): `URLSession` surfaces a cancelled
/// task as `URLError(.cancelled)`, not `CancellationError`. `FetchEarningsCalendarUseCase`'s
/// guard only rethrows `CancellationError` — every other failure degrades to `[]` — so this
/// adapter translates `.cancelled` to `CancellationError()` before it reaches the use case;
/// otherwise a cancelled SwiftUI task would silently degrade to "no earnings" instead of
/// propagating cancellation.
public final class FinnhubEarningsRepository: EarningsCalendarRepository, @unchecked Sendable {
    private struct CacheEntry {
        let events: [EarningsEvent]
        let at: Date
    }

    private let apiKey: String
    private let now: () -> Date
    private let ttl: TimeInterval
    private let base = "https://finnhub.io/api/v1"
    private let fetchData: (URL) async throws -> Data

    private let lock = NSLock()
    private var cache: [String: CacheEntry] = [:]

    public init(
        apiKey: String,
        session: URLSession = .shared,
        now: @escaping () -> Date = Date.init,
        ttl: TimeInterval = 6 * 60 * 60
    ) {
        self.apiKey = apiKey
        self.now = now
        self.ttl = ttl
        self.fetchData = { url in
            let (data, response) = try await session.data(from: url)
            if let http = response as? HTTPURLResponse {
                if http.statusCode == 429 { throw AppError.rateLimited }
                guard (200..<300).contains(http.statusCode) else { throw AppError.network }
            }
            return data
        }
    }

    /// Test seam: injects a fake `fetchData` closure instead of a real `URLSession`, so
    /// cache/TTL/cancellation behavior can be exercised without a network stub.
    init(
        apiKey: String,
        now: @escaping () -> Date = Date.init,
        ttl: TimeInterval = 6 * 60 * 60,
        fetchData: @escaping (URL) async throws -> Data
    ) {
        self.apiKey = apiKey
        self.now = now
        self.ttl = ttl
        self.fetchData = fetchData
    }

    public func earnings(fromDay: String, toDay: String) async throws -> [EarningsEvent] {
        let key = "\(fromDay)|\(toDay)"

        let cached = lock.withLock { cache[key] }
        if let cached, now().timeIntervalSince(cached.at) < ttl {
            return cached.events
        }

        guard var components = URLComponents(string: base + "/calendar/earnings") else {
            throw AppError.network
        }
        components.queryItems = [
            URLQueryItem(name: "from", value: fromDay),
            URLQueryItem(name: "to", value: toDay),
            URLQueryItem(name: "token", value: apiKey),
        ]
        guard let url = components.url else { throw AppError.network }

        let events: [EarningsEvent]
        do {
            let data = try await fetchData(url)
            events = try FinnhubEarningsMapper.events(from: data)
        } catch let urlError as URLError where urlError.code == .cancelled {
            throw CancellationError()
        } catch let error as AppError {
            throw error
        } catch {
            throw AppError.network
        }

        lock.withLock { cache[key] = CacheEntry(events: events, at: now()) }
        return events
    }
}
