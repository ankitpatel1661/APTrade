import SwiftUI
import Charts
import APTradeApplication
import APTradeDomain

/// The Portfolio tab's analytics surface: return/risk metric cards, a normalized
/// benchmark-overlay chart, and concentration warnings. All state lives in the view model.
struct PerformanceSection: View {
    @Bindable var viewModel: PerformanceViewModel

    var body: some View {
        Group {
            switch viewModel.state {
            case .idle, .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .empty:
                emptyState
            case .loaded(let report):
                loaded(report)
            }
        }
        .task { await viewModel.onAppear() }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "chart.line.uptrend.xyaxis")
                .font(.system(size: 34)).foregroundStyle(Theme.textSecondary)
            Text("Not enough history yet")
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textPrimary)
            Text("Add holdings to see performance analytics.")
                .font(.system(size: 13)).foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func loaded(_ report: PerformanceReport) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                metricGrid(report.metrics)
                benchmarkPicker
                if !report.benchmarkCurve.isEmpty {
                    overlayChart(report)
                } else {
                    Text("Benchmark unavailable")
                        .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                }
                diversification(report)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
    }

    // MARK: Metric cards

    private func metricGrid(_ m: PerformanceMetrics) -> some View {
        let columns = [GridItem(.adaptive(minimum: 150), spacing: 12)]
        return LazyVGrid(columns: columns, spacing: 12) {
            MetricCard(title: "Total Return", value: percent(m.totalReturn), positive: m.totalReturn >= 0)
            MetricCard(title: "Annualized", value: percent(m.annualizedReturn), positive: m.annualizedReturn >= 0)
            MetricCard(title: "Volatility", value: percent(m.volatility), positive: nil)
            MetricCard(title: "Max Drawdown", value: percent(m.maxDrawdown), positive: false)
            MetricCard(title: "Sharpe", value: ratio(m.sharpe), positive: (m.sharpe ?? 0) >= 0)
            MetricCard(title: "Beta", value: ratio(m.beta), positive: nil)
            MetricCard(title: "Alpha", value: m.alpha.map { percent($0) } ?? "—", positive: (m.alpha ?? 0) >= 0)
        }
    }

    // MARK: Benchmark

    private var benchmarkPicker: some View {
        Picker("Benchmark", selection: $viewModel.benchmark) {
            ForEach(viewModel.benchmarks, id: \.self) { Text($0).tag($0) }
        }
        .pickerStyle(.segmented)
    }

    private func overlayChart(_ report: PerformanceReport) -> some View {
        let port = rebased(report.equityCurve.map { ($0.date, ($0.value.amount as NSDecimalNumber).doubleValue) })
        let bench = rebased(report.benchmarkCurve.map { ($0.date, ($0.close.amount as NSDecimalNumber).doubleValue) })
        return Chart {
            ForEach(port, id: \.0) { point in
                LineMark(x: .value("Date", point.0), y: .value("Portfolio", point.1),
                         series: .value("Series", "Portfolio"))
                    .foregroundStyle(Theme.gold)
            }
            ForEach(bench, id: \.0) { point in
                LineMark(x: .value("Date", point.0), y: .value(report.benchmarkSymbol, point.1),
                         series: .value("Series", report.benchmarkSymbol))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .frame(height: 200)
        .chartForegroundStyleScale(["Portfolio": Theme.gold, report.benchmarkSymbol: Theme.textSecondary])
    }

    // MARK: Concentration

    private func diversification(_ report: PerformanceReport) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Diversification")
                .font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.textSecondary)
            Text(String(format: "%.1f effective holdings", report.effectiveHoldings))
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
            return "\(label) is \(percent(weight)) of holdings"
        case .assetClass(let kind, let weight):
            return "\(kind.capitalized) is \(percent(weight)) of holdings"
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
