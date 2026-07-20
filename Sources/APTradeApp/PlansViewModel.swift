import Foundation
import APTradeApplication
import APTradeDomain

/// Plans tab: lists the user's investment Pies as summary cards and drives the detail
/// sheet, one-off contributions, and manual rebalancing. Every user-visible string this
/// view model produces (the insufficient-cash error, the next-contribution label) is
/// routed through `tr(_:)`.
@MainActor
@Observable
public final class PlansViewModel {

    /// One list-card's worth of display data for a Pie.
    public struct PieRow: Identifiable, Equatable {
        public let id: String
        public let name: String
        public let currentValue: Money
        public let nextContributionLabel: String?
        /// Largest absolute drift (percentage points) across the pie's slices. The view
        /// shows a drift badge when this exceeds 5pp.
        public let maxDriftPP: Decimal
        public let sliceWeights: [(String, Percentage)]

        public init(id: String, name: String, currentValue: Money, nextContributionLabel: String?,
                   maxDriftPP: Decimal, sliceWeights: [(String, Percentage)]) {
            self.id = id
            self.name = name
            self.currentValue = currentValue
            self.nextContributionLabel = nextContributionLabel
            self.maxDriftPP = maxDriftPP
            self.sliceWeights = sliceWeights
        }

        // `[(String, Percentage)]` can't derive `Equatable` (tuples don't conform to the
        // protocol), so this compares element-by-element instead.
        public static func == (lhs: PieRow, rhs: PieRow) -> Bool {
            lhs.id == rhs.id
                && lhs.name == rhs.name
                && lhs.currentValue == rhs.currentValue
                && lhs.nextContributionLabel == rhs.nextContributionLabel
                && lhs.maxDriftPP == rhs.maxDriftPP
                && lhs.sliceWeights.elementsEqual(rhs.sliceWeights) { $0.0 == $1.0 && $0.1 == $1.1 }
        }
    }

    /// Full detail-sheet data for one Pie: each slice's target vs. actual allocation and
    /// drift, the activity log, and the recurring contribution schedule (if any).
    public struct PieDetail: Equatable {
        public struct SliceDetail: Identifiable, Equatable {
            public let symbol: String
            public let assetKind: AssetKind
            public let targetWeight: Percentage
            public let actualWeight: Percentage
            public let drift: Percentage
            public let currentValue: Money
            public var id: String { symbol }

            public init(symbol: String, assetKind: AssetKind, targetWeight: Percentage,
                       actualWeight: Percentage, drift: Percentage, currentValue: Money) {
                self.symbol = symbol
                self.assetKind = assetKind
                self.targetWeight = targetWeight
                self.actualWeight = actualWeight
                self.drift = drift
                self.currentValue = currentValue
            }
        }

        public let pieId: String
        public let name: String
        public let slices: [SliceDetail]
        public let activity: [PieActivityEntry]
        public let schedule: ContributionSchedule?

        public init(pieId: String, name: String, slices: [SliceDetail],
                   activity: [PieActivityEntry], schedule: ContributionSchedule?) {
            self.pieId = pieId
            self.name = name
            self.slices = slices
            self.activity = activity
            self.schedule = schedule
        }
    }

    public private(set) var rows: [PieRow] = []
    public private(set) var detail: PieDetail?
    public private(set) var rebalancePreview: [RebalanceOrder]?
    public private(set) var errorMessage: String?

    private let loadPies: LoadPies
    private let deletePieUseCase: DeletePie
    private let contributeToPie: ContributeToPie
    private let rebalancePie: RebalancePie
    private let reconcileLedgers: ReconcilePieLedgers
    private let fetchQuotes: FetchQuotesUseCase
    private let calendar: MarketCalendar
    private let now: () -> Date

    public init(
        loadPies: LoadPies,
        deletePie: DeletePie,
        contributeToPie: ContributeToPie,
        rebalancePie: RebalancePie,
        reconcileLedgers: ReconcilePieLedgers,
        fetchQuotes: FetchQuotesUseCase,
        calendar: MarketCalendar = MarketCalendar(),
        now: @escaping () -> Date = Date.init
    ) {
        self.loadPies = loadPies
        self.deletePieUseCase = deletePie
        self.contributeToPie = contributeToPie
        self.rebalancePie = rebalancePie
        self.reconcileLedgers = reconcileLedgers
        self.fetchQuotes = fetchQuotes
        self.calendar = calendar
        self.now = now
    }

    /// Reconciles every Pie's ledger against actual portfolio holdings BEFORE building
    /// rows, so a manual sell made outside a Pie can never show stale, over-claimed
    /// values here (see `ReconcilePieLedgers`'s cross-pie drift note).
    public func onAppear() async {
        _ = reconcileLedgers()
        await reloadRows()
    }

    public func openDetail(id: String) async {
        rebalancePreview = nil // a preview from a different pie must never linger
        guard let pie = loadPies().first(where: { $0.id == id }) else {
            detail = nil
            return
        }
        detail = await buildDetail(for: pie)
    }

    public func contributeNow(id: String, amount: Money) async {
        errorMessage = nil
        rebalancePreview = nil
        guard amount.amount > 0 else {
            errorMessage = tr(.pieInvalidAmount)
            return
        }
        let day = calendar.tradingDay(of: now())
        do {
            let outcome = try await contributeToPie(pieId: id, amount: amount, day: day, now: now())
            if case .skippedInsufficientCash = outcome {
                errorMessage = tr(.pieInsufficientCash)
            }
            await reloadRows()
            if detail?.pieId == id { await openDetail(id: id) }
        } catch is CancellationError {
            // Cooperative cancellation: leave state untouched rather than surfacing it
            // as a contribution failure (mirrors CalendarViewModel's convention).
        } catch {
            errorMessage = tr(.couldntPlaceOrder)
        }
    }

    /// Prices the Pie's current holdings against live quotes and fills `rebalancePreview`
    /// with the trades `confirmRebalance` would place. Read-only.
    public func requestRebalance(id: String) async {
        errorMessage = nil
        do {
            rebalancePreview = try await rebalancePie.preview(pieId: id)
        } catch is CancellationError {
        } catch {
            errorMessage = tr(.couldntPlaceOrder)
        }
    }

    /// Executes the previewed orders, then clears the preview and refreshes rows/detail.
    public func confirmRebalance(id: String) async {
        errorMessage = nil
        do {
            _ = try await rebalancePie.execute(pieId: id, day: calendar.tradingDay(of: now()), now: now())
            rebalancePreview = nil
            await reloadRows()
            if detail?.pieId == id { await openDetail(id: id) }
        } catch is CancellationError {
        } catch {
            errorMessage = tr(.couldntPlaceOrder)
        }
    }

    public func deletePie(id: String) async {
        rebalancePreview = nil
        _ = deletePieUseCase(id: id)
        if detail?.pieId == id { detail = nil }
        await reloadRows()
    }

    // MARK: - Row / detail construction

    private func reloadRows() async {
        let pies = loadPies()
        let symbols = Array(Set(pies.flatMap { $0.slices.map(\.symbol) }))
        let quotes = await fetchQuotes(symbols: symbols)
        rows = pies.map { buildRow(for: $0, quotes: quotes) }
    }

    private func buildRow(for pie: Pie, quotes: [String: Result<Quote, AppError>]) -> PieRow {
        let currentValues = currentValues(for: pie, quotes: quotes)
        let totalValue = currentValues.values.reduce(Money(amount: 0)) { $0 + $1 }
        let drift = PieMath.drift(currentValues: currentValues, targets: pie.slices)
        let maxDriftPP = drift.values.map { abs($0.value) }.max() ?? 0
        return PieRow(
            id: pie.id,
            name: pie.name,
            currentValue: totalValue,
            nextContributionLabel: nextContributionLabel(for: pie),
            maxDriftPP: maxDriftPP,
            sliceWeights: pie.slices.map { ($0.symbol, $0.targetWeight) }
        )
    }

    private func buildDetail(for pie: Pie) async -> PieDetail {
        let quotes = await fetchQuotes(symbols: pie.slices.map(\.symbol))
        let currentValues = currentValues(for: pie, quotes: quotes)
        let totalValue = currentValues.values.reduce(Decimal(0)) { $0 + $1.amount }
        let driftBySymbol = PieMath.drift(currentValues: currentValues, targets: pie.slices)
        let sliceDetails = pie.slices.map { slice -> PieDetail.SliceDetail in
            let value = currentValues[slice.symbol] ?? Money(amount: 0)
            let actual = totalValue > 0
                ? Percentage(value: (value.amount / totalValue) * 100)
                : Percentage(value: 0)
            return PieDetail.SliceDetail(
                symbol: slice.symbol,
                assetKind: slice.assetKind,
                targetWeight: slice.targetWeight,
                actualWeight: actual,
                drift: driftBySymbol[slice.symbol] ?? Percentage(value: 0),
                currentValue: value
            )
        }
        return PieDetail(pieId: pie.id, name: pie.name, slices: sliceDetails, activity: pie.activity, schedule: pie.schedule)
    }

    /// Prices `pie`'s ledger against `quotes`; a symbol with no successful quote is
    /// simply omitted (mirrors `PieMath`'s own "0 if missing" treatment).
    private func currentValues(for pie: Pie, quotes: [String: Result<Quote, AppError>]) -> [String: Money] {
        var result: [String: Money] = [:]
        for slice in pie.slices {
            guard case .success(let quote)? = quotes[slice.symbol] else { continue }
            let quantity = pie.quantity(of: slice.symbol)
            result[slice.symbol] = Money(amount: quantity.amount * quote.price.amount, currencyCode: quote.price.currencyCode)
        }
        return result
    }

    private func nextContributionLabel(for pie: Pie) -> String? {
        guard let schedule = pie.schedule,
              let date = PieSchedule.date(fromDay: schedule.nextDueDay, calendar: calendar)
        else { return nil }
        return String(format: tr(.nextContributionFormat), Self.dueDayFormatter.string(from: date))
    }

    private static let dueDayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.dateFormat = "MMM d"
        return f
    }()
}
