import APTradeDomain

/// `EarningsSession` -> localized label. Swift twin of the Kotlin `sessionLabel`. Shared
/// by `CalendarView`, `AssetDetailView`'s next-earnings stat, and
/// `MarketActivityCoordinator`'s earnings-day notification body — hence its own file
/// rather than `private` in any single view.
@MainActor
func sessionLabel(_ session: EarningsSession) -> String {
    switch session {
    case .beforeOpen: return tr(.sessionBeforeOpen)
    case .afterClose: return tr(.sessionAfterClose)
    case .duringMarket: return tr(.sessionDuringMarket)
    case .unknown: return ""
    }
}
