import Foundation
import APTradeApplication
import APTradeDomain

/// Everything the Income engine has to say about dividends, reduced to the two facts
/// Home cares about. Built by wrapping the SAME pipeline `IncomeViewModel` runs (see
/// `CompositionRoot.makeHomeViewModel`) — Home never recomputes dividend math itself.
struct HomeIncomeSummary: Equatable, Sendable {
    let receivedYTD: Money
    let nextDividend: IncomeViewModel.UpcomingRow?
}

/// One row in Home's "Today" feed. Carries data only — copy/formatting is the view's job
/// (per house rule: ViewModels expose data, Views format it through `tr(.…)`).
enum HomeFeedItem: Equatable {
    case marketStatus(isOpen: Bool, nextTransition: Date)
    case topGainer(symbol: String, changePercent: Percentage)
    case topLoser(symbol: String, changePercent: Percentage)
    case earnings(symbol: String, session: EarningsSession, date: Date)
    case dividend(symbol: String, amount: Money, date: Date)
    case screenerFresh(name: String, matches: Int)
}

/// Home dashboard: PURE aggregation over the same engines Portfolio/Watchlist/
/// Performance/Income/Calendar/Screener/Alerts already expose — no calculation here is
/// reimplemented from those surfaces, only combined.
///
/// Failure isolation: every source is fetched independently and wrapped in its own
/// `do`/`catch`. Cooperative cancellation (`CancellationError`) is never swallowed into a
/// degraded empty state — each helper returns immediately, leaving whatever was already
/// on screen untouched, mirroring `CalendarViewModel.load()`'s handling. Any OTHER error
/// degrades just that source's output/row; every other source is unaffected — e.g. a
/// portfolio-load failure still leaves `feed`'s market-status row intact.
@MainActor
@Observable
final class HomeViewModel {
    private(set) var totalValue = Money(amount: 0)
    private(set) var dayChange = Money(amount: 0)
    private(set) var dayChangePercent = Percentage(value: 0)
    private(set) var totalReturnPercent: Double = 0
    private(set) var cash = Money(amount: 0)
    private(set) var incomeYTD = Money(amount: 0)
    private(set) var sparkValues: [Double] = []
    private(set) var feed: [HomeFeedItem] = []
    private(set) var alertCount: Int = 0

    // MARK: - Injected sources (each independently fakeable/failable)

    /// The same source `PortfolioViewModel` reads: positions + cash. Typed `throws` (rather
    /// than the concrete non-throwing `FetchPortfolioUseCase`) purely so tests can inject a
    /// failing double to exercise per-source isolation — production wiring's non-throwing
    /// use-case call still satisfies this type.
    private let loadPortfolio: @Sendable () async throws -> Portfolio
    /// Live quotes for whatever symbol set is asked for — already per-symbol failure
    /// isolated (`FetchQuotesUseCase` never throws), so it's injected directly, unwrapped.
    private let fetchQuotes: FetchQuotesUseCase
    /// Holdings ∪ watchlist symbols, deduped — the SAME provider `CompositionRoot` already
    /// builds for the earnings-calendar's `ownSymbols` closure precedent. Independent of
    /// `loadPortfolio` above, so a portfolio-load failure never blocks the movers row.
    private let ownSymbols: @Sendable () async -> Set<String>
    /// The Performance tab's own equity-curve reconstruction — reused verbatim for both
    /// `totalReturnPercent` and (trailing ≤30 points of) `sparkValues`.
    private let fetchPerformanceReport: @Sendable () async throws -> PerformanceReport
    /// Wraps the SAME pipeline `IncomeViewModel` runs (YTD + next upcoming payout).
    private let loadIncomeSummary: @Sendable () async throws -> HomeIncomeSummary
    /// The earnings-calendar fetch, restricted to owned+watched symbols, soonest first —
    /// reuses `FetchEarningsCalendarUseCase.execute`'s existing owned-first day-ascending
    /// sort rather than re-deriving "next" itself.
    private let fetchNextEarnings: @Sendable () async throws -> EarningsEvent?
    /// The persisted screener snapshot — same store `ScreenerViewModel` reads.
    private let loadScreenerSnapshot: @Sendable () -> ScreenerSnapshot?
    /// All alerts, for `alertCount` — same store `WatchlistViewModel` loads from.
    private let loadAlerts: LoadAlertsUseCase
    /// Shared with `ScreenerViewModel`'s freshness check and the market-hours status below,
    /// so "today" means the same trading day everywhere.
    private let calendar: MarketCalendar
    private let now: () -> Date

    init(loadPortfolio: @escaping @Sendable () async throws -> Portfolio,
         fetchQuotes: FetchQuotesUseCase,
         ownSymbols: @escaping @Sendable () async -> Set<String>,
         fetchPerformanceReport: @escaping @Sendable () async throws -> PerformanceReport,
         loadIncomeSummary: @escaping @Sendable () async throws -> HomeIncomeSummary,
         fetchNextEarnings: @escaping @Sendable () async throws -> EarningsEvent?,
         loadScreenerSnapshot: @escaping @Sendable () -> ScreenerSnapshot?,
         loadAlerts: LoadAlertsUseCase,
         calendar: MarketCalendar = MarketCalendar(),
         now: @escaping () -> Date = Date.init) {
        self.loadPortfolio = loadPortfolio
        self.fetchQuotes = fetchQuotes
        self.ownSymbols = ownSymbols
        self.fetchPerformanceReport = fetchPerformanceReport
        self.loadIncomeSummary = loadIncomeSummary
        self.fetchNextEarnings = fetchNextEarnings
        self.loadScreenerSnapshot = loadScreenerSnapshot
        self.loadAlerts = loadAlerts
        self.calendar = calendar
        self.now = now
    }

    // MARK: - Per-source scratch state, assembled into `feed` in fixed order.

    private var marketStatusItem: HomeFeedItem?
    private var gainerItem: HomeFeedItem?
    private var loserItem: HomeFeedItem?
    private var earningsItem: HomeFeedItem?
    private var dividendItem: HomeFeedItem?
    private var screenerItem: HomeFeedItem?

    func refresh() async {
        refreshMarketStatus()
        await refreshHero()
        await refreshMovers()
        await refreshEquityCurve()
        await refreshIncome()
        await refreshEarnings()
        refreshScreener()
        refreshAlerts()
        rebuildFeed()
    }

    /// Fixed order: status, then movers (gainer, loser), then earnings, dividend, screener.
    private func rebuildFeed() {
        feed = [marketStatusItem, gainerItem, loserItem, earningsItem, dividendItem, screenerItem]
            .compactMap { $0 }
    }

    // MARK: - Market status (pure/sync — can't fail)

    private func refreshMarketStatus() {
        let instant = now()
        let status = calendar.status(at: instant)
        let transition = nextTransition(from: instant, currentStatus: status)
        marketStatusItem = .marketStatus(isOpen: status == .open, nextTransition: transition)
    }

    /// Finds the next minute at which `calendar.status(at:)` disagrees with `currentStatus`
    /// by stepping forward minute-by-minute. This queries the calendar's own hours table as
    /// an oracle rather than re-encoding 9:30/16:00/holiday knowledge here — "reuse, don't
    /// duplicate." Capped at one week of minutes, comfortably covering any holiday+weekend
    /// stack; the cap is defensive only and should never be hit in practice.
    private func nextTransition(from date: Date, currentStatus: MarketStatus) -> Date {
        var candidate = date
        for _ in 0..<(7 * 24 * 60) {
            candidate = candidate.addingTimeInterval(60)
            if calendar.status(at: candidate) != currentStatus {
                return candidate
            }
        }
        return candidate
    }

    // MARK: - Hero stats (totalValue / dayChange / dayChangePercent / cash)

    private func refreshHero() async {
        do {
            let portfolio = try await loadPortfolio()
            let symbols = portfolio.positions.map(\.asset.symbol)
            let results = symbols.isEmpty ? [:] : await fetchQuotes(symbols: symbols)
            let quotes = results.compactMapValues { try? $0.get() }
            let valuation = portfolio.valuation(quotes: quotes)
            totalValue = valuation.totalValue
            dayChange = valuation.dayChange
            cash = valuation.cash
            let previousTotal = valuation.totalValue.amount - valuation.dayChange.amount
            dayChangePercent = previousTotal == 0
                ? Percentage(value: 0)
                : Percentage(value: (valuation.dayChange.amount / previousTotal) * 100)
        } catch is CancellationError {
            return
        } catch {
            totalValue = Money(amount: 0)
            dayChange = Money(amount: 0)
            dayChangePercent = Percentage(value: 0)
            cash = Money(amount: 0)
        }
    }

    // MARK: - Movers (top gainer / top loser across holdings ∪ watchlist)

    private func refreshMovers() async {
        let symbols = await ownSymbols()
        guard !symbols.isEmpty else {
            gainerItem = nil
            loserItem = nil
            return
        }
        let results = await fetchQuotes(symbols: Array(symbols))
        let quoted: [(symbol: String, quote: Quote)] = results.compactMap { symbol, result in
            guard case .success(let quote) = result else { return nil }
            return (symbol, quote)
        }
        guard !quoted.isEmpty else {
            gainerItem = nil
            loserItem = nil
            return
        }
        let sorted = quoted.sorted { $0.quote.changePercent.value > $1.quote.changePercent.value }
        let gainer = sorted[0]
        gainerItem = .topGainer(symbol: gainer.symbol, changePercent: gainer.quote.changePercent)
        if sorted.count > 1 {
            let loser = sorted[sorted.count - 1]
            loserItem = .topLoser(symbol: loser.symbol, changePercent: loser.quote.changePercent)
        } else {
            loserItem = nil
        }
    }

    // MARK: - Equity curve (spark) + total return, from the Performance tab's reconstruction

    private func refreshEquityCurve() async {
        do {
            let report = try await fetchPerformanceReport()
            totalReturnPercent = report.metrics.totalReturn
            sparkValues = report.equityCurve.suffix(30).map { ($0.value.amount as NSDecimalNumber).doubleValue }
        } catch is CancellationError {
            return
        } catch {
            totalReturnPercent = 0
            sparkValues = []
        }
    }

    // MARK: - Income (YTD + next upcoming dividend)

    private func refreshIncome() async {
        do {
            let summary = try await loadIncomeSummary()
            incomeYTD = summary.receivedYTD
            if let next = summary.nextDividend {
                dividendItem = .dividend(symbol: next.symbol, amount: next.estimatedAmount, date: next.estimatedExDate)
            } else {
                dividendItem = nil
            }
        } catch is CancellationError {
            return
        } catch {
            incomeYTD = Money(amount: 0)
            dividendItem = nil
        }
    }

    // MARK: - Next earnings among owned+watched

    private func refreshEarnings() async {
        do {
            guard let event = try await fetchNextEarnings() else {
                earningsItem = nil
                return
            }
            earningsItem = .earnings(symbol: event.symbol, session: event.session, date: Self.date(fromDay: event.day))
        } catch is CancellationError {
            return
        } catch {
            earningsItem = nil
        }
    }

    private static var dayParser: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    private static func date(fromDay day: String) -> Date {
        dayParser.date(from: day) ?? Date()
    }

    // MARK: - Screener freshness (sync — same store + calendar `ScreenerViewModel` reads)

    private func refreshScreener() {
        guard let snapshot = loadScreenerSnapshot(),
              snapshot.tradingDay == calendar.tradingDay(of: now()) else {
            screenerItem = nil
            return
        }
        // No "last selected screen" is persisted anywhere (`ScreenerViewModel`'s active
        // `selection` lives only in that VM's in-memory state) — Home falls back to the
        // first preset, matching `ScreenerViewModel`'s own default selection.
        guard let preset = PresetScreen.allCases.first else {
            screenerItem = nil
            return
        }
        let matches = ScreenSelection.preset(preset).evaluate(snapshot.rows).count
        screenerItem = .screenerFresh(name: preset.rawValue, matches: matches)
    }

    // MARK: - Alerts (sync — same store `WatchlistViewModel` loads from)

    private func refreshAlerts() {
        alertCount = loadAlerts().count
    }
}
