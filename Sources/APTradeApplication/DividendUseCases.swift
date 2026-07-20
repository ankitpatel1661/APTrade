import Foundation
import APTradeDomain

// MARK: - Shared Helper (replicated, not imported)

/// Fetches and indexes daily historical closes by symbol and day. A verbatim replica of
/// `PieUseCases.swift`'s `private func fetchClosesByDay` — that one is file-private, so it
/// cannot be shared across files; duplicating the ~15 lines keeps `DividendUseCases`
/// self-contained rather than widening either file's API surface just to share it.
private func fetchDividendClosesByDay(
    market: MarketDataRepository,
    symbols: [String],
    calendar: MarketCalendar
) async throws -> [String: [String: Money]] {
    var result: [String: [String: Money]] = [:]
    for symbol in symbols {
        let points = try await market.history(for: symbol, timeframe: .oneYear)
        var byDay: [String: Money] = [:]
        for point in points {
            byDay[calendar.tradingDay(of: point.date)] = point.close
        }
        result[symbol] = byDay
    }
    return result
}

// MARK: - Outcome

/// The result of crediting one dividend event to the portfolio.
public enum DividendOutcome: Equatable, Sendable {
    /// Cash was credited: a plain dividend, or a DRIP that fell back to cash because the
    /// ex-date trading day's close was missing or non-positive.
    case credited(symbol: String, cash: Money)
    /// Cash was credited and immediately reinvested into `shares` of the position, bought
    /// at the ex-date trading day's close (DRIP).
    case reinvested(symbol: String, cash: Money, shares: Quantity)
}

// MARK: - ProcessDueDividends

/// Dividend-crediting engine: for every non-crypto position, fetches its historical
/// dividend events and credits any the ledger hasn't already recorded — as cash, or,
/// when DRIP is enabled and the event post-dates this install's first run, reinvested at
/// the ex-date close.
///
/// **Backfill.** The very first run records `dividendsFirstRunDay` (this install's first
/// dividend-processing trading day) BEFORE crediting anything. Events whose ex-date
/// trading day predates it are historical backfill and always credit as cash, regardless
/// of the DRIP toggle — reinvesting a payout that landed before the user ever enabled the
/// feature would fabricate share lots they never chose. Events on or after that day honor
/// `isDripEnabled()`, read fresh at processing time.
///
/// **Idempotency & concurrency.** Every event is credited inside its own
/// `serializer.run` critical section, mirroring `ExecuteDueContributions`' per-step
/// discipline: the portfolio is reloaded fresh, the rule-4 ledger dedup is re-checked
/// against that fresh ledger, and only then is the mutation applied and saved. Two
/// concurrent `callAsFunction` runs therefore cannot double-credit — whichever acquires
/// the lock first writes the `.dividend` transaction; the second reloads, sees it, and
/// skips. Network (dividend events and daily closes) is fetched OUTSIDE the lock, never
/// within it.
///
/// **Degradation.** `callAsFunction` never throws. Any error while processing one symbol
/// (a dividend-events fetch, a history fetch, decoding) abandons that symbol's remaining
/// events silently; other symbols still process, and events already saved stay saved.
public struct ProcessDueDividends: Sendable {
    private let portfolioStore: PortfolioStore
    private let market: MarketDataRepository
    private let dividends: DividendEventsRepository
    private let stateStore: SchedulerStateStore
    private let calendar: MarketCalendar
    private let serializer: TradeSerializer
    private let isDripEnabled: @Sendable () -> Bool

    public init(portfolioStore: PortfolioStore, market: MarketDataRepository,
                dividends: DividendEventsRepository, stateStore: SchedulerStateStore,
                calendar: MarketCalendar, serializer: TradeSerializer,
                isDripEnabled: @escaping @Sendable () -> Bool) {
        self.portfolioStore = portfolioStore
        self.market = market
        self.dividends = dividends
        self.stateStore = stateStore
        self.calendar = calendar
        self.serializer = serializer
        self.isDripEnabled = isDripEnabled
    }

    public func callAsFunction(now: Date) async -> [DividendOutcome] {
        // Rule 1: establish (and persist, before any crediting) this install's
        // first-run marker. Backfill events (ex-date trading day < this) always credit
        // as cash below.
        let firstRunDay = ensureFirstRunDay(now: now)

        // Rule 2: candidate symbols are the portfolio's non-crypto positions. This
        // snapshot only selects symbols and their earliest ledger dates (the `since`
        // bound); every actual mutation reloads fresh inside the serializer.
        let snapshot = portfolioStore.load()
        let candidates = snapshot.positions.filter { $0.asset.kind != .crypto }

        var outcomes: [DividendOutcome] = []
        for position in candidates {
            let symbol = position.asset.symbol
            // Rule 7: a throw while processing this symbol abandons its remaining events
            // silently; other symbols still process, already-saved events stay saved.
            do {
                let symbolOutcomes = try await process(symbol: symbol, firstRunDay: firstRunDay,
                                                       transactions: snapshot.transactions)
                outcomes.append(contentsOf: symbolOutcomes)
            } catch {
                continue
            }
        }
        return outcomes
    }

    /// Loads state; if `dividendsFirstRunDay` is unset, sets it to today's trading day
    /// and saves BEFORE any crediting, then returns whichever value is now in effect.
    private func ensureFirstRunDay(now: Date) -> String {
        var state = stateStore.load()
        if let existing = state.dividendsFirstRunDay { return existing }
        let today = calendar.tradingDay(of: now)
        state.dividendsFirstRunDay = today
        stateStore.save(state)
        return today
    }

    /// Fetches, filters, and credits one symbol's dividend events. Both network calls
    /// (events and — only when a DRIP is actually possible — daily closes) happen here,
    /// outside the serializer; each surviving event is then credited in its own locked
    /// critical section. A throw propagates to `callAsFunction`, degrading this symbol.
    private func process(symbol: String, firstRunDay: String,
                         transactions: [Transaction]) async throws -> [DividendOutcome] {
        // `since` = this symbol's earliest ledger date. A held position always has at
        // least one buy, so `Date.distantPast` is never the right bound here.
        let since = transactions.filter { $0.symbol == symbol }.map(\.date).min() ?? Date.distantPast
        let events = try await dividends.dividendEvents(for: symbol, since: since)

        // Whether any surviving event could reinvest — the only reason to fetch closes.
        // Read `isDripEnabled()` once here for the fetch decision; each event re-reads it
        // at processing time below.
        let dripOn = isDripEnabled()
        let mayReinvest = dripOn && events.contains { event in
            calendar.tradingDay(of: event.exDate) >= firstRunDay
        }
        var closesByDay: [String: Money] = [:]
        if mayReinvest {
            closesByDay = try await fetchDividendClosesByDay(market: market, symbols: [symbol],
                                                             calendar: calendar)[symbol] ?? [:]
        }

        var outcomes: [DividendOutcome] = []
        for event in events {
            if let outcome = try await credit(event: event, firstRunDay: firstRunDay, closesByDay: closesByDay) {
                outcomes.append(outcome)
            }
        }
        return outcomes
    }

    /// Credits one event inside its own `serializer.run` block: reload fresh, re-dedup
    /// against the fresh ledger, recompute eligibility, mutate, save. Returns `nil` when
    /// the event is skipped (already credited, or no shares held strictly-before the
    /// ex-date).
    private func credit(event: DividendEvent, firstRunDay: String,
                        closesByDay: [String: Money]) async throws -> DividendOutcome? {
        let eventDay = calendar.tradingDay(of: event.exDate)

        return try await serializer.run {
            let portfolio = self.portfolioStore.load()

            // Rule 4 (re-checked on the fresh ledger): a `.dividend` transaction for this
            // symbol on this trading day means it is already credited — skip. This is the
            // in-lock guarantee against a concurrent run double-crediting.
            let alreadyCredited = portfolio.transactions.contains { txn in
                txn.side == .dividend && txn.symbol == event.symbol
                    && self.calendar.tradingDay(of: txn.date) == eventDay
            }
            if alreadyCredited { return nil }

            // Rule 3: eligibility is shares held STRICTLY BEFORE the ex-date, recomputed
            // from the fresh ledger. Zero (or a non-positive dividend) -> skip silently.
            let shares = DividendMath.sharesHeld(symbol: event.symbol, at: event.exDate,
                                                 transactions: portfolio.transactions)
            guard shares.amount > 0, event.amountPerShare.amount > 0 else { return nil }

            let cash = Money(amount: event.amountPerShare.amount * shares.amount,
                             currencyCode: event.amountPerShare.currencyCode)

            // Rule 1 & 5: backfill (ex-date day < firstRunDay) always credits as cash.
            // Otherwise, when DRIP is enabled and a positive close exists for the ex-date
            // day, reinvest; a missing/non-positive close falls back to cash.
            let isBackfill = eventDay < firstRunDay
            if !isBackfill, self.isDripEnabled(), let close = closesByDay[eventDay], close.amount > 0 {
                let credited = try portfolio.receivingDividend(event.symbol, amountPerShare: event.amountPerShare,
                                                               shares: shares, on: event.exDate)
                let quantity = Quantity(cash.amount / close.amount) // unrounded fractional shares
                let asset = credited.position(for: event.symbol)?.asset
                    ?? Asset(symbol: event.symbol, name: event.symbol, kind: .stock)
                let reinvested = try credited.buying(asset, quantity: quantity, at: close,
                                                     on: event.exDate, isDrip: true)
                self.portfolioStore.save(reinvested)
                return .reinvested(symbol: event.symbol, cash: cash, shares: quantity)
            } else {
                let credited = try portfolio.receivingDividend(event.symbol, amountPerShare: event.amountPerShare,
                                                               shares: shares, on: event.exDate)
                self.portfolioStore.save(credited)
                return .credited(symbol: event.symbol, cash: cash)
            }
        }
    }
}
