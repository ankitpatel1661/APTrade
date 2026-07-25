import SwiftUI
import APTradeDomain

/// Progress + honest projection for one portfolio goal. Shared by the Income section
/// (income goal) and the Performance section (value goal, Task 13) — same card, same
/// editing sheet, different `title`/`current`/`goal`/`projection` inputs. Mirrors the
/// surrounding cards' chrome (`Theme.surface` fill, hairline stroke, rounded 16pt corners)
/// rather than inventing a new visual language.
///
/// An unset goal renders a quiet "Set a goal" affordance instead of an empty card. Once
/// set, `projection` is rendered via `projectionText`, which switches on every
/// `GoalProjection` case explicitly so an honest "not on track" or "needs more history"
/// reading is never collapsed into silence or a fabricated ETA.
struct GoalCard: View {
    let title: String
    /// Which quantity this card measures — selects the target-entry range/hint (Task 5
    /// fix-round: an income goal and a value goal are wildly different magnitudes, so
    /// validating both against the starting-balance range made small income targets and
    /// large value targets alike unsettable).
    let kind: GoalKind
    let current: Money
    let goal: PortfolioGoal?
    let projection: GoalProjection?
    let onSet: (Money) -> Void
    let onRemove: () -> Void

    @State private var isEditing = false
    @State private var targetText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            if let goal {
                progressContent(goal)
            } else {
                Button(tr(.setGoal)) { beginEditing() }
                    .buttonStyle(.plain)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.gold)
            }
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .stroke(Theme.hairline, lineWidth: 1))
        .sheet(isPresented: $isEditing) {
            GoalEditSheet(title: title, kind: kind, targetText: $targetText, onSave: onSet)
        }
    }

    private var header: some View {
        HStack {
            Text(title)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
            Spacer()
            if goal != nil {
                Menu {
                    Button(tr(.editGoal), systemImage: "pencil") { beginEditing() }
                    Button(tr(.removeGoal), systemImage: "trash", role: .destructive) { onRemove() }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.textSecondary)
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
            }
        }
    }

    private func progressContent(_ goal: PortfolioGoal) -> some View {
        let fraction = GoalMath.progress(current: current, target: goal.target)
        return VStack(alignment: .leading, spacing: 10) {
            ProgressView(value: min(fraction, 1.0))
                .tint(Theme.gold)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(current.formatted)
                    .font(.system(size: 14, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.textPrimary)
                Text("/")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.textTertiary)
                Text(goal.target.formatted)
                    .font(.system(size: 13, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                Text("\(Int((fraction * 100).rounded()))%")
                    .font(.system(size: 13, weight: .bold).monospacedDigit())
                    .foregroundStyle(Theme.gold)
            }
            Text(Self.projectionText(projection))
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Theme.textTertiary)
        }
    }

    private func beginEditing() {
        targetText = goal.map { NSDecimalNumber(decimal: $0.target.amount).stringValue } ?? ""
        isEditing = true
    }

    /// Renders every `GoalProjection` case (plus "not yet computed") as its own honest
    /// sentence — never collapsed into a generic "on track"/"off track" binary.
    static func projectionText(_ projection: GoalProjection?) -> String {
        switch projection {
        case .reached: return tr(.goalReached)
        case .notOnTrack: return tr(.goalNotOnTrack)
        case .insufficientHistory, .none: return tr(.goalNeedsHistory)
        case .beyondHorizon:
            // Reads `GoalMath.horizonYears` rather than baking "30" into the copy, so the
            // string can't silently go stale if the constant ever moves.
            return String(format: tr(.goalBeyondHorizon), String(Int(GoalMath.horizonYears)))
        case let .years(y):
            let rounded = y < 10 ? String(format: "%.1f", y) : String(Int(y.rounded()))
            return String(format: tr(.goalYearsFormat), rounded)
        }
    }
}

/// Goal-target amount entry. Reuses `StartingBalanceInput`'s locale-aware parser (Task 4)
/// rather than inventing a second validator, but validates against `kind.targetRange`
/// (Task 5 fix-round) rather than the starting-balance range — an income-goal target and
/// a value-goal target are different quantities at different scales, so the field's hint
/// must describe the range it's actually enforcing. Mirrors `ResetPortfolioSheet`'s plain
/// (non-`Theme`-styled) modal chrome — sheets in this app use system styling; `Theme` is
/// for the persistent card surfaces.
struct GoalEditSheet: View {
    let title: String
    let kind: GoalKind
    @Binding var targetText: String
    let onSave: (Money) -> Void
    @Environment(\.dismiss) private var dismiss

    private var parsed: Money? { StartingBalanceInput.parse(targetText, range: kind.targetRange) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.title3.weight(.semibold))
            VStack(alignment: .leading, spacing: 6) {
                Text(tr(.goalTarget))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                TextField("", text: $targetText)
                    .textFieldStyle(.roundedBorder)
                Text(tr(kind.rangeHintKey))
                    .font(.caption2)
                    .foregroundStyle(parsed == nil && !targetText.isEmpty ? Color.red : .secondary)
            }
            HStack {
                Spacer()
                Button(tr(.cancel)) { dismiss() }
                Button(tr(.saveAction)) {
                    guard let amount = parsed else { return }
                    onSave(amount)
                    dismiss()
                }
                .disabled(parsed == nil)
            }
        }
        .padding(20)
        .frame(minWidth: 320)
    }
}

/// Per-kind goal-target validation range (Task 5 fix-round). `StartingBalanceInput`'s
/// `$1,000–$10,000,000` range describes a portfolio's OPENING CASH — reusing it for both
/// goal kinds made an ordinary income goal like "$50/month in dividends" ($600/yr, well
/// under $1,000) unsettable, and capped a value goal at $10M even though a value goal is
/// meant to run far past a starting balance. Each kind gets a range sized to what it
/// actually measures, plus its own hint copy so the field never describes the wrong
/// quantity.
extension GoalKind {
    var targetRange: ClosedRange<Decimal> {
        switch self {
        case .income: return Decimal(100)...Decimal(1_000_000)
        case .value: return Decimal(1_000)...Decimal(100_000_000)
        }
    }

    var rangeHintKey: L10n.Key {
        switch self {
        case .income: return .incomeGoalRange
        case .value: return .valueGoalRange
        }
    }
}
