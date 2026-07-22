import SwiftUI
import APTradeApplication
import APTradeDomain

// MARK: - Navigation

/// Where a Home row/card sends the user — `RootView` owns the actual tab/section switch
/// (it maps each case onto `Tab` + the destination tab's section state); Home only
/// describes the destination, never navigates directly.
enum HomeDestination {
    case marketsWatchlist
    case marketsScreener
    case marketsCalendar
    case marketsNews
    case investIncome
    case portfolio
}

// MARK: - Formatting shared by both platform bodies

/// Earnings/dividend "day" dates come from `HomeViewModel` parsed at UTC midnight (see
/// `HomeViewModel.dayParser`). Formatting them with any other timezone can print the
/// PREVIOUS calendar day the moment the display timezone's offset crosses zero relative to
/// UTC — America/New_York is always behind UTC, so a UTC-midnight `Date` shown in ET reads
/// as "yesterday" on every single render, not just near a DST edge. Fixed to UTC here so
/// display always agrees with the parse (T2 review, mandatory fix #2).
private let homeUTCDayFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "EEE"
    f.timeZone = TimeZone(identifier: "UTC")
    f.locale = Locale(identifier: "en_US_POSIX")
    return f
}()

/// Market-hours transition instants (from `MarketCalendar`, via `HomeFeedItem.marketStatus`)
/// ARE real instants (not day-only values) — displayed in the exchange's own timezone,
/// matching `CalendarView`'s existing America/New_York precedent.
private let homeCloseTimeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "h:mm a"
    f.timeZone = TimeZone(identifier: "America/New_York")
    f.locale = Locale(identifier: "en_US")
    return f
}()

private let homeOpenTimeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "EEE, h:mm a"
    f.timeZone = TimeZone(identifier: "America/New_York")
    f.locale = Locale(identifier: "en_US")
    return f
}()

/// `HomeFeedItem.screenerFresh` carries the raw `PresetScreen.rawValue` (e.g.
/// "rsiOversold"), not display text — `ScreenerView` has its own private `presetTitleKey`
/// mapping but it's file-scoped there, so Home keeps its own copy of the same mapping.
@MainActor
private func presetDisplayName(_ rawValue: String) -> String {
    guard let preset = PresetScreen(rawValue: rawValue) else { return rawValue }
    switch preset {
    case .rsiOversold: return tr(.presetRsiOversold)
    case .rsiOverbought: return tr(.presetRsiOverbought)
    case .macdBullishCross: return tr(.presetMacdBullish)
    case .macdBearishCross: return tr(.presetMacdBearish)
    case .goldenCross: return tr(.presetGoldenCross)
    case .deathCross: return tr(.presetDeathCross)
    case .bollingerSqueeze: return tr(.presetBollingerSqueeze)
    case .near52wHigh: return tr(.presetNear52wHigh)
    case .near52wLow: return tr(.presetNear52wLow)
    }
}

@MainActor
private func earningsWhenText(session: EarningsSession, date: Date) -> String {
    switch session {
    case .beforeOpen: return tr(.earningsSessionBeforeOpen)
    case .afterClose: return tr(.earningsSessionAfterClose)
    case .duringMarket, .unknown: return homeUTCDayFormatter.string(from: date)
    }
}

private func signedMoney(_ money: Money) -> String {
    money.amount > 0 ? "+" + money.formatted : money.formatted
}

private func signedPercent1dp(_ value: Double) -> String {
    let sign = value > 0 ? "+" : ""
    return "\(sign)\(value.formatted(.number.precision(.fractionLength(1))))%"
}

@MainActor
private func returnColor(_ value: Double) -> Color {
    if value > 0 { return Theme.up }
    if value < 0 { return Theme.down }
    return Theme.textPrimary
}

/// Splits an L10n format string with exactly one "%@" and bolds the substituted argument —
/// e.g. `leadsHoldingsFmt` "%@ leads your holdings" -> "**NVDA** leads your holdings". Falls
/// back to plain `String(format:)` if the catalog string doesn't have exactly one
/// placeholder (defensive only; every format this is called with has exactly one).
@MainActor
private func boldedRow(_ format: String, arg: String) -> Text {
    let parts = format.components(separatedBy: "%@")
    guard parts.count == 2 else { return Text(String(format: format, arg)) }
    return Text(parts[0]) + Text(arg).fontWeight(.bold) + Text(parts[1])
}

/// Same idea as `boldedRow`, for `dividendEstFmt`'s two placeholders (symbol, amount) —
/// bolds only the symbol (the first "%@"), leaves the amount formatted into the rest.
@MainActor
private func dividendRowText(symbol: String, amount: String) -> Text {
    let format = tr(.dividendEstFmt)
    guard let range = format.range(of: "%@") else {
        return Text(String(format: format, symbol, amount))
    }
    let prefix = String(format[format.startIndex..<range.lowerBound])
    let rest = String(format[range.upperBound...])
    return Text(prefix) + Text(symbol).fontWeight(.bold) + Text(String(format: rest, amount))
}

// MARK: - Hero sparkline

/// The hero's gold area sparkline — same Canvas technique as `Sparkline` (soft gradient
/// fill + stroke), fixed gold rather than direction-colored (this is a brand moment, not a
/// day's up/down trace), plus an endpoint dot per the mockup.
private struct HomeHeroSpark: View {
    let values: [Double]

    var body: some View {
        // Precomputed OUTSIDE the Canvas closure: `Theme` is `@MainActor`-isolated and
        // `Canvas`'s drawing closure isn't — mirrors `Sparkline`'s `fillTopOpacity` capture.
        let strokeColor = Theme.gold
        let fillColor = Theme.gold
        let dotColor = Theme.goldLight
        return Canvas { context, size in
            guard values.count > 1,
                  let minValue = values.min(),
                  let maxValue = values.max() else { return }

            let range = maxValue - minValue
            let stepX = size.width / CGFloat(values.count - 1)
            let inset: CGFloat = 3

            func point(at index: Int) -> CGPoint {
                let x = CGFloat(index) * stepX
                let normalized = range == 0 ? 0.5 : (values[index] - minValue) / range
                let y = inset + (1 - CGFloat(normalized)) * (size.height - inset * 2)
                return CGPoint(x: x, y: y)
            }

            var line = Path()
            line.move(to: point(at: 0))
            for index in 1..<values.count { line.addLine(to: point(at: index)) }

            var fill = line
            fill.addLine(to: CGPoint(x: size.width, y: size.height))
            fill.addLine(to: CGPoint(x: 0, y: size.height))
            fill.closeSubpath()

            context.fill(fill, with: .linearGradient(
                Gradient(colors: [fillColor.opacity(0.30), fillColor.opacity(0)]),
                startPoint: CGPoint(x: 0, y: 0),
                endPoint: CGPoint(x: 0, y: size.height)
            ))
            context.stroke(line, with: .color(strokeColor),
                           style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))

            let endpoint = point(at: values.count - 1)
            let dot = Path(ellipseIn: CGRect(x: endpoint.x - 3, y: endpoint.y - 3, width: 6, height: 6))
            context.fill(dot, with: .color(dotColor))
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Shared building blocks (hero stats trio, Today feed rows)

@MainActor
@ViewBuilder
private func homeStatTile(title: String, value: String, color: Color) -> some View {
    VStack(alignment: .leading, spacing: 3) {
        Text(title.uppercased())
            .font(.system(size: 9, weight: .bold)).tracking(1.0)
            .foregroundStyle(Theme.textTertiary)
        Text(value)
            .font(.system(size: 13, weight: .bold).monospacedDigit())
            .foregroundStyle(color)
    }
}

@MainActor
@ViewBuilder
private func homeStatsRow(vm: HomeViewModel) -> some View {
    HStack(alignment: .top, spacing: 22) {
        homeStatTile(title: tr(.totalReturn), value: signedPercent1dp(vm.totalReturnPercent), color: returnColor(vm.totalReturnPercent))
        homeStatTile(title: tr(.cashLabel), value: vm.cash.formatted, color: Theme.textPrimary)
        homeStatTile(title: tr(.incomeYtdLabel), value: vm.incomeYTD.formatted, color: Theme.textPrimary)
    }
}

/// One `HomeFeedItem`, rendered per the mockup's Today list: leading glyph, message
/// (bolded symbol where the format has one), trailing time/when label. Every case except
/// `.marketStatus` navigates on tap — `.marketStatus` is informational only.
@MainActor
@ViewBuilder
private func homeFeedRow(_ item: HomeFeedItem, onNavigate: @escaping (HomeDestination) -> Void) -> some View {
    switch item {
    case .marketStatus(let isOpen, let nextTransition):
        HStack(spacing: 10) {
            Circle().fill(isOpen ? Theme.up : Theme.down).frame(width: 6, height: 6)
            Text(isOpen ? tr(.marketOpenStatus) : tr(.marketClosedStatus))
                .font(.system(size: 13)).foregroundStyle(Theme.textPrimary)
            Spacer()
            Text(isOpen
                 ? String(format: tr(.closesAtFmt), homeCloseTimeFormatter.string(from: nextTransition))
                 : String(format: tr(.opensAtFmt), homeOpenTimeFormatter.string(from: nextTransition)))
                .font(.system(size: 11)).foregroundStyle(Theme.textTertiary)
        }
        .padding(.vertical, 8)

    case .topGainer(let symbol, let changePercent):
        Button { onNavigate(.marketsWatchlist) } label: {
            HStack(spacing: 10) {
                ChangePill(percent: changePercent)
                boldedRow(tr(.leadsHoldingsFmt), arg: symbol)
                    .font(.system(size: 13)).foregroundStyle(Theme.textPrimary)
                Spacer()
            }
            .contentShape(Rectangle())
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)

    case .topLoser(let symbol, let changePercent):
        Button { onNavigate(.marketsWatchlist) } label: {
            HStack(spacing: 10) {
                ChangePill(percent: changePercent)
                boldedRow(tr(.biggestFallerFmt), arg: symbol)
                    .font(.system(size: 13)).foregroundStyle(Theme.textPrimary)
                Spacer()
            }
            .contentShape(Rectangle())
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)

    case .earnings(let symbol, let session, let date):
        Button { onNavigate(.marketsCalendar) } label: {
            HStack(spacing: 10) {
                Image(systemName: "clock")
                    .font(.system(size: 11)).foregroundStyle(Theme.textSecondary).frame(width: 16)
                boldedRow(tr(.reportsEarningsFmt), arg: symbol)
                    .font(.system(size: 13)).foregroundStyle(Theme.textPrimary)
                Spacer()
                Text(earningsWhenText(session: session, date: date))
                    .font(.system(size: 11)).foregroundStyle(Theme.textTertiary)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)

    case .dividend(let symbol, let amount, let date):
        Button { onNavigate(.investIncome) } label: {
            HStack(spacing: 10) {
                Image(systemName: "banknote")
                    .font(.system(size: 11)).foregroundStyle(Theme.gold).frame(width: 16)
                dividendRowText(symbol: symbol, amount: amount.formatted)
                    .font(.system(size: 13)).foregroundStyle(Theme.textPrimary)
                Spacer()
                Text(homeUTCDayFormatter.string(from: date))
                    .font(.system(size: 11)).foregroundStyle(Theme.textTertiary)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)

    case .screenerFresh(let name, let matches):
        Button { onNavigate(.marketsScreener) } label: {
            HStack(spacing: 10) {
                Image(systemName: "scope")
                    .font(.system(size: 11)).foregroundStyle(Theme.gold).frame(width: 16)
                Text(String(format: tr(.screenerFreshFmt), presetDisplayName(name), "\(matches)"))
                    .font(.system(size: 13)).foregroundStyle(Theme.textPrimary)
                Spacer()
            }
            .contentShape(Rectangle())
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }
}

@MainActor
@ViewBuilder
private func homeTodayCard(vm: HomeViewModel, onNavigate: @escaping (HomeDestination) -> Void) -> some View {
    VStack(alignment: .leading, spacing: 2) {
        Text(tr(.todaySection).uppercased())
            .font(.system(size: 10, weight: .bold)).tracking(1.4)
            .foregroundStyle(Theme.textTertiary)
            .padding(.bottom, 6)
        VStack(alignment: .leading, spacing: 0) {
            // Duplicate ForEach identity is undefined in SwiftUI and `HomeFeedItem` isn't
            // Identifiable — offset-composited identity mirrors `CalendarView`'s own
            // defensive precedent for the same reason.
            ForEach(Array(vm.feed.enumerated()), id: \.offset) { index, item in
                homeFeedRow(item, onNavigate: onNavigate)
                if index < vm.feed.count - 1 {
                    Divider().overlay(Theme.hairline)
                }
            }
        }
    }
    .padding(16)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
}

// MARK: - Quick card (phone grid)

private struct HomeQuickCard: View {
    let title: String
    let subtitle: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                // A hidden placeholder line (rather than omitting the row) keeps every
                // card in the 2×2 grid the same height whether or not it has a live
                // one-liner right now.
                Text(subtitle ?? " ")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(2)
                    .opacity(subtitle == nil ? 0 : 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - iPhone Home

/// The Home dashboard on iPhone: hero + gold spark, quick-stat trio, the Today feed, a
/// 2×2 quick-card grid, and a badged Alerts bell. Data comes entirely from `HomeViewModel`
/// (Task 2) — this view only lays it out and formats it.
struct HomeView: View {
    var onNavigate: (HomeDestination) -> Void = { _ in }

    @State private var vm = CompositionRoot.makeHomeViewModel()
    @State private var showAlerts = false

    var body: some View {
        ZStack(alignment: .top) {
            Theme.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Color.clear.frame(height: 30) // reserves room under the fixed bell button
                    hero
                    homeStatsRow(vm: vm)
                    homeTodayCard(vm: vm, onNavigate: onNavigate)
                    quickCardsGrid
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
            }
            HStack {
                Spacer()
                bellButton
            }
            .padding(.horizontal, 20)
            .padding(.top, 10)
        }
        .sheet(isPresented: $showAlerts) { AlertsCenterView() }
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
        // 15s live-refresh loop: strictly sequential (refresh, sleep, refresh, …) inside one
        // `.task` — never fires a new `refresh()` while one is in flight, since there is no
        // concurrent Task/timer, only this single awaiting loop (T2 review, mandatory fix
        // #1). Mirrors `WatchlistViewModel.runLiveUpdates`'s house pattern.
        .task {
            while !Task.isCancelled {
                await vm.refresh()
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(tr(.portfolioValue).uppercased())
                .font(.system(size: 10, weight: .bold)).tracking(1.4)
                .foregroundStyle(Theme.textTertiary)
            SuperscriptPrice(money: vm.totalValue, size: 32)
            HStack(spacing: 6) {
                Text(signedMoney(vm.dayChange))
                    .font(.system(size: 12, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.changeColor(vm.dayChangePercent))
                ChangePill(percent: vm.dayChangePercent)
                Text(tr(.todaySection).lowercased())
                    .font(.system(size: 10.5))
                    .foregroundStyle(Theme.textTertiary)
            }
            HomeHeroSpark(values: vm.sparkValues)
                .frame(height: 60)
                .padding(.top, 4)
        }
    }

    private var quickCardsGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible())], spacing: 12) {
            HomeQuickCard(title: tr(.screenerTab), subtitle: screenerQuickSubtitle) {
                onNavigate(.marketsScreener)
            }
            HomeQuickCard(title: tr(.alertsCenterTitle),
                          subtitle: String(format: tr(.alertsActiveFmt), "\(vm.alertCount)")) {
                showAlerts = true
            }
            HomeQuickCard(title: tr(.calendarTab), subtitle: calendarQuickSubtitle) {
                onNavigate(.marketsCalendar)
            }
            // `HomeViewModel` carries no news data at all (it aggregates Portfolio/
            // Watchlist/Performance/Income/Calendar/Screener/Alerts, never News) and
            // `NewsViewModel`'s feed isn't scoped to owned/watched symbols the way
            // `quickNewsFmt`'s "for your symbols" copy implies — showing a count here would
            // fabricate a claim the data doesn't support, so this card degrades to a plain
            // title/nav affordance rather than a live one-liner.
            HomeQuickCard(title: tr(.news), subtitle: nil) {
                onNavigate(.marketsNews)
            }
        }
    }

    /// Reuses the SAME `.screenerFresh` row Today shows (same freshness check, same match
    /// count) rather than re-deriving it — just trimmed to `screenerMatchCountFmt`'s
    /// shorter "N matches" copy so it doesn't repeat the card's own "Screener" title.
    private var screenerQuickSubtitle: String {
        for item in vm.feed {
            if case .screenerFresh(_, let matches) = item {
                return String(format: tr(.screenerMatchCountFmt), "\(matches)")
            }
        }
        return tr(.screenerNotScanned)
    }

    /// `HomeViewModel` only ever surfaces the SINGLE next earnings event among owned+watched
    /// symbols (not a true weekly tally across the whole market) — reusing it here can
    /// undercount a week with more than one event, but never overstates: "1" is always
    /// honest given what's actually known. Degrades to no subtitle when nothing is upcoming.
    private var calendarQuickSubtitle: String? {
        let hasEarnings = vm.feed.contains { if case .earnings = $0 { return true }; return false }
        return hasEarnings ? String(format: tr(.quickEarningsWeekFmt), "1") : nil
    }

    /// The bell, styled like the mockup's circular "cbtn" idiom (gold ring over
    /// `Theme.surface`), with a small badge dot — matching the mockup's `.cbtn.badged` —
    /// when there's at least one alert.
    private var bellButton: some View {
        Button { showAlerts = true } label: {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "bell.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.gold)
                    .frame(width: 28, height: 28)
                    .background(Theme.surface, in: Circle())
                    .overlay(Circle().stroke(Theme.gold.opacity(0.4), lineWidth: 1))
                if vm.alertCount > 0 {
                    Circle()
                        .fill(Theme.gold)
                        .frame(width: 8, height: 8)
                        .overlay(Circle().stroke(Theme.bgTop, lineWidth: 1.5))
                        .offset(x: 2, y: -2)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tr(.alertsCenterTitle))
    }
}

// MARK: - macOS / Windows-desktop Home

/// The desktop Home dashboard: hero + spark full-width, then a two-column row — Today card
/// on the left, stats + Alerts card on the right — per the mockup's `dv-home`. Hosted
/// directly by `RootView`'s temporary `macBody` for now; Task 6 moves it into the real
/// sidebar shell without changing this view.
struct HomeViewMac: View {
    var onNavigate: (HomeDestination) -> Void = { _ in }

    @State private var vm = CompositionRoot.makeHomeViewModel()
    /// Read-only here, purely for the Alerts card's 2-alert preview (symbol + condition
    /// text) — `vm.alertCount` already covers the header count, this just needs the actual
    /// alert content `HomeViewModel` doesn't carry. Same load path `AlertsCenterView` uses.
    @State private var alertsVM = CompositionRoot.makeAlertsCenterViewModel()
    @State private var showAlerts = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                hero
                HStack(alignment: .top, spacing: 16) {
                    homeTodayCard(vm: vm, onNavigate: onNavigate)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    VStack(spacing: 14) {
                        statsCard
                        alertsCard
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(24)
        }
        .background(Theme.background.ignoresSafeArea())
        .sheet(isPresented: $showAlerts) { AlertsCenterView() }
        // Same sequential, non-overlapping 15s loop as the iPhone body (T2 review,
        // mandatory fix #1) — `alertsVM.load()` is a synchronous, cheap re-read of the
        // same on-disk alert store, folded into the same tick rather than its own timer.
        .task {
            while !Task.isCancelled {
                await vm.refresh()
                alertsVM.load()
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(tr(.portfolioValue).uppercased())
                .font(.system(size: 10, weight: .bold)).tracking(1.4)
                .foregroundStyle(Theme.textTertiary)
            HStack(alignment: .firstTextBaseline, spacing: 16) {
                SuperscriptPrice(money: vm.totalValue, size: 34)
                HStack(spacing: 6) {
                    Text(signedMoney(vm.dayChange))
                        .font(.system(size: 13, weight: .semibold).monospacedDigit())
                        .foregroundStyle(Theme.changeColor(vm.dayChangePercent))
                    ChangePill(percent: vm.dayChangePercent)
                    Text(tr(.todaySection).lowercased())
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textTertiary)
                }
            }
            HomeHeroSpark(values: vm.sparkValues)
                .frame(height: 70)
                .padding(.top, 4)
        }
    }

    private var statsCard: some View {
        HStack(spacing: 22) {
            homeStatTile(title: tr(.totalReturn), value: signedPercent1dp(vm.totalReturnPercent), color: returnColor(vm.totalReturnPercent))
            homeStatTile(title: tr(.cashLabel), value: vm.cash.formatted, color: Theme.textPrimary)
            homeStatTile(title: tr(.incomeYtdLabel), value: vm.incomeYTD.formatted, color: Theme.textPrimary)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
    }

    /// First two ARMED (untriggered) alerts across every symbol, oldest-store-order within
    /// each symbol — mirrors the mockup's 2-row Alerts card preview.
    private var alertsPreview: [(symbol: String, alert: PriceAlert)] {
        Array(
            alertsVM.groups
                .flatMap { group in group.alerts.filter { !$0.isTriggered }.map { (group.symbol, $0) } }
                .prefix(2)
        )
    }

    private var alertsCard: some View {
        Button { showAlerts = true } label: {
            VStack(alignment: .leading, spacing: 8) {
                Text("\(tr(.alertsCenterTitle).uppercased()) · \(String(format: tr(.alertsActiveFmt), "\(vm.alertCount)"))")
                    .font(.system(size: 10, weight: .bold)).tracking(1.0)
                    .foregroundStyle(Theme.textTertiary)
                if alertsPreview.isEmpty {
                    Text(tr(.alertsEmpty))
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                } else {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(alertsPreview.enumerated()), id: \.offset) { _, row in
                            HStack(spacing: 8) {
                                Image(systemName: "bell.fill")
                                    .font(.system(size: 10)).foregroundStyle(Theme.gold)
                                Text(row.symbol)
                                    .font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.textPrimary)
                                Text(row.alert.condition.localizedSummary)
                                    .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                                    .lineLimit(1)
                                Spacer()
                                Text(tr(.alertArmed).uppercased())
                                    .font(.system(size: 8, weight: .bold)).tracking(0.6)
                                    .foregroundStyle(Theme.gold)
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(Theme.gold.opacity(0.14), in: Capsule())
                            }
                        }
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
