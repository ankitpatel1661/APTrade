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
    private let search: SearchSymbolUseCase

    private(set) var rows: [RowState] = []
    var isRefreshing = false
    var addError: String?

    init(load: LoadWatchlistUseCase,
         add: AddToWatchlistUseCase,
         remove: RemoveFromWatchlistUseCase,
         fetchQuotes: FetchQuotesUseCase,
         search: SearchSymbolUseCase) {
        self.load = load
        self.add = add
        self.remove = remove
        self.fetchQuotes = fetchQuotes
        self.search = search
    }

    private func reloadRows() {
        let existing = Dictionary(uniqueKeysWithValues: rows.map { ($0.asset.symbol, $0.quote) })
        rows = load().map { RowState(asset: $0, quote: existing[$0.symbol] ?? nil) }
    }

    func onAppear() async {
        reloadRows()
        await refresh()
    }

    func refresh() async {
        guard !rows.isEmpty else { return }
        isRefreshing = true
        defer { isRefreshing = false }
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
    }

    func add(query: String) async {
        addError = nil
        do {
            let asset = try await search(query: query)
            _ = add(asset)
            reloadRows()
            await refresh()
        } catch {
            addError = "Couldn't find \"\(query.trimmingCharacters(in: .whitespaces))\""
        }
    }

    func remove(symbol: String) {
        _ = remove(symbol: symbol)
        reloadRows()
    }
}
