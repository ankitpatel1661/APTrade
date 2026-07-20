import Foundation
import APTradeDomain

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

/// Catch-up engine: executes scheduled Pie contributions that were missed because the
/// app wasn't running on their due day(s). For each due day strictly after the Pie's
/// schedule cursor (`schedule.nextDueDay`) through today, contributes at that day's
/// historical closing price — except for today's own due day (if due), which uses a
/// live quote via `ContributeToPie` (delegating keeps the one live-pricing path defined
/// in a single place rather than duplicated here).
///
/// Historical closes come from `MarketDataRepository.history(for:timeframe:)` — the
/// same daily-history method `FetchPortfolioPerformanceUseCase` uses to reconstruct the
/// portfolio value curve — fetched once per slice symbol (not once per due day) and
/// indexed by day string via `MarketCalendar.tradingDay(of:)`, mirroring that use
/// case's convention. `.oneYear` is requested; a Pie neglected longer than that simply
/// has its oldest due days silently skipped for lack of a close — the same fallback as
/// a day with a genuinely missing close (see below) rather than a hard failure.
///
/// A past due day missing a close for ANY slice symbol is skipped silently: no
/// `ContributionOutcome`, no activity entry, no ledger change — it is simply consumed
/// (the schedule cursor still advances past it), mirroring `PieMathBacktest`'s
/// insufficient-history skip semantics. Insufficient cash, in contrast, IS recorded
/// (`.skippedInsufficientCash`) and does not stop later due days in the same run from
/// being attempted.
///
/// Each Pie is processed independently and defensively: any thrown error while
/// processing a Pie (a live-quote failure on today's due day, or a `history` fetch
/// failure while building the historical closes table) degrades that one Pie's result
/// to an empty outcomes list rather than failing the whole run — other Pies still get
/// processed, and any historical due days already executed for that Pie before the
/// failure remain persisted (each due day commits to the stores independently, exactly
/// like a standalone `ContributeToPie` call would).
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
                // whole run; any due days it already executed before the failure are
                // still reflected in the reloaded Pie below (each due day persists
                // independently as it's processed, see `executeAtClose`).
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
        let firstDue = PieSchedule.nextDueDay(
            anchorDay: schedule.nextDueDay, cadence: schedule.cadence, afterDay: dayBeforeCursor, calendar: calendar
        )
        guard firstDue <= today else { return nil }

        let laterDueDays = PieSchedule.dueDays(
            anchorDay: schedule.nextDueDay, cadence: schedule.cadence, afterDay: firstDue, throughDay: today,
            calendar: calendar
        )
        let dueDays = ([firstDue] + laterDueDays).sorted()

        let closesBySymbol = try await historicalCloses(for: pie.slices.map(\.symbol), excluding: today, in: dueDays)

        var outcomes: [ContributionOutcome] = []
        for day in dueDays {
            if day == today {
                let outcome = try await ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: market)(
                    pieId: pie.id, amount: schedule.amount, day: day, now: now
                )
                outcomes.append(outcome)
            } else if let outcome = try executeAtClose(
                pieId: pie.id, amount: schedule.amount, day: day, closesBySymbol: closesBySymbol
            ) {
                outcomes.append(outcome)
            }
            // else: at least one slice symbol is missing a close on `day` — silently skipped.
        }

        let newNextDueDay = PieSchedule.nextDueDay(
            anchorDay: schedule.nextDueDay, cadence: schedule.cadence, afterDay: today, calendar: calendar
        )
        let finalPie = try advanceSchedule(pieId: pie.id, to: newNextDueDay, amount: schedule.amount, cadence: schedule.cadence)
        return (pie: finalPie, outcomes: outcomes)
    }

    /// `closes[symbol][day]`, fetched once per symbol and reused across every
    /// historical due day in this run. Skipped entirely when the only due day is
    /// `today` — no historical closes are needed in that case.
    private func historicalCloses(
        for symbols: [String], excluding today: String, in dueDays: [String]
    ) async throws -> [String: [String: Money]] {
        guard dueDays.contains(where: { $0 != today }) else { return [:] }

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

    /// Executes one historical due day at its close, mirroring `ContributeToPie`'s core
    /// semantics (distribute -> unrounded qty = share/close -> buy -> ledger/activity)
    /// but priced from `closesBySymbol` instead of a live quote. Reloads the Pie and
    /// Portfolio fresh so each due day sees the previous due day's results (cash spent,
    /// ledger grown) within the same run.
    ///
    /// - Returns: `nil` (no outcome, no mutation) if any slice symbol is missing a
    ///   positive close on `day` — the whole day is silently skipped.
    private func executeAtClose(
        pieId: String, amount: Money, day: String, closesBySymbol: [String: [String: Money]]
    ) throws -> ContributionOutcome? {
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

        let portfolio = portfolioStore.load()
        guard amount.amount <= portfolio.cash.amount else {
            let missed = PieActivityEntry(kind: .missedInsufficientCash, day: day, amount: amount)
            let updatedPie = try replace(pie, in: pies, activity: pie.activity + [missed])
            return .skippedInsufficientCash(updatedPie)
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
        let updatedPie = try replace(pie, in: pies, ledger: newLedger, activity: pie.activity + [contributed])

        portfolioStore.save(updatedPortfolio)
        return .executed(updatedPortfolio, updatedPie)
    }

    /// Rebuilds the Pie's schedule with an advanced `nextDueDay`, leaving the ledger and
    /// activity log exactly as the due-day loop in `catchUp` left them.
    private func advanceSchedule(pieId: String, to nextDueDay: String, amount: Money, cadence: PieCadence) throws -> Pie {
        let pies = pieStore.load()
        guard let pie = pies.first(where: { $0.id == pieId }) else { throw AppError.notFound }
        let updatedSchedule = ContributionSchedule(amount: amount, cadence: cadence, nextDueDay: nextDueDay)
        let updated = try Pie(id: pie.id, name: pie.name, slices: pie.slices, schedule: updatedSchedule,
                              createdDay: pie.createdDay, ledger: pie.ledger, activity: pie.activity)
        var all = pies
        if let index = all.firstIndex(where: { $0.id == pie.id }) {
            all[index] = updated
        }
        pieStore.save(all)
        return updated
    }

    /// Rebuilds `pie` with the given overrides via `Pie`'s validating init (rethrows —
    /// slices pass through unchanged so validation always succeeds here), replaces it
    /// within `pies`, and persists the full list. Mirrors `ContributeToPie`'s identical
    /// private helper (duplicated rather than shared — a small pure rebuild-and-save
    /// step isn't worth introducing a shared dependency between the two use cases).
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
