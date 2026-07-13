import APTradeDomain

/// Ticker forms differ between sources ("BRK.B" vs "BRK-B"); compare on a dot/dash-blind key.
private func normalized(_ symbol: String) -> String {
    symbol.uppercased().replacingOccurrences(of: "-", with: ".")
}

/// Serves both earnings surfaces. `ownSymbols` is a provider (watchlist ∪ portfolio read
/// fresh per call — both change at runtime). Filtering: keep events whose symbol is in the
/// S&P 500 snapshot OR owned; owned events sort before index events within a day, then
/// alphabetically. Failures degrade to an empty list — the calendar's holiday banners must
/// render even when the network is down.
public struct FetchEarningsCalendarUseCase: Sendable {
    private let repository: EarningsCalendarRepository
    private let ownSymbols: @Sendable () async -> Set<String>

    public init(repository: EarningsCalendarRepository, ownSymbols: @escaping @Sendable () async -> Set<String>) {
        self.repository = repository
        self.ownSymbols = ownSymbols
    }

    public func execute(fromDay: String, toDay: String) async -> [EarningsEvent] {
        let events = await fetchOrEmpty(fromDay: fromDay, toDay: toDay)
        let own = Set(await ownSymbols().map(normalized))
        let index = Set(SP500Symbols.set.map(normalized))
        return events
            .filter { own.contains(normalized($0.symbol)) || index.contains(normalized($0.symbol)) }
            .sorted { lhs, rhs in
                if lhs.day != rhs.day { return lhs.day < rhs.day }
                let lhsOwned = own.contains(normalized(lhs.symbol))
                let rhsOwned = own.contains(normalized(rhs.symbol))
                if lhsOwned != rhsOwned { return lhsOwned } // owned first
                return lhs.symbol < rhs.symbol
            }
    }

    /// Earliest event for `symbol` in the window, or nil. Uses the same fetch (and any
    /// caching the repository provides) as the calendar screen.
    public func nextEarnings(symbol: String, fromDay: String, toDay: String) async -> EarningsEvent? {
        let key = normalized(symbol)
        let events = await fetchOrEmpty(fromDay: fromDay, toDay: toDay)
        return events
            .filter { normalized($0.symbol) == key }
            .min { $0.day < $1.day }
    }

    /// Events on `day` restricted to symbols the user actually owns/watches — index-only
    /// events (S&P 500 constituents the user has no position or watch on) are excluded.
    public func ownedToday(day: String) async -> [EarningsEvent] {
        let events = await fetchOrEmpty(fromDay: day, toDay: day)
        let own = Set(await ownSymbols().map(normalized))
        return events.filter { own.contains(normalized($0.symbol)) }
    }

    private func fetchOrEmpty(fromDay: String, toDay: String) async -> [EarningsEvent] {
        do {
            return try await repository.earnings(fromDay: fromDay, toDay: toDay)
        } catch {
            return []
        }
    }
}
