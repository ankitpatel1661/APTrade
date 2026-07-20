import SwiftUI
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

    var body: some View {
        content
            .task { await viewModel.load() }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.isLoading && viewModel.cards == nil {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if isEmptyLedger {
            emptyState
        } else {
            list
        }
    }

    /// No dividend has ever been received and none is projected — the whole section would
    /// otherwise render as a wall of zeroed cards and empty lists.
    private var isEmptyLedger: Bool {
        viewModel.history.isEmpty && viewModel.upcoming.isEmpty
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let cards = viewModel.cards {
                    summaryGrid(cards)
                }
                if !viewModel.months.isEmpty {
                    monthlyChart
                }
                if !viewModel.upcoming.isEmpty {
                    upcomingCard
                }
                if !viewModel.holdings.isEmpty {
                    holdingsCard
                }
                if !viewModel.history.isEmpty {
                    historyCard
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "banknote")
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(Theme.textTertiary)
            Text(tr(.incomeNoDividends))
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
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
            sectionHeader(tr(.incomeMonthlyTitle))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .bottom, spacing: 8) {
                    ForEach(viewModel.months) { bar in
                        monthBarColumn(bar, maxAmount: maxAmount)
                    }
                }
                .frame(height: 120)
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
        return VStack(spacing: 6) {
            GeometryReader { geo in
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(bar.isProjected ? Theme.gold.opacity(0.4) : Theme.gold)
                    .frame(height: max(2, geo.size.height * fraction))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            }
            Text(monthLabel(bar.id))
                .font(.system(size: 8, weight: .semibold))
                .foregroundStyle(Theme.textTertiary)
                .fixedSize()
        }
        .frame(width: 22)
    }

    private var legendRow: some View {
        HStack(spacing: 6) {
            Spacer()
            Circle().fill(Theme.gold.opacity(0.4)).frame(width: 7, height: 7)
            Text(tr(.incomeEstimatedBadge))
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(Theme.textTertiary)
        }
    }

    private func monthLabel(_ key: String) -> String {
        guard let date = incomeMonthKeyParser.date(from: key) else { return key }
        return incomeMonthLabelFormatter.string(from: date)
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
