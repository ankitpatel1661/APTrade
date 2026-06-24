import SwiftUI
import Charts
import APTradeDomain

struct AssetDetailView: View {
    @State private var viewModel: AssetDetailViewModel
    @State private var tradeSide: TradeSide?

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
                    tradeButtons
                    chart
                    TimeframeBar(selection: viewModel.timeframe) { tf in
                        Task { await viewModel.select(tf) }
                    }
                    keyStats
                    positionPanel
                }
                .padding(24)
            }
        }
        .navigationTitle(viewModel.asset.symbol)
        .task {
            await viewModel.load()
            await viewModel.runLiveUpdates()
        }
        .sheet(item: $tradeSide) { side in
            TradeSheet(asset: viewModel.asset, side: side) {
                viewModel.reloadPosition()
            }
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

    // MARK: Trade buttons

    private var tradeButtons: some View {
        HStack(spacing: 12) {
            tradeButton(title: "Buy", side: .buy, filled: true)
            tradeButton(title: "Sell", side: .sell, filled: false)
        }
    }

    private func tradeButton(title: String, side: TradeSide, filled: Bool) -> some View {
        Button { tradeSide = side } label: {
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(filled ? Theme.bgBottom : Theme.gold)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    AnyShapeStyle(filled ? AnyShapeStyle(Theme.goldGradient) : AnyShapeStyle(Color.clear)),
                    in: Capsule()
                )
                .overlay(Capsule().stroke(Theme.gold.opacity(filled ? 0 : 0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(side == .sell && (viewModel.position == nil))
    }

    // MARK: Position panel

    @ViewBuilder
    private var positionPanel: some View {
        if let position = viewModel.position, let quote = viewModel.quote {
            VStack(alignment: .leading, spacing: 16) {
                Text("YOUR POSITION")
                    .font(.system(size: 11, weight: .bold)).tracking(1.8)
                    .foregroundStyle(Theme.textSecondary)
                let columns = [GridItem(.flexible(), spacing: 24), GridItem(.flexible(), spacing: 24)]
                LazyVGrid(columns: columns, alignment: .leading, spacing: 18) {
                    StatTile(label: "Shares", value: position.quantity.formatted)
                    StatTile(label: "Average cost", value: position.averageCost.formatted)
                    StatTile(label: "Market value", value: position.marketValue(at: quote.price).formatted)
                    StatTile(label: "Unrealized P&L",
                             value: signed(position.unrealizedPnL(at: quote.price)),
                             valueColor: pnlColor(position.unrealizedPnL(at: quote.price)))
                }
            }
            .padding(20)
            .background(Theme.surface.opacity(0.5), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
        }
    }

    /// Green for a gain, red for a loss — P&L direction is data, not branding.
    private func pnlColor(_ money: Money) -> Color {
        if money.amount > 0 { return Theme.up }
        if money.amount < 0 { return Theme.down }
        return Theme.textPrimary
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
