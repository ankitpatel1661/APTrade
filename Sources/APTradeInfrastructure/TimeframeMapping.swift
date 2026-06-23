import APTradeDomain

extension Timeframe {
    public var yahooRange: String {
        switch self {
        case .oneDay: return "1d"
        case .oneWeek: return "5d"
        case .oneMonth: return "1mo"
        case .oneYear: return "1y"
        }
    }
    public var yahooInterval: String {
        switch self {
        case .oneDay: return "5m"
        case .oneWeek: return "30m"
        case .oneMonth: return "1d"
        case .oneYear: return "1d"
        }
    }
}
