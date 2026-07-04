import Foundation

/// One point on a reconstructed portfolio performance curve: the account's value and its
/// unrealized P&L at a past date, valued against that date's market prices.
public struct PortfolioPerformancePoint: Equatable, Sendable {
    public let date: Date
    /// Cash plus the market value of current holdings at this date.
    public let value: Money
    /// Unrealized P&L of current holdings versus average cost at this date.
    public let pnl: Money

    public init(date: Date, value: Money, pnl: Money) {
        self.date = date
        self.value = value
        self.pnl = pnl
    }
}

public extension Portfolio {
    /// Reconstructs a value / unrealized-P&L time series by valuing the *current* holdings
    /// against each symbol's historical closes, forward-filling each symbol's last known
    /// close at every date so symbols with different trading calendars (e.g. crypto vs.
    /// equities) stay aligned. Cash is treated as constant. Pure — callers supply the
    /// per-symbol histories; this does no networking.
    ///
    /// All-priced gate (adopted from the Kotlin shared core in increment 6b.3, reversing the
    /// original macOS-first direction): a date is emitted only once *every* symbol with any
    /// history is priced. A symbol that has history but no close yet at a given date sets
    /// `allPriced = false` and the whole date is skipped, so a leading window before a
    /// mixed-calendar symbol's first candle (e.g. crypto trading 24/7 alongside market-hours
    /// equities) no longer emits a point valuing only the already-priced symbols — that
    /// avoided a valuation cliff the moment the late symbol's first candle joins. Symbols
    /// with NO history at all stay excluded from the gate (they must not blank the curve).
    func performanceSeries(histories: [String: [PricePoint]]) -> [PortfolioPerformancePoint] {
        guard !positions.isEmpty else { return [] }
        let code = cash.currencyCode

        var sorted: [String: [PricePoint]] = [:]
        var allDates = Set<Date>()
        for position in positions {
            let points = (histories[position.asset.symbol] ?? []).sorted { $0.date < $1.date }
            guard !points.isEmpty else { continue }
            sorted[position.asset.symbol] = points
            for point in points { allDates.insert(point.date) }
        }
        guard !allDates.isEmpty else { return [] }

        var cursor: [String: Int] = [:]        // forward-fill pointer per symbol
        var lastClose: [String: Decimal] = [:]  // last close seen at or before the date
        var result: [PortfolioPerformancePoint] = []
        result.reserveCapacity(allDates.count)

        for date in allDates.sorted() {
            var holdings = Decimal(0)
            var pnl = Decimal(0)
            var allPriced = true
            for position in positions {
                let symbol = position.asset.symbol
                guard let points = sorted[symbol] else { continue }  // no history at all: excluded, doesn't gate
                var i = cursor[symbol] ?? 0
                while i < points.count, points[i].date <= date {
                    lastClose[symbol] = points[i].close.amount
                    i += 1
                }
                cursor[symbol] = i
                guard let close = lastClose[symbol] else {
                    // Gate: this symbol has history but no close yet at this date. Skip the
                    // whole date rather than valuing only the already-priced symbols.
                    allPriced = false
                    continue
                }
                let quantity = position.quantity.amount
                holdings += close * quantity
                pnl += (close - position.averageCost.amount) * quantity
            }
            guard allPriced else { continue }
            result.append(PortfolioPerformancePoint(
                date: date,
                value: Money(amount: cash.amount + holdings, currencyCode: code),
                pnl: Money(amount: pnl, currencyCode: code)
            ))
        }
        return result
    }
}
