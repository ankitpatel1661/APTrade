import APTradeDomain

struct RowState: Identifiable, Equatable {
    let asset: Asset
    var quote: Quote?
    var failed: Bool = false
    /// Intraday closing prices (today, 5-min cadence) powering the row's sparkline.
    var spark: [Double] = []
    var id: String { asset.symbol }
}
