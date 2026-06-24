import SwiftUI
import APTradeDomain

struct PortfolioView: View {
    @State private var viewModel = CompositionRoot.makePortfolioViewModel()
    @State private var selectedAsset: Asset?
    @State private var showResetConfirm = false

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    summary
                    Divider().overlay(Theme.hairline)
                    content
                }
            }
            .navigationDestination(item: $selectedAsset) { asset in
                AssetDetailView(asset: asset)
            }
            .toolbar(.hidden, for: .windowToolbar)
            .task {
                await viewModel.onAppear()
                await viewModel.runLiveUpdates()
            }
            .refreshable { await viewModel.refresh() }
            .confirmationDialog("Reset portfolio to $100,000 cash and clear all holdings?",
                                isPresented: $showResetConfirm, titleVisibility: .visible) {
                Button("Reset", role: .destructive) { viewModel.reset() }
                Button("Cancel", role: .cancel) {}
            }
        }
        .frame(minWidth: 560, minHeight: 640)
        .preferredColorScheme(.dark)
        .onAppear { viewModel.reload() }
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("TOTAL VALUE")
                        .font(.system(size: 11, weight: .bold)).tracking(1.8)
                        .foregroundStyle(Theme.textSecondary)
                    SuperscriptPrice(money: viewModel.valuation.totalValue, size: 40, weight: .semibold)
                }
                Spacer()
                HStack(spacing: 10) {
                    if viewModel.isRefreshing {
                        ProgressView().controlSize(.small)
                    } else if viewModel.isLive {
                        LiveBadge()
                    }
                    Menu {
                        Button("Reset portfolio", systemImage: "arrow.counterclockwise", role: .destructive) {
                            showResetConfirm = true
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .font(.system(size: 16))
                            .foregroundStyle(Theme.textSecondary)
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }
            }
            HStack(spacing: 22) {
                metric(label: "Day P&L", money: viewModel.valuation.dayChange, colored: true)
                metric(label: "Unrealized P&L", money: viewModel.valuation.unrealizedPnL, colored: true)
                metric(label: "Cash", money: viewModel.valuation.cash, colored: false)
            }
            Text("Simulated · paper trading")
                .font(.system(size: 10, weight: .semibold)).tracking(0.6)
                .foregroundStyle(Theme.textTertiary)
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 18)
    }

    private func metric(label: String, money: Money, colored: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .semibold)).tracking(1.0)
                .foregroundStyle(Theme.textTertiary)
            Text(signed(money, showsSign: colored))
                .font(.system(size: 16, weight: .semibold).monospacedDigit())
                .foregroundStyle(colored ? pnlColor(money) : Theme.textPrimary)
        }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.holdings.isEmpty {
            emptyState
        } else {
            List {
                ForEach(viewModel.holdings, id: \.asset.symbol) { position in
                    HoldingRow(position: position, quote: viewModel.quote(for: position.asset.symbol))
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                        .contentShape(Rectangle())
                        .onTapGesture { selectedAsset = position.asset }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "chart.pie")
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(Theme.textTertiary)
            Text("No holdings yet")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text("Open an asset and tap Buy to start a simulated position.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }

    private func pnlColor(_ money: Money) -> Color {
        if money.amount > 0 { return Theme.up }
        if money.amount < 0 { return Theme.down }
        return Theme.textPrimary
    }

    private func signed(_ money: Money, showsSign: Bool) -> String {
        guard showsSign, money.amount > 0 else { return money.formatted }
        return "+" + money.formatted
    }
}

private struct HoldingRow: View {
    let position: Position
    let quote: Quote?

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(position.asset.name)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                Text("\(position.quantity.formatted) @ \(position.averageCost.formatted)")
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
            Spacer(minLength: 12)
            VStack(alignment: .trailing, spacing: 5) {
                if let quote {
                    SuperscriptPrice(money: position.marketValue(at: quote.price), size: 18, weight: .semibold)
                    Text(signed(position.unrealizedPnL(at: quote.price)))
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .foregroundStyle(pnlColor(position.unrealizedPnL(at: quote.price)))
                } else {
                    SuperscriptPrice(money: position.marketValue(at: position.averageCost), size: 18, weight: .semibold)
                }
            }
        }
        .padding(.vertical, 13)
        .padding(.horizontal, 10)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.hairline).frame(height: 1).padding(.horizontal, 10)
        }
    }

    private func pnlColor(_ money: Money) -> Color {
        if money.amount > 0 { return Theme.up }
        if money.amount < 0 { return Theme.down }
        return Theme.textPrimary
    }

    private func signed(_ money: Money) -> String {
        let sign = money.amount > 0 ? "+" : ""
        return sign + money.formatted
    }
}
