import SwiftUI
import APTradeDomain

struct TradeSheet: View {
    @State private var viewModel: TradeViewModel
    @Environment(\.dismiss) private var dismiss
    let onComplete: () -> Void

    init(asset: Asset, side: TradeSide, onComplete: @escaping () -> Void) {
        let vm = CompositionRoot.makeTradeViewModel(for: asset)
        vm.side = side
        _viewModel = State(initialValue: vm)
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
        Picker("", selection: $viewModel.side) {
            Text("Buy").tag(TradeSide.buy)
            Text("Sell").tag(TradeSide.sell)
        }
        .pickerStyle(.segmented)
        .onChange(of: viewModel.side) { _, _ in viewModel.quantityText = "" }
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

            Button(viewModel.side == .buy ? "Buy" : "Sell") {
                Task {
                    await viewModel.submit()
                    if viewModel.didComplete { onComplete(); dismiss() }
                }
            }
            .buttonStyle(.plain)
            .font(.system(size: 14, weight: .bold))
            .foregroundStyle(viewModel.canSubmit ? Theme.bgBottom : Theme.textTertiary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
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
