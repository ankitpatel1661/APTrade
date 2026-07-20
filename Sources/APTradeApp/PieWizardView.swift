import SwiftUI
import Charts
import APTradeApplication
import APTradeDomain

/// Four-step Pie creation/edit wizard: name → slice allocation → contribution schedule →
/// DCA backtest preview. All validation and persistence live in `PieWizardViewModel`;
/// this view only renders its published state and forwards user input.
struct PieWizardView: View {
    private enum Step: Int, CaseIterable {
        case name, slices, schedule, backtest
    }

    @State private var viewModel: PieWizardViewModel
    @State private var step: Step = .name
    @State private var searchQuery = ""
    @State private var searchResults: [Asset] = []
    @State private var backtestYears = 1
    @Environment(\.dismiss) private var dismiss

    private let isEditing: Bool
    let onSave: () -> Void

    init(existingPie: Pie?, onSave: @escaping () -> Void) {
        _viewModel = State(initialValue: CompositionRoot.makePieWizardViewModel(existingPie: existingPie))
        self.isEditing = existingPie != nil
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    stepIndicator
                    Divider().overlay(Theme.hairline)
                    ScrollView {
                        stepContent
                    }
                    Divider().overlay(Theme.hairline)
                    navigationBar
                }
            }
            .navigationTitle(isEditing ? tr(.editPlan) : tr(.createPlan))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(tr(.cancel)) { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(width: 480, height: 600)
        #endif
    }

    // MARK: - Step indicator

    private var stepIndicator: some View {
        HStack(spacing: 0) {
            stepLabel(.name, tr(.name))
            stepLabel(.slices, tr(.stepSlicesTitle))
            stepLabel(.schedule, tr(.stepScheduleTitle))
            stepLabel(.backtest, tr(.backtestTitle))
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    private func stepLabel(_ target: Step, _ title: String) -> some View {
        let selected = step == target
        return VStack(spacing: 6) {
            Text(title)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(selected ? Theme.gold : Theme.textSecondary)
            Capsule().fill(selected ? Theme.gold : Theme.hairline).frame(height: 2)
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var stepContent: some View {
        switch step {
        case .name: nameStep
        case .slices: slicesStep
        case .schedule: scheduleStep
        case .backtest: backtestStep
        }
    }

    // MARK: - Step 1: Name

    private var nameStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(tr(.pieNameLabel).uppercased())
                .font(.system(size: 10, weight: .bold)).tracking(1.0)
                .foregroundStyle(Theme.textTertiary)
            TextField(tr(.pieNamePlaceholder), text: $viewModel.name)
                .textFieldStyle(.plain)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .padding(16)
                .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .padding(24)
    }

    // MARK: - Step 2: Slices

    private var slicesStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            searchField
            if !searchResults.isEmpty {
                searchResultsList
            }
            HStack {
                Text(tr(.sliceWeights).uppercased())
                    .font(.system(size: 10, weight: .bold)).tracking(1.0)
                    .foregroundStyle(Theme.textTertiary)
                Spacer()
                Button(tr(.equalSplit)) { viewModel.equalSplit() }
                    .buttonStyle(.plain)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Theme.gold)
                    .disabled(viewModel.slices.isEmpty)
            }
            if viewModel.slices.isEmpty {
                Text(tr(.noSlicesYetHint))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            } else {
                VStack(spacing: 10) {
                    ForEach(viewModel.slices, id: \.symbol) { slice in
                        sliceEditorRow(slice)
                    }
                }
            }
            weightSumFooter
        }
        .padding(24)
        .task(id: searchQuery) { await runSearch() }
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(Theme.textTertiary)
            TextField(tr(.searchAssetsToAddPlaceholder), text: $searchQuery)
                .textFieldStyle(.plain)
                .font(.system(size: 14))
                .foregroundStyle(Theme.textPrimary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .background(Theme.surface, in: Capsule())
        .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
    }

    private var searchResultsList: some View {
        VStack(spacing: 0) {
            ForEach(searchResults, id: \.symbol) { asset in
                Button {
                    viewModel.addSlice(asset: asset)
                    searchQuery = ""
                    searchResults = []
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(asset.name)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Theme.textPrimary)
                                .lineLimit(1)
                            Text(asset.symbol)
                                .font(.system(size: 11, weight: .medium).monospacedDigit())
                                .foregroundStyle(Theme.textSecondary)
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                }
                .buttonStyle(.plain)
            }
        }
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private func sliceEditorRow(_ slice: PieSlice) -> some View {
        HStack(spacing: 12) {
            Text(slice.symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Spacer()
            Stepper(value: weightBinding(for: slice), in: 0...100, step: 1) {
                Text("\(Int(weightBinding(for: slice).wrappedValue))%")
                    .font(.system(size: 14, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                    .frame(width: 50, alignment: .trailing)
            }
            .fixedSize()
            Button { viewModel.removeSlice(symbol: slice.symbol) } label: {
                Image(systemName: "trash")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textTertiary)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func weightBinding(for slice: PieSlice) -> Binding<Double> {
        Binding(
            get: { (slice.targetWeight.value as NSDecimalNumber).doubleValue },
            set: { viewModel.setWeight(symbol: slice.symbol, pp: Decimal($0)) }
        )
    }

    private var weightSumFooter: some View {
        HStack {
            Text(tr(.weightSumLabel))
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
            Spacer()
            Text("\(Int((viewModel.weightSumPP as NSDecimalNumber).doubleValue))%")
                .font(.system(size: 14, weight: .bold).monospacedDigit())
                .foregroundStyle(viewModel.weightSumPP == 100 ? Theme.up : Theme.down)
        }
    }

    /// Debounced (250ms) asset search reusing the same `SearchAssetsUseCase` the command
    /// palette and watchlist search use — `.task(id: searchQuery)` cancels the previous
    /// in-flight search automatically whenever the query text changes.
    private func runSearch() async {
        let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            searchResults = []
            return
        }
        try? await Task.sleep(nanoseconds: 250_000_000)
        guard !Task.isCancelled else { return }
        let results = (try? await CompositionRoot.makeSearchAssetsUseCase()(query: query)) ?? []
        guard !Task.isCancelled else { return }
        searchResults = results.filter { asset in !viewModel.slices.contains { $0.symbol == asset.symbol } }
    }

    // MARK: - Step 3: Schedule

    private var scheduleStep: some View {
        VStack(alignment: .leading, spacing: 20) {
            Toggle(tr(.recurringContributionToggle), isOn: $viewModel.scheduleEnabled)
                .tint(Theme.gold)
                .foregroundStyle(Theme.textPrimary)

            if viewModel.scheduleEnabled {
                VStack(alignment: .leading, spacing: 4) {
                    Text(tr(.contributionAmountLabel).uppercased())
                        .font(.system(size: 10, weight: .bold)).tracking(1.0)
                        .foregroundStyle(Theme.textTertiary)
                    TextField("0", text: $viewModel.scheduleAmountText)
                        .textFieldStyle(.plain)
                        .font(.system(size: 22, weight: .semibold).monospacedDigit())
                        .foregroundStyle(Theme.textPrimary)
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                }
                .padding(16)
                .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

                VStack(alignment: .leading, spacing: 6) {
                    Text(tr(.cadenceLabel).uppercased())
                        .font(.system(size: 10, weight: .bold)).tracking(1.0)
                        .foregroundStyle(Theme.textTertiary)
                    Picker(tr(.cadenceLabel), selection: $viewModel.cadence) {
                        ForEach(PieCadence.allCases, id: \.self) { cadence in
                            Text(cadenceLabel(cadence)).tag(cadence)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
            }
        }
        .padding(24)
    }

    // MARK: - Step 4: Backtest

    private var backtestStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Picker("", selection: $backtestYears) {
                    Text("1Y").tag(1)
                    Text("3Y").tag(3)
                    Text("5Y").tag(5)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                Button(tr(.runBacktest)) { Task { await viewModel.runBacktest(years: backtestYears) } }
                    .buttonStyle(.plain)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Theme.gold)
            }

            if let report = viewModel.backtest {
                backtestChart(report)
                HStack(spacing: 16) {
                    StatTile(label: tr(.backtestInvested), value: report.totalInvested.formatted)
                    StatTile(label: tr(.backtestValue), value: report.finalValue.formatted,
                            valueColor: report.finalValue.amount >= report.totalInvested.amount ? Theme.up : Theme.down)
                    StatTile(label: tr(.totalReturn), value: report.totalReturn.formatted,
                            valueColor: report.totalReturn.isNegative ? Theme.down : Theme.up)
                }
                Text("\(tr(.backtestLumpSum)): \(report.lumpSumFinalValue.formatted)")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textTertiary)
            } else {
                Text(tr(.backtestInsufficient))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(24)
        .task(id: backtestYears) { await viewModel.runBacktest(years: backtestYears) }
    }

    private func backtestChart(_ report: BacktestReport) -> some View {
        let investedLabel = tr(.backtestInvested)
        let valueLabel = tr(.backtestValue)
        return Chart {
            ForEach(report.points, id: \.day) { point in
                LineMark(x: .value("Day", point.day),
                         y: .value(investedLabel, (point.invested.amount as NSDecimalNumber).doubleValue),
                         series: .value("Series", investedLabel))
                    .foregroundStyle(Theme.textSecondary)
            }
            ForEach(report.points, id: \.day) { point in
                LineMark(x: .value("Day", point.day),
                         y: .value(valueLabel, (point.value.amount as NSDecimalNumber).doubleValue),
                         series: .value("Series", valueLabel))
                    .foregroundStyle(Theme.gold)
            }
        }
        .chartXAxis(.hidden)
        .frame(height: 180)
        .chartForegroundStyleScale([investedLabel: Theme.textSecondary, valueLabel: Theme.gold])
    }

    // MARK: - Footer navigation

    private var navigationBar: some View {
        HStack(spacing: 12) {
            if step != .name {
                Button(tr(.back)) { goBack() }
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Theme.surface, in: Capsule())
                    .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
            }
            if step == .backtest {
                Button(tr(.saveAction)) { save() }
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(viewModel.canSave ? Theme.bgBottom : Theme.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        AnyShapeStyle(viewModel.canSave ? AnyShapeStyle(Theme.goldGradient) : AnyShapeStyle(Theme.surface)),
                        in: Capsule()
                    )
                    .disabled(!viewModel.canSave)
            } else {
                Button(tr(.next)) { goNext() }
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(canAdvance ? Theme.bgBottom : Theme.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        AnyShapeStyle(canAdvance ? AnyShapeStyle(Theme.goldGradient) : AnyShapeStyle(Theme.surface)),
                        in: Capsule()
                    )
                    .disabled(!canAdvance)
            }
        }
        .padding(20)
    }

    /// Per-step gate for the "Next" button. Full 100%-weight validation is `canSave`'s
    /// job (enforced only at Save, with the live red/green sum label as the in-step
    /// hint) — this only blocks advancing past a step that's structurally incomplete.
    private var canAdvance: Bool {
        switch step {
        case .name: return !viewModel.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .slices: return !viewModel.slices.isEmpty
        case .schedule, .backtest: return true
        }
    }

    private func goNext() {
        guard let next = Step(rawValue: step.rawValue + 1) else { return }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) { step = next }
    }

    private func goBack() {
        guard let previous = Step(rawValue: step.rawValue - 1) else { return }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) { step = previous }
    }

    private func save() {
        Task {
            if await viewModel.save() {
                onSave()
                dismiss()
            }
        }
    }
}
