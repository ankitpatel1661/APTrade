import SwiftUI
import APTradeDomain

/// Destructive reset flow with a validated starting-balance field.
struct ResetPortfolioSheet: View {
    @Binding var amountText: String
    let onConfirm: (Money) -> Void
    @Environment(\.dismiss) private var dismiss

    private var parsed: Money? { StartingBalanceInput.parse(amountText) }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(tr(.resetPortfolioTitle))
                .font(.title3.weight(.semibold))
            Text(tr(.resetPortfolioBody))
                .font(.callout)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 6) {
                Text(tr(.startingBalance))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                TextField("", text: $amountText)
                    .textFieldStyle(.roundedBorder)
                Text(tr(.startingBalanceRange))
                    .font(.caption2)
                    .foregroundStyle(parsed == nil && !amountText.isEmpty ? Color.red : .secondary)
            }

            HStack {
                Spacer()
                Button(tr(.cancel)) { dismiss() }
                Button(tr(.reset), role: .destructive) {
                    guard let amount = parsed else { return }
                    onConfirm(amount)
                    dismiss()
                }
                .disabled(parsed == nil)
            }
        }
        .padding(20)
        .frame(minWidth: 320)
    }
}
