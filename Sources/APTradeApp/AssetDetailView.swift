import SwiftUI
import Charts
import APTradeDomain

struct AssetDetailView: View {
    @State private var viewModel: AssetDetailViewModel
    @State private var tradeSide: TradeSide?
    @State private var hoverPoint: PricePoint?

    init(asset: Asset) {
        _viewModel = State(initialValue: CompositionRoot.makeDetailViewModel(for: asset))
    }

    /// Colors the badge/chart by the selected timeframe's own move (points-derived),
    /// not always the quote's intraday day-change — a stock down today but up over 1W
    /// should read green once 1W is selected.
    private var directionColor: Color {
        Theme.changeColor(viewModel.periodChangePercent)
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
                HStack(alignment: .bottom, spacing: 14) {
                    SuperscriptPrice(money: quote.price, size: 44, weight: .semibold)
                    VStack(alignment: .leading, spacing: 2) {
                        ChangePill(percent: viewModel.periodChangePercent ?? quote.changePercent)
                        if let periodChange = viewModel.periodChange {
                            Text(signed(periodChange))
                                .font(.system(size: 13, weight: .medium).monospacedDigit())
                                .foregroundStyle(directionColor)
                        }
                    }
                }
            }
        }
    }

    // MARK: Chart

    /// Pads the chart's Y range to the data's own min/max so the line reads with
    /// visible movement, instead of Charts' default AreaMark baseline of 0.
    private var yDomain: ClosedRange<Double> {
        let values = viewModel.points.map { ($0.close.amount as NSDecimalNumber).doubleValue }
        guard let lo = values.min(), let hi = values.max(), hi > lo else {
            return 0...1
        }
        let padding = (hi - lo) * 0.12
        return (lo - padding)...(hi + padding)
    }

    private var xAxisFormat: Date.FormatStyle {
        switch viewModel.timeframe {
        case .oneDay: return .dateTime.hour().minute()
        case .oneWeek: return .dateTime.weekday(.abbreviated).day()
        case .oneMonth: return .dateTime.month(.abbreviated).day()
        case .oneYear: return .dateTime.month(.abbreviated).year(.twoDigits)
        }
    }

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

                if let hoverPoint, hoverPoint.date == point.date {
                    RuleMark(x: .value("Date", hoverPoint.date))
                        .foregroundStyle(Theme.hairline)
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    PointMark(x: .value("Date", hoverPoint.date), y: .value("Price", value))
                        .foregroundStyle(directionColor)
                        .symbolSize(60)
                }
            }
            .chartYScale(domain: yDomain)
            .chartYAxis {
                AxisMarks(position: .trailing, values: .automatic(desiredCount: 4)) { value in
                    AxisGridLine().foregroundStyle(Theme.hairline)
                    AxisValueLabel().foregroundStyle(Theme.textTertiary)
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                    AxisValueLabel(format: xAxisFormat).foregroundStyle(Theme.textTertiary)
                }
            }
            .chartOverlay { proxy in
                GeometryReader { geometry in
                    ZStack(alignment: .topLeading) {
                        Rectangle()
                            .fill(Color.clear)
                            .contentShape(Rectangle())
                            .onContinuousHover { phase in
                                switch phase {
                                case .active(let location):
                                    updateHover(at: location, proxy: proxy, geometry: geometry)
                                case .ended:
                                    hoverPoint = nil
                                }
                            }
                        if let hoverPoint, let plotFrame = proxy.plotFrame {
                            let frame = geometry[plotFrame]
                            let value = (hoverPoint.close.amount as NSDecimalNumber).doubleValue
                            if let x = proxy.position(forX: hoverPoint.date),
                               let y = proxy.position(forY: value) {
                                let tooltipWidth: CGFloat = 110
                                let clampedX = min(
                                    max(frame.origin.x + x, tooltipWidth / 2),
                                    geometry.size.width - tooltipWidth / 2
                                )
                                let tooltipY = max(frame.origin.y + y - 44, 0)
                                hoverTooltip(for: hoverPoint)
                                    .frame(width: tooltipWidth)
                                    .position(x: clampedX, y: tooltipY)
                            }
                        }
                    }
                }
            }
            .frame(minHeight: 260)
        }
    }

    private func updateHover(at location: CGPoint, proxy: ChartProxy, geometry: GeometryProxy) {
        let origin = geometry[proxy.plotFrame!].origin
        let relativeX = location.x - origin.x
        guard let date: Date = proxy.value(atX: relativeX) else { return }
        hoverPoint = viewModel.points.min { lhs, rhs in
            abs(lhs.date.timeIntervalSince(date)) < abs(rhs.date.timeIntervalSince(date))
        }
    }

    private func hoverTooltip(for point: PricePoint) -> some View {
        VStack(spacing: 2) {
            Text(point.close.formatted)
                .font(.system(size: 13, weight: .bold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
            Text(point.date.formatted(.dateTime.month().day().hour().minute()))
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
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
                             valueColor: Theme.changeColor(quote.changePercent))
                    StatTile(label: "Day change %",
                             value: quote.changePercent.formatted,
                             valueColor: Theme.changeColor(quote.changePercent))
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
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
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
