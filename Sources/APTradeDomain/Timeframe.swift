public enum Timeframe: String, CaseIterable, Sendable {
    case oneDay, oneWeek, oneMonth, oneYear

    public var displayName: String {
        switch self {
        case .oneDay: return "1D"
        case .oneWeek: return "1W"
        case .oneMonth: return "1M"
        case .oneYear: return "1Y"
        }
    }
}
