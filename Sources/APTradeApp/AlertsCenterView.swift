import SwiftUI
import APTradeDomain

/// The Alerts center: every price alert across every symbol, in one place — the "read"
/// half of alerts (creation stays per-symbol, from a watchlist row's bell or the detail
/// page, unchanged). Reached from Home's bell/card (Task 5).
///
/// Self-contained (own header + dismiss), so it presents identically as a sheet from
/// either an iPhone tab bar bell or a macOS Home card — the caller only needs
/// `.sheet(isPresented:) { AlertsCenterView() }`.
struct AlertsCenterView: View {
    @State private var viewModel = CompositionRoot.makeAlertsCenterViewModel()
    @State private var selectedAsset: Asset?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Divider().overlay(Theme.hairline)
            if viewModel.isEmpty {
                emptyState
            } else {
                list
            }
        }
        #if os(iOS)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        #else
        .frame(width: 420, height: 520)
        #endif
        .background(Theme.surface)
        .sheet(item: $selectedAsset) { asset in
            NavigationStack {
                AssetDetailView(asset: asset)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button(tr(.done)) { selectedAsset = nil } } }
            }
        }
    }

    private var header: some View {
        HStack {
            Text(tr(.alertsCenterTitle))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
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

    private var list: some View {
        List {
            ForEach(viewModel.groups) { group in
                Section {
                    ForEach(group.alerts) { alert in
                        alertRow(symbol: group.symbol, alert: alert)
                            .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                            .contentShape(Rectangle())
                            .onTapGesture { selectedAsset = viewModel.asset(for: group.symbol) }
                            #if os(iOS)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) { viewModel.remove(alert.id) } label: {
                                    Image(systemName: "trash")
                                }
                            }
                            #endif
                    }
                } header: {
                    Text(group.symbol)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)
                        .textCase(nil)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func alertRow(symbol: String, alert: PriceAlert) -> some View {
        HStack(spacing: 10) {
            Image(systemName: alert.isTriggered ? "bell.slash" : "bell.fill")
                .font(.system(size: 12))
                .foregroundStyle(alert.isTriggered ? Theme.textTertiary : Theme.gold)
            Text(alert.condition.localizedSummary)
                .font(.system(size: 13))
                .foregroundStyle(alert.isTriggered ? Theme.textTertiary : Theme.textPrimary)
                .strikethrough(alert.isTriggered)
            Spacer()
            if !alert.isTriggered {
                Text(tr(.alertArmed).uppercased())
                    .font(.system(size: 9, weight: .bold))
                    .tracking(0.6)
                    .foregroundStyle(Theme.gold)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Theme.gold.opacity(0.14), in: Capsule())
            }
            Button { viewModel.remove(alert.id) } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textTertiary)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 8)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "bell.slash")
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(Theme.textTertiary)
            Text(tr(.alertsEmpty))
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}
