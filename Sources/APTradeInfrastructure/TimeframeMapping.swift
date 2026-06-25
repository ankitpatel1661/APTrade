import Foundation
import APTradeDomain

extension Timeframe {
    /// Requests a slightly wider window than the timeframe needs (e.g. 5d of raw data
    /// for a "last 24 hours" view) so `clampWindow` can trim to an exact rolling window
    /// — Yahoo's own ranges count trading days, not calendar time, so "5d" alone isn't
    /// a real week and "1d" alone isn't a rolling 24 hours.
    public var yahooRange: String {
        switch self {
        case .oneDay: return "5d"
        case .oneWeek: return "1mo"
        case .oneMonth: return "3mo"
        case .oneYear: return "1y"
        }
    }
    public var yahooInterval: String {
        switch self {
        case .oneDay: return "5m"
        case .oneWeek: return "60m"
        case .oneMonth: return "1d"
        case .oneYear: return "1d"
        }
    }

    /// The exact rolling window to clamp raw points to, anchored to now.
    public var windowDuration: TimeInterval {
        switch self {
        case .oneDay: return 24 * 3600
        case .oneWeek: return 7 * 24 * 3600
        case .oneMonth: return 30 * 24 * 3600
        case .oneYear: return 365 * 24 * 3600
        }
    }
}
