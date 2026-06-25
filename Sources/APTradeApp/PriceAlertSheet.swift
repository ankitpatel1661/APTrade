import SwiftUI
import APTradeDomain

/// Lets the user arm a price alert on one symbol — an absolute price threshold or a
/// daily percentage move — and review/remove the ones already set.
struct PriceAlertSheet: View {
    let asset: Asset
    let currentPrice: Money?
    let existing: [PriceAlert]
    let onCreate: (AlertCondition) -> Void
    let onDelete: (PriceAlert.ID) -> Void

    private enum Kind: String, CaseIterable { case above = "Price above", below = "Price below", percent = "% move" }

    @Environment(\.dismiss) private var dismiss
    @State private var kind: Kind = .below
    @State private var priceText = ""
    @State private var percentText = "5"

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            if !existing.isEmpty {
                Divider().overlay(Theme.hairline)
                existingAlerts
            }

            Divider().overlay(Theme.hairline)

            VStack(alignment: .leading, spacing: 16) {
                if let currentPrice {
                    Text("Current price: \(currentPrice.formatted)")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                }

                Picker("", selection: $kind) {
                    ForEach(Kind.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()

                Group {
                    switch kind {
                    case .above, .below:
                        labeledField(label: "Target price ($)", text: $priceText)
                    case .percent:
                        labeledField(label: "Daily move (%)", text: $percentText)
                    }
                }

                Button(action: create) {
                    Text("Add Alert")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Theme.bgBottom)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Theme.goldGradient, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(!isValid)
            }
            .padding(20)
        }
        .frame(width: 360)
        .background(Theme.surface)
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(asset.name)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                Text(asset.symbol)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
            }
            Spacer()
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 24, height: 24)
                    .background(Theme.surfaceHi, in: Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 14)
    }

    private var existingAlerts: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(existing) { alert in
                HStack {
                    Image(systemName: alert.isTriggered ? "bell.slash" : "bell.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(alert.isTriggered ? Theme.textTertiary : Theme.gold)
                    Text(alert.condition.summary)
                        .font(.system(size: 13))
                        .foregroundStyle(alert.isTriggered ? Theme.textTertiary : Theme.textPrimary)
                        .strikethrough(alert.isTriggered)
                    Spacer()
                    Button { onDelete(alert.id) } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 11))
                            .foregroundStyle(Theme.textTertiary)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 8)
            }
        }
    }

    private func labeledField(label: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .bold))
                .tracking(1.0)
                .foregroundStyle(Theme.textTertiary)
            TextField("", text: text)
                .textFieldStyle(.plain)
                .font(.system(size: 16, weight: .medium).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 8))
        }
    }

    private var isValid: Bool {
        switch kind {
        case .above, .below: return Decimal(string: priceText) != nil
        case .percent: return Decimal(string: percentText) != nil
        }
    }

    private func create() {
        switch kind {
        case .above:
            guard let value = Decimal(string: priceText) else { return }
            onCreate(.priceAbove(Money(amount: value)))
        case .below:
            guard let value = Decimal(string: priceText) else { return }
            onCreate(.priceBelow(Money(amount: value)))
        case .percent:
            guard let value = Decimal(string: percentText) else { return }
            onCreate(.percentChange(Percentage(value: value)))
        }
        dismiss()
    }
}
