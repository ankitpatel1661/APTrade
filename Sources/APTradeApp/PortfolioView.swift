import SwiftUI
import Charts
import APTradeDomain

struct PortfolioView: View {
    enum Section: String, CaseIterable {
        case holdings = "Holdings", allocation = "Allocation", activity = "Activity", performance = "Performance"

        @MainActor
        var title: String {
            switch self {
            case .holdings: return tr(.holdingsSection)
            case .allocation: return tr(.allocationSection)
            case .activity: return tr(.activitySection)
            case .performance: return tr(.performanceSection)
            }
        }
    }

    var switcher: AnyView? = nil
    var onOpenSearch: (() -> Void)? = nil
    var onOpenAccount: (() -> Void)? = nil
    @State private var viewModel = CompositionRoot.makePortfolioViewModel()
    @State private var performanceVM = CompositionRoot.makePerformanceViewModel()
    @State private var selectedAsset: Asset?
    @State private var showResetConfirm = false
    @State private var showChart = false
    @State private var section: Section = .holdings
    @Namespace private var sectionPill

    /// Unrealized P&L over time, reconstructed from real historical prices — a far more
    /// meaningful curve than total account value, which is dominated by constant cash.
    private var pnlValues: [Double] {
        viewModel.performance.map { ($0.pnl.amount as NSDecimalNumber).doubleValue }
    }
    private var pnlDates: [Date] { viewModel.performance.map { $0.date } }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    summary
                    if showChart && pnlValues.count > 1 {
                        expandedChart
                            #if os(iOS)
                            .padding(.horizontal, 16)
                            #else
                            .padding(.horizontal, 24)
                            #endif
                            .padding(.bottom, 16)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                    if !viewModel.holdings.isEmpty {
                        sectionPicker
                            .padding(.horizontal, 24)
                            .padding(.bottom, 8)
                    }
                    Divider().overlay(Theme.hairline)
                    content
                }
            }
            .navigationDestination(item: $selectedAsset) { asset in
                AssetDetailView(asset: asset)
            }
            #if os(iOS)
            .iosTopChrome(onSearch: { onOpenSearch?() }, onAccount: { onOpenAccount?() })
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .task {
                await viewModel.onAppear()
                await viewModel.runLiveUpdates()
            }
            .refreshable { await viewModel.refresh() }
            .confirmationDialog(tr(.resetPortfolioConfirm),
                                isPresented: $showResetConfirm, titleVisibility: .visible) {
                Button(tr(.reset), role: .destructive) { viewModel.reset() }
                Button(tr(.cancel), role: .cancel) {}
            }
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 640)
        #endif
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
        .onAppear { viewModel.reload() }
    }

    private var chartSpring: Animation { .spring(response: 0.34, dampingFraction: 0.84) }

    private var expandedChart: some View {
        VStack(spacing: 10) {
            ExpandedValueCard(
                title: tr(.portfolioUnrealizedPnLChartTitle),
                values: pnlValues,
                dates: pnlDates,
                color: trendColor,
                format: { Money(amount: Decimal($0), currencyCode: viewModel.valuation.totalValue.currencyCode).formatted },
                changeStyle: .money,
                stats: [
                    ChartStatItem(label: tr(.dayPnL),
                                  value: signed(viewModel.valuation.dayChange, showsSign: true),
                                  color: pnlColor(viewModel.valuation.dayChange)),
                    ChartStatItem(label: tr(.unrealizedPnL),
                                  value: signed(viewModel.valuation.unrealizedPnL, showsSign: true),
                                  color: pnlColor(viewModel.valuation.unrealizedPnL))
                ],
                onClose: { withAnimation(chartSpring) { showChart = false } }
            )
            spanBar
                .padding(.horizontal, 12)
        }
    }

    /// 1D · 1W · 1M · 1Y · MAX selector for the P&L chart (MAX = since first purchase).
    private var spanBar: some View {
        HStack(spacing: 0) {
            ForEach(PortfolioSpan.allCases) { item in
                let selected = viewModel.span == item
                Button {
                    Task { await viewModel.setSpan(item) }
                } label: {
                    VStack(spacing: 6) {
                        Text(item.rawValue)
                            .font(.system(size: 13, weight: .semibold).monospacedDigit())
                            .foregroundStyle(selected ? Theme.gold : Theme.textSecondary)
                        Capsule()
                            .fill(selected ? Theme.gold : .clear)
                            .frame(height: 2)
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.85), value: viewModel.span)
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(tr(.totalValue))
                        .font(.system(size: 11, weight: .bold)).tracking(1.8)
                        .foregroundStyle(Theme.textSecondary)
                    #if os(iOS)
                    SuperscriptPrice(money: viewModel.valuation.totalValue, size: 34, weight: .semibold)
                    #else
                    SuperscriptPrice(money: viewModel.valuation.totalValue, size: 40, weight: .semibold)
                    #endif
                }
                Spacer()
                #if os(iOS)
                // iOS: no top switcher (bottom tab bar); keep the reset control only.
                HStack {
                    Spacer()
                    resetMenu
                }
                #else
                HStack(alignment: .center, spacing: 10) {
                    if let switcher { switcher }
                    HStack(spacing: 10) {
                        if viewModel.isRefreshing {
                            ProgressView().controlSize(.small)
                        } else if viewModel.isLive {
                            LiveBadge()
                        }
                    }
                    .frame(width: 60, alignment: .trailing)
                    resetMenu
                }
                #endif
            }
            #if os(iOS)
            // iPhone: give the three metrics the full width (one line each), and move the
            // P&L sparkline to its own full-width row below instead of crowding them.
            HStack(alignment: .top, spacing: 12) {
                metric(label: tr(.dayPnL), money: viewModel.valuation.dayChange, colored: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                metric(label: tr(.unrealizedPnL), money: viewModel.valuation.unrealizedPnL, colored: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                metric(label: tr(.cashLabel), money: viewModel.valuation.cash, colored: false)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if pnlValues.count > 1 && !showChart {
                Button { withAnimation(chartSpring) { showChart.toggle() } } label: {
                    Sparkline(values: pnlValues, color: trendColor)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            #else
            HStack(alignment: .center, spacing: 22) {
                metric(label: tr(.dayPnL), money: viewModel.valuation.dayChange, colored: true)
                metric(label: tr(.unrealizedPnL), money: viewModel.valuation.unrealizedPnL, colored: true)
                metric(label: tr(.cashLabel), money: viewModel.valuation.cash, colored: false)
                Spacer()
                if pnlValues.count > 1 {
                    ExpandableSparkline(
                        values: pnlValues,
                        color: trendColor
                    ) { withAnimation(chartSpring) { showChart.toggle() } }
                }
            }
            #endif
            Text(tr(.simulatedPaperTradingFooter))
                .font(.system(size: 10, weight: .semibold)).tracking(0.6)
                .foregroundStyle(Theme.textTertiary)
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 18)
    }

    /// Overflow menu offering portfolio reset — shared verbatim between the macOS and
    /// iOS header clusters.
    private var resetMenu: some View {
        Menu {
            Button(tr(.resetPortfolio), systemImage: "arrow.counterclockwise", role: .destructive) {
                showResetConfirm = true
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.system(size: 16))
                .foregroundStyle(Theme.textSecondary)
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .frame(width: 28, alignment: .trailing)
    }

    private func metric(label: String, money: Money, colored: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .semibold)).tracking(1.0)
                .foregroundStyle(Theme.textTertiary)
            Text(signed(money, showsSign: colored))
                .font(.system(size: 16, weight: .semibold).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .foregroundStyle(colored ? pnlColor(money) : Theme.textPrimary)
        }
    }

    // MARK: - Section switcher

    private var sectionPicker: some View {
        #if os(iOS)
        // iPhone: the four labels don't fit one line as a static row (they wrap), so
        // make the row horizontally scrollable with each label pinned to a single line.
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) { sectionButtons }
        }
        #else
        HStack(spacing: 6) {
            sectionButtons
            Spacer()
        }
        #endif
    }

    @ViewBuilder
    private var sectionButtons: some View {
        ForEach(Section.allCases, id: \.self) { item in
            let selected = section == item
            Button {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { section = item }
            } label: {
                Text(item.title)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
                    .fixedSize()
                    .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
                    .padding(.horizontal, 14).padding(.vertical, 6)
                    .background {
                        if selected {
                            Capsule().fill(Theme.surfaceHi)
                                .overlay(Capsule().stroke(Theme.gold.opacity(0.40), lineWidth: 1))
                                .matchedGeometryEffect(id: "section", in: sectionPill)
                        }
                    }
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.holdings.isEmpty {
            emptyState
        } else {
            switch section {
            case .holdings: holdingsList
            case .allocation: allocationView
            case .activity: activityView
            case .performance: PerformanceSection(viewModel: performanceVM)
            }
        }
    }

    // MARK: - Holdings

    private var holdingsList: some View {
        List {
            ForEach(viewModel.holdings, id: \.asset.symbol) { position in
                HoldingRow(position: position, quote: viewModel.quote(for: position.asset.symbol))
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                    .contentShape(Rectangle())
                    .onTapGesture { selectedAsset = position.asset }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    // MARK: - Allocation

    private var allocationView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .center, spacing: 20) {
                    allocationDonut
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(viewModel.allocationByKind) { slice in
                            HStack(spacing: 8) {
                                Circle().fill(kindColor(slice.id)).frame(width: 9, height: 9)
                                Text(assetClassLabel(slice))
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(Theme.textPrimary)
                                Spacer()
                                Text("\(slice.fraction * 100, specifier: "%.1f")%")
                                    .font(.system(size: 13, weight: .semibold).monospacedDigit())
                                    .foregroundStyle(Theme.textSecondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, 24).padding(.top, 18)

                Text(tr(.byHolding))
                    .font(.system(size: 10, weight: .bold)).tracking(1.4)
                    .foregroundStyle(Theme.textTertiary)
                    .padding(.horizontal, 24)

                VStack(spacing: 14) {
                    ForEach(viewModel.allocationByHolding) { slice in
                        allocationBar(slice)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
            }
        }
    }

    private var allocationDonut: some View {
        Chart(viewModel.allocationByKind) { slice in
            SectorMark(angle: .value("Value", slice.value),
                       innerRadius: .ratio(0.64), angularInset: 1.5)
                .cornerRadius(3)
                .foregroundStyle(kindColor(slice.id))
        }
        .chartLegend(.hidden)
        .frame(width: 150, height: 150)
        .overlay {
            VStack(spacing: 2) {
                Text(tr(.holdingsLabel)).font(.system(size: 8, weight: .bold)).tracking(1.2)
                    .foregroundStyle(Theme.textTertiary)
                Text(viewModel.valuation.holdingsValue.formatted)
                    .font(.system(size: 14, weight: .bold).monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                    .minimumScaleFactor(0.6).lineLimit(1)
            }
            .padding(.horizontal, 18)
        }
    }

    private func allocationBar(_ slice: AllocationSlice) -> some View {
        VStack(spacing: 6) {
            HStack {
                Text(slice.label)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                Text("\(slice.fraction * 100, specifier: "%.1f")%")
                    .font(.system(size: 13, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.surfaceHi).frame(height: 7)
                    Capsule().fill(Theme.goldGradient)
                        .frame(width: max(4, geo.size.width * slice.fraction), height: 7)
                }
            }
            .frame(height: 7)
        }
    }

    private func kindColor(_ id: String) -> Color {
        switch id {
        case AssetKind.stock.rawValue: return Theme.gold
        case AssetKind.etf.rawValue: return Theme.goldDeep
        case AssetKind.crypto.rawValue: return Theme.silver
        default: return Theme.textTertiary
        }
    }

    /// `allocationByKind` slices are identified by `AssetKind.rawValue` ("stock"/"etf"/"crypto");
    /// map to the localized asset-class label rather than the view model's English-only
    /// `AllocationSlice.label`. Falls back to the raw label for any unrecognized id.
    private func assetClassLabel(_ slice: AllocationSlice) -> String {
        switch slice.id {
        case AssetKind.stock.rawValue: return tr(.stocksLabel)
        case AssetKind.etf.rawValue: return tr(.etfsLabel)
        case AssetKind.crypto.rawValue: return tr(.cryptoLabel)
        default: return slice.label
        }
    }

    // MARK: - Activity

    private var activityView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 22) {
                    metric(label: tr(.realizedPnL), money: viewModel.realizedPnL, colored: true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(tr(.tradesLabel))
                            .font(.system(size: 10, weight: .semibold)).tracking(1.0)
                            .foregroundStyle(Theme.textTertiary)
                        Text("\(viewModel.transactions.count)")
                            .font(.system(size: 16, weight: .semibold).monospacedDigit())
                            .foregroundStyle(Theme.textPrimary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 24).padding(.vertical, 16)

                Divider().overlay(Theme.hairline)

                if viewModel.transactions.isEmpty {
                    Text(tr(.noTransactionsYet))
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .padding(24)
                } else {
                    ForEach(viewModel.transactions) { txn in
                        TransactionRow(tx: txn, name: viewModel.assetName(for: txn.symbol))
                    }
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "chart.pie")
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(Theme.textTertiary)
            Text(tr(.noHoldingsYet))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text(tr(.noHoldingsHint))
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }

    private func pnlColor(_ money: Money) -> Color {
        if money.amount > 0 { return Theme.up }
        if money.amount < 0 { return Theme.down }
        return Theme.textPrimary
    }

    /// Green when the value series ends higher than it began, red when lower — so the chart
    /// and sparkline are colored by the direction they actually show. Falls back to the
    /// day's P&L sign when the series is flat or too short to have a direction.
    private var trendColor: Color {
        guard let first = pnlValues.first, let last = pnlValues.last, first != last else {
            return pnlColor(viewModel.valuation.unrealizedPnL)
        }
        return last > first ? Theme.up : Theme.down
    }

    private func signed(_ money: Money, showsSign: Bool) -> String {
        guard showsSign, money.amount > 0 else { return money.formatted }
        return "+" + money.formatted
    }
}

private struct HoldingRow: View {
    let position: Position
    let quote: Quote?

    var body: some View {
        HStack(spacing: 14) {
            Text(position.asset.name)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
            Spacer(minLength: 12)
            VStack(alignment: .trailing, spacing: 5) {
                if let quote {
                    SuperscriptPrice(money: position.marketValue(at: quote.price), size: 18, weight: .semibold)
                    Text(unrealizedText(at: quote.price))
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .foregroundStyle(pnlColor(position.unrealizedPnL(at: quote.price)))
                } else {
                    SuperscriptPrice(money: position.marketValue(at: position.averageCost), size: 18, weight: .semibold)
                }
            }
            .frame(minWidth: 104, alignment: .trailing)
        }
        .padding(.vertical, 13)
        .padding(.horizontal, 10)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.hairline).frame(height: 1).padding(.horizontal, 10)
        }
    }

    private func pnlColor(_ money: Money) -> Color {
        if money.amount > 0 { return Theme.up }
        if money.amount < 0 { return Theme.down }
        return Theme.textPrimary
    }

    /// Unrealized P&L with its return percentage beside it, e.g. "−$60.82 (−0.74%)".
    private func unrealizedText(at price: Money) -> String {
        let pnl = position.unrealizedPnL(at: price)
        let cost = position.averageCost.amount * position.quantity.amount
        let percent = cost == 0
            ? 0
            : (pnl.amount as NSDecimalNumber).doubleValue / (cost as NSDecimalNumber).doubleValue * 100
        let percentString = String(format: "%@%.2f%%", percent >= 0 ? "+" : "", percent)
        return "\(signed(pnl)) (\(percentString))"
    }

    private func signed(_ money: Money) -> String {
        let sign = money.amount > 0 ? "+" : ""
        return sign + money.formatted
    }
}

private struct TransactionRow: View {
    let tx: APTradeDomain.Transaction
    let name: String

    var body: some View {
        HStack(spacing: 14) {
            Text(tx.side == .buy ? tr(.buyChip) : tr(.sellChip))
                .font(.system(size: 10, weight: .bold)).tracking(0.8)
                .foregroundStyle(sideColor)
                .frame(width: 44, height: 22)
                .background(sideColor.opacity(0.12), in: Capsule())

            VStack(alignment: .leading, spacing: 3) {
                Text(tx.symbol)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text(tx.date.formatted(.dateTime.month().day().year().hour().minute()))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.textTertiary)
            }
            Spacer(minLength: 12)
            VStack(alignment: .trailing, spacing: 3) {
                Text(amount.formatted)
                    .font(.system(size: 14, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                Text("\(tx.quantity.formatted) @ \(tx.price.formatted)")
                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 24)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.hairline).frame(height: 1).padding(.horizontal, 24)
        }
    }

    private var sideColor: Color { tx.side == .buy ? Theme.up : Theme.down }

    private var amount: Money {
        Money(amount: tx.price.amount * tx.quantity.amount, currencyCode: tx.price.currencyCode)
    }
}
