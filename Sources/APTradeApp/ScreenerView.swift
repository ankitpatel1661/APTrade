import SwiftUI
import APTradeApplication
import APTradeDomain

// MARK: - Formatting helpers

/// `MMM d, h:mm a` in a fixed locale — matches the app-wide convention (see
/// `IncomeSection.swift`/`CalendarView.swift`) of formatting numbers/dates in `en_US`
/// regardless of the active display language, only the surrounding copy is localized.
private let screenerScanTimeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US")
    f.dateFormat = "MMM d, h:mm a"
    return f
}()

private func formatPlain(_ value: Double, decimals: Int) -> String {
    let f = NumberFormatter()
    f.locale = Locale(identifier: "en_US")
    f.numberStyle = .decimal
    f.minimumFractionDigits = decimals
    f.maximumFractionDigits = decimals
    return f.string(from: NSNumber(value: value)) ?? String(format: "%.\(decimals)f", value)
}

/// `value` is already in percent units (e.g. `3.21` means `3.21%`) — routes through
/// `Percentage` so the sign/decimals match every other percent readout in the app.
private func formatSignedPercent(_ value: Double) -> String {
    Percentage(value: Decimal(value)).formatted
}

/// Unsigned percent readout for metrics that are always ≥ 0 (52-week distances) — a
/// forced "+" would misread as a directional change these values never carry.
private func formatUnsignedPercent(_ value: Double) -> String {
    "\(formatPlain(value, decimals: 2))%"
}

// MARK: - Active metric column

/// One extra results-table column beyond the always-present symbol/name/price/day-change
/// four: the single metric that best characterizes the active screen. This mirrors
/// `ScreenerViewModel.activeMetricValue(_:)` exactly — that mapping is `private` on the
/// view model (it only needs the raw `Double?` for sorting), so this is a deliberate,
/// documented duplication for display purposes. Keep both in sync if either changes;
/// `ScreenerSortColumn.activeMetric` sorts by this same field.
private struct ActiveMetricColumn {
    let titleKey: L10n.Key
    let value: (ScreenerSnapshotRow) -> Double?
    let format: (Double) -> String
}

/// Preset mapping — see `ScreenerViewModel.activeMetricValue(_:)`'s doc comment for the
/// rationale behind each choice.
private func activeMetricColumn(for selection: ScreenSelection) -> ActiveMetricColumn? {
    switch selection {
    case .preset(let preset):
        switch preset {
        case .rsiOversold, .rsiOverbought:
            return ActiveMetricColumn(titleKey: .metricRsi, value: { $0.rsi14 },
                                       format: { formatPlain($0, decimals: 1) })
        case .macdBullishCross, .macdBearishCross:
            return ActiveMetricColumn(titleKey: .indicatorMACD, value: { $0.macdHistogram },
                                       format: { formatPlain($0, decimals: 2) })
        case .goldenCross, .deathCross:
            return ActiveMetricColumn(titleKey: .metricVsSma50, value: { $0.pctVsSma50 },
                                       format: formatSignedPercent)
        case .bollingerSqueeze:
            // Bandwidth/%B are RAW fractions everywhere (columns, presets, builder) —
            // M9.2 must preserve. See the identical note on the custom-metric mapping
            // below for the full rationale.
            return ActiveMetricColumn(titleKey: .metricBandwidth, value: { $0.bollingerBandwidth },
                                       format: { formatPlain($0, decimals: 4) })
        case .near52wHigh:
            return ActiveMetricColumn(titleKey: .metricTo52wHigh, value: { $0.pctTo52wHigh },
                                       format: formatUnsignedPercent)
        case .near52wLow:
            return ActiveMetricColumn(titleKey: .metricTo52wLow, value: { $0.pctTo52wLow },
                                       format: formatUnsignedPercent)
        }
    case .custom(let screen):
        // A zero-condition (unbuilt) screen never has matches to show a column for —
        // `ScreenSelection.custom.evaluate` already returns `[]` in that case.
        guard let metric = screen.conditions.first?.metric else { return nil }
        return activeMetricColumn(for: metric)
    }
}

/// Custom-screen mapping — one column definition per `ScreenerMetric` case. `.price` and
/// `.dayChangePercent` return `nil`: those two metrics are already the always-present
/// Price/Day % columns, so surfacing them again here would render an identical duplicate
/// column beside the real one. This is a deliberate asymmetry with sorting — the view
/// model's `ScreenerSortColumn.activeMetric` can still target either field (sorting by
/// price via "active metric" is indistinguishable from sorting by the Price column
/// itself), only the redundant *visual* column is suppressed.
private func activeMetricColumn(for metric: ScreenerMetric) -> ActiveMetricColumn? {
    switch metric {
    case .price, .dayChangePercent:
        return nil
    case .rsi14:
        return ActiveMetricColumn(titleKey: .metricRsi, value: { $0.rsi14 },
                                   format: { formatPlain($0, decimals: 1) })
    case .bollingerPercentB:
        // Bandwidth/%B are RAW fractions everywhere (columns, presets, builder) — M9.2
        // must preserve. `ScreenSelection`/`ScreenCondition` compare these two metrics as
        // raw fractions (e.g. the bollingerSqueeze preset's `bandwidth < 0.05`), and the
        // builder sheet's free-text threshold field is parsed as a raw `Double` with no
        // ×100/÷100 conversion either — so a displayed "0.05" here is directly the number
        // a user types into a custom screen's threshold, with no percent-unit translation
        // in between.
        return ActiveMetricColumn(titleKey: .metricPercentB, value: { $0.bollingerPercentB },
                                   format: { formatPlain($0, decimals: 4) })
    case .bollingerBandwidth:
        return ActiveMetricColumn(titleKey: .metricBandwidth, value: { $0.bollingerBandwidth },
                                   format: { formatPlain($0, decimals: 4) })
    case .pctTo52wHigh:
        return ActiveMetricColumn(titleKey: .metricTo52wHigh, value: { $0.pctTo52wHigh },
                                   format: formatUnsignedPercent)
    case .pctTo52wLow:
        return ActiveMetricColumn(titleKey: .metricTo52wLow, value: { $0.pctTo52wLow },
                                   format: formatUnsignedPercent)
    case .relativeVolume:
        return ActiveMetricColumn(titleKey: .metricRelVolume, value: { $0.relativeVolume },
                                   format: { formatPlain($0, decimals: 2) + "×" })
    case .pctVsSma50:
        return ActiveMetricColumn(titleKey: .metricVsSma50, value: { $0.pctVsSma50 },
                                   format: formatSignedPercent)
    case .pctVsSma200:
        return ActiveMetricColumn(titleKey: .metricVsSma200, value: { $0.pctVsSma200 },
                                   format: formatSignedPercent)
    }
}

private func presetTitleKey(_ preset: PresetScreen) -> L10n.Key {
    switch preset {
    case .rsiOversold: return .presetRsiOversold
    case .rsiOverbought: return .presetRsiOverbought
    case .macdBullishCross: return .presetMacdBullish
    case .macdBearishCross: return .presetMacdBearish
    case .goldenCross: return .presetGoldenCross
    case .deathCross: return .presetDeathCross
    case .bollingerSqueeze: return .presetBollingerSqueeze
    case .near52wHigh: return .presetNear52wHigh
    case .near52wLow: return .presetNear52wLow
    }
}

// MARK: - Column widths (shared between header + rows so they stay aligned)

private enum ScreenerColumnWidth {
    static let symbol: CGFloat = 76
    static let price: CGFloat = 96
    static let dayChange: CGFloat = 84
    static let metric: CGFloat = 96
    static let addButton: CGFloat = 30
}

/// Screener tab: nine curated technical presets plus user-saved custom screens, a
/// full-universe scan bar, and sortable results. Fifth top-level destination alongside
/// Watchlist/Portfolio/News/Calendar — same `switcher`-threading and `iosTopChrome`
/// scaffold every other tab uses (see `WatchlistView`/`CalendarView`).
struct ScreenerView: View {
    var switcher: AnyView? = nil
    var onOpenSearch: (() -> Void)? = nil
    var onOpenAccount: (() -> Void)? = nil

    @State private var viewModel = CompositionRoot.makeScreenerViewModel()
    /// Drives `ScreenBuilderSheet`'s presentation. `builderTarget` is `nil` for a
    /// brand-new screen (the "+" chip) and set to the screen being edited when opened
    /// from a saved-screen chip's context menu — presentation is owned entirely by this
    /// view (not threaded up to `RootView`), matching how `PlansSection` owns its own
    /// wizard sheet state locally.
    @State private var showBuilder = false
    @State private var builderTarget: CustomScreen?
    /// Independent of `viewModel` — the Screener tab reads market data but writes to the
    /// SAME watchlist store every other tab shares, so an add-from-screener row shows up
    /// immediately back on the Watchlist tab (and, symmetrically, a symbol already on the
    /// watchlist shows its checkmark here — see `loadWatchlist`/the `.task` below).
    @State private var addToWatchlist = AddToWatchlistUseCase(store: CompositionRoot.makeStore())
    @State private var loadWatchlist = LoadWatchlistUseCase(store: CompositionRoot.makeStore())
    /// Seeded from the real watchlist store on appear (below), then kept in sync locally
    /// as this view's own add-taps land — mirrors `WatchlistViewModel.onAppear()`'s load,
    /// just without needing a whole view model for one read.
    @State private var addedSymbols: Set<String> = []
    @State private var selectedAsset: Asset?

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
                    Divider().overlay(Theme.hairline)
                    content
                }
            }
            .navigationDestination(item: $selectedAsset) { asset in
                AssetDetailView(asset: asset)
            }
            .sheet(isPresented: $showBuilder) {
                ScreenBuilderSheet(
                    existingScreen: builderTarget,
                    matchCount: { viewModel.matchCount(for: $0) },
                    onSave: { viewModel.saveScreen($0) },
                    onDelete: builderTarget.map { screen in { viewModel.deleteScreen(id: screen.id) } }
                )
                .presentationBackground(Theme.surface)
            }
            .iosTopChrome(onSearch: { onOpenSearch?() }, onAccount: { onOpenAccount?() })
            .navigationBarTitleDisplayMode(.inline)
            .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
            .task {
                addedSymbols = Set(loadWatchlist().map(\.symbol))
            }
            // macOS tab switching destroys this view's `@State` view model outright; an
            // unstructured `Task { await viewModel.scan() }` fired from a button used to
            // keep running after that teardown, orphaned, so returning to this tab and
            // rescanning could fire TWO overlapping engines (8 parallel fetches instead
            // of 4). `viewModel.startScan()` owns the task on the view model itself, and
            // this `.onDisappear` cancels it the moment the view goes away.
            .onDisappear {
                viewModel.cancelScan()
            }
        }
    }
    #endif

    // MARK: - macOS: master–detail (Task 7)

    #if os(macOS)
    /// Scan bar + chips stay full-width above the split (unchanged from the pre-Task-7
    /// single-pane layout); only the results table becomes the list column below, mirroring
    /// `WatchlistView`'s treatment.
    private var macBody: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(Theme.hairline)
            HStack(spacing: 0) {
                content
                    // Wider than Watchlist's ~300pt: the results table keeps its full,
                    // unshrunk column set (symbol/name/price/day%/active metric/add-star),
                    // so the list column needs enough room for all of them to stay legible
                    // rather than overlap — this is the "constrained" width, not a redesign
                    // of the columns themselves.
                    .frame(width: 520)
                    .overlay(alignment: .trailing) {
                        Rectangle().fill(Theme.hairline).frame(width: 1)
                    }
                detailPane
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .sheet(isPresented: $showBuilder) {
            ScreenBuilderSheet(
                existingScreen: builderTarget,
                matchCount: { viewModel.matchCount(for: $0) },
                onSave: { viewModel.saveScreen($0) },
                onDelete: builderTarget.map { screen in { viewModel.deleteScreen(id: screen.id) } }
            )
        }
        .frame(minWidth: 560, minHeight: 640)
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
        .task {
            addedSymbols = Set(loadWatchlist().map(\.symbol))
        }
        // Same orphaned-scan-task guard as before Task 7 (see the historical note this
        // replaced): `viewModel.startScan()` owns the task on the view model itself, and
        // this `.onDisappear` cancels it the moment the view goes away — swapping sidebar
        // destinations tears this view down exactly like tab switching used to.
        .onDisappear {
            viewModel.cancelScan()
        }
    }

    /// Selecting another row swaps this pane's content — `.id(asset.symbol)` forces a fresh
    /// `AssetDetailView` (and its own `@State` view models) per selection, same rationale
    /// and mechanism as `WatchlistView.detailPane`.
    @ViewBuilder
    private var detailPane: some View {
        if let asset = selectedAsset {
            AssetDetailView(asset: asset, embedded: true)
                .id(asset.symbol)
        } else {
            // No fitting "select a symbol" copy exists in L10n — reuses the same neutral,
            // textless empty region as `WatchlistView.detailPane`/this file's own
            // scanning-in-progress state below.
            Color.clear
        }
    }
    #endif

    // MARK: Header — switcher + chips + scan bar

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let switcher {
                switcher.padding(.horizontal, 24).padding(.top, 8)
            }
            #if os(iOS)
            ScrollView(.horizontal, showsIndicators: false) {
                chipsRow.padding(.horizontal, 2)
            }
            .padding(.horizontal, 24)
            #else
            chipsRow.padding(.horizontal, 24)
            #endif
            scanBar.padding(.horizontal, 24)
        }
        .padding(.top, 12)
        .padding(.bottom, 14)
    }

    // MARK: Chips row

    private var chipsRow: some View {
        HStack(spacing: 6) {
            ForEach(PresetScreen.allCases, id: \.self) { preset in
                chip(title: tr(presetTitleKey(preset)), selected: viewModel.selection == .preset(preset)) {
                    viewModel.select(.preset(preset))
                }
            }
            ForEach(viewModel.savedScreens) { screen in
                // `screen.name` is user-authored data, not app copy — rendered verbatim,
                // never routed through `tr(_:)`.
                chip(title: screen.name, selected: viewModel.selection == .custom(screen)) {
                    viewModel.select(.custom(screen))
                }
                // Long-press (iOS) / right-click (macOS) → edit, mirroring
                // `WatchlistRow`'s `.contextMenu` — the only existing long-press/
                // secondary-click affordance in the app. Deletion itself lives inside
                // the builder sheet (with its own destructive confirm), not here.
                .contextMenu {
                    Button(tr(.screenerEditScreen), systemImage: "pencil") {
                        builderTarget = screen
                        showBuilder = true
                    }
                }
            }
            newScreenChip
        }
    }

    private func chip(title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
                .lineLimit(1)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background {
                    if selected {
                        Capsule().fill(Theme.surfaceHi)
                            .overlay(Capsule().stroke(Theme.gold.opacity(0.4), lineWidth: 1))
                    }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var newScreenChip: some View {
        Button {
            builderTarget = nil
            showBuilder = true
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Theme.gold)
                .frame(width: 30, height: 30)
                .background(Theme.surface, in: Circle())
                .overlay(Circle().stroke(Theme.gold.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .help(tr(.screenerNewScreen))
    }

    // MARK: Scan bar

    @ViewBuilder
    private var scanBar: some View {
        switch viewModel.scanState {
        case .scanning(let done, let total):
            VStack(alignment: .leading, spacing: 8) {
                ProgressView(value: Double(done), total: Double(max(total, 1)))
                    .tint(Theme.gold)
                Text(String(format: tr(.screenerScanningFmt), "\(done)", "\(total)"))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.hairline, lineWidth: 1))

        case .failed:
            scanBarAction(icon: "exclamationmark.triangle", tint: Theme.down,
                          message: tr(.screenerScanFailed), actionTitle: tr(.refresh)) {
                viewModel.startScan()
            }

        case .idle:
            if let snapshot = viewModel.snapshot {
                // `isSnapshotFresh` (true when the persisted snapshot was scanned on
                // today's trading day) picks between two readings of the SAME idle+
                // snapshot state: a stale snapshot reads as "not yet scanned today" (the
                // scan icon + the same action label used in the never-scanned state
                // below), a fresh one as "up to date, tap to refresh" (checkmark +
                // refresh). No new strings — both label/icon pairs already exist
                // elsewhere in this scan bar.
                VStack(alignment: .leading, spacing: 6) {
                    scanBarAction(
                        icon: viewModel.isSnapshotFresh ? "checkmark.circle" : "line.3.horizontal.decrease.circle",
                        tint: Theme.gold,
                        message: String(format: tr(.screenerLastScanFmt),
                                        "\(snapshot.rows.count)",
                                        screenerScanTimeFormatter.string(from: snapshot.scannedAt)),
                        actionTitle: viewModel.isSnapshotFresh ? tr(.refresh) : tr(.screenerScan)
                    ) {
                        viewModel.startScan()
                    }
                    if !snapshot.failedSymbols.isEmpty {
                        Text(String(format: tr(.screenerFailedNoteFmt), "\(snapshot.failedSymbols.count)"))
                            .font(.system(size: 11))
                            .foregroundStyle(Theme.textTertiary)
                            .padding(.horizontal, 4)
                    }
                }
            } else {
                scanBarAction(icon: "line.3.horizontal.decrease.circle", tint: Theme.gold,
                              message: tr(.screenerNotScanned), actionTitle: tr(.screenerScan)) {
                    viewModel.startScan()
                }
            }
        }
    }

    private func scanBarAction(icon: String, tint: Color, message: String, actionTitle: String,
                                action: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(tint)
            Text(message)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Button(actionTitle, action: action)
                .buttonStyle(.plain)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Theme.bgBottom)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(Theme.goldGradient, in: Capsule())
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    // MARK: Content — three empty states + results

    @ViewBuilder
    private var content: some View {
        if viewModel.snapshot != nil && !viewModel.results.isEmpty {
            resultsList
        } else if case .scanning = viewModel.scanState {
            // A scan is already visibly in progress via the progress bar above — showing
            // the "Scan the S&P 500…"/no-matches empty-state CTA underneath it here would
            // read as a second, redundant invitation to do the very thing already
            // happening. Render the neutral empty region instead (no icon, no text).
            Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if viewModel.snapshot != nil {
            // Scanned before, but the active screen matches nothing in that snapshot.
            emptyState(icon: "tray", text: tr(.screenerNoMatches))
        } else if case .failed = viewModel.scanState {
            // Never successfully scanned, and the most recent attempt failed outright.
            emptyState(icon: "exclamationmark.triangle", text: tr(.screenerScanFailed))
        } else {
            // Never scanned at all.
            emptyState(icon: "line.3.horizontal.decrease.circle", text: tr(.screenerNotScanned))
        }
    }

    private func emptyState(icon: String, text: String) -> some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: icon)
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(Theme.textTertiary)
            Text(text)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 360)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }

    // MARK: Results

    private var activeColumn: ActiveMetricColumn? {
        activeMetricColumn(for: viewModel.selection)
    }

    private var resultsList: some View {
        VStack(spacing: 0) {
            #if os(macOS)
            columnHeader
            Divider().overlay(Theme.hairline)
            #endif
            List {
                ForEach(viewModel.results) { row in
                    #if os(macOS)
                    macRow(row)
                    #else
                    iosRow(row)
                    #endif
                }
                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    #if os(macOS)
    private var columnHeader: some View {
        HStack(spacing: 10) {
            headerButton(tr(.statSymbol), column: .symbol, width: ScreenerColumnWidth.symbol, alignment: .leading)
            Text(tr(.name))
                .font(.system(size: 11, weight: .bold))
                .tracking(0.6)
                .foregroundStyle(Theme.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
            headerButton(tr(.metricPrice), column: .price, width: ScreenerColumnWidth.price, alignment: .trailing)
            headerButton(tr(.metricDayChange), column: .dayChange, width: ScreenerColumnWidth.dayChange, alignment: .trailing)
            if let activeColumn {
                headerButton(tr(activeColumn.titleKey), column: .activeMetric, width: ScreenerColumnWidth.metric, alignment: .trailing)
            }
            Color.clear.frame(width: ScreenerColumnWidth.addButton, height: 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func headerButton(_ title: String, column: ScreenerSortColumn, width: CGFloat, alignment: Alignment) -> some View {
        Button {
            if viewModel.sortColumn == column {
                viewModel.sortAscending.toggle()
            } else {
                viewModel.sortColumn = column
                viewModel.sortAscending = true
            }
        } label: {
            HStack(spacing: 3) {
                if alignment == .trailing { Spacer(minLength: 0) }
                Text(title)
                if viewModel.sortColumn == column {
                    Image(systemName: viewModel.sortAscending ? "chevron.up" : "chevron.down")
                        .font(.system(size: 8, weight: .bold))
                }
                if alignment == .leading { Spacer(minLength: 0) }
            }
            .font(.system(size: 11, weight: .bold))
            .tracking(0.6)
            .foregroundStyle(viewModel.sortColumn == column ? Theme.textPrimary : Theme.textTertiary)
        }
        .buttonStyle(.plain)
        .frame(width: width, alignment: alignment)
    }

    private func macRow(_ row: ScreenerSnapshotRow) -> some View {
        let isSelected = selectedAsset?.symbol == row.symbol
        return HStack(spacing: 10) {
            Button {
                selectedAsset = asset(for: row)
            } label: {
                HStack(spacing: 10) {
                    Text(row.symbol)
                        .font(.system(size: 13, weight: .semibold).monospacedDigit())
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                        .frame(width: ScreenerColumnWidth.symbol, alignment: .leading)
                    Text(row.name)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    SuperscriptPrice(money: Money(amount: Decimal(row.close)), size: 14, weight: .semibold)
                        .frame(width: ScreenerColumnWidth.price, alignment: .trailing)
                    ChangePill(percent: row.dayChangePercent.map { Percentage(value: Decimal($0)) })
                        .frame(width: ScreenerColumnWidth.dayChange, alignment: .trailing)
                    if let activeColumn {
                        Text(activeColumn.value(row).map(activeColumn.format) ?? "—")
                            .font(.system(size: 13, weight: .medium).monospacedDigit())
                            .foregroundStyle(Theme.textSecondary)
                            .frame(width: ScreenerColumnWidth.metric, alignment: .trailing)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            addButton(for: row)
        }
        .padding(.vertical, 9)
        .padding(.horizontal, 8)
        .background {
            // Same surface-hi + gold inset ring idiom as the sidebar's selected item and
            // `WatchlistRow`'s selected state (Task 7) — "same macOS master–detail
            // treatment" applies to row styling too, not just the split itself.
            if isSelected {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Theme.surfaceHi)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .stroke(Theme.gold.opacity(0.35), lineWidth: 1)
                    )
            }
        }
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.hairline).frame(height: 1)
        }
    }
    #endif

    #if os(iOS)
    private func iosRow(_ row: ScreenerSnapshotRow) -> some View {
        Button {
            selectedAsset = asset(for: row)
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(row.symbol)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                    Text(row.name)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                    if let activeColumn {
                        HStack(spacing: 3) {
                            Text(tr(activeColumn.titleKey))
                                .font(.system(size: 10, weight: .semibold))
                            Text(activeColumn.value(row).map(activeColumn.format) ?? "—")
                                .font(.system(size: 10, weight: .semibold).monospacedDigit())
                        }
                        .foregroundStyle(Theme.textTertiary)
                    }
                }
                Spacer(minLength: 12)
                VStack(alignment: .trailing, spacing: 5) {
                    SuperscriptPrice(money: Money(amount: Decimal(row.close)), size: 16, weight: .semibold)
                    ChangePill(percent: row.dayChangePercent.map { Percentage(value: Decimal($0)) })
                }
                addButton(for: row)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.vertical, 10)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Theme.hairline).frame(height: 1)
        }
    }
    #endif

    private func addButton(for row: ScreenerSnapshotRow) -> some View {
        let added = addedSymbols.contains(row.symbol)
        return Button {
            _ = addToWatchlist(asset(for: row))
            addedSymbols.insert(row.symbol)
        } label: {
            Image(systemName: added ? "checkmark.circle.fill" : "plus.circle")
                .font(.system(size: 15))
                .foregroundStyle(added ? Theme.gold : Theme.textTertiary)
                .frame(width: ScreenerColumnWidth.addButton, height: ScreenerColumnWidth.addButton)
                .background(Circle().fill(Theme.surfaceHi))
        }
        .buttonStyle(.plain)
        .help(tr(.addToWatchlist))
    }

    private func asset(for row: ScreenerSnapshotRow) -> Asset {
        // Screener universe is S&P 500 constituents only — always `.stock`.
        Asset(symbol: row.symbol, name: row.name, kind: .stock)
    }
}
