import SwiftUI
import Charts
import APTradeDomain

/// How the expanded card phrases the "change" line under its headline value.
enum ValueChangeStyle {
    /// Values are money amounts — change reads "+$12.30 (+0.42%)".
    case money
    /// Values are already percentages — change reads "+0.42%" as a point delta.
    case percentagePoints
}

/// One labeled figure shown in the expanded chart's headline (e.g. Day P&L).
struct ChartStatItem: Identifiable {
    let id = UUID()
    let label: String
    let value: String
    let color: Color
}

/// The expanded value chart, rendered **inline** (full width, in the content flow)
/// rather than as a floating popup — tapping the collapsed sparkline grows this in
/// place. It carries axes, a hover crosshair, and a headline: either the value at the
/// cursor plus its change, or a set of caller-supplied stat tiles (Day / Unrealized P&L).
struct ExpandedValueCard: View {
    let title: String
    let values: [Double]
    var dates: [Date]? = nil
    let color: Color
    let format: (Double) -> String
    var changeStyle: ValueChangeStyle = .money
    /// When set, these tiles form the headline instead of the big value + change line.
    var stats: [ChartStatItem]? = nil
    let onClose: () -> Void

    @State private var hoverIndex: Int?

    private var startValue: Double { values.first ?? 0 }
    private var lastIndex: Int { max(values.count - 1, 0) }
    private var activeIndex: Int { hoverIndex ?? lastIndex }
    private var activeValue: Double {
        values.indices.contains(activeIndex) ? values[activeIndex] : (values.last ?? 0)
    }
    private var changeAmount: Double { activeValue - startValue }
    private var changePercent: Double {
        startValue == 0 ? 0 : changeAmount / abs(startValue) * 100
    }
    private var changeColor: Color {
        if changeAmount > 0 { return Theme.up }
        if changeAmount < 0 { return Theme.down }
        return Theme.textSecondary
    }

    private var yDomain: ClosedRange<Double> {
        guard let lo = values.min(), let hi = values.max(), hi > lo else { return 0...1 }
        let pad = (hi - lo) * 0.12
        return (lo - pad)...(hi + pad)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            chart
                #if os(iOS)
                // Fixed (not minHeight): on iPhone the enclosing VStack is a non-scrolling
                // column that can exceed the viewport, and a compressible minHeight lets
                // SwiftUI shrink the chart below what its content needs, clipping the
                // trailing axis label. A fixed height removes that degree of freedom.
                .frame(maxWidth: .infinity)
                .frame(height: 150)
                #else
                .frame(maxWidth: .infinity, minHeight: 150)
                #endif
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        #if os(iOS)
        // Take exactly the content's natural height and refuse vertical compression, so the
        // card can never be squeezed shorter than its title row + stats + chart by the
        // enclosing non-scrolling VStack (the holdings list below yields the space instead).
        .fixedSize(horizontal: false, vertical: true)
        #else
        // Size to content (with a 230pt floor) rather than a hard height: the portfolio card
        // carries a taller two-row stats header, and a fixed height let that content — and the
        // red area fill — overflow the background and spill onto the holdings list below.
        .frame(minHeight: 230)
        #endif
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Theme.hairline, lineWidth: 1)
        )
    }

    @ViewBuilder
    private var header: some View {
        HStack(alignment: .top) {
            if let stats {
                VStack(alignment: .leading, spacing: 8) {
                    Text(title.uppercased())
                        .font(.system(size: 10, weight: .bold)).tracking(1.4)
                        .foregroundStyle(Theme.textTertiary)
                    HStack(alignment: .top, spacing: 26) {
                        ForEach(stats) { stat in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(stat.label.uppercased())
                                    .font(.system(size: 9, weight: .bold)).tracking(1.0)
                                    .foregroundStyle(Theme.textTertiary)
                                Text(stat.value)
                                    .font(.system(size: 20, weight: .bold).monospacedDigit())
                                    .foregroundStyle(stat.color)
                            }
                        }
                    }
                    if let label = timeLabel {
                        Text("\(format(activeValue)) · \(label)")
                            .font(.system(size: 11, weight: .medium).monospacedDigit())
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title.uppercased())
                        .font(.system(size: 10, weight: .bold)).tracking(1.4)
                        .foregroundStyle(Theme.textTertiary)
                    Text(format(activeValue))
                        .font(.system(size: 26, weight: .bold).monospacedDigit())
                        .foregroundStyle(Theme.textPrimary)
                        .contentTransition(.numericText())
                    HStack(spacing: 6) {
                        Text(changeText)
                            .font(.system(size: 12, weight: .semibold).monospacedDigit())
                            .foregroundStyle(changeColor)
                        if let label = timeLabel {
                            Text("· \(label)")
                                .font(.system(size: 11, weight: .medium))
                                .foregroundStyle(Theme.textTertiary)
                        }
                    }
                }
            }
            Spacer()
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 24, height: 24)
                    .background(Theme.surfaceHi, in: Circle())
            }
            .buttonStyle(.plain)
        }
    }

    private var changeText: String {
        let signed = changeAmount >= 0 ? "+" : "−"
        switch changeStyle {
        case .money:
            let amount = abs(changeAmount)
            let pct = changePercent
            let pctSign = pct >= 0 ? "+" : "−"
            return "\(signed)$\(amount.formatted(.number.precision(.fractionLength(2)))) (\(pctSign)\(abs(pct).formatted(.number.precision(.fractionLength(2))))%)"
        case .percentagePoints:
            return "\(signed)\(abs(changeAmount).formatted(.number.precision(.fractionLength(2))))%"
        }
    }

    private var timeLabel: String? {
        guard let dates, dates.indices.contains(activeIndex) else { return nil }
        return dates[activeIndex].formatted(.dateTime.month().day().hour().minute())
    }

    private var chart: some View {
        Chart {
            ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                AreaMark(x: .value("t", index), y: .value("v", value))
                    .interpolationMethod(.catmullRom)
                    .foregroundStyle(.linearGradient(
                        colors: [color.opacity(0.22), color.opacity(0)],
                        startPoint: .top, endPoint: .bottom))
                LineMark(x: .value("t", index), y: .value("v", value))
                    .interpolationMethod(.catmullRom)
                    .foregroundStyle(color)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            }
            if let hoverIndex, values.indices.contains(hoverIndex) {
                RuleMark(x: .value("t", hoverIndex))
                    .foregroundStyle(Theme.hairline)
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                PointMark(x: .value("t", hoverIndex), y: .value("v", values[hoverIndex]))
                    .foregroundStyle(color)
                    .symbolSize(50)
            }
        }
        .chartYScale(domain: yDomain)
        .chartXScale(domain: 0...Double(max(lastIndex, 1)))
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { _ in
                AxisGridLine().foregroundStyle(Theme.hairline)
                AxisValueLabel().foregroundStyle(Theme.textTertiary)
            }
        }
        #if os(iOS)
        // Keeps trailing axis labels clear of the card's rounded-rect clip boundary on
        // iPhone, where the card has less horizontal breathing room than on macOS.
        .chartPlotStyle { $0.padding(.trailing, 6) }
        #endif
        .chartLegend(.hidden)
        .chartOverlay { proxy in
            GeometryReader { geo in
                ZStack(alignment: .topLeading) {
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        #if os(iOS)
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { value in updateHover(at: value.location, proxy: proxy, geo: geo) }
                                .onEnded { _ in hoverIndex = nil }
                        )
                        #else
                        .onContinuousHover { phase in
                            switch phase {
                            case .active(let location): updateHover(at: location, proxy: proxy, geo: geo)
                            case .ended: hoverIndex = nil
                            }
                        }
                        #endif
                    if let hoverIndex, values.indices.contains(hoverIndex), let plotFrame = proxy.plotFrame,
                       let x = proxy.position(forX: hoverIndex),
                       let y = proxy.position(forY: values[hoverIndex]) {
                        let frame = geo[plotFrame]
                        let tooltipWidth: CGFloat = 124
                        let clampedX = min(max(frame.origin.x + x, tooltipWidth / 2),
                                           geo.size.width - tooltipWidth / 2)
                        let tooltipY = max(frame.origin.y + y - 42, 0)
                        hoverTooltip
                            .frame(width: tooltipWidth)
                            .position(x: clampedX, y: tooltipY)
                    }
                }
            }
        }
    }

    /// A floating readout pinned to the hovered point — value plus timestamp when available.
    private var hoverTooltip: some View {
        VStack(spacing: 2) {
            Text(format(activeValue))
                .font(.system(size: 12, weight: .bold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1).minimumScaleFactor(0.7)
            if let label = timeLabel {
                Text(label)
                    .font(.system(size: 9, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
        }
        .padding(.horizontal, 9).padding(.vertical, 5)
        .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private func updateHover(at location: CGPoint, proxy: ChartProxy, geo: GeometryProxy) {
        guard let plotFrame = proxy.plotFrame else { return }
        let relativeX = location.x - geo[plotFrame].origin.x
        guard let raw: Double = proxy.value(atX: relativeX) else { return }
        hoverIndex = min(max(Int(raw.rounded()), 0), lastIndex)
    }
}

/// A compact, tappable sparkline that signals it can be opened. Wraps `Sparkline`
/// with an affordance border and forwards taps to the parent, which owns the
/// expanded card's presentation state.
struct ExpandableSparkline: View {
    let values: [Double]
    let color: Color
    var size = CGSize(width: 140, height: 40)
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Sparkline(values: values, color: color)
                .frame(width: size.width, height: size.height)
                .overlay(alignment: .topTrailing) {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.system(size: 8, weight: .bold))
                        .foregroundStyle(Theme.textTertiary)
                        .padding(3)
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(tr(.expandChart))
    }
}
