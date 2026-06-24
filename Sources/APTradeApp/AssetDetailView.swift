import SwiftUI
import Charts
import APTradeDomain

struct AssetDetailView: View {
    @State private var viewModel: AssetDetailViewModel

    init(asset: Asset) {
        _viewModel = State(initialValue: CompositionRoot.makeDetailViewModel(for: asset))
    }

    private var directionColor: Color {
        Theme.changeColor(viewModel.quote?.changePercent)
    }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    header
                    chart
                    TimeframeBar(selection: viewModel.timeframe) { tf in
                        Task { await viewModel.select(tf) }
                    }
                    keyStats
                }
                .padding(24)
            }
        }
        .navigationTitle(viewModel.asset.symbol)
        .task {
            await viewModel.load()
            await viewModel.runLiveUpdates()
        }
        .frame(minWidth: 560, minHeight: 560)
    }

    // MARK: Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(viewModel.asset.symbol.uppercased())
                        .font(.system(size: 11, weight: .bold))
                        .tracking(2.0)
                        .foregroundStyle(Theme.gold)
                    Text(viewModel.asset.name)
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                }
                Spacer()
                if viewModel.isLive { LiveBadge() }
            }
            if let quote = viewModel.quote {
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    SuperscriptPrice(money: quote.price, size: 44, weight: .semibold)
                    VStack(alignment: .leading, spacing: 2) {
                        ChangePill(percent: quote.changePercent)
                        Text(signed(quote.change))
                            .font(.system(size: 13, weight: .medium).monospacedDigit())
                            .foregroundStyle(directionColor)
                    }
                }
            }
        }
    }

    // MARK: Chart

    @ViewBuilder
    private var chart: some View {
        switch viewModel.loadState {
        case .loading, .idle:
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: 260)
        case .failed:
            ContentUnavailableView("Couldn't load chart", systemImage: "chart.line.downtrend.xyaxis")
                .frame(minHeight: 260)
        case .loaded:
            Chart(viewModel.points, id: \.date) { point in
                let value = (point.close.amount as NSDecimalNumber).doubleValue
                AreaMark(x: .value("Date", point.date), y: .value("Price", value))
                    .interpolationMethod(.catmullRom)
                    .foregroundStyle(
                        .linearGradient(
                            colors: [directionColor.opacity(0.26), directionColor.opacity(0.0)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )
                LineMark(x: .value("Date", point.date), y: .value("Price", value))
                    .interpolationMethod(.catmullRom)
                    .foregroundStyle(directionColor)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            }
            .chartYAxis {
                AxisMarks(position: .trailing, values: .automatic(desiredCount: 4)) { value in
                    AxisGridLine().foregroundStyle(Theme.hairline)
                    AxisValueLabel().foregroundStyle(Theme.textTertiary)
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                    AxisValueLabel().foregroundStyle(Theme.textTertiary)
                }
            }
            .frame(minHeight: 260)
        }
    }

    // MARK: Key stats

    @ViewBuilder
    private var keyStats: some View {
        if let quote = viewModel.quote {
            VStack(alignment: .leading, spacing: 16) {
                Text("KEY STATS")
                    .font(.system(size: 11, weight: .bold))
                    .tracking(1.8)
                    .foregroundStyle(Theme.textSecondary)

                let columns = [GridItem(.flexible(), spacing: 24), GridItem(.flexible(), spacing: 24)]
                LazyVGrid(columns: columns, alignment: .leading, spacing: 18) {
                    StatTile(label: "Last", value: quote.price.formatted)
                    StatTile(label: "Previous close", value: quote.previousClose.formatted)
                    StatTile(label: "Day change",
                             value: signed(quote.change),
                             valueColor: directionColor)
                    StatTile(label: "Day change %",
                             value: quote.changePercent.formatted,
                             valueColor: directionColor)
                    StatTile(label: "Symbol", value: quote.symbol)
                    StatTile(label: "Type", value: typeLabel)
                }
            }
            .padding(20)
            .background(Theme.surface.opacity(0.5), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
        }
    }

    private var typeLabel: String {
        switch viewModel.asset.kind {
        case .stock: return "Stock"
        case .etf: return "ETF"
        case .crypto: return "Crypto"
        }
    }

    /// Money with an explicit + or - sign, for change figures.
    private func signed(_ money: Money) -> String {
        let sign = money.amount > 0 ? "+" : ""
        return sign + money.formatted
    }
}
