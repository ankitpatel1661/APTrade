/// When in the trading day a company reports.
public enum EarningsSession: Equatable, Sendable {
    case beforeOpen
    case afterClose
    case duringMarket
    case unknown
}

/// One upcoming (or just-reported) earnings release. `day` is the market-local date as
/// `yyyy-MM-dd` — the same string shape `MarketCalendar.tradingDay` produces, so day math
/// and grouping are plain string equality. `companyName` may be empty (Finnhub omits it
/// sometimes); UIs fall back to `symbol`.
public struct EarningsEvent: Equatable, Sendable {
    public let symbol: String
    public let companyName: String
    public let day: String
    public let session: EarningsSession
    public let epsEstimate: Double?
    public let epsActual: Double?

    public init(
        symbol: String,
        companyName: String,
        day: String,
        session: EarningsSession,
        epsEstimate: Double?,
        epsActual: Double?
    ) {
        self.symbol = symbol
        self.companyName = companyName
        self.day = day
        self.session = session
        self.epsEstimate = epsEstimate
        self.epsActual = epsActual
    }
}
