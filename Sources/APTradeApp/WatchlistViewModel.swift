import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class WatchlistViewModel {
    private let load: LoadWatchlistUseCase
    private let add: AddToWatchlistUseCase
    private let remove: RemoveFromWatchlistUseCase
    private let fetchQuotes: FetchQuotesUseCase
    private let fetchHistory: FetchHistoryUseCase
    private let search: SearchSymbolUseCase
    private let searchAssets: SearchAssetsUseCase
    private let loadAlerts: LoadAlertsUseCase
    private let createAlert: CreatePriceAlertUseCase
    private let removeAlert: RemovePriceAlertUseCase
    private let evaluateAlerts: EvaluateAlertsUseCase
    private(set) var suggestions: [Asset] = []
    private(set) var alerts: [PriceAlert] = []
    private var searchTask: Task<Void, Never>?

    private(set) var rows: [RowState] = []
    var isRefreshing = false
    var addError: String?
    /// True while the live polling loop is running, so the view can show its LIVE badge.
    private(set) var isLive = false

    /// Which category the watchlist is currently showing. The view's toggle drives this;
    /// only rows of this kind are displayed at a time.
    var selectedKind: AssetKind = .stock

    /// Fixed display order for the grouped watchlist.
    private static let sectionOrder: [AssetKind] = [.stock, .etf, .crypto]

    /// Rows grouped by asset kind, in `sectionOrder`, omitting empty groups.
    var sections: [WatchlistSection] {
        Self.sectionOrder.compactMap { kind in
            let kindRows = rows.filter { $0.asset.kind == kind }
            return kindRows.isEmpty ? nil : WatchlistSection(kind: kind, rows: kindRows)
        }
    }

    /// Rows belonging to the currently selected category.
    var visibleRows: [RowState] {
        rows.filter { $0.asset.kind == selectedKind }
    }

    /// Per-kind row counts, for the toggle's badges.
    var counts: [AssetKind: Int] {
        Dictionary(grouping: rows, by: { $0.asset.kind }).mapValues(\.count)
    }

    /// Names in the visible category that are up on the day.
    var advancers: Int {
        visibleRows.filter { $0.quote?.changePercent.isPositive == true }.count
    }

    /// Names in the visible category that are down on the day.
    var decliners: Int {
        visibleRows.filter { $0.quote?.changePercent.isNegative == true }.count
    }

    /// Mean day change across quoted names in the visible category; `nil` until a quote
    /// is available. Used as the header's headline figure.
    var averageChange: Percentage? {
        let values = visibleRows.compactMap { $0.quote?.changePercent.value }
        guard !values.isEmpty else { return nil }
        let sum = values.reduce(Decimal(0), +)
        return Percentage(value: sum / Decimal(values.count))
    }

    /// Mean intraday trace across the visible category, expressed as % change from
    /// each row's own opening tick, so names of any price level average evenly.
    var averageSpark: [Double] {
        let series = visibleRows.compactMap { row -> [Double]? in
            guard let first = row.spark.first, first != 0 else { return nil }
            return row.spark.map { ($0 - first) / first * 100 }
        }
        guard let count = series.map(\.count).min(), count > 1 else { return [] }
        return (0..<count).map { index in
            series.reduce(0.0) { $0 + $1[index] } / Double(series.count)
        }
    }

    init(load: LoadWatchlistUseCase,
         add: AddToWatchlistUseCase,
         remove: RemoveFromWatchlistUseCase,
         fetchQuotes: FetchQuotesUseCase,
         fetchHistory: FetchHistoryUseCase,
         search: SearchSymbolUseCase,
         searchAssets: SearchAssetsUseCase,
         loadAlerts: LoadAlertsUseCase,
         createAlert: CreatePriceAlertUseCase,
         removeAlert: RemovePriceAlertUseCase,
         evaluateAlerts: EvaluateAlertsUseCase) {
        self.load = load
        self.add = add
        self.remove = remove
        self.fetchQuotes = fetchQuotes
        self.fetchHistory = fetchHistory
        self.search = search
        self.searchAssets = searchAssets
        self.loadAlerts = loadAlerts
        self.createAlert = createAlert
        self.removeAlert = removeAlert
        self.evaluateAlerts = evaluateAlerts
        self.alerts = loadAlerts()
    }

    /// Active (untriggered) alerts set on `symbol`, for the row's alert badge.
    func alerts(for symbol: String) -> [PriceAlert] {
        alerts.filter { $0.symbol == symbol }
    }

    func addAlert(symbol: String, condition: AlertCondition) {
        alerts = createAlert(symbol: symbol, condition: condition)
    }

    func deleteAlert(_ id: PriceAlert.ID) {
        alerts = removeAlert(id: id)
    }

    private func reloadRows() {
        let quotes = Dictionary(uniqueKeysWithValues: rows.map { ($0.asset.symbol, $0.quote) })
        let sparks = Dictionary(uniqueKeysWithValues: rows.map { ($0.asset.symbol, $0.spark) })
        rows = load().map {
            RowState(asset: $0, quote: quotes[$0.symbol] ?? nil, spark: sparks[$0.symbol] ?? [])
        }
    }

    func onAppear() async {
        reloadRows()
        selectFirstPopulatedKind()
        await refresh()
        await loadSparklines()
    }

    /// Keeps the toggle pointed at a category that actually has rows, so the watchlist
    /// never opens on an empty tab when other categories are populated.
    private func selectFirstPopulatedKind() {
        guard visibleRows.isEmpty,
              let firstPopulated = Self.sectionOrder.first(where: { kind in
                  rows.contains { $0.asset.kind == kind }
              }) else { return }
        selectedKind = firstPopulated
    }

    /// Polls quotes on a fixed cadence until the surrounding task is cancelled (which
    /// SwiftUI does when the view disappears). Quotes refresh every tick; the heavier
    /// intraday sparklines refresh every fourth tick. The cache's 15s TTL means each
    /// tick yields genuinely fresh prices rather than a cached repeat.
    func runLiveUpdates() async {
        guard !rows.isEmpty else { return }
        isLive = true
        defer { isLive = false }
        var tick = 0
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(15))
            if Task.isCancelled { break }
            await refresh(showIndicator: false)
            tick += 1
            if tick % 4 == 0 { await loadSparklines() }
        }
    }

    func refresh(showIndicator: Bool = true) async {
        guard !rows.isEmpty else { return }
        if showIndicator { isRefreshing = true }
        defer { if showIndicator { isRefreshing = false } }
        let results = await fetchQuotes(symbols: rows.map { $0.asset.symbol })
        rows = rows.map { row in
            var copy = row
            switch results[row.asset.symbol] {
            case .success(let q): copy.quote = q; copy.failed = false
            case .failure: copy.failed = true
            case .none: break
            }
            return copy
        }
        let quotes = Dictionary(uniqueKeysWithValues: rows.compactMap { row in row.quote.map { (row.asset.symbol, $0) } })
        alerts = await evaluateAlerts(quotes: quotes)
    }

    /// Loads each row's intraday sparkline concurrently. Best-effort: a symbol whose
    /// history fails simply renders without a sparkline rather than failing the row.
    func loadSparklines() async {
        let symbols = rows.map { $0.asset.symbol }
        guard !symbols.isEmpty else { return }
        let fetch = fetchHistory
        let results = await withTaskGroup(of: (String, [Double]).self) { group in
            for symbol in symbols {
                group.addTask {
                    let points = (try? await fetch(symbol: symbol, timeframe: .oneDay)) ?? []
                    return (symbol, points.map { ($0.close.amount as NSDecimalNumber).doubleValue })
                }
            }
            var out: [String: [Double]] = [:]
            for await (symbol, values) in group { out[symbol] = values }
            return out
        }
        rows = rows.map { row in
            var copy = row
            if let spark = results[row.asset.symbol] { copy.spark = spark }
            return copy
        }
    }

    func add(query: String) async {
        addError = nil
        do {
            let asset = try await search(query: query)
            _ = add(asset)
            reloadRows()
            await refresh()
            await loadSparklines()
        } catch {
            addError = String(format: tr(.couldntFindSymbolFormat), query.trimmingCharacters(in: .whitespaces))
        }
    }

    func remove(symbol: String) {
        _ = remove(symbol: symbol)
        reloadRows()
    }

    /// Debounced autocomplete. Cancels any in-flight search and, after a 250ms pause,
    /// fetches ranked matches for the current query. Best-effort: errors clear results.
    func updateQuery(_ text: String) {
        searchTask?.cancel()
        let query = text.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { suggestions = []; return }
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self else { return }
            let results = (try? await self.searchAssets(query: query)) ?? []
            if !Task.isCancelled { self.suggestions = results }
        }
    }

    /// Adds a chosen suggestion to the watchlist and clears the dropdown.
    func addSuggestion(_ asset: Asset) async {
        clearSuggestions()
        _ = add(asset)
        reloadRows()
        selectedKind = asset.kind
        await refresh()
        await loadSparklines()
    }

    func clearSuggestions() {
        searchTask?.cancel()
        suggestions = []
    }
}
