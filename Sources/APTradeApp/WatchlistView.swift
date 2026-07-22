import SwiftUI
import APTradeDomain

struct WatchlistView: View {
    var onOpenSearch: (() -> Void)? = nil
    var onOpenAccount: (() -> Void)? = nil
    @State private var viewModel = CompositionRoot.makeWatchlistViewModel()
    @State private var newSymbol = ""
    @State private var selectedAsset: Asset?
    @State private var hoveredSymbol: String?
    @State private var alertTarget: Asset?
    @State private var showChart = false

    private var chartSpring: Animation { .spring(response: 0.34, dampingFraction: 0.84) }

    var body: some View {
        #if os(macOS)
        macBody
        #else
        iosBody
        #endif
    }

    // MARK: - iOS: full-window push (unchanged)

    #if os(iOS)
    private var iosBody: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    header
                    if showChart && viewModel.averageSpark.count > 1 {
                        ExpandedValueCard(
                            title: tr(.avgDayChangeTitle),
                            values: viewModel.averageSpark,
                            color: Theme.changeColor(viewModel.averageChange),
                            format: { "\($0 >= 0 ? "+" : "")\($0.formatted(.number.precision(.fractionLength(2))))%" },
                            changeStyle: .percentagePoints,
                            onClose: { withAnimation(chartSpring) { showChart = false } }
                        )
                        .padding(.horizontal, 24)
                        .padding(.bottom, 16)
                        .transition(.move(edge: .top).combined(with: .opacity))
                    }
                    Divider().overlay(Theme.hairline)
                    content
                }
            }
            .navigationDestination(item: $selectedAsset) { asset in
                AssetDetailView(asset: asset)
            }
            .iosTopChrome(onSearch: { onOpenSearch?() }, onAccount: { onOpenAccount?() })
            .navigationBarTitleDisplayMode(.inline)
            .task {
                await viewModel.onAppear()
                await viewModel.runLiveUpdates()
            }
            .refreshable { await viewModel.refresh() }
            .sheet(item: $alertTarget) { asset in
                PriceAlertSheet(
                    asset: asset,
                    currentPrice: viewModel.visibleRows.first { $0.asset.symbol == asset.symbol }?.quote?.price,
                    existing: viewModel.alerts(for: asset.symbol),
                    onCreate: { condition in viewModel.addAlert(symbol: asset.symbol, condition: condition) },
                    onDelete: { id in viewModel.deleteAlert(id) }
                )
                .presentationDetents([.medium, .large])
                .presentationBackground(Theme.surface)
            }
        }
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
    }
    #endif

    // MARK: - macOS: master–detail (Task 7)

    #if os(macOS)
    /// Kind toggle + live pulse, full width above the split — mirrors how `ScreenerView`
    /// keeps its chips/scan bar full-width above its own split. Deliberately does NOT
    /// include `addBar`: the search affordance travels with the rows into the narrow list
    /// column instead (per the mockup's `.mlist`), since it's scoped to filtering what's
    /// visible there, not a page-level control.
    private var macTopBar: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top, spacing: 10) {
                KindToggle(selection: $viewModel.selectedKind, counts: viewModel.counts)
                Spacer()
                HStack(spacing: 10) {
                    if viewModel.isRefreshing {
                        ProgressView().controlSize(.small)
                    } else if viewModel.isLive {
                        LiveBadge()
                    }
                }
                .frame(width: 60, alignment: .trailing)
            }
            pulse
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 18)
    }

    private var macBody: some View {
        VStack(spacing: 0) {
            macTopBar
            if showChart && viewModel.averageSpark.count > 1 {
                ExpandedValueCard(
                    title: tr(.avgDayChangeTitle),
                    values: viewModel.averageSpark,
                    color: Theme.changeColor(viewModel.averageChange),
                    format: { "\($0 >= 0 ? "+" : "")\($0.formatted(.number.precision(.fractionLength(2))))%" },
                    changeStyle: .percentagePoints,
                    onClose: { withAnimation(chartSpring) { showChart = false } }
                )
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
            Divider().overlay(Theme.hairline)
            HStack(spacing: 0) {
                listColumn
                    .frame(width: 300)
                    .overlay(alignment: .trailing) {
                        Rectangle().fill(Theme.hairline).frame(width: 1)
                    }
                detailPane
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .frame(minWidth: 560, minHeight: 640)
        .task {
            await viewModel.onAppear()
            await viewModel.runLiveUpdates()
        }
        .refreshable { await viewModel.refresh() }
        .sheet(item: $alertTarget) { asset in
            PriceAlertSheet(
                asset: asset,
                currentPrice: viewModel.visibleRows.first { $0.asset.symbol == asset.symbol }?.quote?.price,
                existing: viewModel.alerts(for: asset.symbol),
                onCreate: { condition in viewModel.addAlert(symbol: asset.symbol, condition: condition) },
                onDelete: { id in viewModel.deleteAlert(id) }
            )
        }
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
    }

    /// Search affordance + rows, ~300pt — the mockup's `.mlist`.
    private var listColumn: some View {
        VStack(spacing: 0) {
            addBar
                .padding(.horizontal, 14)
                .padding(.top, 14)
                .padding(.bottom, 10)
            content
        }
    }

    /// Selecting another row swaps this pane's content — `.id(asset.symbol)` forces SwiftUI
    /// to tear down and rebuild `AssetDetailView` (and, with it, its own `@State`
    /// `AssetDetailViewModel`/`AssetNewsViewModel`) per selection, exactly like a fresh
    /// `navigationDestination` push used to: without it, swapping the bound `asset` while
    /// the view's identity/position in the tree stays the same would let SwiftUI reuse the
    /// OLD view model instead of loading the newly-selected symbol (the per-selection
    /// lifecycle note from the 6a.5 Windows fidelity pass — `remember(symbol) { ... }` /
    /// `DisposableEffect(symbol)` there, `.id(symbol)` here). No back affordance needed:
    /// the pane simply swaps.
    @ViewBuilder
    private var detailPane: some View {
        if let asset = selectedAsset {
            AssetDetailView(asset: asset, embedded: true)
                .id(asset.symbol)
        } else {
            // No "select a symbol" copy exists in L10n and this task may not add one —
            // reuses the same neutral, textless empty region `ScreenerView.content` renders
            // while a scan is already visibly in progress (no icon, no text, just the
            // background showing through).
            Color.clear
        }
    }
    #endif

    // MARK: Header — toggle + day pulse (iOS only after Task 7; macOS uses `macTopBar`)

    #if os(iOS)
    private var header: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 12) {
                KindToggle(selection: $viewModel.selectedKind, counts: viewModel.counts)
                HStack {
                    if viewModel.isRefreshing { ProgressView().controlSize(.small) }
                    else if viewModel.isLive { LiveBadge() }
                    Spacer()
                }
            }
            pulse
            addBar
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 18)
    }
    #endif

    private var pulse: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(viewModel.averageChange?.formatted ?? "—")
                    .font(.system(size: 34, weight: .semibold).monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .foregroundStyle(Theme.changeColor(viewModel.averageChange))
                    .contentTransition(.numericText())
                    .animation(.easeOut(duration: 0.3), value: viewModel.averageChange)
                Text(tr(.avgDayChange))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                if viewModel.averageSpark.count > 1 {
                    ExpandableSparkline(
                        values: viewModel.averageSpark,
                        color: Theme.changeColor(viewModel.averageChange),
                        size: CGSize(width: 140, height: 36)
                    ) { withAnimation(chartSpring) { showChart.toggle() } }
                }
            }
            if !viewModel.visibleRows.isEmpty {
                PulseBar(advancers: viewModel.advancers, decliners: viewModel.decliners)
                    .frame(width: 180)
                HStack(spacing: 14) {
                    legend(format: tr(.advancingFormat), count: viewModel.advancers, color: Theme.up)
                    legend(format: tr(.decliningFormat), count: viewModel.decliners, color: Theme.down)
                }
            }
        }
    }

    private func legend(format: String, count: Int, color: Color) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(String(format: format, count))
                .font(.system(size: 12).monospacedDigit())
                .foregroundStyle(Theme.textSecondary)
        }
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if viewModel.visibleRows.isEmpty {
            emptyState
        } else {
            list
        }
    }

    private var list: some View {
        List {
            ForEach(viewModel.visibleRows) { row in
                WatchlistRow(
                    row: row,
                    isHovered: hoveredSymbol == row.id,
                    isSelected: isRowSelected(row),
                    alertCount: viewModel.alerts(for: row.asset.symbol).count,
                    onOpen: { selectedAsset = row.asset },
                    onRemove: { viewModel.remove(symbol: row.asset.symbol) },
                    onSetAlert: { alertTarget = row.asset }
                )
                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                .onHover { hovering in
                    if hovering { hoveredSymbol = row.id }
                    else if hoveredSymbol == row.id { hoveredSymbol = nil }
                }
                #if os(iOS)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) { viewModel.remove(symbol: row.asset.symbol) } label: {
                        Label(tr(.removeFromWatchlist), systemImage: "trash")
                    }
                }
                .swipeActions(edge: .leading) {
                    Button { alertTarget = row.asset } label: {
                        Label(tr(.setPriceAlert), systemImage: "bell")
                    }.tint(Theme.gold)
                }
                #endif
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .animation(.easeInOut(duration: 0.2), value: viewModel.selectedKind)
    }

    /// macOS only: is `row` the symbol currently shown in the detail pane? Always `false`
    /// on iOS — the row's own selection state there is a transient push trigger, not a
    /// persistent "currently viewed" marker, so it must never paint a lingering gold ring
    /// on the row after popping back (iOS behavior stays unchanged).
    private func isRowSelected(_ row: RowState) -> Bool {
        #if os(macOS)
        row.id == selectedAsset?.symbol
        #else
        false
        #endif
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: iconForKind)
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(Theme.textTertiary)
            Text(tr(noneYetKey))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text(tr(.addSymbolHint))
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }

    private var iconForKind: String {
        switch viewModel.selectedKind {
        case .stock: return "building.columns"
        case .etf: return "square.stack.3d.up"
        case .crypto: return "bitcoinsign.circle"
        }
    }

    private var noneYetKey: L10n.Key {
        switch viewModel.selectedKind {
        case .stock: return .noStocksYet
        case .etf: return .noETFsYet
        case .crypto: return .noCryptoYet
        }
    }

    // MARK: Add bar

    private var addBar: some View {
        VStack(spacing: 8) {
            if let error = viewModel.addError {
                Text(error)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.down)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(Theme.textTertiary)
                TextField(tr(.searchTickerPlaceholder), text: $newSymbol)
                    .textFieldStyle(.plain)
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textPrimary)
                    .onChange(of: newSymbol) { _, text in viewModel.updateQuery(text) }
                    .onSubmit(submit)
                if !newSymbol.trimmingCharacters(in: .whitespaces).isEmpty {
                    Button(tr(.add), action: submit)
                        .buttonStyle(.plain)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Theme.bgBottom)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                        .background(Theme.goldGradient, in: Capsule())
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(Theme.surface, in: Capsule())
            .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
            if !viewModel.suggestions.isEmpty {
                suggestionList
            }
        }
    }

    private var suggestionList: some View {
        VStack(spacing: 0) {
            ForEach(viewModel.suggestions, id: \.symbol) { asset in
                Button {
                    newSymbol = ""
                    Task { await viewModel.addSuggestion(asset) }
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(asset.name)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Theme.textPrimary)
                                .lineLimit(1)
                            Text(asset.symbol)
                                .font(.system(size: 11, weight: .medium).monospacedDigit())
                                .foregroundStyle(Theme.textSecondary)
                        }
                        Spacer()
                        Text(kindChipLabel(asset.kind))
                            .font(.system(size: 10, weight: .bold))
                            .tracking(0.6)
                            .foregroundStyle(Theme.textSecondary)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Theme.surfaceHi, in: Capsule())
                    }
                    .contentShape(Rectangle())
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                }
                .buttonStyle(.plain)
                if asset.symbol != viewModel.suggestions.last?.symbol {
                    Divider().overlay(Theme.hairline)
                }
            }
        }
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    private func kindChipLabel(_ kind: AssetKind) -> String {
        switch kind {
        case .stock: return tr(.stockChip)
        case .etf: return tr(.etfChip)
        case .crypto: return tr(.cryptoChip)
        }
    }

    private func submit() {
        let query = newSymbol
        newSymbol = ""
        viewModel.clearSuggestions()
        Task { await viewModel.add(query: query) }
    }
}

// MARK: - Row

private struct WatchlistRow: View {
    let row: RowState
    let isHovered: Bool
    let isSelected: Bool
    let alertCount: Int
    let onOpen: () -> Void
    let onRemove: () -> Void
    let onSetAlert: () -> Void

    private var directionColor: Color {
        Theme.changeColor(row.quote?.changePercent)
    }

    var body: some View {
        ZStack(alignment: .trailing) {
            Button(action: onOpen) {
                HStack(spacing: 14) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(row.asset.name)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Theme.textPrimary)
                            .lineLimit(1)
                        Text(row.asset.symbol)
                            .font(.system(size: 12, weight: .medium).monospacedDigit())
                            .foregroundStyle(Theme.textSecondary)
                    }
                    Spacer(minLength: 12)
                    if row.spark.count > 1 {
                        Sparkline(values: row.spark, color: directionColor)
                            .frame(width: 72, height: 32)
                    }
                    #if os(iOS)
                    alertButton
                    #else
                    alertButton
                        .opacity(isHovered || alertCount > 0 ? 1 : 0)
                    #endif
                    priceColumn
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            removeButton
                .opacity(isHovered ? 1 : 0)
                .offset(x: 4)
        }
        .padding(.vertical, 13)
        .padding(.horizontal, 10)
        .background {
            if isSelected {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Theme.surfaceHi)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(Theme.gold.opacity(0.35), lineWidth: 1)
                    )
            } else if isHovered {
                RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Theme.surface)
            }
        }
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(Theme.hairline)
                .frame(height: 1)
                .padding(.horizontal, 10)
                .opacity(isHovered || isSelected ? 0 : 1)
        }
        .contextMenu {
            Button(tr(.setPriceAlert), systemImage: "bell", action: onSetAlert)
            Button(tr(.removeFromWatchlist), systemImage: "trash", role: .destructive, action: onRemove)
        }
    }

    private var alertButton: some View {
        Button(action: onSetAlert) {
            Image(systemName: alertCount > 0 ? "bell.fill" : "bell")
                .font(.system(size: 13))
                .foregroundStyle(alertCount > 0 ? Theme.gold : Theme.textTertiary)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Theme.surfaceHi))
        }
        .buttonStyle(.plain)
        .help(alertCount > 0 ? String(format: tr(.activeAlertsFormat), alertCount) : tr(.setAPriceAlert))
    }

    private var removeButton: some View {
        Button(action: onRemove) {
            Image(systemName: "minus.circle.fill")
                .font(.system(size: 18))
                .foregroundStyle(Theme.textTertiary)
                .background(Circle().fill(Theme.surfaceHi).padding(2))
        }
        .buttonStyle(.plain)
        .help(tr(.removeFromWatchlistHelp))
    }

    @ViewBuilder
    private var priceColumn: some View {
        if let quote = row.quote {
            VStack(alignment: .trailing, spacing: 5) {
                SuperscriptPrice(money: quote.price, size: 18, weight: .semibold)
                ChangePill(percent: quote.changePercent)
            }
            .frame(minWidth: 104, alignment: .trailing)
        } else if row.failed {
            Text("—")
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .frame(minWidth: 104, alignment: .trailing)
        } else {
            ProgressView()
                .controlSize(.small)
                .frame(minWidth: 104, alignment: .trailing)
        }
    }
}
