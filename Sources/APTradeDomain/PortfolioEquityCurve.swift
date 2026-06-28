import Foundation

/// One point on the reconstructed account-value curve: the true total account value
/// (cash + holdings) at a past date, derived by replaying the transaction log.
public struct EquityPoint: Equatable, Sendable {
    public let date: Date
    public let value: Money
    public init(date: Date, value: Money) {
        self.date = date
        self.value = value
    }
}

public extension Portfolio {
    /// Reconstructs the *true* historical account value by replaying the transaction log.
    /// For each date in the union of supplied histories: computes the holdings and cash the
    /// account actually had at that date, and values holdings against each symbol's
    /// forward-filled close. Anchored on the current `cash` (no external deposits exist) —
    /// cash at a past date adds back the net cash of trades that happened *after* it. Pure.
    func equitySeries(histories: [String: [PricePoint]]) -> [EquityPoint] {
        let code = cash.currencyCode
        let allDates = Set(histories.values.flatMap { $0.map(\.date) }).sorted()
        guard !allDates.isEmpty else { return [] }

        let sortedTxns = transactions.sorted { $0.date < $1.date }
        let sorted = histories.mapValues { $0.sorted { $0.date < $1.date } }

        var result: [EquityPoint] = []
        result.reserveCapacity(allDates.count)

        for date in allDates {
            // Holdings as of `date`: net buys/sells up to and including it.
            var qty: [String: Decimal] = [:]
            for t in sortedTxns where t.date <= date {
                qty[t.symbol, default: 0] += (t.side == .buy ? t.quantity.amount : -t.quantity.amount)
            }
            // Cash as of `date`: current cash with later trade cashflows reversed.
            var cashAt = cash.amount
            for t in sortedTxns where t.date > date {
                let flow = t.price.amount * t.quantity.amount
                cashAt += (t.side == .buy ? flow : -flow)   // undo a later buy (+) / sell (−)
            }

            let hasHoldings = qty.contains { $0.value != 0 }
            var holdings = Decimal(0)
            if hasHoldings {
                var pricedAny = false
                for (symbol, q) in qty where q != 0 {
                    guard let points = sorted[symbol],
                          let close = lastClose(in: points, onOrBefore: date) else { continue }
                    holdings += close * q
                    pricedAny = true
                }
                guard pricedAny else { continue }   // holdings exist but no price yet → skip
            }
            result.append(EquityPoint(date: date,
                                      value: Money(amount: cashAt + holdings, currencyCode: code)))
        }
        return result
    }

    private func lastClose(in points: [PricePoint], onOrBefore date: Date) -> Decimal? {
        var last: Decimal?
        for p in points {
            if p.date <= date { last = p.close.amount } else { break }
        }
        return last
    }
}
