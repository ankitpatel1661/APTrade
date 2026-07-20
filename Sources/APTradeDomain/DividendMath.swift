import Foundation

/// A dividend event as the Swift domain sees it (bridged from the shared core
/// by infrastructure; pure value here).
public struct DividendEvent: Equatable, Sendable {
    public let symbol: String
    public let exDate: Date
    public let amountPerShare: Money

    public init(symbol: String, exDate: Date, amountPerShare: Money) {
        self.symbol = symbol
        self.exDate = exDate
        self.amountPerShare = amountPerShare
    }
}

/// How frequently a security pays dividends, inferred from historical ex-dates.
public enum DividendCadence: Equatable, Sendable {
    case monthly, quarterly, semiAnnual, annual
}

/// Pure dividend & income math: held-shares reconstruction from a transaction
/// ledger, trailing-twelve-month rate, cadence inference/projection, and cash
/// aggregation. No networking, no persistence — Foundation only.
public enum DividendMath {
    private static let secondsPerDay: TimeInterval = 86_400

    /// Shares held STRICTLY BEFORE `date`: sum of buy quantities minus sell
    /// quantities across transactions with `txn.date < date` (dividend entries
    /// contribute nothing; DRIP buys count like any buy).
    public static func sharesHeld(symbol: String, at date: Date,
                                  transactions: [Transaction]) -> Quantity {
        var net = Decimal(0)
        for txn in transactions where txn.symbol == symbol && txn.date < date {
            switch txn.side {
            case .buy:
                net += txn.quantity.amount
            case .sell:
                net -= txn.quantity.amount
            case .dividend:
                break
            }
        }
        return Quantity(net)
    }

    /// Sum of event amounts with exDate in (asOf − 365 days, asOf]. Zero when none.
    public static func trailingAnnualPerShare(events: [DividendEvent], asOf: Date) -> Money {
        let windowStart = asOf.addingTimeInterval(-365 * secondsPerDay)
        var total = Money(amount: 0)
        for event in events where event.exDate > windowStart && event.exDate <= asOf {
            total = total + event.amountPerShare
        }
        return total
    }

    /// Median gap between consecutive ex-dates → cadence. nil when < 2 events.
    /// Buckets (days): ≤45 monthly, ≤135 quarterly, ≤270 semiAnnual, else annual.
    public static func inferredCadence(events: [DividendEvent]) -> DividendCadence? {
        guard events.count >= 2 else { return nil }

        let sortedDates = events.map(\.exDate).sorted()
        var gapsInDays: [Double] = []
        for i in 1..<sortedDates.count {
            gapsInDays.append(sortedDates[i].timeIntervalSince(sortedDates[i - 1]) / secondsPerDay)
        }
        let sortedGaps = gapsInDays.sorted()
        let count = sortedGaps.count
        let medianGapDays = count % 2 == 1
            ? sortedGaps[count / 2]
            : (sortedGaps[count / 2 - 1] + sortedGaps[count / 2]) / 2

        switch medianGapDays {
        case ...45: return .monthly
        case ...135: return .quarterly
        case ...270: return .semiAnnual
        default: return .annual
        }
    }

    /// Last exDate + cadence interval (monthly 30d, quarterly 91d, semiAnnual 182d,
    /// annual 365d), amount = last event's amount. nil when cadence is nil.
    public static func nextProjected(events: [DividendEvent]) -> DividendEvent? {
        guard let cadence = inferredCadence(events: events),
              let last = events.max(by: { $0.exDate < $1.exDate }) else {
            return nil
        }

        let intervalDays: Double
        switch cadence {
        case .monthly: intervalDays = 30
        case .quarterly: intervalDays = 91
        case .semiAnnual: intervalDays = 182
        case .annual: intervalDays = 365
        }

        return DividendEvent(
            symbol: last.symbol,
            exDate: last.exDate.addingTimeInterval(intervalDays * secondsPerDay),
            amountPerShare: last.amountPerShare
        )
    }

    /// Received dividend cash per calendar month (UTC, "yyyy-MM" keys) from
    /// `.dividend` transactions only.
    public static func monthlyReceived(transactions: [Transaction]) -> [String: Money] {
        var result: [String: Money] = [:]
        for txn in transactions where txn.side == .dividend {
            let key = monthKey(for: txn.date)
            let cash = Money(amount: txn.price.amount * txn.quantity.amount, currencyCode: txn.price.currencyCode)
            let running = result[key] ?? Money(amount: 0, currencyCode: cash.currencyCode)
            result[key] = running + cash
        }
        return result
    }

    /// trailingAnnualPerShare × shares, per held symbol, summed. Symbols absent
    /// from `eventsBySymbol` contribute zero.
    public static func projectedAnnualIncome(positions: [Position],
                                             eventsBySymbol: [String: [DividendEvent]],
                                             asOf: Date) -> Money {
        var total = Money(amount: 0)
        for position in positions {
            let events = eventsBySymbol[position.asset.symbol] ?? []
            let perShare = trailingAnnualPerShare(events: events, asOf: asOf)
            let contribution = Money(amount: perShare.amount * position.quantity.amount,
                                     currencyCode: perShare.currencyCode)
            total = total + contribution
        }
        return total
    }

    /// UTC "yyyy-MM" bucket key for a date, used for monthly aggregation.
    private static func monthKey(for date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "UTC")!
        formatter.dateFormat = "yyyy-MM"
        return formatter.string(from: date)
    }
}
