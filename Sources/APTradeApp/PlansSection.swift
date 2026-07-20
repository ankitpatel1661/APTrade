import SwiftUI
import APTradeApplication
import APTradeDomain

/// Colors a Pie's slices in a stable, repeatable order — the gold-family ramp plus
/// silver, dimming a shade further each time the palette wraps so an eight-slice pie
/// still reads as distinct rings rather than repeating identical colors. Never touches
/// `Theme.up`/`Theme.down`: those stay reserved for price-direction data (drift bars),
/// never allocation decoration.
@MainActor
func pieSliceColor(_ index: Int) -> Color {
    let palette: [Color] = [Theme.gold, Theme.silver, Theme.goldDeep, Theme.goldLight, Theme.textSecondary]
    let cycle = index / palette.count
    let opacity = max(0.35, 1.0 - Double(cycle) * 0.25)
    return palette[index % palette.count].opacity(opacity)
}

private let planDueDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US")
    f.dateFormat = "MMM d"
    return f
}()

@MainActor
func cadenceLabel(_ cadence: PieCadence) -> String {
    switch cadence {
    case .weekly: return tr(.cadenceWeekly)
    case .biweekly: return tr(.cadenceBiweekly)
    case .monthly: return tr(.cadenceMonthly)
    }
}

/// The Portfolio tab's Plans section: a card list of the user's investment Pies plus the
/// wizard sheet for creating one. All state lives in `PlansViewModel`/`PieWizardViewModel`;
/// this view (and `PieDetailScreen` below) are declarative only.
struct PlansSection: View {
    @State private var viewModel = CompositionRoot.makePlansViewModel()
    @State private var showWizard = false
    @State private var selectedPieId: String?

    var body: some View {
        content
            .task { await viewModel.onAppear() }
            .navigationDestination(item: $selectedPieId) { id in
                PieDetailScreen(viewModel: viewModel, pieId: id)
            }
            .sheet(isPresented: $showWizard) {
                PieWizardView(existingPie: nil) { Task { await viewModel.onAppear() } }
                    #if os(iOS)
                    .presentationBackground(Theme.surface)
                    #endif
            }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.rows.isEmpty {
            emptyState
        } else {
            list
        }
    }

    private var list: some View {
        ScrollView {
            VStack(alignment: .trailing, spacing: 14) {
                createButton
                LazyVStack(spacing: 10) {
                    ForEach(viewModel.rows) { row in
                        Button { selectedPieId = row.id } label: {
                            PieRowCard(row: row)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
    }

    private var createButton: some View {
        Button { showWizard = true } label: {
            HStack(spacing: 6) {
                Image(systemName: "plus")
                Text(tr(.createPlan))
            }
            .font(.system(size: 13, weight: .bold))
            .foregroundStyle(Theme.bgBottom)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Theme.goldGradient, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "chart.pie.fill")
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(Theme.textTertiary)
            Text(tr(.plansEmptyTitle))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text(tr(.plansEmptyHint))
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            Button { showWizard = true } label: {
                Text(tr(.createPlan))
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Theme.bgBottom)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(Theme.goldGradient, in: Capsule())
            }
            .buttonStyle(.plain)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }
}

/// One list card: mini target-weight donut, name, drift badge, next-contribution line,
/// current value.
private struct PieRowCard: View {
    let row: PlansViewModel.PieRow

    var body: some View {
        HStack(spacing: 14) {
            DonutChart(
                slices: row.sliceWeights.enumerated().map { index, item in
                    DonutSlice(id: item.0, value: (item.1.value as NSDecimalNumber).doubleValue,
                              color: pieSliceColor(index))
                },
                size: 52
            )
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(row.name)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                    if row.maxDriftPP > 5 {
                        driftBadge
                    }
                }
                if let next = row.nextContributionLabel {
                    Text(next)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            Spacer(minLength: 12)
            SuperscriptPrice(money: row.currentValue, size: 18, weight: .semibold)
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 14)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private var driftBadge: some View {
        Text("\(tr(.driftLabel)) \(driftText)")
            .font(.system(size: 9, weight: .bold))
            .tracking(0.4)
            .foregroundStyle(Theme.bgBottom)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Theme.gold, in: Capsule())
    }

    private var driftText: String {
        String(format: "%.1f%%", (row.maxDriftPP as NSDecimalNumber).doubleValue)
    }
}

/// Pushed detail screen for one Pie: donut of target weights, per-slice target/actual/drift
/// rows, the schedule summary, and the contribute/rebalance/edit/delete actions. Reads
/// `viewModel.detail`, populated by `viewModel.openDetail(id:)` — the same shared
/// `PlansViewModel` instance the list uses, so a contribution or rebalance made here is
/// immediately reflected back in the list's rows once this screen is dismissed.
private struct PieDetailScreen: View {
    let viewModel: PlansViewModel
    let pieId: String

    @Environment(\.dismiss) private var dismiss
    @State private var showContribute = false
    @State private var showEditWizard = false
    @State private var showRebalanceSheet = false
    @State private var showDeleteConfirm = false

    var body: some View {
        ScrollView {
            if let detail = viewModel.detail, detail.pieId == pieId {
                detailContent(detail)
            } else {
                ProgressView().padding(60)
            }
        }
        .background(Theme.background.ignoresSafeArea())
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { await viewModel.openDetail(id: pieId) }
        .sheet(isPresented: $showContribute) {
            if let detail = viewModel.detail {
                ContributeSheet(pieName: detail.name) { amount in
                    Task { await viewModel.contributeNow(id: pieId, amount: amount) }
                }
                #if os(iOS)
                .presentationDetents([.medium])
                .presentationBackground(Theme.surface)
                #endif
            }
        }
        .sheet(isPresented: $showEditWizard) {
            if let pie = existingPie {
                PieWizardView(existingPie: pie) { Task { await viewModel.openDetail(id: pieId) } }
                    #if os(iOS)
                    .presentationBackground(Theme.surface)
                    #endif
            }
        }
        .sheet(isPresented: $showRebalanceSheet) {
            RebalanceSheet(
                orders: viewModel.rebalancePreview ?? [],
                onConfirm: {
                    Task {
                        await viewModel.confirmRebalance(id: pieId)
                        showRebalanceSheet = false
                    }
                },
                onCancel: { showRebalanceSheet = false }
            )
            #if os(iOS)
            .presentationBackground(Theme.surface)
            #endif
        }
        .alert(tr(.deletePlan), isPresented: $showDeleteConfirm) {
            Button(tr(.deletePlan), role: .destructive) {
                Task {
                    await viewModel.deletePie(id: pieId)
                    dismiss()
                }
            }
            Button(tr(.cancel), role: .cancel) {}
        } message: {
            Text(tr(.deletePlanConfirm))
        }
    }

    /// The raw domain `Pie` the edit wizard needs (id, ledger, activity — not just the
    /// priced `PieDetail` the rest of this screen reads). Reads the shared pie store
    /// directly, mirroring `TradeSheet`'s identical `CompositionRoot.loadSettings()`
    /// read-through for a value the view models on this screen don't otherwise expose.
    private var existingPie: Pie? {
        CompositionRoot.pieStore.load().first { $0.id == pieId }
    }

    private func detailContent(_ detail: PlansViewModel.PieDetail) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            header(detail)
            if detail.activity.contains(where: { $0.kind == .manualAdjustment }) {
                Text(tr(.manualAdjustmentNote))
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textTertiary)
            }
            if let schedule = detail.schedule {
                scheduleCard(schedule)
            }
            VStack(spacing: 14) {
                ForEach(detail.slices) { slice in
                    sliceRow(slice)
                }
            }
            if let error = viewModel.errorMessage {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.down)
            }
            actionButtons
        }
        .padding(24)
    }

    private func header(_ detail: PlansViewModel.PieDetail) -> some View {
        HStack(alignment: .center, spacing: 20) {
            DonutChart(
                slices: detail.slices.enumerated().map { index, slice in
                    DonutSlice(id: slice.symbol, value: (slice.targetWeight.value as NSDecimalNumber).doubleValue,
                              color: pieSliceColor(index))
                },
                size: 130
            )
            VStack(alignment: .leading, spacing: 6) {
                Text(detail.name)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                SuperscriptPrice(money: detail.totalValue, size: 24, weight: .semibold)
            }
            Spacer()
        }
    }

    private func scheduleCard(_ schedule: ContributionSchedule) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(tr(.scheduleSection).uppercased())
                .font(.system(size: 10, weight: .bold)).tracking(1.4)
                .foregroundStyle(Theme.textTertiary)
            HStack(spacing: 20) {
                StatTile(label: tr(.contributionAmountLabel), value: schedule.amount.formatted)
                StatTile(label: tr(.cadenceLabel), value: cadenceLabel(schedule.cadence))
                StatTile(label: tr(.nextContribution), value: nextDueText(schedule))
            }
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private func nextDueText(_ schedule: ContributionSchedule) -> String {
        guard let date = PieSchedule.date(fromDay: schedule.nextDueDay, calendar: MarketCalendar()) else { return "—" }
        return planDueDateFormatter.string(from: date)
    }

    private func sliceRow(_ slice: PlansViewModel.PieDetail.SliceDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(slice.symbol)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                Text("\(tr(.targetWeightLabel)) \(slice.targetWeight.formatted)")
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                Text("\(tr(.actualWeightLabel)) \(slice.actualWeight.formatted)")
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
            driftBar(slice.drift)
        }
    }

    /// A signed drift bar: green when the slice is over target, red when under — data
    /// color, never the brand accent — scaled by magnitude against a 20pp full-width cap
    /// so ordinary single-digit drifts stay readable rather than pinning instantly.
    private func driftBar(_ drift: Percentage) -> some View {
        let value = (drift.value as NSDecimalNumber).doubleValue
        let color = value >= 0 ? Theme.up : Theme.down
        let fraction = min(abs(value) / 20.0, 1.0)
        return GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.surfaceHi).frame(height: 6)
                Capsule().fill(color).frame(width: max(3, geo.size.width * fraction), height: 6)
            }
        }
        .frame(height: 6)
    }

    private var actionButtons: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                actionButton(tr(.contributeNow), kind: .primary) { showContribute = true }
                actionButton(tr(.rebalanceNow), kind: .secondary) {
                    Task {
                        await viewModel.requestRebalance(id: pieId)
                        showRebalanceSheet = true
                    }
                }
            }
            HStack(spacing: 10) {
                actionButton(tr(.editPlan), kind: .secondary) { showEditWizard = true }
                actionButton(tr(.deletePlan), kind: .destructive) { showDeleteConfirm = true }
            }
        }
    }

    private enum ButtonKind { case primary, secondary, destructive }

    private func actionButton(_ title: String, kind: ButtonKind, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(foreground(kind))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(background(kind), in: Capsule())
                .overlay(
                    Capsule().stroke(kind == .secondary ? Theme.hairline : .clear, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    private func foreground(_ kind: ButtonKind) -> Color {
        switch kind {
        case .primary: return Theme.bgBottom
        case .secondary: return Theme.textPrimary
        case .destructive: return Theme.down
        }
    }

    private func background(_ kind: ButtonKind) -> AnyShapeStyle {
        switch kind {
        case .primary: return AnyShapeStyle(Theme.goldGradient)
        case .secondary: return AnyShapeStyle(Theme.surface)
        case .destructive: return AnyShapeStyle(Theme.down.opacity(0.12))
        }
    }
}

/// Numeric-amount sheet for a one-off contribution to a Pie.
private struct ContributeSheet: View {
    let pieName: String
    let onSubmit: (Money) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var amountText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                Text(String(format: tr(.contributeSheetTitleFormat), pieName))
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                Spacer()
                closeButton
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(tr(.contributionAmountLabel).uppercased())
                    .font(.system(size: 10, weight: .bold)).tracking(1.0)
                    .foregroundStyle(Theme.textTertiary)
                TextField("0", text: $amountText)
                    .textFieldStyle(.plain)
                    .font(.system(size: 28, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
            }
            .padding(16)
            .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            Button {
                guard let amount = Decimal(string: amountText), amount > 0 else { return }
                onSubmit(Money(amount: amount))
                dismiss()
            } label: {
                Text(tr(.contributeNow))
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(canSubmit ? Theme.bgBottom : Theme.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        AnyShapeStyle(canSubmit ? AnyShapeStyle(Theme.goldGradient) : AnyShapeStyle(Theme.surface)),
                        in: Capsule()
                    )
            }
            .buttonStyle(.plain)
            .disabled(!canSubmit)
        }
        .padding(20)
        #if os(iOS)
        .frame(maxWidth: .infinity, alignment: .top)
        .background(Theme.surface)
        #else
        .frame(width: 360)
        .background(Theme.surface)
        #endif
    }

    private var closeButton: some View {
        Button { dismiss() } label: {
            Image(systemName: "xmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Theme.textSecondary)
                .frame(width: 24, height: 24)
                .background(Theme.surfaceHi, in: Circle())
        }
        .buttonStyle(.plain)
    }

    private var canSubmit: Bool {
        guard let value = Decimal(string: amountText) else { return false }
        return value > 0
    }
}

/// Rebalance preview sheet: lists the `RebalanceOrder`s a confirm would place,
/// side-colored, and honors the app's Confirm Trades setting before executing —
/// snapshotting the preference at presentation time, exactly like `TradeSheet`.
private struct RebalanceSheet: View {
    let orders: [RebalanceOrder]
    let onConfirm: () -> Void
    let onCancel: () -> Void

    private let confirmTrades = CompositionRoot.loadSettings().confirmTrades
    @State private var showConfirmDialog = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(tr(.rebalancePreviewTitle))
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                Button { onCancel() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(width: 24, height: 24)
                        .background(Theme.surfaceHi, in: Circle())
                }
                .buttonStyle(.plain)
            }
            .padding(20)

            Divider().overlay(Theme.hairline)

            if orders.isEmpty {
                Text(tr(.rebalanceOrdersEmpty))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .padding(20)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(orders.enumerated()), id: \.offset) { _, order in
                            orderRow(order)
                        }
                    }
                }
                .frame(maxHeight: 280)
            }

            HStack(spacing: 12) {
                Button(tr(.cancel)) { onCancel() }
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Theme.surface, in: Capsule())
                    .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))

                Button(tr(.rebalanceNow)) { attemptConfirm() }
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(orders.isEmpty ? Theme.textTertiary : Theme.bgBottom)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        AnyShapeStyle(orders.isEmpty ? AnyShapeStyle(Theme.surface) : AnyShapeStyle(Theme.goldGradient)),
                        in: Capsule()
                    )
                    .disabled(orders.isEmpty)
            }
            .padding(20)
        }
        #if os(iOS)
        .frame(maxWidth: .infinity, alignment: .top)
        .background(Theme.surface)
        #else
        .frame(width: 420)
        .background(Theme.surface)
        #endif
        .confirmationDialog(tr(.confirmRebalanceTitle), isPresented: $showConfirmDialog, titleVisibility: .visible) {
            Button(tr(.rebalanceNow)) { onConfirm() }
            Button(tr(.cancel), role: .cancel) {}
        } message: {
            Text(String(format: tr(.confirmRebalanceMessageFormat), orders.count))
        }
    }

    private func attemptConfirm() {
        if confirmTrades { showConfirmDialog = true } else { onConfirm() }
    }

    private func orderRow(_ order: RebalanceOrder) -> some View {
        HStack(spacing: 14) {
            Text(order.side == .buy ? tr(.buyChip) : tr(.sellChip))
                .font(.system(size: 10, weight: .bold)).tracking(0.8)
                .foregroundStyle(sideColor(order.side))
                .frame(width: 44, height: 22)
                .background(sideColor(order.side).opacity(0.12), in: Capsule())
            Text(order.symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Spacer()
            Text(order.amount.formatted)
                .font(.system(size: 14, weight: .semibold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
    }

    private func sideColor(_ side: RebalanceSide) -> Color {
        side == .buy ? Theme.up : Theme.down
    }
}
