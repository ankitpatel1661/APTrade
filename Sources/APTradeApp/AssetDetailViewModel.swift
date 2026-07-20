import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class AssetDetailViewModel {
    enum LoadState: Equatable { case idle, loading, loaded, failed }

    /// Dividend snapshot shown on the asset detail screen. `nil` hides the section
    /// entirely — non-payers, crypto, and degraded fetches all present the same way.
    struct DividendInfo: Equatable {
        let trailingAnnualRate: Money       // per share
        let yieldFraction: Double           // trailingAnnualRate / current price
        let nextEstimatedExDate: Date?
        let recentAmounts: [Money]          // last 8 events, oldest first (mini chart)
    }

    let asset: Asset
    private let fetchCandles: FetchCandlesUseCase
    private let fetchQuotes: FetchQuotesUseCase
    private let fetchPortfolio: FetchPortfolioUseCase
    /// nil when no Finnhub key is configured — the next-earnings stat then shows "—"
    /// rather than erroring (see `CompositionRoot.makeDetailViewModel`).
    private let fetchEarnings: FetchEarningsCalendarUseCase?
    private let dividendEventsRepository: DividendEventsRepository
    private let calendar: MarketCalendar
    private let now: () -> Date

    private(set) var quote: Quote?
    private(set) var candles: [Candle] = []
    /// Closing prices, derived from `candles`, driving line/area charts and indicators.
    private(set) var points: [PricePoint] = []
    var timeframe: Timeframe = .oneDay
    private(set) var loadState: LoadState = .idle
    private(set) var isLive = false
    private(set) var position: Position?
    /// Earliest upcoming (or just-reported) earnings release for this asset in the next
    /// 30 days, or nil when keyless, none scheduled, or the fetch degrades to empty.
    private(set) var nextEarnings: EarningsEvent?
    /// nil hides the Dividends card — crypto assets (never fetched), non-payers (zero
    /// events in the trailing 2 years), and fetch failures all degrade here identically;
    /// see `loadDividendInfo` for the full semantics.
    private(set) var dividendInfo: DividendInfo?

    init(asset: Asset,
         fetchCandles: FetchCandlesUseCase,
         fetchQuotes: FetchQuotesUseCase,
         fetchPortfolio: FetchPortfolioUseCase,
         fetchEarnings: FetchEarningsCalendarUseCase? = nil,
         dividendEventsRepository: DividendEventsRepository,
         calendar: MarketCalendar = MarketCalendar(),
         now: @escaping () -> Date = Date.init) {
        self.asset = asset
        self.fetchCandles = fetchCandles
        self.fetchQuotes = fetchQuotes
        self.fetchPortfolio = fetchPortfolio
        self.fetchEarnings = fetchEarnings
        self.dividendEventsRepository = dividendEventsRepository
        self.calendar = calendar
        self.now = now
    }

    private func apply(_ bars: [Candle]) {
        candles = bars
        points = bars.map { $0.pricePoint }
    }

    /// Closing prices as plain Doubles, for indicator math.
    var closes: [Double] { points.map { ($0.close.amount as NSDecimalNumber).doubleValue } }

    /// Re-reads whether this asset is currently held (after a trade or on appear).
    func reloadPosition() {
        position = fetchPortfolio().position(for: asset.symbol)
    }

    func load() async {
        reloadPosition()
        loadState = .loading
        let quotes = await fetchQuotes(symbols: [asset.symbol])
        if case .success(let q) = quotes[asset.symbol] { quote = q }
        do {
            apply(try await fetchCandles(symbol: asset.symbol, timeframe: timeframe))
            loadState = .loaded
        } catch {
            loadState = .failed
        }
        await loadNextEarnings()
        await loadDividendInfo()
    }

    /// A 30-day window from "today" in market-local terms — same shape as the Kotlin
    /// twin's detail VM. `FetchEarningsCalendarUseCase.nextEarnings` already degrades
    /// non-cancellation failures to nil internally, so only cooperative cancellation
    /// needs handling here, and it leaves `nextEarnings` untouched rather than
    /// masquerading a cancelled fetch as "no earnings scheduled".
    private func loadNextEarnings() async {
        guard let fetchEarnings else { return }
        let start = now()
        let today = calendar.tradingDay(of: start)
        let toDay = calendar.tradingDay(of: start.addingTimeInterval(30 * 86_400))
        do {
            nextEarnings = try await fetchEarnings.nextEarnings(symbol: asset.symbol, fromDay: today, toDay: toDay)
        } catch is CancellationError {
            return
        } catch {
            // Unreachable: `nextEarnings` only ever throws `CancellationError`.
        }
    }

    /// Fetches dividend events over a trailing-2-year window and derives `dividendInfo`.
    /// Crypto is skipped WITHOUT fetching — dividends don't apply to it. Zero events in
    /// the window (non-payer) and any fetch failure (including cancellation) both degrade
    /// to nil identically: this stat is never worth surfacing as an error state. The yield
    /// fraction guards against a zero/missing current price by degrading to 0 rather than
    /// dividing by zero — same choice `IncomeViewModel` makes for its yield fields.
    private func loadDividendInfo() async {
        guard asset.kind != .crypto else { dividendInfo = nil; return }
        let asOf = now()
        let windowStart = asOf.addingTimeInterval(-730 * 86_400)
        do {
            let events = try await dividendEventsRepository.dividendEvents(for: asset.symbol, since: windowStart)
            guard !events.isEmpty else { dividendInfo = nil; return }

            let rate = DividendMath.trailingAnnualPerShare(events: events, asOf: asOf)
            let price = quote?.price.amount ?? 0
            let yieldFraction = price > 0 ? (rate.amount / price as NSDecimalNumber).doubleValue : 0
            let recentAmounts = events.sorted { $0.exDate < $1.exDate }.suffix(8).map(\.amountPerShare)
            // Future-only guard, mirroring `IncomeViewModel.buildUpcoming`: a projection
            // that lands before "now" (e.g. a payer whose last event in the window is old
            // enough that lastEvent.exDate + cadenceInterval < now) must not surface a past
            // date under the "Est." badge. The row simply hides — the rest of DividendInfo
            // (rate/yield/recentAmounts) still shows since nextEstimatedExDate is Optional.
            let projected = DividendMath.nextProjected(events: events)
            let nextEstimatedExDate = (projected?.exDate).flatMap { $0 > asOf ? $0 : nil }

            dividendInfo = DividendInfo(
                trailingAnnualRate: rate,
                yieldFraction: yieldFraction,
                nextEstimatedExDate: nextEstimatedExDate,
                recentAmounts: Array(recentAmounts)
            )
        } catch {
            dividendInfo = nil
        }
    }

    /// Polls the live quote (and, on the intraday timeframe, the chart itself) every 15s
    /// until the surrounding task is cancelled on disappear.
    func runLiveUpdates() async {
        isLive = true
        defer { isLive = false }
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(15))
            if Task.isCancelled { break }
            let quotes = await fetchQuotes(symbols: [asset.symbol])
            if case .success(let q) = quotes[asset.symbol] { quote = q }
            if timeframe == .oneDay, let fresh = try? await fetchCandles(symbol: asset.symbol, timeframe: .oneDay) {
                apply(fresh)
            }
        }
    }

    /// Change over the currently selected timeframe's window, derived from the chart's
    /// own points rather than the quote's always-intraday `changePercent` — so the
    /// badge and chart color reflect 1W/1M/1Y performance, not just today's move.
    var periodChange: Money? {
        guard let first = points.first?.close, let last = points.last?.close else { return nil }
        return last - first
    }

    var periodChangePercent: Percentage? {
        guard let first = points.first?.close, let last = points.last?.close, first.amount != 0 else { return nil }
        let percent = (last.amount - first.amount) / first.amount * 100
        return Percentage(value: percent)
    }

    func select(_ timeframe: Timeframe) async {
        self.timeframe = timeframe
        loadState = .loading
        do {
            apply(try await fetchCandles(symbol: asset.symbol, timeframe: timeframe))
            loadState = .loaded
        } catch {
            loadState = .failed
        }
    }
}
