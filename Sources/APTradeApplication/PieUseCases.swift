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
