import Foundation
import APTradeApplication
import APTradeDomain

/// Income tab: dividend summary cards, monthly bars (received + projected), upcoming
/// payouts, per-holding breakdown, and payment history. Mirrors `PlansViewModel`'s
/// dependency style — injected use cases/stores, `now` as a testable seam.
///
/// Failure isolation: a dividend-event fetch failure for one symbol degrades only that
/// symbol's projections (to empty/zero) — it never blocks another symbol's events, and
/// never blocks the ledger-derived pieces (`history`, `cards.receivedYTD`). A missing
/// quote degrades market-value-dependent math to its cost-basis fallback; if that
/// fallback is also zero, the affected yield fraction degrades to zero rather than
/// dividing by zero.
@MainActor
final class IncomeViewModel: ObservableObject {

    struct SummaryCards: Equatable {
        let projectedAnnual: Money
        let receivedYTD: Money
        let portfolioYield: Double      // fraction, 0.031 = 3.1%
        let yieldOnCost: Double
    }

    struct MonthBar: Equatable, Identifiable {
        let id: String                  // "yyyy-MM"
        let amount: Money
        let isProjected: Bool
    }

    struct UpcomingRow: Equatable, Identifiable {
        let id: String                  // symbol
        let symbol: String
        let estimatedExDate: Date
        let estimatedAmount: Money      // amountPerShare × current shares
    }

    struct HoldingRow: Equatable, Identifiable {
        let id: String                  // symbol
        let symbol: String
        let shares: Quantity
        let annualIncome: Money
        let yieldOnCost: Double
        let lastPayment: Money?
    }

    struct HistoryEntry: Equatable, Identifiable {
        let id: UUID                    // transaction id
        let date: Date
        let symbol: String
        let amountPerShare: Money
        let shares: Quantity
        let total: Money
        let wasReinvested: Bool
    }

    @Published private(set) var cards: SummaryCards?
    @Published private(set) var months: [MonthBar] = []          // last 12 received + up to 3 projected
    @Published private(set) var upcoming: [UpcomingRow] = []     // sorted by estimatedExDate
    @Published private(set) var holdings: [HoldingRow] = []      // sorted by annualIncome desc
    @Published private(set) var history: [HistoryEntry] = []     // newest first
    @Published private(set) var isLoading = false

    private let fetchPortfolio: FetchPortfolioUseCase
    private let fetchQuotes: FetchQuotesUseCase
    private let dividendEventsRepository: DividendEventsRepository
    private let calendar: MarketCalendar
    private let now: () -> Date

    init(fetchPortfolio: FetchPortfolioUseCase,
         fetchQuotes: FetchQuotesUseCase,
         dividendEventsRepository: DividendEventsRepository,
         calendar: MarketCalendar = MarketCalendar(),
         now: @escaping () -> Date = Date.init) {
        self.fetchPortfolio = fetchPortfolio
        self.fetchQuotes = fetchQuotes
        self.dividendEventsRepository = dividendEventsRepository
        self.calendar = calendar
        self.now = now
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }

        let portfolio = fetchPortfolio()
        let transactions = portfolio.transactions
        let asOf = now()

        // Ledger-derived pieces never depend on network calls, so they populate even
        // when every remote fetch below fails.
        history = Self.buildHistory(transactions: transactions, calendar: calendar)
        let receivedYTD = Self.receivedYTD(transactions: transactions, asOf: asOf)

        // Dividend events, fetched per non-crypto holding. A single symbol's failure is
        // caught locally and contributes an empty history for that symbol only.
        let nonCryptoPositions = portfolio.positions.filter { $0.asset.kind != .crypto }
        var eventsBySymbol: [String: [DividendEvent]] = [:]
        for position in nonCryptoPositions {
            let symbol = position.asset.symbol
            do {
                eventsBySymbol[symbol] = try await dividendEventsRepository.dividendEvents(
                    for: symbol, since: Self.lookbackStart(asOf: asOf))
            } catch {
                eventsBySymbol[symbol] = []
            }
        }

        // Quotes for every held symbol (including crypto) so market value mirrors
        // `Portfolio.valuation`'s total; a missing quote falls back to cost basis there.
        let quoteResults = await fetchQuotes(symbols: portfolio.positions.map(\.asset.symbol))
        var quotes: [String: Quote] = [:]
        for (symbol, result) in quoteResults {
            if case .success(let quote) = result { quotes[symbol] = quote }
        }

        cards = Self.buildCards(portfolio: portfolio, eventsBySymbol: eventsBySymbol,
                                quotes: quotes, receivedYTD: receivedYTD, asOf: asOf)
        months = Self.buildMonths(transactions: transactions, positions: nonCryptoPositions,
                                  eventsBySymbol: eventsBySymbol, asOf: asOf)
        upcoming = Self.buildUpcoming(positions: nonCryptoPositions, eventsBySymbol: eventsBySymbol, asOf: asOf)
            .sorted { $0.estimatedExDate < $1.estimatedExDate }
        holdings = Self.buildHoldings(positions: nonCryptoPositions, eventsBySymbol: eventsBySymbol,
                                      transactions: transactions, asOf: asOf)
            .sorted { $0.annualIncome.amount > $1.annualIncome.amount }
    }

    // MARK: - History

    private static func buildHistory(transactions: [Transaction], calendar: MarketCalendar) -> [HistoryEntry] {
        let dividendTxns = transactions.filter { $0.side == .dividend }
        return dividendTxns.map { txn in
            let total = Money(amount: txn.price.amount * txn.quantity.amount, currencyCode: txn.price.currencyCode)
            let tradingDay = calendar.tradingDay(of: txn.date)
            let wasReinvested = transactions.contains { other in
                other.side == .buy && other.isDrip && other.symbol == txn.symbol
                    && calendar.tradingDay(of: other.date) == tradingDay
            }
            return HistoryEntry(id: txn.id, date: txn.date, symbol: txn.symbol,
                                amountPerShare: txn.price, shares: txn.quantity,
                                total: total, wasReinvested: wasReinvested)
        }.sorted { $0.date > $1.date }
    }

    // MARK: - Cards

    /// Sum of `.dividend` transaction cash in the current calendar year, UTC.
    private static func receivedYTD(transactions: [Transaction], asOf: Date) -> Money {
        let currentYear = Self.utcCalendar.component(.year, from: asOf)
        var total = Money(amount: 0)
        for txn in transactions where txn.side == .dividend {
            guard Self.utcCalendar.component(.year, from: txn.date) == currentYear else { continue }
            total = total + Money(amount: txn.price.amount * txn.quantity.amount, currencyCode: txn.price.currencyCode)
        }
        return total
    }

    private static func buildCards(portfolio: Portfolio, eventsBySymbol: [String: [DividendEvent]],
                                   quotes: [String: Quote], receivedYTD: Money, asOf: Date) -> SummaryCards {
        let projectedAnnual = DividendMath.projectedAnnualIncome(
            positions: portfolio.positions, eventsBySymbol: eventsBySymbol, asOf: asOf)

        // Mirrors `Portfolio.valuation`'s quote-with-cost-basis-fallback treatment.
        var marketValue = Decimal(0)
        var costBasis = Decimal(0)
        for position in portfolio.positions {
            let q = position.quantity.amount
            if let quote = quotes[position.asset.symbol] {
                marketValue += quote.price.amount * q
            } else {
                marketValue += position.averageCost.amount * q
            }
            costBasis += position.averageCost.amount * q
        }

        let portfolioYield = marketValue > 0
            ? (projectedAnnual.amount / marketValue as NSDecimalNumber).doubleValue : 0
        let yieldOnCost = costBasis > 0
            ? (projectedAnnual.amount / costBasis as NSDecimalNumber).doubleValue : 0

        return SummaryCards(projectedAnnual: projectedAnnual, receivedYTD: receivedYTD,
                            portfolioYield: portfolioYield, yieldOnCost: yieldOnCost)
    }

    // MARK: - Months

    private static func buildMonths(transactions: [Transaction], positions: [Position],
                                    eventsBySymbol: [String: [DividendEvent]], asOf: Date) -> [MonthBar] {
        let monthlyReceived = DividendMath.monthlyReceived(transactions: transactions)
        let currency = transactions.first?.price.currencyCode ?? "USD"
        let receivedKeys = Self.last12MonthKeys(endingAt: asOf)
        let receivedBars = receivedKeys.map { key in
            MonthBar(id: key, amount: monthlyReceived[key] ?? Money(amount: 0, currencyCode: currency), isProjected: false)
        }

        // Up to 3 distinct future months, aggregating each holding's single next-projected
        // payout (DividendMath.nextProjected only projects one step ahead per holding).
        let currentMonthKey = Self.monthKey(for: asOf)
        var projectedByMonth: [String: Decimal] = [:]
        var projectedCurrency = currency
        for position in positions {
            let events = eventsBySymbol[position.asset.symbol] ?? []
            guard let projected = DividendMath.nextProjected(events: events), projected.exDate > asOf else { continue }
            let key = Self.monthKey(for: projected.exDate)
            guard key > currentMonthKey else { continue }
            let contribution = projected.amountPerShare.amount * position.quantity.amount
            projectedByMonth[key, default: 0] += contribution
            projectedCurrency = projected.amountPerShare.currencyCode
        }
        let projectedBars = projectedByMonth.keys.sorted().prefix(3).map { key in
            MonthBar(id: key, amount: Money(amount: projectedByMonth[key] ?? 0, currencyCode: projectedCurrency), isProjected: true)
        }

        return receivedBars + projectedBars
    }

    // MARK: - Upcoming

    private static func buildUpcoming(positions: [Position],
                                      eventsBySymbol: [String: [DividendEvent]], asOf: Date) -> [UpcomingRow] {
        positions.compactMap { position -> UpcomingRow? in
            let events = eventsBySymbol[position.asset.symbol] ?? []
            guard let projected = DividendMath.nextProjected(events: events), projected.exDate > asOf else { return nil }
            let amount = Money(amount: projected.amountPerShare.amount * position.quantity.amount,
                               currencyCode: projected.amountPerShare.currencyCode)
            return UpcomingRow(id: position.asset.symbol, symbol: position.asset.symbol,
                               estimatedExDate: projected.exDate, estimatedAmount: amount)
        }
    }

    // MARK: - Holdings

    private static func buildHoldings(positions: [Position], eventsBySymbol: [String: [DividendEvent]],
                                      transactions: [Transaction], asOf: Date) -> [HoldingRow] {
        positions.map { position -> HoldingRow in
            let symbol = position.asset.symbol
            let events = eventsBySymbol[symbol] ?? []
            let perShare = DividendMath.trailingAnnualPerShare(events: events, asOf: asOf)
            let annualIncome = Money(amount: perShare.amount * position.quantity.amount, currencyCode: perShare.currencyCode)

            let costBasis = position.averageCost.amount * position.quantity.amount
            let yieldOnCost = costBasis > 0
                ? (annualIncome.amount / costBasis as NSDecimalNumber).doubleValue : 0

            let lastPaymentTxn = transactions
                .filter { $0.side == .dividend && $0.symbol == symbol }
                .max { $0.date < $1.date }
            let lastPayment = lastPaymentTxn.map {
                Money(amount: $0.price.amount * $0.quantity.amount, currencyCode: $0.price.currencyCode)
            }

            return HoldingRow(id: symbol, symbol: symbol, shares: position.quantity,
                              annualIncome: annualIncome, yieldOnCost: yieldOnCost, lastPayment: lastPayment)
        }
    }

    // MARK: - Date helpers

    private static var utcCalendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal
    }()

    /// UTC "yyyy-MM" bucket key — matches `DividendMath.monthlyReceived`'s key format.
    private static func monthKey(for date: Date) -> String {
        let c = utcCalendar.dateComponents([.year, .month], from: date)
        return String(format: "%04d-%02d", c.year ?? 0, c.month ?? 0)
    }

    /// Ascending "yyyy-MM" keys for the 12 months ending at (and including) `date`'s month.
    private static func last12MonthKeys(endingAt date: Date) -> [String] {
        (0..<12).reversed().compactMap { offset in
            utcCalendar.date(byAdding: .month, value: -offset, to: date).map(monthKey(for:))
        }
    }

    /// How far back to fetch dividend events: two years covers the trailing-annual
    /// window (365d) plus enough history for cadence inference on slower-paying assets.
    private static func lookbackStart(asOf: Date) -> Date {
        asOf.addingTimeInterval(-730 * 86_400)
    }
}
