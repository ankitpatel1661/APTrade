import Foundation
import APTradeDomain

// MARK: - Shared Helpers

/// Fetches and indexes daily historical closes by symbol and day.
/// Used by both `ExecuteDueContributions` and `SimulateDCA` to build the
/// `[String: [String: Money]]` table for backtest/execution.
private func fetchClosesByDay(
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

public struct LoadPies: Sendable {
    private let store: PieStore

    public init(store: PieStore) {
        self.store = store
    }

    public func callAsFunction() -> [Pie] {
        store.load()
    }
}

public struct SavePie: Sendable {
    private let store: PieStore

    public init(store: PieStore) {
        self.store = store
    }

    /// Create a new Pie or replace an existing one with the same id.
    ///
    /// - Parameter pie: The Pie to save.
    /// - Returns: The complete list of Pies after the save operation.
    public func callAsFunction(_ pie: Pie) -> [Pie] {
        var pies = store.load()
        if let index = pies.firstIndex(where: { $0.id == pie.id }) {
            pies[index] = pie
        } else {
            pies.append(pie)
        }
        store.save(pies)
        return pies
    }
}

public struct DeletePie: Sendable {
    private let store: PieStore

    public init(store: PieStore) {
        self.store = store
    }

    /// Delete a Pie by id. No-op if the id is not found.
    ///
    /// - Parameter id: The id of the Pie to delete.
    /// - Returns: The complete list of Pies after the delete operation.
    public func callAsFunction(id: String) -> [Pie] {
        let pies = store.load()
        let filtered = pies.filter { $0.id != id }
        store.save(filtered)
        return filtered
    }
}

/// Outcome of a pie contribution: either buys were executed across the portfolio and
/// pie, or the whole contribution was skipped due to insufficient cash (never partial).
public enum ContributionOutcome: Equatable, Sendable {
    case executed(Portfolio, Pie)
    case skippedInsufficientCash(Pie)      // pie updated with missed activity entry
}

/// Turns a cash contribution into self-balancing buys across a pie's slices.
///
/// Fetches a live quote for every slice symbol first — a missing or failing quote for
/// any slice symbol propagates as a thrown error (this use case does not degrade to a
/// partial contribution; the caller decides how to surface the failure). Each slice's
/// current value is computed from the pie's ledger (`quantity × quote price`), and
/// `PieMath.distribute` decides how to split `amount` across slices, preferring
/// underweight ones.
///
/// If `amount` exceeds the portfolio's cash, the whole contribution is skipped: a
/// `missedInsufficientCash` activity entry is recorded on the pie, the pie is saved,
/// and `.skippedInsufficientCash` is returned with the portfolio left completely
/// untouched. Otherwise, each slice's allocated share is converted to a fractional
/// share quantity (`share ÷ price`, unrounded — fractional shares are allowed) and
/// bought into the portfolio tagged with `pieId`. Slices that received a zero share
/// are skipped entirely (no zero-quantity buys). The pie's ledger is incremented for
/// each symbol bought, a `contribution` activity entry is appended, and both stores
/// are saved.
public struct ContributeToPie: Sendable {
    private let pieStore: PieStore
    private let portfolioStore: PortfolioStore
    private let market: MarketDataRepository

    public init(pieStore: PieStore, portfolioStore: PortfolioStore, market: MarketDataRepository) {
        self.pieStore = pieStore
        self.portfolioStore = portfolioStore
        self.market = market
    }

    /// - Parameters:
    ///   - day: Stamps the pie's activity entry (`contribution` or `missedInsufficientCash`).
    ///   - now: Stamps the date of any resulting transactions.
    public func callAsFunction(pieId: String, amount: Money, day: String, now: Date) async throws -> ContributionOutcome {
        let pies = pieStore.load()
        guard let pie = pies.first(where: { $0.id == pieId }) else {
            throw AppError.notFound
        }

        var quotes: [String: Quote] = [:]
        for slice in pie.slices {
            quotes[slice.symbol] = try await market.quote(for: slice.symbol)
        }

        var currentValues: [String: Money] = [:]
        for slice in pie.slices {
            guard let quote = quotes[slice.symbol] else { throw AppError.notFound }
            let quantity = pie.quantity(of: slice.symbol)
            currentValues[slice.symbol] = Money(amount: quantity.amount * quote.price.amount,
                                                currencyCode: quote.price.currencyCode)
        }

        let shares = PieMath.distribute(contribution: amount, currentValues: currentValues, targets: pie.slices)

        let portfolio = portfolioStore.load()
        guard amount.amount <= portfolio.cash.amount else {
            let missed = PieActivityEntry(kind: .missedInsufficientCash, day: day, amount: amount)
            let updatedPie = try replace(pie, in: pies, activity: pie.activity + [missed])
            return .skippedInsufficientCash(updatedPie)
        }

        var updatedPortfolio = portfolio
        var newLedger = pie.ledger
        for slice in pie.slices {
            guard let share = shares[slice.symbol], share.amount > 0,
                  let quote = quotes[slice.symbol] else { continue }

            let quantity = Quantity(share.amount / quote.price.amount)
            let asset = Asset(symbol: slice.symbol, name: slice.symbol, kind: slice.assetKind)
            updatedPortfolio = try updatedPortfolio.buying(asset, quantity: quantity, at: quote.price,
                                                            on: now, pieId: pieId)

            let newQuantity = Quantity(pie.quantity(of: slice.symbol).amount + quantity.amount)
            if let index = newLedger.firstIndex(where: { $0.symbol == slice.symbol }) {
                newLedger[index] = PieLedgerEntry(symbol: slice.symbol, quantity: newQuantity)
            } else {
                newLedger.append(PieLedgerEntry(symbol: slice.symbol, quantity: newQuantity))
            }
        }

        let contributed = PieActivityEntry(kind: .contribution, day: day, amount: amount)
        let updatedPie = try replace(pie, in: pies, ledger: newLedger, activity: pie.activity + [contributed])

        portfolioStore.save(updatedPortfolio)
        return .executed(updatedPortfolio, updatedPie)
    }

    /// Rebuilds `pie` with the given overrides via `Pie`'s validating init (rethrows —
    /// slices pass through unchanged so validation always succeeds here), replaces it
    /// within `pies`, and persists the full list.
    private func replace(_ pie: Pie, in pies: [Pie],
                         ledger: [PieLedgerEntry]? = nil,
                         activity: [PieActivityEntry]) throws -> Pie {
        let updated = try Pie(id: pie.id, name: pie.name, slices: pie.slices, schedule: pie.schedule,
                              createdDay: pie.createdDay, ledger: ledger ?? pie.ledger, activity: activity)
        var all = pies
        if let index = all.firstIndex(where: { $0.id == pie.id }) {
            all[index] = updated
        } else {
            all.append(updated)
        }
        pieStore.save(all)
        return updated
    }
}

/// Manual rebalance: prices a Pie's current ledger against live quotes and derives the
/// trades needed to restore its target allocation (`PieMath.rebalancePlan`), then
/// optionally executes them.
public struct RebalancePie: Sendable {
    private let pieStore: PieStore
    private let portfolioStore: PortfolioStore
    private let market: MarketDataRepository

    public init(pieStore: PieStore, portfolioStore: PortfolioStore, market: MarketDataRepository) {
        self.pieStore = pieStore
        self.portfolioStore = portfolioStore
        self.market = market
    }

    /// The orders `execute` would place, priced at live quotes. Read-only — fetches
    /// quotes but never touches either store.
    public func preview(pieId: String) async throws -> [RebalanceOrder] {
        try await plan(pieId: pieId).orders
    }

    /// Executes the previewed orders: all sells first (freeing cash), then all buys,
    /// each tagged `pieId`. Sell quantities are clamped to the pie's own ledger
    /// quantity for that symbol (a pie only rebalances what it owns) and, defensively,
    /// to the portfolio's actually-held quantity (in case the ledger has drifted ahead
    /// of a manual sell that `ReconcilePieLedgers` hasn't yet clamped). The pie's ledger
    /// is updated for every filled order and a `rebalance` activity entry — its amount
    /// the total value bought (equivalently sold, since orders net to zero) — is
    /// appended.
    public func execute(pieId: String, day: String, now: Date) async throws -> (Portfolio, Pie) {
        let (pies, pie, quotes, orders) = try await plan(pieId: pieId)

        var portfolio = portfolioStore.load()
        var ledger = pie.ledger

        for order in orders where order.side == .sell {
            guard let quote = quotes[order.symbol] else { throw AppError.notFound }
            let rawQuantity = order.amount.amount / quote.price.amount
            let ledgerQuantity = ledger.first(where: { $0.symbol == order.symbol })?.quantity.amount ?? 0
            // `heldQuantity` is the portfolio-WIDE position for this symbol, per spec —
            // not this pie's slice of it. If another pie also claims the symbol, this
            // clamp alone can't see that cross-pie contention (only the portfolio total
            // vs. THIS pie's ledger), so a narrow drift window exists between a manual
            // sell and the next `ReconcilePieLedgers` run where two pies could both
            // still believe they own shares the portfolio no longer has. That's the
            // clamp `ReconcilePieLedgers` exists to rebound; this one only guards
            // against this single pie outrunning the portfolio's total holding.
            let heldQuantity = portfolio.position(for: order.symbol)?.quantity.amount ?? 0
            let quantity = min(rawQuantity, ledgerQuantity, heldQuantity)
            guard quantity > 0 else { continue }

            portfolio = try portfolio.selling(order.symbol, quantity: Quantity(quantity), at: quote.price,
                                              on: now, pieId: pieId)
            apply(delta: -quantity, symbol: order.symbol, to: &ledger)
        }

        for order in orders where order.side == .buy {
            guard let quote = quotes[order.symbol] else { throw AppError.notFound }
            let quantity = order.amount.amount / quote.price.amount
            guard quantity > 0 else { continue }

            let kind = pie.slices.first(where: { $0.symbol == order.symbol })?.assetKind ?? .stock
            let asset = Asset(symbol: order.symbol, name: order.symbol, kind: kind)
            portfolio = try portfolio.buying(asset, quantity: Quantity(quantity), at: quote.price,
                                             on: now, pieId: pieId)
            apply(delta: quantity, symbol: order.symbol, to: &ledger)
        }

        let tradedAmount = orders.filter { $0.side == .buy }.reduce(Decimal(0)) { $0 + $1.amount.amount }
        let currencyCode = orders.first?.amount.currencyCode ?? portfolio.cash.currencyCode
        let rebalanced = PieActivityEntry(kind: .rebalance, day: day,
                                          amount: Money(amount: tradedAmount, currencyCode: currencyCode))
        let updatedPie = try replace(pie, in: pies, ledger: ledger, activity: pie.activity + [rebalanced])

        portfolioStore.save(portfolio)
        return (portfolio, updatedPie)
    }

    /// Increments or decrements `symbol`'s ledger quantity by `delta`, creating the
    /// entry if it doesn't already exist. `Quantity`'s init clamps negative results to
    /// zero, so an over-large sell can never drive the ledger negative.
    private func apply(delta: Decimal, symbol: String, to ledger: inout [PieLedgerEntry]) {
        let current = ledger.first(where: { $0.symbol == symbol })?.quantity.amount ?? 0
        let updated = PieLedgerEntry(symbol: symbol, quantity: Quantity(current + delta))
        if let index = ledger.firstIndex(where: { $0.symbol == symbol }) {
            ledger[index] = updated
        } else {
            ledger.append(updated)
        }
    }

    /// Fetches a live quote for every slice symbol, prices the pie's current ledger
    /// against them, and derives the rebalance plan — shared by `preview` and `execute`
    /// so both price against the exact same set of live quotes in one fetch.
    private func plan(pieId: String) async throws -> (pies: [Pie], pie: Pie, quotes: [String: Quote], orders: [RebalanceOrder]) {
        let pies = pieStore.load()
        guard let pie = pies.first(where: { $0.id == pieId }) else {
            throw AppError.notFound
        }

        var quotes: [String: Quote] = [:]
        for slice in pie.slices {
            quotes[slice.symbol] = try await market.quote(for: slice.symbol)
        }

        var currentValues: [String: Money] = [:]
        for slice in pie.slices {
            guard let quote = quotes[slice.symbol] else { throw AppError.notFound }
            let quantity = pie.quantity(of: slice.symbol)
            currentValues[slice.symbol] = Money(amount: quantity.amount * quote.price.amount,
                                                currencyCode: quote.price.currencyCode)
        }

        let orders = PieMath.rebalancePlan(currentValues: currentValues, targets: pie.slices)
        return (pies, pie, quotes, orders)
    }

    /// Rebuilds `pie` with the given overrides via `Pie`'s validating init (rethrows —
    /// slices pass through unchanged so validation always succeeds here), replaces it
    /// within `pies`, and persists the full list. Mirrors `ContributeToPie`'s identical
    /// private helper (duplicated rather than shared — a small pure rebuild-and-save
    /// step isn't worth introducing a shared dependency between the two use cases).
    private func replace(_ pie: Pie, in pies: [Pie], ledger: [PieLedgerEntry],
                         activity: [PieActivityEntry]) throws -> Pie {
        let updated = try Pie(id: pie.id, name: pie.name, slices: pie.slices, schedule: pie.schedule,
                              createdDay: pie.createdDay, ledger: ledger, activity: activity)
        var all = pies
        if let index = all.firstIndex(where: { $0.id == pie.id }) {
            all[index] = updated
        } else {
            all.append(updated)
        }
        pieStore.save(all)
        return updated
    }
}

/// Manual-sell clamp: keeps every Pie's ledger honest against what the portfolio
/// actually holds. A symbol sold manually outside a Pie (e.g. from the portfolio
/// screen) can leave one or more Pies' ledgers claiming more shares than the portfolio
/// still owns; this reconciles every over-claimed symbol back down to the held
/// quantity, recording who lost how much.
///
/// Synchronous and store-only — no market data is touched, since reconciliation only
/// compares recorded quantities, never prices.
public struct ReconcilePieLedgers: Sendable {
    private let pieStore: PieStore
    private let portfolioStore: PortfolioStore
    private let calendar: MarketCalendar
    private let now: @Sendable () -> Date

    public init(pieStore: PieStore, portfolioStore: PortfolioStore,
               calendar: MarketCalendar = MarketCalendar(), now: @escaping @Sendable () -> Date = { Date() }) {
        self.pieStore = pieStore
        self.portfolioStore = portfolioStore
        self.calendar = calendar
        self.now = now
    }

    /// Clamps every pie ledger entry to the actually-held portfolio quantity for that
    /// symbol. When multiple Pies claim the same over-subscribed symbol, the clamp is
    /// applied largest-ledger-first: smaller claims are preserved in full and the
    /// largest claimant absorbs the shortfall (walking to the next-largest if even a
    /// fully-zeroed largest claimant isn't enough). Ties break lexicographically by pie
    /// id — the lexicographically first id clamps first. Only pies whose ledger
    /// actually changed gain a `manualAdjustment` activity entry; saves the full pie
    /// list once.
    public func callAsFunction() -> [Pie] {
        let pies = pieStore.load()
        let portfolio = portfolioStore.load()
        let today = calendar.tradingDay(of: now())

        // symbol -> pieId -> current ledger quantity (mutated in place as clamps apply).
        var quantities: [String: [String: Decimal]] = [:]
        var symbols: Set<String> = []
        for pie in pies {
            for entry in pie.ledger where entry.quantity.amount > 0 {
                quantities[entry.symbol, default: [:]][pie.id] = entry.quantity.amount
                symbols.insert(entry.symbol)
            }
        }

        var clampedPieIds: Set<String> = []

        for symbol in symbols {
            guard var claims = quantities[symbol] else { continue }
            let totalClaimed = claims.values.reduce(Decimal(0), +)
            let heldQuantity = portfolio.position(for: symbol)?.quantity.amount ?? 0
            guard totalClaimed > heldQuantity else { continue }

            // Smallest claim first (full allocation preserved); ties give priority to
            // the lexicographically LATER pie id here, so the lexicographically FIRST
            // id lands last in this walk — exactly where the remaining budget runs out
            // first, making it the one clamped.
            let ordered = claims.keys.sorted { a, b in
                let qa = claims[a] ?? 0
                let qb = claims[b] ?? 0
                if qa == qb { return a > b }
                return qa < qb
            }

            var remaining = heldQuantity
            for pieId in ordered {
                let claim = claims[pieId] ?? 0
                if remaining >= claim {
                    remaining -= claim
                } else {
                    let clampedQuantity = max(0, remaining)
                    if clampedQuantity != claim {
                        claims[pieId] = clampedQuantity
                        clampedPieIds.insert(pieId)
                    }
                    remaining = 0
                }
            }
            quantities[symbol] = claims
        }

        guard !clampedPieIds.isEmpty else { return pies }

        var updatedPies: [Pie] = []
        for pie in pies {
            guard clampedPieIds.contains(pie.id) else {
                updatedPies.append(pie)
                continue
            }

            var newLedger = pie.ledger
            for index in newLedger.indices {
                let symbol = newLedger[index].symbol
                if let clamped = quantities[symbol]?[pie.id] {
                    newLedger[index] = PieLedgerEntry(symbol: symbol, quantity: Quantity(clamped))
                }
            }

            let adjustment = PieActivityEntry(kind: .manualAdjustment, day: today, amount: nil)
            // `Pie`'s validating init only rejects malformed slices (empty, duplicate,
            // mis-summed weights) — none of which this ledger-only rebuild can trigger,
            // since `slices` passes through unchanged from an already-valid Pie.
            guard let updated = try? Pie(id: pie.id, name: pie.name, slices: pie.slices, schedule: pie.schedule,
                                        createdDay: pie.createdDay, ledger: newLedger,
                                        activity: pie.activity + [adjustment]) else {
                updatedPies.append(pie)
                continue
            }
            updatedPies.append(updated)
        }

        pieStore.save(updatedPies)
        return updatedPies
    }
}

/// Catch-up engine: executes scheduled Pie contributions that were missed because the
/// app wasn't running on their due day(s). For each due day strictly after the Pie's
/// schedule cursor (`schedule.nextDueDay`) through today, contributes at that day's
/// historical closing price — except for today's own due day (if due), which uses a
/// live quote via `ContributeToPie` (delegating keeps the one live-pricing path defined
/// in a single place rather than duplicated here).
///
/// Historical closes come from `MarketDataRepository.history(for:timeframe:)` — the
/// same daily-history method `FetchPortfolioPerformanceUseCase` uses for its own
/// reconstruction — fetched once per slice symbol (not once per due day). Unlike that
/// use case (which keys off raw `PricePoint.date` values and forward-fills each
/// symbol's last known close across mismatched trading calendars), each point here is
/// indexed by its market-local day string via `MarketCalendar.tradingDay(of:)` and
/// looked up with an exact match against the due day being executed — deliberately
/// more precise than forward-filling, since a contribution must price against that
/// day's own close, never a nearby one. `.oneYear` is requested; a Pie neglected longer
/// than that simply has its oldest due days silently skipped for lack of a close — the
/// same fallback as a day with a genuinely missing close (see below) rather than a hard
/// failure.
///
/// A past due day missing a close for ANY slice symbol is skipped silently: no
/// `ContributionOutcome`, no activity entry, no ledger change — it is simply consumed
/// (the schedule cursor still advances past it), mirroring `PieMathBacktest`'s
/// insufficient-history skip semantics. Insufficient cash, in contrast, IS recorded
/// (`.skippedInsufficientCash`) and does not stop later due days in the same run from
/// being attempted.
///
/// **Crash/failure resumability:** the schedule cursor (`nextDueDay`) advances
/// incrementally, immediately after each due day is *consumed* — executed, recorded as
/// missed (insufficient cash), or silently skipped (missing close) — not once at the
/// end of the due-day loop. Executed/missed days advance the cursor atomically with
/// that same day's ledger/activity write (one `replace` call, one store save); a
/// silently-skipped day has no ledger/activity write of its own, so its cursor advance
/// is its only persisted trace. This makes a mid-run throw (e.g. today's live quote
/// failing after several historical days already executed) safe to retry: the cursor
/// sits exactly at the first not-yet-consumed day, so the next run resumes there
/// instead of replaying already-executed days.
///
/// Each Pie is processed independently and defensively: any thrown error while
/// processing a Pie (a live-quote failure on today's due day, or a `history` fetch
/// failure while building the historical closes table) degrades that one Pie's result
/// to an empty outcomes list rather than failing the whole run — other Pies still get
/// processed, and any due days already consumed for that Pie before the failure remain
/// persisted, cursor included (each due day commits to the stores independently,
/// exactly like a standalone `ContributeToPie` call would).
public struct ExecuteDueContributions: Sendable {
    private let pieStore: PieStore
    private let portfolioStore: PortfolioStore
    private let market: MarketDataRepository
    private let calendar: MarketCalendar

    public init(pieStore: PieStore, portfolioStore: PortfolioStore, market: MarketDataRepository, calendar: MarketCalendar) {
        self.pieStore = pieStore
        self.portfolioStore = portfolioStore
        self.market = market
        self.calendar = calendar
    }

    /// Runs catch-up for every scheduled Pie. Pies without a `schedule`, or whose
    /// schedule has nothing due yet, are left completely untouched and omitted from the
    /// result.
    public func callAsFunction(now: Date) async -> [(pie: Pie, outcomes: [ContributionOutcome])] {
        let today = calendar.tradingDay(of: now)
        var results: [(pie: Pie, outcomes: [ContributionOutcome])] = []

        for pie in pieStore.load() {
            guard let schedule = pie.schedule else { continue }

            do {
                if let result = try await catchUp(pie: pie, schedule: schedule, today: today, now: now) {
                    results.append(result)
                }
            } catch {
                // Degrade this Pie's result to empty outcomes rather than aborting the
                // whole run; any due days already consumed before the failure — buys,
                // missed-cash entries, silently-skipped days — are still reflected in
                // the reloaded Pie below, cursor included, since each one persists
                // immediately as it's processed (see `catchUp`'s per-day loop).
                let latest = pieStore.load().first(where: { $0.id == pie.id }) ?? pie
                results.append((pie: latest, outcomes: []))
            }
        }
        return results
    }

    /// - Returns: `nil` when nothing is due yet (the schedule cursor is still in the future).
    private func catchUp(
        pie: Pie, schedule: ContributionSchedule, today: String, now: Date
    ) async throws -> (pie: Pie, outcomes: [ContributionOutcome])? {
        guard let dayBeforeCursor = dayBefore(schedule.nextDueDay) else { return nil }

        // The cursor itself is the first eligible due day — `PieSchedule.dueDays` never
        // treats its own anchor as a candidate (stepping starts at anchor + 1×cadence),
        // so, exactly like `PieMathBacktest.dcaBacktest`, `nextDueDay`'s step-0 case
        // (which DOES treat the anchor as eligible) finds this first one, and `dueDays`
        // finds every later one from there.
        //
        // Every step below uses `schedule.anchorDay` (the schedule's fixed, ORIGINAL
        // first due day) — never `schedule.nextDueDay` (the moving cursor) — as the
        // cadence anchor. `nextDueDay`/`dueDays` step monthly cadences via
        // `DateComponents(month:)` from the anchor, which Foundation clamps in shorter
        // months (e.g. the 31st -> Feb 28); re-anchoring on a clamped cursor would
        // permanently lose the original day-of-month. `schedule.nextDueDay` is still
        // used as the window/cursor bound (`afterDay`) below — only the anchor changed.
        let firstDue = PieSchedule.nextDueDay(
            anchorDay: schedule.anchorDay, cadence: schedule.cadence, afterDay: dayBeforeCursor, calendar: calendar
        )
        guard firstDue <= today else { return nil }

        let laterDueDays = PieSchedule.dueDays(
            anchorDay: schedule.anchorDay, cadence: schedule.cadence, afterDay: firstDue, throughDay: today,
            calendar: calendar
        )
        let dueDays = ([firstDue] + laterDueDays).sorted()

        let closesBySymbol = try await historicalCloses(for: pie.slices.map(\.symbol), excluding: today, in: dueDays)

        var outcomes: [ContributionOutcome] = []
        var latestPie = pie
        for day in dueDays {
            // The cursor value once `day` is fully consumed — always derived from the
            // fixed original anchor (never a shifting one), so it lands on exactly the
            // next entry `dueDays` would itself have produced.
            let cursorAfterDay = PieSchedule.nextDueDay(
                anchorDay: schedule.anchorDay, cadence: schedule.cadence, afterDay: day, calendar: calendar
            )

            if day == today {
                let outcome = try await ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: market)(
                    pieId: pie.id, amount: schedule.amount, day: day, now: now
                )
                outcomes.append(outcome)
                // ContributeToPie already persisted the ledger/activity change; this
                // persists the cursor advance as its own immediate follow-up write —
                // if a LATER pie in this run throws, this Pie's progress up through
                // today is not lost.
                latestPie = try advanceScheduleOnly(
                    pieId: pie.id, to: cursorAfterDay, amount: schedule.amount, anchorDay: schedule.anchorDay,
                    cadence: schedule.cadence
                )
            } else if let (outcome, updatedPie) = try executeAtClose(
                pieId: pie.id, amount: schedule.amount, day: day, closesBySymbol: closesBySymbol,
                newNextDueDay: cursorAfterDay, anchorDay: schedule.anchorDay, cadence: schedule.cadence
            ) {
                outcomes.append(outcome)
                latestPie = updatedPie
            } else {
                // At least one slice symbol is missing a close on `day`: no buy, no
                // activity entry — but the day is still consumed, so the cursor still
                // advances past it (its only persisted trace) so it is never
                // reconsidered on a later run.
                latestPie = try advanceScheduleOnly(
                    pieId: pie.id, to: cursorAfterDay, amount: schedule.amount, anchorDay: schedule.anchorDay,
                    cadence: schedule.cadence
                )
            }
        }

        return (pie: latestPie, outcomes: outcomes)
    }

    /// `closes[symbol][day]`, fetched once per symbol and reused across every
    /// historical due day in this run. Skipped entirely when the only due day is
    /// `today` — no historical closes are needed in that case.
    private func historicalCloses(
        for symbols: [String], excluding today: String, in dueDays: [String]
    ) async throws -> [String: [String: Money]] {
        guard dueDays.contains(where: { $0 != today }) else { return [:] }
        return try await fetchClosesByDay(market: market, symbols: symbols, calendar: calendar)
    }

    /// Executes one historical due day at its close, mirroring `ContributeToPie`'s core
    /// semantics (distribute -> unrounded qty = share/close -> buy -> ledger/activity)
    /// but priced from `closesBySymbol` instead of a live quote. Reloads the Pie and
    /// Portfolio fresh so each due day sees the previous due day's results (cash spent,
    /// ledger grown) within the same run. The schedule cursor is advanced to
    /// `newNextDueDay` in the SAME `replace` write as the ledger/activity change, so a
    /// throw on a later day can never leave this day partially persisted (mutation
    /// applied but cursor stale, or vice versa).
    ///
    /// - Returns: `nil` (no outcome, no mutation at all — cursor included) if any slice
    ///   symbol is missing a positive close on `day`. The caller is responsible for
    ///   still advancing the cursor past a `nil` result, since the day is consumed
    ///   either way.
    private func executeAtClose(
        pieId: String, amount: Money, day: String, closesBySymbol: [String: [String: Money]],
        newNextDueDay: String, anchorDay: String, cadence: PieCadence
    ) throws -> (outcome: ContributionOutcome, pie: Pie)? {
        let pies = pieStore.load()
        guard let pie = pies.first(where: { $0.id == pieId }) else { return nil }

        var closes: [String: Money] = [:]
        for slice in pie.slices {
            guard let close = closesBySymbol[slice.symbol]?[day], close.amount > 0 else { return nil }
            closes[slice.symbol] = close
        }

        var currentValues: [String: Money] = [:]
        for slice in pie.slices {
            let quantity = pie.quantity(of: slice.symbol)
            guard let close = closes[slice.symbol] else { continue }
            currentValues[slice.symbol] = Money(amount: quantity.amount * close.amount, currencyCode: close.currencyCode)
        }

        let shares = PieMath.distribute(contribution: amount, currentValues: currentValues, targets: pie.slices)
        let advancedSchedule = ContributionSchedule(amount: amount, cadence: cadence, anchorDay: anchorDay,
                                                     nextDueDay: newNextDueDay)

        let portfolio = portfolioStore.load()
        guard amount.amount <= portfolio.cash.amount else {
            let missed = PieActivityEntry(kind: .missedInsufficientCash, day: day, amount: amount)
            let updatedPie = try replace(pie, in: pies, schedule: advancedSchedule, activity: pie.activity + [missed])
            return (.skippedInsufficientCash(updatedPie), updatedPie)
        }

        var updatedPortfolio = portfolio
        var newLedger = pie.ledger
        let transactionDate = PieSchedule.date(fromDay: day, calendar: calendar) ?? Date()
        for slice in pie.slices {
            guard let share = shares[slice.symbol], share.amount > 0, let close = closes[slice.symbol] else { continue }

            let quantity = Quantity(share.amount / close.amount)
            let asset = Asset(symbol: slice.symbol, name: slice.symbol, kind: slice.assetKind)
            updatedPortfolio = try updatedPortfolio.buying(asset, quantity: quantity, at: close,
                                                            on: transactionDate, pieId: pieId)

            let newQuantity = Quantity(pie.quantity(of: slice.symbol).amount + quantity.amount)
            if let index = newLedger.firstIndex(where: { $0.symbol == slice.symbol }) {
                newLedger[index] = PieLedgerEntry(symbol: slice.symbol, quantity: newQuantity)
            } else {
                newLedger.append(PieLedgerEntry(symbol: slice.symbol, quantity: newQuantity))
            }
        }

        let contributed = PieActivityEntry(kind: .contribution, day: day, amount: amount)
        let updatedPie = try replace(pie, in: pies, schedule: advancedSchedule, ledger: newLedger,
                                     activity: pie.activity + [contributed])

        portfolioStore.save(updatedPortfolio)
        return (.executed(updatedPortfolio, updatedPie), updatedPie)
    }

    /// Advances the schedule cursor past a day that was consumed without a Pie mutation
    /// of its own to piggy-back the cursor write onto — either today's live-quote day
    /// (whose ledger/activity change, if any, was already persisted inside
    /// `ContributeToPie`) or a historical day silently skipped for a missing close.
    /// Persisting the cursor immediately here, rather than deferring it to the end of
    /// the due-day loop, is what makes a mid-run throw resumable without replaying
    /// already-consumed days.
    private func advanceScheduleOnly(
        pieId: String, to nextDueDay: String, amount: Money, anchorDay: String, cadence: PieCadence
    ) throws -> Pie {
        let pies = pieStore.load()
        guard let pie = pies.first(where: { $0.id == pieId }) else { throw AppError.notFound }
        let schedule = ContributionSchedule(amount: amount, cadence: cadence, anchorDay: anchorDay, nextDueDay: nextDueDay)
        return try replace(pie, in: pies, schedule: schedule, activity: pie.activity)
    }

    /// Rebuilds `pie` with the given overrides via `Pie`'s validating init (rethrows —
    /// slices pass through unchanged so validation always succeeds here), replaces it
    /// within `pies`, and persists the full list in one write. `schedule` is always
    /// passed explicitly (never defaulted to `pie.schedule`) so every call site is
    /// forced to state, atomically with whatever else it's changing, exactly where the
    /// cursor lands. Otherwise mirrors `ContributeToPie`'s identical private helper
    /// (duplicated rather than shared — a small pure rebuild-and-save step isn't worth
    /// introducing a shared dependency between the two use cases).
    private func replace(_ pie: Pie, in pies: [Pie], schedule: ContributionSchedule,
                         ledger: [PieLedgerEntry]? = nil,
                         activity: [PieActivityEntry]) throws -> Pie {
        let updated = try Pie(id: pie.id, name: pie.name, slices: pie.slices, schedule: schedule,
                              createdDay: pie.createdDay, ledger: ledger ?? pie.ledger, activity: activity)
        var all = pies
        if let index = all.firstIndex(where: { $0.id == pie.id }) {
            all[index] = updated
        } else {
            all.append(updated)
        }
        pieStore.save(all)
        return updated
    }

    /// The calendar day immediately before `day`, in the same `yyyy-MM-dd` shape (used
    /// as `dueDays`'/`nextDueDay`'s lower-bound `afterDay` so the schedule cursor itself
    /// is treated as eligible to be the first due day). `nil` on malformed input.
    /// Mirrors `PieMathBacktest`'s identical private `dayBefore` helper.
    private func dayBefore(_ day: String) -> String? {
        guard let date = PieSchedule.date(fromDay: day, calendar: calendar) else { return nil }
        guard let previous = Self.parsingCalendar.date(byAdding: .day, value: -1, to: date) else { return nil }
        return calendar.tradingDay(of: previous)
    }

    private static var parsingCalendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        return cal
    }
}

/// Dollar-cost-averaging (DCA) simulation: fetches daily historical closes for a Pie's
/// slices over a given timeframe and runs a backtest to show projected portfolio growth
/// under a scheduled contribution plan.
///
/// Fetches one year of daily history per slice symbol using
/// `MarketDataRepository.history(for:timeframe:.oneYear)`. The `years` parameter
/// (1, 3, or 5) determines the backtest window: `startDay = now minus years calendar years`,
/// `endDay = today's trading day`. For `years > 1`, the fetched history may not cover the
/// full span (the history port's longest timeframe is `.oneYear`); `PieMathBacktest`'s
/// missing-close semantics handle the gap gracefully — early due days simply skip if their
/// close is absent, and the report shows only what's coverable. A future M8 extension to
/// the history port may lift this limitation; do NOT extend `MarketDataRepository` yourself.
///
/// Network failure on ANY symbol → returns `nil` (never throws — mirrors
/// `FetchEarningsCalendarUseCase`'s degrade pattern). Cancellation degrades to `nil`
/// like any other error; callers needing to distinguish cancellation from other failures
/// may check `Task.isCancelled`.
public struct SimulateDCA: Sendable {
    private let market: MarketDataRepository
    private let calendar: MarketCalendar

    public init(market: MarketDataRepository, calendar: MarketCalendar) {
        self.market = market
        self.calendar = calendar
    }

    /// Fetches daily history for each slice symbol over `years` and runs
    /// `PieMathBacktest.dcaBacktest`. Returns `nil` if insufficient history prevents
    /// any execution (every due day missing a close) or on any network failure, including
    /// cancellation.
    ///
    /// - Parameters:
    ///   - slices: Target allocation for the Pie being simulated.
    ///   - amount: Contribution amount on each due day.
    ///   - cadence: Contribution frequency.
    ///   - years: Backtest window in calendar years (1, 3, or 5).
    ///   - now: Reference date for computing the window and trading day of today.
    /// - Returns: A backtest report, or `nil` if no due day is executable or any
    ///   network failure (including cancellation) occurs.
    public func callAsFunction(
        slices: [PieSlice],
        amount: Money,
        cadence: PieCadence,
        years: Int,
        now: Date
    ) async -> BacktestReport? {
        do {
            // Compute window: startDay = now - years, endDay = today
            guard years > 0 else { return nil }
            let dateComponents = DateComponents(year: -years)
            let startDate = Calendar.current.date(byAdding: dateComponents, to: now) ?? now
            let startDay = calendar.tradingDay(of: startDate)
            let endDay = calendar.tradingDay(of: now)

            // Fetch history for each slice symbol once (shared helper)
            let dailyCloses = try await fetchClosesByDay(
                market: market,
                symbols: slices.map(\.symbol),
                calendar: calendar
            )

            // Run backtest
            return PieMathBacktest.dcaBacktest(
                slices: slices,
                amount: amount,
                cadence: cadence,
                startDay: startDay,
                endDay: endDay,
                dailyCloses: dailyCloses,
                calendar: calendar
            )
        } catch {
            return nil
        }
    }
}
