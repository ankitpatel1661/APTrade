import SwiftUI
import Charts
import APTradeDomain

struct AssetDetailView: View {
    enum ChartStyle: String, CaseIterable { case area = "Area", candles = "Candles" }
    enum Indicator: String, CaseIterable, Identifiable {
        case sma = "SMA 20", ema = "EMA 12", vwap = "VWAP", bollinger = "BB 20", rsi = "RSI 14", macd = "MACD"
        var id: String { rawValue }
        /// Overlays draw on the price chart; the rest get their own sub-pane.
        var isOverlay: Bool { self == .sma || self == .ema || self == .vwap || self == .bollinger }
    }

    private static let smaPeriod = 20
    private static let emaPeriod = 12
    private static let rsiPeriod = 14
    private static let bollingerPeriod = 20

    @State private var viewModel: AssetDetailViewModel
    @State private var newsVM: AssetNewsViewModel
    @State private var tradeSide: TradeSide?
    @State private var hoverPoint: PricePoint?
    @State private var chartStyle: ChartStyle = .area
    @State private var indicators: Set<Indicator> = []

    init(asset: Asset) {
        _viewModel = State(initialValue: CompositionRoot.makeDetailViewModel(for: asset))
        _newsVM = State(initialValue: CompositionRoot.makeAssetNewsViewModel(for: asset))
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
                    chartControls
                    chart
                    if indicators.contains(.rsi) {
                        rsiPane
                    }
                    if indicators.contains(.macd) {
                        macdPane
                    }
                    TimeframeBar(selection: viewModel.timeframe) { tf in
                        Task { await viewModel.select(tf) }
                    }
                    keyStats
                    positionPanel
                    AssetNewsSection(viewModel: newsVM)
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
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 560)
        #endif
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

    /// Pads the chart's Y range to the data's own min/max (using candle highs/lows so
    /// wicks stay in frame) instead of Charts' default AreaMark baseline of 0.
    private var yDomain: ClosedRange<Double> {
        var lows = viewModel.candles.map { dbl($0.low) }
        var highs = viewModel.candles.map { dbl($0.high) }
        // Keep Bollinger bands in frame when they extend past the price extremes.
        let bands = bollingerSeries
        if !bands.isEmpty {
            lows += bands.map(\.lower)
            highs += bands.map(\.upper)
        }
        guard let lo = lows.min(), let hi = highs.max(), hi > lo else { return 0...1 }
        let padding = (hi - lo) * 0.12
        return (lo - padding)...(hi + padding)
    }

    private func dbl(_ money: Money) -> Double { (money.amount as NSDecimalNumber).doubleValue }

    private var xAxisFormat: Date.FormatStyle {
        switch viewModel.timeframe {
        case .oneDay: return .dateTime.hour().minute()
        case .oneWeek: return .dateTime.weekday(.abbreviated).day()
        case .oneMonth: return .dateTime.month(.abbreviated).day()
        case .oneYear: return .dateTime.month(.abbreviated).year(.twoDigits)
        }
    }

    /// Display label for the chart-style toggle — `ChartStyle` is view-local (not a
    /// domain enum), so this maps it straight to a localized string.
    private func chartStyleLabel(_ style: ChartStyle) -> String {
        switch style {
        case .area: return tr(.chartStyleArea)
        case .candles: return tr(.chartStyleCandles)
        }
    }

    /// Display label for an indicator chip — `Indicator` is view-local (not a domain
    /// enum); the codes themselves (SMA/EMA/VWAP/etc.) are standard finance notation
    /// kept identical across languages, but routed through `tr` for catalog completeness.
    private func indicatorLabel(_ indicator: Indicator) -> String {
        switch indicator {
        case .sma: return tr(.indicatorSMA)
        case .ema: return tr(.indicatorEMA)
        case .vwap: return tr(.indicatorVWAP)
        case .bollinger: return tr(.indicatorBollinger)
        case .rsi: return tr(.indicatorRSI)
        case .macd: return tr(.indicatorMACD)
        }
    }

    /// Chart-style toggle (Area / Candles) plus the indicator chips.
    private var chartControls: some View {
        HStack(spacing: 10) {
            HStack(spacing: 4) {
                ForEach(ChartStyle.allCases, id: \.self) { style in
                    let selected = chartStyle == style
                    Button { withAnimation(.easeInOut(duration: 0.2)) { chartStyle = style } } label: {
                        Text(chartStyleLabel(style))
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(selected ? Theme.bgBottom : Theme.textSecondary)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background { if selected { Capsule().fill(Theme.goldGradient) } }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(3)
            .background(Theme.surface, in: Capsule())
            .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Indicator.allCases) { indicator in
                        indicatorChip(indicator)
                    }
                }
                .padding(.trailing, 2)
            }
        }
    }

    private func indicatorChip(_ indicator: Indicator) -> some View {
        let on = indicators.contains(indicator)
        return Button {
            withAnimation(.easeInOut(duration: 0.2)) {
                if on { indicators.remove(indicator) } else { indicators.insert(indicator) }
            }
        } label: {
            HStack(spacing: 5) {
                Circle().fill(indicatorColor(indicator)).frame(width: 7, height: 7).opacity(on ? 1 : 0.4)
                Text(indicatorLabel(indicator))
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(on ? Theme.textPrimary : Theme.textTertiary)
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(on ? Theme.surfaceHi : Theme.surface, in: Capsule())
            .overlay(Capsule().stroke(on ? indicatorColor(indicator).opacity(0.5) : Theme.hairline, lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var chart: some View {
        switch viewModel.loadState {
        case .loading, .idle:
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: 260)
        case .failed:
            ContentUnavailableView(tr(.couldntLoadChart), systemImage: "chart.line.downtrend.xyaxis")
                .frame(minHeight: 260)
        case .loaded:
            priceChart
        }
    }

    private var priceChart: some View {
        Chart {
            if chartStyle == .candles {
                ForEach(viewModel.candles, id: \.date) { candle in
                    let color = candle.isUp ? Theme.up : Theme.down
                    RuleMark(x: .value("Date", candle.date),
                             yStart: .value("Low", dbl(candle.low)),
                             yEnd: .value("High", dbl(candle.high)))
                        .foregroundStyle(color.opacity(0.7))
                        .lineStyle(StrokeStyle(lineWidth: 1))
                    RectangleMark(x: .value("Date", candle.date),
                                  yStart: .value("Open", dbl(candle.open)),
                                  yEnd: .value("Close", dbl(candle.close)),
                                  width: .fixed(candleWidth))
                        .foregroundStyle(color)
                        .cornerRadius(1)
                }
            } else {
                ForEach(viewModel.points, id: \.date) { point in
                    AreaMark(x: .value("Date", point.date), y: .value("Price", dbl(point.close)))
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(.linearGradient(
                            colors: [directionColor.opacity(ThemeManager.shared.isDark ? 0.26 : 0.12),
                                     directionColor.opacity(0.0)],
                            startPoint: .top, endPoint: .bottom))
                    LineMark(x: .value("Date", point.date), y: .value("Price", dbl(point.close)),
                             series: .value("Series", "Price"))
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(directionColor)
                        .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                }
            }

            ForEach(overlayLine(.sma)) { point in
                LineMark(x: .value("Date", point.date), y: .value("SMA", point.value),
                         series: .value("Series", "SMA"))
                    .foregroundStyle(indicatorColor(.sma))
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
            }
            ForEach(overlayLine(.ema)) { point in
                LineMark(x: .value("Date", point.date), y: .value("EMA", point.value),
                         series: .value("Series", "EMA"))
                    .foregroundStyle(indicatorColor(.ema))
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
            }

            ForEach(overlayLine(.vwap)) { point in
                LineMark(x: .value("Date", point.date), y: .value("VWAP", point.value),
                         series: .value("Series", "VWAP"))
                    .foregroundStyle(indicatorColor(.vwap))
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [5, 3]))
            }

            ForEach(bollingerSeries) { band in
                AreaMark(x: .value("Date", band.date),
                         yStart: .value("Lower", band.lower), yEnd: .value("Upper", band.upper))
                    .foregroundStyle(indicatorColor(.bollinger).opacity(0.07))
            }
            ForEach(bollingerSeries) { band in
                LineMark(x: .value("Date", band.date), y: .value("BBUpper", band.upper),
                         series: .value("Series", "BBUpper"))
                    .foregroundStyle(indicatorColor(.bollinger)).lineStyle(StrokeStyle(lineWidth: 1))
            }
            ForEach(bollingerSeries) { band in
                LineMark(x: .value("Date", band.date), y: .value("BBLower", band.lower),
                         series: .value("Series", "BBLower"))
                    .foregroundStyle(indicatorColor(.bollinger)).lineStyle(StrokeStyle(lineWidth: 1))
            }
            ForEach(bollingerSeries) { band in
                LineMark(x: .value("Date", band.date), y: .value("BBMid", band.middle),
                         series: .value("Series", "BBMid"))
                    .foregroundStyle(indicatorColor(.bollinger).opacity(0.6))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
            }

            if let hoverPoint {
                RuleMark(x: .value("Date", hoverPoint.date))
                    .foregroundStyle(Theme.hairline)
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                PointMark(x: .value("Date", hoverPoint.date), y: .value("Price", dbl(hoverPoint.close)))
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
            // iOS: a fixed height — inside the ScrollView a `minHeight`-only Chart
            // balloons to fill the viewport, so its area fill floods the screen.
            #if os(iOS)
            .frame(height: 260)
            #else
            .frame(minHeight: 260)
            #endif
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
        let candle = viewModel.candles.first { $0.date == point.date }
        return VStack(spacing: 2) {
            Text(point.close.formatted)
                .font(.system(size: 13, weight: .bold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
            if chartStyle == .candles, let candle {
                Text(String(format: tr(.highLowFormat), candle.high.formatted, candle.low.formatted))
                    .font(.system(size: 9, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textTertiary)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
            Text(point.date.formatted(.dateTime.month().day().hour().minute()))
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    // MARK: Indicators

    private struct IndicatorPoint: Identifiable {
        var id: Date { date }
        let date: Date
        let value: Double
    }

    private func overlayLine(_ indicator: Indicator) -> [IndicatorPoint] {
        guard indicators.contains(indicator) else { return [] }
        let series: [Double?]
        switch indicator {
        case .sma: series = TechnicalIndicators.sma(viewModel.closes, period: Self.smaPeriod)
        case .ema: series = TechnicalIndicators.ema(viewModel.closes, period: Self.emaPeriod)
        case .vwap:
            let typical = viewModel.candles.map { (dbl($0.high) + dbl($0.low) + dbl($0.close)) / 3 }
            let volumes = viewModel.candles.map(\.volume)
            series = TechnicalIndicators.vwap(typicalPrices: typical, volumes: volumes)
        case .bollinger, .rsi, .macd: return []
        }
        return zip(viewModel.points, series).compactMap { point, value in
            value.map { IndicatorPoint(date: point.date, value: $0) }
        }
    }

    private struct BollingerPoint: Identifiable {
        var id: Date { date }
        let date: Date
        let upper: Double
        let middle: Double
        let lower: Double
    }

    private var bollingerSeries: [BollingerPoint] {
        guard indicators.contains(.bollinger) else { return [] }
        let bands = TechnicalIndicators.bollingerBands(viewModel.closes, period: Self.bollingerPeriod)
        var result: [BollingerPoint] = []
        for (i, point) in viewModel.points.enumerated() {
            guard let upper = bands.upper[i], let middle = bands.middle[i], let lower = bands.lower[i] else { continue }
            result.append(BollingerPoint(date: point.date, upper: upper, middle: middle, lower: lower))
        }
        return result
    }

    /// Each indicator gets a distinct hue, all kept clear of the green/red price direction.
    private func indicatorColor(_ indicator: Indicator) -> Color {
        switch indicator {
        case .sma: return Theme.gold                                    // champagne
        case .ema: return Color(red: 0.30, green: 0.74, blue: 0.86)     // teal
        case .vwap: return Theme.silver                                 // neutral reference
        case .bollinger: return Color(red: 0.38, green: 0.56, blue: 0.95) // blue
        case .rsi: return Color(red: 0.65, green: 0.49, blue: 0.92)     // violet
        case .macd: return Color(red: 0.90, green: 0.58, blue: 0.26)    // amber
        }
    }

    private var macdSignalColor: Color { Color(red: 0.84, green: 0.45, blue: 0.67) } // pink

    /// Candle body width scales down as the bar count grows, so dense timeframes stay legible.
    private var candleWidth: CGFloat {
        switch viewModel.candles.count {
        case let n where n > 160: return 1.5
        case let n where n > 90: return 2.5
        case let n where n > 45: return 4
        case let n where n > 20: return 6
        default: return 8
        }
    }

    /// A separate 0–100 pane for RSI, with 30/70 guide lines, shown when RSI is enabled.
    private var rsiPane: some View {
        let series = TechnicalIndicators.rsi(viewModel.closes, period: Self.rsiPeriod)
        let points = zip(viewModel.points, series).compactMap { point, value in
            value.map { IndicatorPoint(date: point.date, value: $0) }
        }
        return VStack(alignment: .leading, spacing: 6) {
            Text(String(format: tr(.rsiPeriodFormat), Self.rsiPeriod))
                .font(.system(size: 10, weight: .bold)).tracking(1.2)
                .foregroundStyle(Theme.textTertiary)
            Chart {
                RuleMark(y: .value("Overbought", 70))
                    .foregroundStyle(Theme.hairline).lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                RuleMark(y: .value("Oversold", 30))
                    .foregroundStyle(Theme.hairline).lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                ForEach(points) { point in
                    LineMark(x: .value("Date", point.date), y: .value("RSI", point.value),
                             series: .value("Series", "RSI"))
                        .foregroundStyle(indicatorColor(.rsi))
                        .lineStyle(StrokeStyle(lineWidth: 1.5))
                }
            }
            .chartYScale(domain: 0...100)
            .chartYAxis {
                AxisMarks(position: .trailing, values: [0, 30, 70, 100]) { _ in
                    AxisGridLine().foregroundStyle(Theme.hairline)
                    AxisValueLabel().foregroundStyle(Theme.textTertiary)
                }
            }
            .chartXAxis(.hidden)
            .frame(height: 90)
        }
    }

    /// A separate pane for MACD: histogram bars (green/red) plus the MACD and signal lines.
    private var macdPane: some View {
        let result = TechnicalIndicators.macd(viewModel.closes)
        func points(_ series: [Double?]) -> [IndicatorPoint] {
            zip(viewModel.points, series).compactMap { point, value in
                value.map { IndicatorPoint(date: point.date, value: $0) }
            }
        }
        let macdLine = points(result.macd)
        let signalLine = points(result.signal)
        let histogram = points(result.histogram)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Text(tr(.macdParamsLabel)).font(.system(size: 10, weight: .bold)).tracking(1.2)
                    .foregroundStyle(Theme.textTertiary)
                legendDot(indicatorColor(.macd), tr(.indicatorMACD))
                legendDot(macdSignalColor, tr(.signalLegend))
            }
            Chart {
                ForEach(histogram) { point in
                    BarMark(x: .value("Date", point.date), y: .value("Hist", point.value),
                            width: .fixed(candleWidth))
                        .foregroundStyle((point.value >= 0 ? Theme.up : Theme.down).opacity(0.5))
                }
                ForEach(macdLine) { point in
                    LineMark(x: .value("Date", point.date), y: .value("MACD", point.value),
                             series: .value("Series", "MACD"))
                        .foregroundStyle(indicatorColor(.macd)).lineStyle(StrokeStyle(lineWidth: 1.5))
                }
                ForEach(signalLine) { point in
                    LineMark(x: .value("Date", point.date), y: .value("Signal", point.value),
                             series: .value("Series", "Signal"))
                        .foregroundStyle(macdSignalColor).lineStyle(StrokeStyle(lineWidth: 1.5))
                }
            }
            .chartYAxis {
                AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { _ in
                    AxisGridLine().foregroundStyle(Theme.hairline)
                    AxisValueLabel().foregroundStyle(Theme.textTertiary)
                }
            }
            .chartXAxis(.hidden)
            .frame(height: 100)
        }
    }

    private func legendDot(_ color: Color, _ label: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(label).font(.system(size: 9, weight: .semibold)).foregroundStyle(Theme.textTertiary)
        }
    }

    // MARK: Key stats

    @ViewBuilder
    private var keyStats: some View {
        if let quote = viewModel.quote {
            VStack(alignment: .leading, spacing: 16) {
                Text(tr(.keyStats))
                    .font(.system(size: 11, weight: .bold))
                    .tracking(1.8)
                    .foregroundStyle(Theme.textSecondary)

                let columns = [GridItem(.flexible(), spacing: 24), GridItem(.flexible(), spacing: 24)]
                LazyVGrid(columns: columns, alignment: .leading, spacing: 18) {
                    StatTile(label: tr(.statLast), value: quote.price.formatted)
                    StatTile(label: tr(.statPreviousClose), value: quote.previousClose.formatted)
                    StatTile(label: tr(.statDayChange),
                             value: signed(quote.change),
                             valueColor: Theme.changeColor(quote.changePercent))
                    StatTile(label: tr(.statDayChangePercent),
                             value: quote.changePercent.formatted,
                             valueColor: Theme.changeColor(quote.changePercent))
                    StatTile(label: tr(.statSymbol), value: quote.symbol)
                    StatTile(label: tr(.statType), value: typeLabel)
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
            tradeButton(title: tr(.buy), side: .buy, filled: true)
            tradeButton(title: tr(.sell), side: .sell, filled: false)
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
                Text(tr(.yourPosition))
                    .font(.system(size: 11, weight: .bold)).tracking(1.8)
                    .foregroundStyle(Theme.textSecondary)
                let columns = [GridItem(.flexible(), spacing: 24), GridItem(.flexible(), spacing: 24)]
                LazyVGrid(columns: columns, alignment: .leading, spacing: 18) {
                    StatTile(label: tr(.statShares), value: position.quantity.formatted)
                    StatTile(label: tr(.statAverageCost), value: position.averageCost.formatted)
                    StatTile(label: tr(.statMarketValue), value: position.marketValue(at: quote.price).formatted)
                    StatTile(label: tr(.unrealizedPnL),
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
        case .stock: return tr(.assetKindStock)
        case .etf: return tr(.etfChip)
        case .crypto: return tr(.cryptoLabel)
        }
    }

    /// Money with an explicit + or - sign, for change figures.
    private func signed(_ money: Money) -> String {
        let sign = money.amount > 0 ? "+" : ""
        return sign + money.formatted
    }
}
