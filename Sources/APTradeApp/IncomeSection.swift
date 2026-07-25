import SwiftUI
import Charts
import APTradeApplication
import APTradeDomain

private let incomeMonthKeyParser: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "yyyy-MM"
    return f
}()

private let incomeMonthLabelFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US")
    f.dateFormat = "MMM"
    return f
}()

private let incomeDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US")
    f.dateFormat = "MMM d"
    return f
}()

/// The Portfolio tab's Income section: dividend summary cards, a monthly received/projected
/// bar chart, upcoming payouts, a per-holding breakdown, and payment history. Structurally
/// mirrors `PlansSection` (scroll of cards, matching empty state), but `IncomeViewModel` is
/// `ObservableObject` — mandated so its testable-seam style matches the rest of M8 — rather
/// than this app's usual `@Observable`, so this view holds it with `@StateObject`, never
/// `@Bindable`.
struct IncomeSection: View {
    @StateObject private var viewModel = CompositionRoot.makeIncomeViewModel()
    /// M10.1 Task 8: DRIP re-homed here from Account Settings — bound to the SAME
    /// `settingsVM.settings.dripEnabled` field `RootView` owns, threaded down through
    /// `InvestView` rather than this view instantiating its own `SettingsViewModel`
    /// (there'd then be two independent copies of the same persisted setting).
    let dripEnabled: Binding<Bool>
    /// Which monthly bar's tooltip is showing (M10.1 UAT U6) — macOS sets/clears it on
    /// hover (mirrors `WatchlistView.hoveredSymbol`'s idiom), iOS toggles it on tap.
    @State private var activeMonthID: String?

    var body: some View {
        content
            .task { await viewModel.load() }
    }

    /// The DRIP header card (M10.1 Task 8) is now the section's own reachability floor:
    /// unlike the summary grid/chart/history, it must render even before the user has ever
    /// received or projected a dividend — someone turning DRIP on ahead of their first
    /// payout is the common case, not an edge case. So `content` always wraps `dripCard`
    /// in the same ScrollView, switching only the content BELOW it between the empty state
    /// and the full ledger — rather than the old all-or-nothing `emptyState`/`list` split,
    /// which would have hidden the toggle entirely for a brand-new portfolio.
    @ViewBuilder
    private var content: some View {
        if viewModel.isLoading && viewModel.cards == nil {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    dripCard
                    if isEmptyLedger {
                        emptyState
                    } else {
                        ledger
                    }
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
            }
        }
    }

    /// No dividend has ever been received and none is projected — the ledger portion would
    /// otherwise render as a wall of zeroed cards and empty lists.
    private var isEmptyLedger: Bool {
        viewModel.history.isEmpty && viewModel.upcoming.isEmpty
    }

    /// `viewModel.forecast` always holds `horizon.rawValue` entries, even for an empty
    /// portfolio — `DividendMath.incomeForecast` fills every year with a zero `Money`
    /// rather than returning `[]`, so `!forecast.isEmpty` can never distinguish "no
    /// income to project" from real data (M11.1 Task 11 review finding). `cards
    /// .projectedAnnual` is the same trailing-twelve-month sum as `forecast[0].income`
    /// (both are `DividendMath.projectedAnnualIncome` over the same positions), and every
    /// later forecast year is either that value grown or still zero — so a zero year 1
    /// guarantees a zero curve throughout. Comparing it directly avoids re-deriving that
    /// logic in the view.
    private var hasForecastData: Bool {
        (viewModel.cards?.projectedAnnual.amount ?? 0) > 0
    }

    @ViewBuilder
    private var ledger: some View {
        if let cards = viewModel.cards {
            summaryGrid(cards)
        }
        incomeGoalCard
        if !viewModel.months.isEmpty {
            monthlyChart
        }
        upcomingCalendarCard
        forecastCard
#if os(iOS)
        if !viewModel.upcoming.isEmpty {
            upcomingCard
        }
        if !viewModel.holdings.isEmpty {
            holdingsCard
        }
#else
        // Wide layout: upcoming + per-holding share one row, so both tables are
        // visible without scrolling and neither stretches symbol-to-price across
        // the whole window.
        if !viewModel.upcoming.isEmpty, !viewModel.holdings.isEmpty {
            HStack(alignment: .top, spacing: 20) {
                upcomingCard.frame(maxWidth: .infinity, alignment: .topLeading)
                holdingsCard.frame(maxWidth: .infinity, alignment: .topLeading)
            }
        } else if !viewModel.upcoming.isEmpty {
            upcomingCard
        } else if !viewModel.holdings.isEmpty {
            holdingsCard
        }
#endif
        if !viewModel.history.isEmpty {
            historyCard
        }
    }

    /// Bold title + subtitle + gold toggle, bound to the same `settingsVM.settings
    /// .dripEnabled` field the account panel used to host — mirrors `monthlyChart`'s card
    /// chrome (surface fill, hairline stroke) and `RootView.toggleRow`'s switch styling.
    private var dripCard: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(tr(.dripCardTitle))
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                Text(tr(.dripCardSubtitle))
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textTertiary)
            }
            Spacer(minLength: 12)
            Toggle("", isOn: dripEnabled)
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(Theme.gold)
                .controlSize(.small)
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "banknote")
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(Theme.textTertiary)
            Text(tr(.incomeNoDividends))
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    // MARK: - Summary cards

    private func summaryGrid(_ cards: IncomeViewModel.SummaryCards) -> some View {
        let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
        return LazyVGrid(columns: columns, spacing: 12) {
            IncomeSummaryCard(title: tr(.incomeProjectedAnnual), value: cards.projectedAnnual.formatted)
            IncomeSummaryCard(title: tr(.incomeReceivedYTD), value: cards.receivedYTD.formatted)
            IncomeSummaryCard(title: tr(.incomePortfolioYield), value: percentText(cards.portfolioYield))
            IncomeSummaryCard(title: tr(.incomeYieldOnCost), value: percentText(cards.yieldOnCost))
        }
    }

    // MARK: - Monthly chart

    private var monthlyChart: some View {
        let maxAmount = viewModel.months
            .map { ($0.amount.amount as NSDecimalNumber).doubleValue }
            .max() ?? 0
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                sectionHeader(tr(.incomeMonthlyTitle))
                Spacer()
                // Subtle max-value axis label (M10.1 UAT U6) — no new copy, just the
                // tallest bar's own already-formatted amount, read straight off the data
                // the bars themselves are already scaled against.
                if let maxMonth = viewModel.months.max(by: { $0.amount.amount < $1.amount.amount }) {
                    Text(maxMonth.amount.formatted)
                        .font(.system(size: 10, weight: .semibold).monospacedDigit())
                        .foregroundStyle(Theme.textTertiary)
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .bottom, spacing: 8) {
                    ForEach(viewModel.months) { bar in
                        monthBarColumn(bar, maxAmount: maxAmount)
                    }
                }
                .frame(height: 120)
                // Headroom for a tooltip floating above whichever bar is active, without
                // clipping against the chart's own bounds.
                .padding(.top, 32)
            }
            legendRow
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private func monthBarColumn(_ bar: IncomeViewModel.MonthBar, maxAmount: Double) -> some View {
        let value = (bar.amount.amount as NSDecimalNumber).doubleValue
        let fraction = maxAmount > 0 ? value / maxAmount : 0
        let isActive = activeMonthID == bar.id
        return VStack(spacing: 6) {
            GeometryReader { geo in
                // Projected months render as a dashed outline with a faint fill so they
                // read as clearly provisional next to the solid received bars.
                Group {
                    if bar.isProjected {
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .fill(Theme.gold.opacity(0.12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 3, style: .continuous)
                                    .strokeBorder(Theme.gold.opacity(0.6),
                                                  style: StrokeStyle(lineWidth: 1, dash: [3, 2]))
                            )
                    } else {
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .fill(isActive ? Theme.goldLight : Theme.gold)
                    }
                }
                .frame(height: max(2, geo.size.height * fraction))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            }
            Text(monthLabel(bar.id))
                .font(.system(size: 8, weight: .semibold))
                .foregroundStyle(Theme.textTertiary)
                .fixedSize()
        }
        .frame(width: 22)
        // Month + exact amount on hover/tap (M10.1 UAT U6) — no amount is shown anywhere
        // else on this chart, only relative bar height. Same floating-readout idiom as
        // `ExpandedValueCard.hoverTooltip` (rounded surface-hi card, hairline stroke).
        .overlay(alignment: .top) {
            if isActive {
                monthTooltip(bar).fixedSize().offset(y: -30)
            }
        }
        #if os(macOS)
        .onHover { hovering in
            if hovering { activeMonthID = bar.id }
            else if activeMonthID == bar.id { activeMonthID = nil }
        }
        #else
        // iOS: tap toggles the same tooltip (task allows tap OR long-press; tap is the
        // simpler, more discoverable of the two for a bar this narrow).
        .onTapGesture {
            activeMonthID = (activeMonthID == bar.id) ? nil : bar.id
        }
        #endif
    }

    private func monthTooltip(_ bar: IncomeViewModel.MonthBar) -> some View {
        VStack(spacing: 2) {
            Text(bar.amount.formatted)
                .font(.system(size: 11, weight: .bold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
            Text(monthLabel(bar.id))
                .font(.system(size: 9, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private var legendRow: some View {
        HStack(spacing: 6) {
            Spacer()
            Circle()
                .fill(Theme.gold.opacity(0.12))
                .overlay(Circle().strokeBorder(Theme.gold.opacity(0.6), lineWidth: 1))
                .frame(width: 7, height: 7)
            Text(tr(.incomeEstimatedBadge))
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(Theme.textTertiary)
        }
    }

    private func monthLabel(_ key: String) -> String {
        guard let date = incomeMonthKeyParser.date(from: key) else { return key }
        return incomeMonthLabelFormatter.string(from: date)
    }

    // MARK: - Income goal (M11.1 Task 12)

    private var incomeGoalCard: some View {
        GoalCard(title: tr(.incomeGoal),
                 current: viewModel.cards?.projectedAnnual ?? Money(amount: 0),
                 goal: viewModel.incomeGoal,
                 projection: viewModel.incomeGoalProjection,
                 onSet: { viewModel.setIncomeGoal($0) },
                 onRemove: { viewModel.removeIncomeGoal() })
    }

    // MARK: - Dividend calendar (M11.1 Task 12)

    /// `viewModel.calendarMonths` can hold up to 13 entries: the underlying window is a
    /// fixed 365 days, so with "now" mid-month the first and last buckets are both
    /// partial months — and when that span crosses a year boundary, those two partial
    /// buckets share the same month *name* with different years (e.g. "July 2026" and
    /// "July 2027"). `month.title` always carries the year, so rendering it in full
    /// (rather than assuming a 12-item grid or trimming to a bare month name) disambiguates
    /// them automatically; this `ForEach` makes no assumption about the count.
    private var upcomingCalendarCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                sectionHeader(tr(.upcomingDividends))
                Spacer()
                // Every row below is a projection — Yahoo exposes no declared future
                // ex-dates — so the card carries the disclaimer both here and per row.
                Text(tr(.estimatedShort))
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(Theme.textTertiary)
            }
            if viewModel.calendarMonths.isEmpty {
                Text(tr(.noUpcomingDividends))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 24)
            } else {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(viewModel.calendarMonths) { month in
                        calendarMonthGroup(month)
                    }
                }
            }
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private func calendarMonthGroup(_ month: IncomeViewModel.CalendarMonth) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(month.title)
                    .font(.system(size: 11, weight: .bold)).tracking(0.4)
                    .foregroundStyle(Theme.textTertiary)
                Spacer()
                Text(month.total.formatted)
                    .font(.system(size: 11, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
            // `ScheduledDividend` isn't `Identifiable`/`Hashable`, and two holdings can
            // legitimately project to the exact same ex-date within a month, so index the
            // row by its position rather than `\.exDate` (which could collide).
            ForEach(Array(month.rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: 8) {
                    Text(row.symbol)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                    Spacer(minLength: 8)
                    Text(row.estimatedAmount.formatted)
                        .font(.system(size: 13, weight: .medium).monospacedDigit())
                        .foregroundStyle(Theme.textSecondary)
                    Text(tr(.estimatedShort))
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(Theme.textTertiary)
                }
            }
        }
    }

    // MARK: - Income forecast (M11.1 Task 12)

    private var forecastCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                sectionHeader(tr(.incomeForecast))
                Spacer()
                Picker("", selection: $viewModel.horizon) {
                    ForEach(IncomeViewModel.ForecastHorizon.allCases) { horizon in
                        Text(horizon.label).tag(horizon)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
            }
            if hasForecastData {
                forecastChart
                Text(tr(.forecastCaption))
                    .font(.system(size: 10))
                    .foregroundStyle(Theme.textTertiary)
            } else {
                Text(tr(.incomeNoDividends))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 40)
            }
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    /// Area + line, gold-on-brand — matches `AssetDetailView`'s price-chart treatment
    /// (gradient fill fading to transparent, a solid line on top) rather than Swift
    /// Charts' default blue, which would clash with the app's gold identity.
    private var forecastChart: some View {
        Chart(viewModel.forecast, id: \.yearOffset) { year in
            AreaMark(x: .value("Year", year.yearOffset),
                     y: .value("Income", (year.income.amount as NSDecimalNumber).doubleValue))
                .interpolationMethod(.catmullRom)
                .foregroundStyle(.linearGradient(
                    colors: [Theme.gold.opacity(ThemeManager.shared.isDark ? 0.26 : 0.12),
                             Theme.gold.opacity(0)],
                    startPoint: .top, endPoint: .bottom))
            LineMark(x: .value("Year", year.yearOffset),
                     y: .value("Income", (year.income.amount as NSDecimalNumber).doubleValue))
                .interpolationMethod(.catmullRom)
                .foregroundStyle(Theme.gold)
                .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
        .frame(height: 180)
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) { _ in
                AxisGridLine().foregroundStyle(Theme.hairline)
                AxisValueLabel().foregroundStyle(Theme.textTertiary)
            }
        }
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 5)) { _ in
                AxisValueLabel().foregroundStyle(Theme.textTertiary)
            }
        }
    }

    // MARK: - Upcoming

    private var upcomingCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            sectionHeader(tr(.incomeUpcomingTitle))
            VStack(spacing: 0) {
                ForEach(viewModel.upcoming) { row in
                    upcomingRow(row)
                }
            }
        }
    }

    private func upcomingRow(_ row: IncomeViewModel.UpcomingRow) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(row.symbol)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text(incomeDateFormatter.string(from: row.estimatedExDate))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.textTertiary)
            }
            Spacer(minLength: 12)
            Text(row.estimatedAmount.formatted)
                .font(.system(size: 14, weight: .semibold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
            estimatedBadge
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 10)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.hairline).frame(height: 1).padding(.horizontal, 10)
        }
    }

    // MARK: - Per-holding

    private var holdingsCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            sectionHeader(tr(.incomePerHoldingTitle))
            VStack(spacing: 0) {
                ForEach(viewModel.holdings) { row in
                    holdingRow(row)
                }
            }
        }
    }

    private func holdingRow(_ row: IncomeViewModel.HoldingRow) -> some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(row.symbol)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text(row.shares.formatted)
                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textTertiary)
            }
            Spacer(minLength: 12)
            VStack(alignment: .trailing, spacing: 3) {
                Text(row.annualIncome.formatted)
                    .font(.system(size: 14, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                Text(percentText(row.yieldOnCost))
                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
            VStack(alignment: .trailing, spacing: 3) {
                Text(tr(.incomeLastPayment).uppercased())
                    .font(.system(size: 8, weight: .bold)).tracking(0.6)
                    .foregroundStyle(Theme.textTertiary)
                Text(row.lastPayment?.formatted ?? "—")
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
            .frame(minWidth: 76, alignment: .trailing)
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 10)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.hairline).frame(height: 1).padding(.horizontal, 10)
        }
    }

    // MARK: - History

    private var historyCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            sectionHeader(tr(.incomeHistoryTitle))
            VStack(spacing: 0) {
                ForEach(viewModel.history) { entry in
                    historyRow(entry)
                }
            }
        }
    }

    private func historyRow(_ entry: IncomeViewModel.HistoryEntry) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(entry.symbol)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                    if entry.wasReinvested {
                        reinvestedBadge
                    }
                }
                Text(incomeDateFormatter.string(from: entry.date))
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.textTertiary)
            }
            Spacer(minLength: 12)
            VStack(alignment: .trailing, spacing: 3) {
                Text(entry.total.formatted)
                    .font(.system(size: 14, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                Text("\(entry.shares.formatted) @ \(entry.amountPerShare.formatted)")
                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 10)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.hairline).frame(height: 1).padding(.horizontal, 10)
        }
    }

    // MARK: - Shared bits

    private func sectionHeader(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 10, weight: .bold)).tracking(1.4)
            .foregroundStyle(Theme.textTertiary)
    }

    private var estimatedBadge: some View { pillBadge(tr(.incomeEstimatedBadge), color: Theme.gold) }
    private var reinvestedBadge: some View { pillBadge(tr(.incomeReinvestedBadge), color: Theme.silver) }

    private func pillBadge(_ text: String, color: Color) -> some View {
        Text(text.uppercased())
            .font(.system(size: 9, weight: .bold)).tracking(0.4)
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(color.opacity(0.12), in: Capsule())
            .overlay(Capsule().stroke(color.opacity(0.28), lineWidth: 1))
    }

    private func percentText(_ v: Double) -> String { String(format: "%.2f%%", v * 100) }
}

/// One labeled figure in the Income section's 2×2 summary grid — mirrors
/// `PerformanceSection`'s `MetricCard` styling (surface card, hairline stroke).
private struct IncomeSummaryCard: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title.uppercased())
                .font(.system(size: 10, weight: .bold)).tracking(1.0)
                .foregroundStyle(Theme.textTertiary)
            Text(value)
                .font(.system(size: 18, weight: .semibold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }
}
