import APTradeDomain

struct RowState: Identifiable, Equatable {
    let asset: Asset
    var quote: Quote?
    var failed: Bool = false
    var id: String { asset.symbol }
}
