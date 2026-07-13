import APTradeApplication
import APTradeDomain

/// The fallback earnings source used when no API key is configured. Always returns no
/// events, so calendar surfaces render their empty state without a live network call.
public struct EmptyEarningsRepository: EarningsCalendarRepository {
    public init() {}
    public func earnings(fromDay: String, toDay: String) async throws -> [EarningsEvent] { [] }
}
