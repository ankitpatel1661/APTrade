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
            GoalEditSheet(title: title, targetText: $targetText, onSave: onSet)
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
        case .beyondHorizon: return tr(.goalBeyondHorizon)
        case let .years(y):
            let rounded = y < 10 ? String(format: "%.1f", y) : String(Int(y.rounded()))
            return String(format: tr(.goalYearsFormat), rounded)
        }
    }
}

/// Goal-target amount entry. Reuses `StartingBalanceInput`'s range and locale-aware
/// parser (Task 4) rather than inventing a second validator — the `$1,000–$10,000,000`
/// range reads sensibly for a goal target too, and the copy itself never says "starting
/// balance". Mirrors `ResetPortfolioSheet`'s plain (non-`Theme`-styled) modal chrome —
/// sheets in this app use system styling; `Theme` is for the persistent card surfaces.
struct GoalEditSheet: View {
    let title: String
    @Binding var targetText: String
    let onSave: (Money) -> Void
    @Environment(\.dismiss) private var dismiss

    private var parsed: Money? { StartingBalanceInput.parse(targetText) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.title3.weight(.semibold))
            VStack(alignment: .leading, spacing: 6) {
                Text(tr(.goalTarget))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                TextField("", text: $targetText)
                    .textFieldStyle(.roundedBorder)
                Text(tr(.startingBalanceRange))
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
