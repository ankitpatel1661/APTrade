import SwiftUI
import Charts
import APTradeApplication
import APTradeDomain

/// The Portfolio tab's analytics surface: return/risk metric cards, a normalized
/// benchmark-overlay chart, and concentration warnings. All state lives in the view model.
struct PerformanceSection: View {
    @Bindable var viewModel: PerformanceViewModel

    var body: some View {
        content
            .task { await viewModel.onAppear() }
    }

    /// Which of the three mutually exclusive bodies renders BELOW the always-present goal
    /// card. Naming the mapping (rather than switching inline) makes it unit-testable and,
    /// being exhaustive over `PerformanceViewModel.State`, makes it a compile error to add a
    /// state that quietly takes the whole section away from the goal card again.
    enum ReportBody: Equatable {
        case spinner
        case empty
        case report(PerformanceReport)
    }

    /// `.idle`/`.loading` drive only the SPINNER — never the empty state: since M11.1 UAT F3
    /// every appearance re-enters `.loading`, so mapping it to `emptyState` would flash
    /// "Not enough history yet" over a portfolio that has plenty.
    static func reportBody(for state: PerformanceViewModel.State) -> ReportBody {
        switch state {
        case .idle, .loading: return .spinner
        case .empty: return .empty
        case .loaded(let report): return .report(report)
        }
    }

    /// The value-goal card (M11.1 Task 13) is the section's own reachability floor: unlike
    /// the metric grid/chart/diversification, it must render even before the user holds a
    /// single position — someone who has only deposited cash setting a value goal ahead of
    /// their first trade is the common case, not an edge case. Mirrors `IncomeSection`'s
    /// `content` (`IncomeSection.swift:62-81`): the goal card sits in the same ScrollView
    /// always, and the ONLY state switch left in this section is the one inside `reportBody`
    /// BELOW it.
    ///
    /// M11.1 UAT F4: `content` itself used to switch on the state and render a bare
    /// `ProgressView()` for `.idle`/`.loading`, so the card the doc comment above promised
    /// did not exist at all until the first report landed — the user saw a spinner where the
    /// goal card should be on every cold launch.
    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                goalCard
                reportBody
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
    }

    private var goalCard: some View {
        GoalCard(title: tr(.valueGoal),
                 kind: .value,
                 current: viewModel.currentValue,
                 goal: viewModel.valueGoal,
                 projection: viewModel.valueGoalProjection,
                 onSet: { viewModel.setValueGoal($0) },
                 onRemove: { viewModel.removeValueGoal() })
    }

    @ViewBuilder
    private var reportBody: some View {
        switch Self.reportBody(for: viewModel.state) {
        case .spinner:
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, 60)
        case .empty:
            emptyState
        case .report(let report):
            metricGrid(report.metrics)
            benchmarkPicker
            if !report.benchmarkCurve.isEmpty {
                overlayChart(report)
            } else {
                Text(tr(.benchmarkUnavailable))
                    .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
            }
            diversification(report)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "chart.line.uptrend.xyaxis")
                .font(.system(size: 34)).foregroundStyle(Theme.textSecondary)
            Text(tr(.notEnoughHistoryYet))
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textPrimary)
            Text(tr(.addHoldingsForAnalytics))
                .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    // MARK: Metric cards

    private func metricGrid(_ m: PerformanceMetrics) -> some View {
        let columns = [GridItem(.adaptive(minimum: 150), spacing: 12)]
        return LazyVGrid(columns: columns, spacing: 12) {
            MetricCard(title: tr(.totalReturn), value: percent(m.totalReturn), positive: m.totalReturn >= 0)
            MetricCard(title: tr(.annualizedReturn), value: percent(m.annualizedReturn), positive: m.annualizedReturn >= 0)
            MetricCard(title: tr(.volatility), value: percent(m.volatility), positive: nil)
            MetricCard(title: tr(.maxDrawdown), value: percent(m.maxDrawdown), positive: false)
            MetricCard(title: tr(.sharpe), value: ratio(m.sharpe), positive: (m.sharpe ?? 0) >= 0)
            MetricCard(title: tr(.beta), value: ratio(m.beta), positive: nil)
            MetricCard(title: tr(.alpha), value: m.alpha.map { percent($0) } ?? "—", positive: (m.alpha ?? 0) >= 0)
        }
    }

    // MARK: Benchmark

    private var benchmarkPicker: some View {
        Picker(tr(.benchmark), selection: $viewModel.benchmark) {
            ForEach(viewModel.benchmarks, id: \.self) { Text($0).tag($0) }
        }
        .pickerStyle(.segmented)
    }

    private func overlayChart(_ report: PerformanceReport) -> some View {
        let port = rebased(report.equityCurve.map { ($0.date, ($0.value.amount as NSDecimalNumber).doubleValue) })
        let bench = rebased(report.benchmarkCurve.map { ($0.date, ($0.close.amount as NSDecimalNumber).doubleValue) })
        let portfolioLabel = tr(.portfolio)
        return Chart {
            ForEach(port, id: \.0) { point in
                LineMark(x: .value("Date", point.0), y: .value(portfolioLabel, point.1),
                         series: .value("Series", portfolioLabel))
                    .foregroundStyle(Theme.gold)
            }
            ForEach(bench, id: \.0) { point in
                LineMark(x: .value("Date", point.0), y: .value(report.benchmarkSymbol, point.1),
                         series: .value("Series", report.benchmarkSymbol))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .frame(height: 200)
        .chartForegroundStyleScale([portfolioLabel: Theme.gold, report.benchmarkSymbol: Theme.textSecondary])
    }

    // MARK: Concentration

    private func diversification(_ report: PerformanceReport) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(tr(.diversification))
                .font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.textSecondary)
            Text(String(format: tr(.effectiveHoldingsFormat), report.effectiveHoldings))
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textPrimary)
            ForEach(Array(report.warnings.enumerated()), id: \.offset) { _, warning in
                Label(warningText(warning), systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func warningText(_ w: ConcentrationWarning) -> String {
        switch w {
        case .singleName(let label, let weight):
            return String(format: tr(.concentrationWarningFormat), label, percent(weight))
        case .assetClass(let kind, let weight):
            return String(format: tr(.concentrationWarningFormat), assetClassLabel(kind), percent(weight))
        }
    }

    /// `ConcentrationWarning.assetClass`'s `kind` is `AssetKind.rawValue` ("stock"/"etf"/"crypto")
    /// degraded to a plain `String` at the domain boundary — map back to the localized
    /// asset-class label rather than `kind.capitalized`, which only ever produces English text.
    private func assetClassLabel(_ kind: String) -> String {
        switch kind {
        case AssetKind.stock.rawValue: return tr(.stocksLabel)
        case AssetKind.etf.rawValue: return tr(.etfsLabel)
        case AssetKind.crypto.rawValue: return tr(.cryptoLabel)
        default: return kind.capitalized
        }
    }

    // MARK: Formatting helpers

    private func percent(_ v: Double) -> String { String(format: "%.2f%%", v * 100) }
    private func ratio(_ v: Double?) -> String { v.map { String(format: "%.2f", $0) } ?? "—" }

    /// Rebase a value series to 100 at its first point for a like-for-like overlay.
    private func rebased(_ series: [(Date, Double)]) -> [(Date, Double)] {
        guard let base = series.first?.1, base > 0 else { return series }
        return series.map { ($0.0, $0.1 / base * 100) }
    }
}

/// A single labelled metric tile in the performance grid.
private struct MetricCard: View {
    let title: String
    let value: String
    /// nil = neutral (no color), true = positive (gold/green), false = negative (red).
    let positive: Bool?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 11, weight: .medium)).foregroundStyle(Theme.textSecondary)
            Text(value)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.hairline, lineWidth: 1))
    }

    private var color: Color {
        switch positive {
        case .some(true): return Theme.gold
        case .some(false): return .red
        case .none: return Theme.textPrimary
        }
    }
}
