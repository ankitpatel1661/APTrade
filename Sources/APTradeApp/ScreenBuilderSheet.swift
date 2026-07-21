import SwiftUI
import APTradeApplication
import APTradeDomain

// MARK: - ScreenBuilderModel

/// Local editing state for the custom screen builder sheet: a name, an ordered list of
/// draft conditions, and the validation that gates Save. Deliberately store-free — it
/// only shapes a `CustomScreen` in memory; `ScreenerViewModel.saveScreen`/`deleteScreen`
/// own the actual persistence, and `ScreenerView` wires this model to those calls.
@MainActor
@Observable
public final class ScreenBuilderModel {

    /// One condition row's editable state. `thresholdText` is free-form user input —
    /// intentionally NOT `Double` — so a row can sit in an unparseable, mid-typing state
    /// (e.g. "-", "") without discarding what the user has typed so far; validation and
    /// parsing happen at `isValid`/`buildScreen()`, not on every keystroke.
    public struct ConditionDraft: Identifiable, Equatable {
        public let id: String
        public var metric: ScreenerMetric
        public var comparison: ScreenComparison
        public var thresholdText: String

        public init(
            id: String = UUID().uuidString,
            metric: ScreenerMetric = .price,
            comparison: ScreenComparison = .above,
            thresholdText: String = ""
        ) {
            self.id = id
            self.metric = metric
            self.comparison = comparison
            self.thresholdText = thresholdText
        }
    }

    public var name: String
    public var conditions: [ConditionDraft]

    /// The id a saved `CustomScreen` will carry: the original screen's id when editing,
    /// or a freshly generated one for a brand-new screen — decided once at `init` so it
    /// stays stable across edits within one sheet session.
    public let screenId: String
    public let isEditing: Bool

    /// `existingScreen` nil creates a fresh screen (one blank condition row to start);
    /// non-nil pre-fills every field from the screen being edited.
    public init(existingScreen: CustomScreen? = nil) {
        if let existingScreen {
            self.screenId = existingScreen.id
            self.name = existingScreen.name
            self.conditions = existingScreen.conditions.map {
                ConditionDraft(metric: $0.metric, comparison: $0.comparison,
                               thresholdText: Self.text(for: $0.threshold))
            }
            self.isEditing = true
        } else {
            self.screenId = UUID().uuidString
            self.name = ""
            self.conditions = [ConditionDraft()]
            self.isEditing = false
        }
    }

    /// Non-empty trimmed name, at least one condition row, and every row's threshold a
    /// parseable number — the exact three rules Task 8's brief calls out.
    public var isValid: Bool {
        !trimmedName.isEmpty && !conditions.isEmpty
            && conditions.allSatisfy { Self.parsedThreshold($0.thresholdText) != nil }
    }

    public func addCondition() {
        conditions.append(ConditionDraft())
    }

    public func removeCondition(id: String) {
        conditions.removeAll { $0.id == id }
    }

    /// The `CustomScreen` this draft represents, or `nil` while `isValid` is false —
    /// callers (the sheet's Save button) should treat a `nil` result as "can't save yet"
    /// rather than force-unwrap.
    public func buildScreen() -> CustomScreen? {
        guard isValid else { return nil }
        let built = conditions.compactMap { draft -> ScreenCondition? in
            guard let threshold = Self.parsedThreshold(draft.thresholdText) else { return nil }
            return ScreenCondition(metric: draft.metric, comparison: draft.comparison, threshold: threshold)
        }
        return CustomScreen(id: screenId, name: trimmedName, conditions: built)
    }

    /// Best-effort conditions for the sheet's LIVE match-count preview: rows with a
    /// still-being-typed (unparseable) threshold are skipped rather than blocking the
    /// whole preview — unlike `buildScreen()`, which requires every row valid before it
    /// will produce anything at all. A user midway through typing a second condition's
    /// threshold still sees a live count reflecting the rows that already parse.
    public var matchableConditions: [ScreenCondition] {
        conditions.compactMap { draft in
            guard let threshold = Self.parsedThreshold(draft.thresholdText) else { return nil }
            return ScreenCondition(metric: draft.metric, comparison: draft.comparison, threshold: threshold)
        }
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Parses a threshold field's raw text as a `Double`. Normalizes a comma decimal
    /// separator to a dot BEFORE parsing: the iOS decimal pad inserts the locale's own
    /// separator, and this app ships DE/IT/ES, all of which use "," (e.g. "1,5") where
    /// `Double.init(String)` only ever accepts ".". This is a simple, predictable
    /// normalization (not a full `NumberFormatter`/locale-aware parse) — good enough for
    /// a plain decimal threshold, and it correctly still rejects genuine garbage like
    /// "1,5.2" (becomes "1.5.2", which `Double.init` fails on either way).
    private static func parsedThreshold(_ text: String) -> Double? {
        let normalized = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        return Double(normalized)
    }

    /// Pre-fill text for an existing condition's threshold — whole numbers render without
    /// a trailing ".0" (e.g. `30` not `30.0`), matching how a user would actually type it.
    private static func text(for threshold: Double) -> String {
        threshold == threshold.rounded() ? String(Int(threshold)) : String(threshold)
    }
}

// MARK: - Metric labels

/// Every `ScreenerMetric` case's builder-picker label. Distinct from `ScreenerView`'s
/// `activeMetricColumn(for:)` mapping, which deliberately returns `nil` for `.price`/
/// `.dayChangePercent` (those already have dedicated result-table columns) — the builder's
/// metric picker has no such exclusion, since every metric is a valid thing to condition
/// a screen on.
private func screenerMetricLabelKey(_ metric: ScreenerMetric) -> L10n.Key {
    switch metric {
    case .price: return .metricPrice
    case .dayChangePercent: return .metricDayChange
    case .rsi14: return .metricRsi
    case .bollingerPercentB: return .metricPercentB
    case .bollingerBandwidth: return .metricBandwidth
    case .pctTo52wHigh: return .metricTo52wHigh
    case .pctTo52wLow: return .metricTo52wLow
    case .relativeVolume: return .metricRelVolume
    case .pctVsSma50: return .metricVsSma50
    case .pctVsSma200: return .metricVsSma200
    }
}

// MARK: - ScreenBuilderSheet

/// Create/edit sheet for a custom screen: name, an editable list of AND-combined
/// conditions, a live match count against the current snapshot, and Save / (when editing)
/// Delete. Follows `PieWizardView`'s sheet idioms (`.sheet` container, `Theme` styling,
/// `NavigationStack` + toolbar Cancel) — this feature just doesn't need PieWizard's
/// multi-step structure, so it's a single scrollable form.
struct ScreenBuilderSheet: View {
    @State private var model: ScreenBuilderModel
    @Environment(\.dismiss) private var dismiss

    /// Live match count for the sheet's current (possibly still-invalid) condition set —
    /// routes straight to `ScreenerViewModel.matchCount(for:)` via the caller.
    let matchCount: ([ScreenCondition]) -> Int
    let onSave: (CustomScreen) -> Void
    /// `nil` for a brand-new screen — the Delete button and its confirmation only appear
    /// when this is non-nil (i.e. editing an existing saved screen).
    let onDelete: (() -> Void)?

    @State private var showDeleteConfirm = false

    init(
        existingScreen: CustomScreen?,
        matchCount: @escaping ([ScreenCondition]) -> Int,
        onSave: @escaping (CustomScreen) -> Void,
        onDelete: (() -> Void)?
    ) {
        _model = State(initialValue: ScreenBuilderModel(existingScreen: existingScreen))
        self.matchCount = matchCount
        self.onSave = onSave
        self.onDelete = onDelete
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 20) {
                            nameField
                            conditionsSection
                            matchCountFooter
                        }
                        .padding(24)
                    }
                    Divider().overlay(Theme.hairline)
                    actions
                }
            }
            .navigationTitle(tr(model.isEditing ? .screenerEditScreen : .screenerNewScreen))
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
        .frame(width: 460, height: 600)
        #endif
        .confirmationDialog(tr(.screenerDeleteScreen), isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button(tr(.screenerDeleteScreen), role: .destructive) {
                onDelete?()
                dismiss()
            }
            Button(tr(.cancel), role: .cancel) {}
        }
    }

    // MARK: Name

    private var nameField: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(tr(.screenerScreenName).uppercased())
                .font(.system(size: 10, weight: .bold)).tracking(1.0)
                .foregroundStyle(Theme.textTertiary)
            TextField(tr(.screenerScreenName), text: $model.name)
                .textFieldStyle(.plain)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .padding(14)
                .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }

    // MARK: Conditions

    private var conditionsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(model.conditions) { draft in
                conditionRow(draft)
            }
            addConditionButton
        }
    }

    private func conditionRow(_ draft: ScreenBuilderModel.ConditionDraft) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Picker("", selection: metricBinding(for: draft.id)) {
                    ForEach(ScreenerMetric.allCases, id: \.self) { metric in
                        Text(tr(screenerMetricLabelKey(metric))).tag(metric)
                    }
                }
                .labelsHidden()
                Spacer(minLength: 8)
                // Removing the last remaining row would leave the builder with zero
                // conditions — always invalid — so the trash action only appears once a
                // second row exists to fall back to.
                if model.conditions.count > 1 {
                    Button {
                        model.removeCondition(id: draft.id)
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 12))
                            .foregroundStyle(Theme.textTertiary)
                    }
                    .buttonStyle(.plain)
                }
            }
            HStack(spacing: 10) {
                Picker("", selection: comparisonBinding(for: draft.id)) {
                    Text(tr(.screenerAbove)).tag(ScreenComparison.above)
                    Text(tr(.screenerBelow)).tag(ScreenComparison.below)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: 150)
                TextField("0", text: thresholdBinding(for: draft.id))
                    .textFieldStyle(.plain)
                    .font(.system(size: 14, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
        }
        .padding(14)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private var addConditionButton: some View {
        Button {
            model.addCondition()
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "plus")
                Text(tr(.screenerAddCondition))
            }
            .font(.system(size: 13, weight: .bold))
            .foregroundStyle(Theme.gold)
        }
        .buttonStyle(.plain)
    }

    /// Per-row bindings keyed by the draft's stable `id` rather than array index — an
    /// index would shift out from under an in-flight edit the moment a row is added or
    /// removed elsewhere in the list.
    private func metricBinding(for id: String) -> Binding<ScreenerMetric> {
        Binding(
            get: { model.conditions.first { $0.id == id }?.metric ?? .price },
            set: { newValue in
                guard let index = model.conditions.firstIndex(where: { $0.id == id }) else { return }
                model.conditions[index].metric = newValue
            }
        )
    }

    private func comparisonBinding(for id: String) -> Binding<ScreenComparison> {
        Binding(
            get: { model.conditions.first { $0.id == id }?.comparison ?? .above },
            set: { newValue in
                guard let index = model.conditions.firstIndex(where: { $0.id == id }) else { return }
                model.conditions[index].comparison = newValue
            }
        )
    }

    private func thresholdBinding(for id: String) -> Binding<String> {
        Binding(
            get: { model.conditions.first { $0.id == id }?.thresholdText ?? "" },
            set: { newValue in
                guard let index = model.conditions.firstIndex(where: { $0.id == id }) else { return }
                model.conditions[index].thresholdText = newValue
            }
        )
    }

    // MARK: Match count

    private var matchCountFooter: some View {
        Text(String(format: tr(.screenerMatchCountFmt), "\(matchCount(model.matchableConditions))"))
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Theme.textSecondary)
    }

    // MARK: Actions

    private var actions: some View {
        VStack(spacing: 10) {
            Button(tr(.screenerSaveScreen)) { save() }
                .buttonStyle(.plain)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(model.isValid ? Theme.bgBottom : Theme.textTertiary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    AnyShapeStyle(model.isValid ? AnyShapeStyle(Theme.goldGradient) : AnyShapeStyle(Theme.surface)),
                    in: Capsule()
                )
                .disabled(!model.isValid)

            if model.isEditing, onDelete != nil {
                Button(tr(.screenerDeleteScreen)) { showDeleteConfirm = true }
                    .buttonStyle(.plain)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Theme.down)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Theme.down.opacity(0.12), in: Capsule())
            }
        }
        .padding(20)
    }

    private func save() {
        guard let screen = model.buildScreen() else { return }
        onSave(screen)
        dismiss()
    }
}
