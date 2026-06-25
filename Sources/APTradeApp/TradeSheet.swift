import SwiftUI
import APTradeDomain

struct TradeSheet: View {
    @State private var viewModel: TradeViewModel
    @State private var showConfirm = false
    @Environment(\.dismiss) private var dismiss
    let onComplete: () -> Void

    /// Snapshot of the preference at presentation time — the sheet is short-lived, so a
    /// live binding would be overkill.
    private let confirmTrades: Bool

    init(asset: Asset, side: TradeSide, onComplete: @escaping () -> Void) {
        let vm = CompositionRoot.makeTradeViewModel(for: asset)
        vm.side = side
        _viewModel = State(initialValue: vm)
        self.confirmTrades = CompositionRoot.loadSettings().confirmTrades
        self.onComplete = onComplete
    }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 22) {
                header
                sideToggle
                quantityField
                estimateRow
                if let error = viewModel.errorMessage {
                    Text(error)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.down)
                }
                Spacer()
                actions
            }
            .padding(24)
        }
        .frame(width: 420, height: 460)
        .task { await viewModel.load() }
        .confirmationDialog(confirmTitle, isPresented: $showConfirm, titleVisibility: .visible) {
            Button(viewModel.side == .buy ? "Confirm Buy" : "Confirm Sell") { performSubmit() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(confirmMessage)
        }
    }

    private var confirmTitle: String {
        "\(viewModel.side == .buy ? "Buy" : "Sell") \(viewModel.quantityText) \(viewModel.asset.symbol.uppercased())?"
    }

    private var confirmMessage: String {
        let label = viewModel.side == .buy ? "Estimated cost" : "Estimated proceeds"
        return "\(label): \(viewModel.estimatedAmount?.formatted ?? "—")"
    }

    private func attemptSubmit() {
        if confirmTrades {
            showConfirm = true
        } else {
            performSubmit()
        }
    }

    private func performSubmit() {
        Task {
            await viewModel.submit()
            if viewModel.didComplete { onComplete(); dismiss() }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(viewModel.asset.symbol.uppercased())
                .font(.system(size: 11, weight: .bold))
                .tracking(2.0)
                .foregroundStyle(Theme.gold)
            Text(viewModel.asset.name)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            if let quote = viewModel.quote {
                HStack(spacing: 6) {
                    Text("Market price")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                    Text(quote.price.formatted)
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .foregroundStyle(Theme.textPrimary)
                }
            }
            Text("Simulated · paper trading")
                .font(.system(size: 10, weight: .semibold))
                .tracking(0.6)
                .foregroundStyle(Theme.textTertiary)
        }
    }

    private var sideToggle: some View {
        HStack(spacing: 4) {
            sideSegment(.buy, title: "Buy")
            sideSegment(.sell, title: "Sell")
        }
        .padding(4)
        .background(Theme.surface, in: Capsule())
        .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
    }

    private func sideSegment(_ side: TradeSide, title: String) -> some View {
        let selected = viewModel.side == side
        return Button {
            withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                viewModel.side = side
            }
            viewModel.quantityText = ""
        } label: {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background {
                    if selected {
                        Capsule()
                            .fill(Theme.surfaceHi)
                            .overlay(Capsule().stroke(Theme.gold.opacity(0.40), lineWidth: 1))
                    }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var quantityField: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("QUANTITY")
                    .font(.system(size: 10, weight: .bold)).tracking(1.0)
                    .foregroundStyle(Theme.textTertiary)
                Spacer()
                Button("Max") { viewModel.setMax() }
                    .buttonStyle(.plain)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Theme.gold)
            }
            TextField("0", text: $viewModel.quantityText)
                .textFieldStyle(.plain)
                .font(.system(size: 28, weight: .semibold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
            Text(secondaryLabel)
                .font(.system(size: 11).monospacedDigit())
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private var secondaryLabel: String {
        switch viewModel.side {
        case .buy: return "Available cash \(viewModel.availableCash.formatted)"
        case .sell: return "Shares owned \(viewModel.sharesOwned.formatted)"
        }
    }

    private var estimateRow: some View {
        HStack {
            Text(viewModel.side == .buy ? "Estimated cost" : "Estimated proceeds")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
            Spacer()
            Text(viewModel.estimatedAmount?.formatted ?? "—")
                .font(.system(size: 15, weight: .semibold).monospacedDigit())
                .foregroundStyle(Theme.textPrimary)
        }
    }

    private var actions: some View {
        HStack(spacing: 12) {
            Button("Cancel") { dismiss() }
                .buttonStyle(.plain)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Theme.surface, in: Capsule())
                .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
                .contentShape(Capsule())

            Button(viewModel.side == .buy ? "Buy" : "Sell") { attemptSubmit() }
            .buttonStyle(.plain)
            .font(.system(size: 14, weight: .bold))
            .foregroundStyle(viewModel.canSubmit ? Theme.bgBottom : Theme.textTertiary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .contentShape(Capsule())
            .background(
                AnyShapeStyle(viewModel.canSubmit ? AnyShapeStyle(Theme.goldGradient) : AnyShapeStyle(Theme.surface)),
                in: Capsule()
            )
            .disabled(!viewModel.canSubmit)
        }
    }
}

extension TradeSide: Identifiable {
    public var id: String { rawValue }
}
