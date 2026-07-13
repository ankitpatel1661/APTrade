import SwiftUI
import APTradeApplication
import APTradeDomain

/// `USMarketHoliday` -> localized name. UI-layer mapping (domain stays L10n-free) —
/// Swift twin of the Kotlin `CalendarPane.kt`'s `holidayName`.
@MainActor
private func holidayName(_ holiday: USMarketHoliday) -> String {
    switch holiday {
    case .newYearsDay: return tr(.holidayNewYears)
    case .martinLutherKingDay: return tr(.holidayMLK)
    case .washingtonsBirthday: return tr(.holidayWashington)
    case .goodFriday: return tr(.holidayGoodFriday)
    case .memorialDay: return tr(.holidayMemorial)
    case .juneteenth: return tr(.holidayJuneteenth)
    case .independenceDay: return tr(.holidayIndependence)
    case .laborDay: return tr(.holidayLabor)
    case .thanksgiving: return tr(.holidayThanksgiving)
    case .christmas: return tr(.holidayChristmas)
    }
}

private let calendarShortDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "MMM d"
    f.timeZone = TimeZone(identifier: "America/New_York")
    f.locale = Locale(identifier: "en_US")
    return f
}()

private let calendarDayHeaderFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "EEEE, MMM d"
    f.timeZone = TimeZone(identifier: "America/New_York")
    f.locale = Locale(identifier: "en_US")
    return f
}()

/// Calendar tab: fourteen days of NYSE holiday/half-day banners plus S&P 500 + owned-symbol
/// earnings, grouped by day. Scaffold mirrors `NewsView.swift` — a `switcher` slot for the
/// macOS pill row, `iosTopChrome` for iOS, `.task` to trigger the one-shot load.
struct CalendarView: View {
    var switcher: AnyView? = nil
    var onOpenSearch: (() -> Void)? = nil
    var onOpenAccount: (() -> Void)? = nil
    @State private var viewModel = CompositionRoot.makeCalendarViewModel()

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 12) {
                    if let switcher { switcher.padding(.horizontal, 24).padding(.top, 8) }
                    content
                }
            }
            #if os(iOS)
            .iosTopChrome(onSearch: { onOpenSearch?() }, onAccount: { onOpenAccount?() })
            .navigationBarTitleDisplayMode(.inline)
            #endif
            #if os(macOS)
            .frame(minWidth: 560, minHeight: 640)
            #endif
            .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
            .task { await viewModel.load() }
        }
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.isLoading && viewModel.days.isEmpty {
            Spacer(); ProgressView(); Spacer()
        } else if viewModel.days.isEmpty && !viewModel.keyMissing {
            Spacer(); noEarningsState; Spacer()
        } else if viewModel.days.isEmpty && viewModel.keyMissing {
            Spacer(); noKeyState; Spacer()
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(viewModel.days) { group in
                        dayGroupSection(group)
                    }
                    if viewModel.keyMissing {
                        noKeyState
                            .padding(.top, 16)
                            .padding(.bottom, 16)
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
            }
        }
    }

    @ViewBuilder
    private func dayGroupSection(_ group: CalendarViewModel.DayGroup) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            dayHeader(group)
            if let holiday = group.holiday {
                banner(String(format: tr(.marketClosedBannerFmt), holidayName(holiday)))
            } else if group.isHalfDay {
                banner(String(format: tr(.closesEarlyBannerFmt), calendarShortDateFormatter.string(from: group.date)))
            }
            ForEach(group.events, id: \.symbol) { event in
                // viewModel.ownSymbols is already normalized (CalendarViewModel) — normalize the
                // event's symbol here too so a watched "BRK-B" still lights up the owned dot on
                // Finnhub's "BRK.B" event.
                earningsRow(event, owned: viewModel.ownSymbols.contains(normalized(event.symbol)))
            }
            Divider().overlay(Theme.hairline).padding(.horizontal, 16).padding(.top, 4)
        }
        .padding(.bottom, 8)
    }

    private func dayHeader(_ group: CalendarViewModel.DayGroup) -> some View {
        Text(calendarDayHeaderFormatter.string(from: group.date).uppercased())
            .font(.system(size: 11, weight: .bold))
            .tracking(1.8)
            .foregroundStyle(Theme.textSecondary)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
    }

    /// Full-width quiet row for a holiday/half-day notice: `Theme.surface` fill, a thin
    /// gold-alpha border (brand accent as a quiet signal, never a price-direction color).
    private func banner(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(Theme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(Theme.gold.opacity(0.4), lineWidth: 1)
            )
            .padding(.horizontal, 16)
    }

    /// One earnings row: a gold dot when owned (watchlist/portfolio symbol), the ticker,
    /// a session chip, and the EPS estimate when present.
    private func earningsRow(_ event: EarningsEvent, owned: Bool) -> some View {
        HStack(spacing: 10) {
            Circle()
                .fill(owned ? Theme.gold : Color.clear)
                .frame(width: 6, height: 6)
            VStack(alignment: .leading, spacing: 1) {
                Text(event.symbol)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                if !event.companyName.isEmpty {
                    Text(event.companyName)
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            sessionChip(event.session)
            if let eps = event.epsEstimate {
                Text("est \(Money(amount: Decimal(eps)).formatted)")
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    /// Session pill — quiet chip idiom shared with the watchlist's asset-kind chips
    /// (`Theme.surfaceHi`, bold tracked caps). Renders nothing for `.unknown`.
    @ViewBuilder
    private func sessionChip(_ session: EarningsSession) -> some View {
        let label = sessionLabel(session)
        if !label.isEmpty {
            Text(label)
                .font(.system(size: 10, weight: .bold))
                .tracking(0.8)
                .foregroundStyle(Theme.textSecondary)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Theme.surfaceHi, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .stroke(Theme.hairline, lineWidth: 1)
                )
        }
    }

    private var noEarningsState: some View {
        Text(tr(.noUpcomingEarnings))
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(Theme.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: 360)
    }

    /// Key-missing empty state — same copy/layout idiom as `NewsView.noKeyState`. The
    /// Calendar tab shares the same Finnhub key gate as News, so the "connect a source"
    /// copy applies verbatim; unlike News (which replaces the whole tab), here it renders
    /// below any holiday/half-day rows already surfaced, or centered alone when there are none.
    private var noKeyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "calendar").font(.system(size: 34)).foregroundStyle(Theme.textSecondary)
            Text(tr(.connectNewsSource))
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.textPrimary)
            Text(finnhubKeyInstructionsText)
                .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center).frame(maxWidth: 360)
        }
        .frame(maxWidth: .infinity)
    }

    private var finnhubKeyInstructionsText: String {
        #if os(iOS)
        tr(.finnhubKeyInstructionsIOS)
        #else
        tr(.finnhubKeyInstructions)
        #endif
    }
}
