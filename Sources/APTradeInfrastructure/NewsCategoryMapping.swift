import APTradeDomain

extension NewsCategory {
    /// Finnhub's `category` query value. The enum's raw values already match Finnhub's
    /// strings; this name makes the coupling explicit at the call site.
    var finnhubValue: String { rawValue }
}
