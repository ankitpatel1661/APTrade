import SwiftUI
import APTradeDomain

struct WatchlistView: View {
    @State private var viewModel = CompositionRoot.makeWatchlistViewModel()
    @State private var newSymbol = ""

    var body: some View {
        NavigationStack {
            List {
                ForEach(viewModel.rows) { row in
                    NavigationLink(value: row.asset) {
                        WatchlistRow(row: row)
                    }
                }
                .onDelete { indexSet in
                    for index in indexSet {
                        viewModel.remove(symbol: viewModel.rows[index].asset.symbol)
                    }
                }
            }
            .navigationTitle("Watchlist")
            .navigationDestination(for: Asset.self) { asset in
                AssetDetailView(asset: asset)
            }
            .safeAreaInset(edge: .bottom) { addBar }
            .task { await viewModel.onAppear() }
            .refreshable { await viewModel.refresh() }
        }
        .frame(minWidth: 420, minHeight: 520)
        .preferredColorScheme(.dark)
    }

    private var addBar: some View {
        VStack(spacing: 4) {
            if let error = viewModel.addError {
                Text(error).font(.caption).foregroundStyle(.red)
            }
            HStack {
                TextField("Add symbol (e.g. NVDA, SOL-USD)", text: $newSymbol)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { submit() }
                Button("Add") { submit() }
                    .disabled(newSymbol.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding()
        .background(.thinMaterial)
    }

    private func submit() {
        let query = newSymbol
        newSymbol = ""
        Task { await viewModel.add(query: query) }
    }
}

private struct WatchlistRow: View {
    let row: RowState

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(row.asset.symbol).font(.headline)
                Text(row.asset.name).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let quote = row.quote {
                VStack(alignment: .trailing) {
                    Text(quote.price.formatted).font(.headline.monospacedDigit())
                    Text(quote.changePercent.formatted)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Theme.changeColor(quote.changePercent))
                }
            } else if row.failed {
                Text("—").foregroundStyle(.secondary)
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .padding(.vertical, 4)
    }
}
