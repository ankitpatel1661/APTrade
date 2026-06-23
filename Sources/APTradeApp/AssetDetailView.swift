import SwiftUI
import Charts
import APTradeDomain

struct AssetDetailView: View {
    @State private var viewModel: AssetDetailViewModel

    init(asset: Asset) {
        _viewModel = State(initialValue: CompositionRoot.makeDetailViewModel(for: asset))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            chart
            timeframePicker
            Spacer()
        }
        .padding()
        .navigationTitle(viewModel.asset.symbol)
        .task { await viewModel.load() }
        .frame(minWidth: 480, minHeight: 420)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(viewModel.asset.name).font(.title2).bold()
            if let quote = viewModel.quote {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(quote.price.formatted).font(.largeTitle.monospacedDigit())
                    Text(quote.changePercent.formatted)
                        .font(.title3.monospacedDigit())
                        .foregroundStyle(Theme.changeColor(quote.changePercent))
                }
            }
        }
    }

    @ViewBuilder
    private var chart: some View {
        switch viewModel.loadState {
        case .loading, .idle:
            ProgressView().frame(maxWidth: .infinity, minHeight: 220)
        case .failed:
            ContentUnavailableView("Couldn't load chart", systemImage: "chart.line.downtrend.xyaxis")
                .frame(minHeight: 220)
        case .loaded:
            Chart(viewModel.points, id: \.date) { point in
                LineMark(
                    x: .value("Date", point.date),
                    y: .value("Price", (point.close.amount as NSDecimalNumber).doubleValue)
                )
                .interpolationMethod(.catmullRom)
            }
            .frame(minHeight: 220)
        }
    }

    private var timeframePicker: some View {
        Picker("Timeframe", selection: Binding(
            get: { viewModel.timeframe },
            set: { tf in Task { await viewModel.select(tf) } }
        )) {
            ForEach(Timeframe.allCases, id: \.self) { tf in
                Text(tf.displayName).tag(tf)
            }
        }
        .pickerStyle(.segmented)
    }
}
