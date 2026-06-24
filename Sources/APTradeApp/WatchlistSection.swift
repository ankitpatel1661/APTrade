import APTradeDomain

/// A watchlist grouped by asset kind, used to render Stocks / ETFs / Crypto
/// as separate sections. The kind drives both the grouping and the heading,
/// so a newly added asset automatically lands in the correct section.
struct WatchlistSection: Identifiable, Equatable {
    let kind: AssetKind
    let rows: [RowState]

    var id: AssetKind { kind }

    var title: String {
        switch kind {
        case .stock: return "Stocks"
        case .etf: return "ETFs"
        case .crypto: return "Crypto"
        }
    }
}
