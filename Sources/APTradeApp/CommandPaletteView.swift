import SwiftUI
import APTradeDomain

/// The ⌘K command palette: a top-anchored floating panel over a dimmed backdrop, with a
/// search field and a keyboard-navigable result list. Presentation-only — all matching and
/// selection state live in `viewModel`; this view only renders it and forwards interaction.
struct CommandPaletteView: View {
    @Bindable var viewModel: CommandPaletteViewModel
    @FocusState private var fieldFocused: Bool
    let onSelect: (PaletteResult) -> Void
    let onClose: () -> Void

    var body: some View {
        ZStack(alignment: .top) {
            #if !os(iOS)
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .onTapGesture { onClose() }
            #endif

            panel
                #if os(iOS)
                .padding(.horizontal, 16)
                .padding(.top, 16)
                #else
                .padding(.top, 80)
                #endif
        }
        .onAppear { fieldFocused = true }
    }

    private var panel: some View {
        VStack(spacing: 0) {
            searchField
            if !viewModel.results.isEmpty {
                Divider().overlay(Theme.hairline)
                resultList
            } else if !viewModel.query.trimmingCharacters(in: .whitespaces).isEmpty {
                // Symbol-search only now (M10.1 UAT U7 — the static nav rows are gone), so
                // an untouched, just-opened palette has an empty query AND empty results;
                // "No matches" only reads correctly once the user has actually typed
                // something that failed to match.
                Text(tr(.noMatches))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.vertical, 16)
            }
        }
        #if os(iOS)
        .frame(maxWidth: .infinity)
        #else
        .frame(width: 520)
        #endif
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
        .shadow(color: .black.opacity(0.35), radius: 24, y: 12)
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Theme.textSecondary)
            TextField(tr(.searchAssetsPlaceholder), text: $viewModel.query)
                .textFieldStyle(.plain)
                .font(.system(size: 16))
                .foregroundStyle(Theme.textPrimary)
                .focused($fieldFocused)
                .onChange(of: viewModel.query) { _, text in viewModel.updateQuery(text) }
                .onKeyPress(.upArrow) { viewModel.moveSelection(-1); return .handled }
                .onKeyPress(.downArrow) { viewModel.moveSelection(1); return .handled }
                .onKeyPress(.return) {
                    if let selected = viewModel.activateSelected() { onSelect(selected) }
                    return .handled
                }
                .onKeyPress(.escape) { onClose(); return .handled }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    private var resultList: some View {
        ScrollView {
            VStack(spacing: 2) {
                ForEach(Array(viewModel.results.enumerated()), id: \.element.id) { index, result in
                    resultRow(result, isSelected: index == viewModel.selectedIndex)
                        .onTapGesture { onSelect(result) }
                }
            }
            .padding(8)
        }
        .frame(maxHeight: 320)
    }

    @ViewBuilder
    private func resultRow(_ result: PaletteResult, isSelected: Bool) -> some View {
        HStack(spacing: 12) {
            switch result {
            case .asset(let asset):
                VStack(alignment: .leading, spacing: 2) {
                    Text(asset.name)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                    Text(asset.symbol)
                        .font(.system(size: 11, weight: .medium).monospacedDigit())
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                Text(kindLabel(asset.kind))
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(isSelected ? Theme.surfaceHi : .clear, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .contentShape(Rectangle())
    }

    private func kindLabel(_ kind: AssetKind) -> String {
        switch kind {
        case .stock: return tr(.stockChip)
        case .etf: return tr(.etfChip)
        case .crypto: return tr(.cryptoChip)
        }
    }
}
