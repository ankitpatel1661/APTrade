package com.aptrade.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.aptrade.desktop.alerts.AlertsCenterDialog
import com.aptrade.desktop.calendar.CalendarPane
import com.aptrade.desktop.calendar.CalendarViewModel
import com.aptrade.desktop.calendar.sessionLabel
import com.aptrade.desktop.designkit.APTradeDesktopTheme
import com.aptrade.desktop.detail.DetailScreen
import com.aptrade.desktop.home.HomePane
import com.aptrade.desktop.news.NewsPane
import com.aptrade.desktop.news.NewsViewModel
import com.aptrade.desktop.portfolio.PortfolioPane
import com.aptrade.desktop.portfolio.PortfolioSection
import com.aptrade.desktop.portfolio.PortfolioViewModel
import com.aptrade.desktop.portfolio.TradeDialog
import com.aptrade.desktop.income.IncomePane
import com.aptrade.desktop.plans.PlansPane
import com.aptrade.desktop.screener.ScreenerPane
import com.aptrade.shared.application.ContributionOutcome
import com.aptrade.shared.application.DividendOutcome
import com.aptrade.desktop.infra.exportFileName
import com.aptrade.desktop.infra.renderPortfolioPdf
import com.aptrade.desktop.infra.saveBinaryFile
import com.aptrade.desktop.infra.saveTextFile
import com.aptrade.desktop.search.PaletteOverlay
import com.aptrade.desktop.search.SearchViewModel
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.l10n.AppLanguage
import com.aptrade.desktop.l10n.LocalizationManager
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.desktop.ui.AccountPanel
import com.aptrade.desktop.ui.AppShell
import com.aptrade.desktop.ui.InvestSection
import com.aptrade.desktop.ui.MarketsSection
import com.aptrade.desktop.ui.SidebarDestination
import com.aptrade.desktop.watchlist.PriceAlertSheet
import com.aptrade.desktop.watchlist.WatchlistPane
import com.aptrade.desktop.watchlist.WatchlistViewModel
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Dimension

fun main() = application {
    // ONE AppGraph — one Ktor client — for the whole process.
    val graph = remember { AppGraph() }
    // Pure/stateless (see MarketCalendar's KDoc); one instance is plenty, shared by the
    // earnings "today" gate below the same way graph's other pure helpers are shared.
    val marketCalendar = remember { MarketCalendar() }

    // RECORDED DIVERGENCE (increment 6d.1, macOS parity gap): macOS delivers alert/
    // order-fill/market notifications via UNUserNotificationCenter — no equivalent
    // cross-platform notification-center API exists in Compose Multiplatform for
    // Desktop. The portable primitive is TrayState.sendNotification, which requires a
    // Tray composable (carrying the app icon) mounted in this ApplicationScope. This
    // Tray joins graph.trayState — the same instance TrayNotifier posts to — so a
    // sendNotification call from anywhere in the app surfaces as a real OS tray
    // notification. See TrayNotifier.kt for the full rationale.
    Tray(
        icon = painterResource("brand/AppLogo.png"),
        state = graph.trayState,
        tooltip = "APTrade",
    )

    // A single Main-confined scope owns the polling watchlist + palette search VMs;
    // it lives as long as the app process, so cancel it when the window closes.
    val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val watchlistViewModel = remember {
        WatchlistViewModel(
            fetchMarketQuotes = graph.fetchMarketQuotes,
            fetchHistory = graph.fetchHistory,
            fetchWatchlist = graph.fetchWatchlist,
            addToWatchlist = graph.addToWatchlist,
            removeFromWatchlist = graph.removeFromWatchlist,
            evaluateAlerts = graph.evaluateAlerts,
            loadAlerts = graph.loadAlerts,
            createPriceAlert = graph.createPriceAlert,
            removePriceAlert = graph.removePriceAlert,
            scope = appScope,
        )
    }
    val portfolioViewModel = remember {
        PortfolioViewModel(
            fetchPortfolio = graph.fetchPortfolio,
            fetchMarketQuotes = graph.fetchMarketQuotes,
            buyAsset = graph.buyAsset,
            sellAsset = graph.sellAsset,
            resetPortfolio = graph.resetPortfolio,
            fetchPerformanceReport = graph.fetchPerformanceReport,
            scope = appScope,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
            notifyOrderFill = graph.notifyOrderFill,
            fetchDividendEvents = graph.fetchDividendEvents,
        )
    }
    // Time-based notifications (market open/close + daily digest), 60s cadence — a
    // second Main-confined coroutine sharing the same lifetime as appScope (dies with
    // the same onDispose below). See DesktopMarketActivityCoordinator for the tick logic.
    val marketActivityCoordinator = remember {
        DesktopMarketActivityCoordinator(
            planner = graph.marketActivityPlanner,
            stateStore = graph.schedulerStateStore,
            loadSettings = { graph.settingsStore.load() },
            notifyMarketStatus = { opened -> graph.trayNotifier.notifyMarketStatus(opened) },
            notifyDigest = { summary -> graph.trayNotifier.notifyDigest(summary) },
            // All L10n formatting happens here (where tr/trf live) — the coordinator only
            // hands back the typed EarningsEvent it fetched; see the KDoc on the coordinator's
            // notifyEarnings param for why.
            notifyEarnings = { event ->
                // sessionLabel(Unknown) is "" (no L10n key for it); every language's
                // EarningsTodayBodyFmt ends with "· %2$s" (EN/DE/IT/ES all verified), so an
                // empty label would leave a dangling "… · " — trim the orphaned separator in
                // that one case only, never when a real session label is present.
                val label = sessionLabel(event.session)
                val body = trf(L10n.Key.EarningsTodayBodyFmt, event.symbol, label)
                graph.trayNotifier.notifyEarnings(
                    title = tr(L10n.Key.EarningsTodayTitle),
                    body = if (label.isEmpty()) body.trimEnd(' ', '·') else body,
                )
            },
            fetchTodaysOwnEarnings = {
                // Same market-local trading-day string the planner/coordinator's own
                // scheduling already keys off of (MarketCalendar.tradingDay), so "today" here
                // always matches the day the 60s tick considers current.
                val today = marketCalendar.tradingDay(System.currentTimeMillis() / 1000)
                graph.fetchEarningsCalendar.ownedToday(today)
            },
            executeDueContributions = { now -> graph.executeDueContributions.execute(now) },
            // Same L10n-here, coordinator-stays-ignorant split as notifyEarnings above: the
            // coordinator hands back the typed ContributionOutcome it produced, and only this
            // (UI-land) closure resolves the executed/skipped title+body via tr/trf.
            notifyPieContribution = { outcome ->
                when (outcome) {
                    is ContributionOutcome.Executed ->
                        graph.trayNotifier.notifyPieContribution(
                            title = tr(L10n.Key.NotifPieExecutedTitle),
                            body = trf(L10n.Key.NotifPieExecutedBody, outcome.pie.name),
                        )
                    is ContributionOutcome.SkippedInsufficientCash ->
                        graph.trayNotifier.notifyPieContribution(
                            title = tr(L10n.Key.NotifPieSkippedTitle),
                            body = trf(L10n.Key.NotifPieSkippedBody, outcome.pie.name),
                        )
                }
            },
            processDueDividends = { now -> graph.processDueDividends.execute(now) },
            // Same L10n-here, coordinator-stays-ignorant split as notifyPieContribution above:
            // the coordinator hands back the typed DividendOutcome(s) it produced (or, for a
            // collapsed backfill run, the tallied count + summed cash), and only this (UI-land)
            // closure resolves the cash/DRIP/backfill-summary title+body via tr/trf.
            notifyDividendOutcome = { outcome ->
                when (outcome) {
                    is DividendOutcome.Credited ->
                        graph.trayNotifier.notifyDividend(
                            title = tr(L10n.Key.NotifDividendTitle),
                            body = trf(L10n.Key.NotifDividendCashBodyFmt, outcome.symbol, outcome.cash.formatted),
                        )
                    is DividendOutcome.Reinvested ->
                        graph.trayNotifier.notifyDividend(
                            title = tr(L10n.Key.NotifDividendTitle),
                            body = trf(L10n.Key.NotifDividendDripBodyFmt, outcome.symbol, outcome.cash.formatted),
                        )
                }
            },
            notifyDividendBackfillSummary = { count, totalCash ->
                graph.trayNotifier.notifyDividend(
                    title = tr(L10n.Key.NotifDividendTitle),
                    body = trf(L10n.Key.NotifDividendBackfillBodyFmt, count.toString(), totalCash.formatted),
                )
            },
            fetchWatchlist = graph.fetchWatchlist,
            fetchMarketQuotes = graph.fetchMarketQuotes,
            scope = appScope,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
            calendar = marketCalendar,
        )
    }
    // News VM is created once (like the others) but started lazily on the first visit to the
    // News tab — its single fetch shouldn't run until the user asks for it.
    val newsViewModel = remember {
        NewsViewModel(
            fetchMarketNews = graph.fetchMarketNews,
            loadBookmarks = graph.loadBookmarks,
            toggleBookmark = graph.toggleBookmark,
            scope = appScope,
        )
    }
    // Calendar VM is created once (like News) but loaded lazily on the first visit to the
    // Calendar tab — see the newsStarted-mirroring calendarStarted gate in AppRoot below.
    val calendarViewModel = remember {
        CalendarViewModel(
            fetch = graph.fetchEarningsCalendar,
            calendar = marketCalendar,
            ownSymbols = graph.ownSymbols,
            needsKey = graph.earningsKeyMissing,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
            scope = appScope,
        )
    }
    // Two independent search surfaces (palette + the watchlist add-field) get their
    // own VM so their queries and result lists never bleed into each other.
    val searchViewModel = remember { SearchViewModel(graph.fetchSearch, appScope) }
    val addFieldViewModel = remember { SearchViewModel(graph.fetchSearch, appScope) }

    var paletteOpen by remember { mutableStateOf(false) }
    // Navigation state for the Portfolio Holdings row → full-window detail (UNCHANGED by
    // M10.2 Task 6 — Watchlist/Screener stop routing through this; see screenerSelectedSymbol
    // below and WatchlistViewModel.selectedSymbol for their own conditional-split state).
    // Hoisted here so window-level Esc can pop it.
    var openSymbol by remember { mutableStateOf<String?>(null) }
    // Screener pane's conditional-split selection (M10.2 Task 6) — a sibling of openSymbol,
    // hoisted here (rather than owned by ScreenerPane-local `remember` state) SPECIFICALLY so
    // window-level Esc can reach it below, exactly like openSymbol always could. Unlike
    // Watchlist (whose selection already lives on the Main-hoisted WatchlistViewModel, so no
    // new var was needed there), ScreenerViewModel is deliberately NOT Main-hoisted — it's
    // built per-composable inside ScreenerPane so its scan job's cancelScan-on-dispose lifecycle
    // stays scoped to a Screener visit (constraint: scan lifecycle unaffected by this task) —
    // so this one extra var carries just the selected symbol, not the whole VM.
    var screenerSelectedSymbol by remember { mutableStateOf<String?>(null) }
    // CROSS-PLATFORM DIVERGENCE (M10.2): Desktop pane detail selections (openSymbol, Watchlist's
    // selectedSymbol, screenerSelectedSymbol) PERSIST across visit-away-and-back — re-visiting
    // Watchlist after navigating to Markets→Calendar and back re-opens the same detail. Swift's
    // @State resets on revisit; this Compose pattern deliberately keeps it open. Rationale:
    // structurally prevents cross-section carry-across (no possibility of opening Watchlist detail
    // but showing Screener's data) and maintains Esc reachability (clear the detail to reset).
    // M10.3 Android must consciously reconcile this behavior (push-nav makes it N/A currently);
    // a Swift macOS backport decision is pending (M10.3 priority TBD).
    // The sidebar's current destination, hoisted here so window-level Esc can gate pane-selection
    // clearing on the active section (Task 6 review fix).
    var sidebarSelection by remember { mutableStateOf<SidebarDestination>(SidebarDestination.Home) }
    // The open trade dialog's target (asset + side + live price text), hoisted here like the
    // palette so the dialog overlays the whole window. TUNNELING ORDER: window-level
    // onPreviewKeyEvent fires BEFORE focused children, so child overlays (TradeDialog,
    // PriceAlertSheet, AccountPanel) must gate the window Esc chain — see the guard
    // (tradeTarget == null && alertTarget == null && !accountOpen) on Esc branches below.
    var tradeTarget by remember { mutableStateOf<TradeTarget?>(null) }
    // The right-anchored account/settings panel (⋯ button). Hoisted here so it overlays the
    // whole window like the palette; it self-consumes Esc on its own panel, never the window's.
    var accountOpen by remember { mutableStateOf(false) }
    // The open price-alert sheet's target symbol, hoisted like tradeTarget so it overlays the
    // whole window. The sheet self-consumes Esc on its own panel (TradeDialog pattern).
    var alertTarget by remember { mutableStateOf<Asset?>(null) }
    // The Alerts center dialog (M10.2 Task 5), hoisted the SAME way as accountOpen/paletteOpen:
    // a plain window-level boolean, passed down as a prop rather than local state inside
    // AppRoot, so every overlay's open/close state lives at one consistent level.
    var alertsCenterOpen by remember { mutableStateOf(false) }
    // Push/email notification toggles (increment 6d.1's Notifications page), hoisted here so
    // both AccountPanel and the load-at-startup effect below share the one in-memory copy.
    // Starts at AppSettings() defaults and is overwritten once the startup load resolves —
    // same "no flash of the wrong value" reasoning as DK.accent below (the defaults ARE
    // AppSettings()'s defaults, so there's nothing to flash).
    var notificationSettings by remember { mutableStateOf(com.aptrade.desktop.infra.AppSettings()) }
    val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)

    // Load persisted settings once at startup. DK.accent stays Champagne Gold, DK.isDark stays
    // true, LocalizationManager.current stays English, and notificationSettings stays at
    // AppSettings() defaults until this resolves — all pixel/value-identical to the defaults,
    // so no flash of the wrong state. Applying the loaded accent/isDarkMode flips DK.accent/
    // DK.isDark, which recomposes every gold/mode reader (an instant colorScheme swap — no
    // animation, recorded decision); applying the loaded language likewise flips
    // LocalizationManager.current, recomposing every tr() reader.
    // CancellationException must propagate so scope teardown isn't swallowed.
    LaunchedEffect(Unit) {
        try {
            val loaded = graph.settingsStore.load()
            DK.accent.value = loaded.accent
            DK.isDark.value = loaded.isDarkMode
            LocalizationManager.current.value = loaded.language
            notificationSettings = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Corrupt/unreadable settings already fall back to defaults inside the store; any
            // other unexpected failure keeps the default accent/theme rather than crashing startup.
        }
    }

    // Fire-and-forget settings persist: load-merge-save so concurrent fields (accent vs. the
    // notification flags) never clobber each other — replaces an earlier version that always
    // wrote a fresh `AppSettings(accent = theme)`, silently resetting every notification flag
    // back to its default on the very next accent change. Both selectAccent and the
    // Notifications page's per-toggle callbacks route through this one function now.
    //
    // The actual load-merge-save sequence lives in the package-visible `persistSettings`
    // function in AppGraph.kt (review fix for Task 5) so it's pinned by a test against a
    // real `FileSettingsStore` — this local function is just the fire-and-forget/error-
    // swallowing wrapper around it. No behavior change from the fully-inline version.
    fun persistSettings(mutate: (com.aptrade.desktop.infra.AppSettings) -> com.aptrade.desktop.infra.AppSettings) {
        appScope.launch {
            try {
                persistSettings(graph.settingsStore, mutate)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A failed persist leaves the in-memory state applied; it just won't survive a
                // restart. Not worth interrupting the user with an error for a cosmetic write.
            }
        }
    }

    fun selectAccent(theme: com.aptrade.desktop.designkit.AccentTheme) {
        DK.accent.value = theme
        persistSettings { it.copy(accent = theme) }
    }

    // Mirrors selectAccent exactly: flip the live DK state first (instant colorScheme swap,
    // no animation — recorded decision) then persist through the same load-merge-save seam.
    fun selectTheme(isDarkMode: Boolean) {
        DK.isDark.value = isDarkMode
        persistSettings { it.copy(isDarkMode = isDarkMode) }
    }

    // Mirrors selectTheme exactly: flip the live LocalizationManager state first (every tr()
    // reader recomposes immediately, same "no flash, no animation" reasoning as accent/theme)
    // then persist through the same load-merge-save seam — no second persistence path.
    fun selectLanguage(lang: AppLanguage) {
        LocalizationManager.current.value = lang
        persistSettings { it.copy(language = lang) }
    }

    fun updateNotificationSettings(mutate: (com.aptrade.desktop.infra.AppSettings) -> com.aptrade.desktop.infra.AppSettings) {
        notificationSettings = mutate(notificationSettings)
        persistSettings(mutate)
    }

    fun closePalette() {
        paletteOpen = false
        searchViewModel.reset()
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "APTrade",
        onPreviewKeyEvent = { event ->
            val isK = event.key == Key.K && (event.isMetaPressed || event.isCtrlPressed)
            when {
                event.type != KeyEventType.KeyDown -> false
                isK -> {
                    paletteOpen = true
                    true
                }
                event.key == Key.Escape && paletteOpen -> {
                    closePalette()
                    true
                }
                event.key == Key.Escape && openSymbol != null && tradeTarget == null && alertTarget == null && !accountOpen -> {
                    openSymbol = null
                    true
                }
                // Gate pane-selection Esc to the active destination only (Task 6 review fix).
                event.key == Key.Escape && sidebarSelection == SidebarDestination.Markets(MarketsSection.Watchlist) && watchlistViewModel.state.value.selectedSymbol != null && tradeTarget == null && alertTarget == null && !accountOpen -> {
                    watchlistViewModel.closeDetail()
                    true
                }
                event.key == Key.Escape && sidebarSelection == SidebarDestination.Markets(MarketsSection.Screener) && screenerSelectedSymbol != null && tradeTarget == null && alertTarget == null && !accountOpen -> {
                    screenerSelectedSymbol = null
                    true
                }
                else -> false
            }
        },
    ) {
        LaunchedEffect(Unit) {
            // Min-width floor, re-derived (constraint 6 — do not copy Swift's number, derive
            // independently from THIS shell's real columns): 208dp rail + the widest list
            // column this shell will host once Task 6 lands conditional master-detail
            // (Screener's ~520dp table, wider than Watchlist's ~300dp) + 1dp hairline divider
            // + a legible ~390dp detail pane = 1119dp, bumped to 1120. That happens to land on
            // the same figure as `RootView.macBody`'s 1120 (208 + 520 + 1 + ~390) — not copied,
            // just the same real column proportions on both shells. The previous floor
            // (1000×680) was smaller than this derivation, so it's bumped up; height (680)
            // already matched and is unchanged.
            window.minimumSize = Dimension(1120, 680)
            watchlistViewModel.start()
            portfolioViewModel.start()
            marketActivityCoordinator.start()
        }
        DisposableEffect(Unit) { onDispose { appScope.cancel(); graph.close() } }

        CompositionLocalProvider(LocalAppGraph provides graph) {
            APTradeDesktopTheme {
                AppRoot(
                    watchlistViewModel = watchlistViewModel,
                    portfolioViewModel = portfolioViewModel,
                    newsViewModel = newsViewModel,
                    calendarViewModel = calendarViewModel,
                    searchViewModel = searchViewModel,
                    addFieldViewModel = addFieldViewModel,
                    paletteOpen = paletteOpen,
                    onOpenPalette = { paletteOpen = true },
                    onClosePalette = { closePalette() },
                    openSymbol = openSymbol,
                    onOpenDetail = { symbol -> openSymbol = symbol },
                    onBack = { openSymbol = null },
                    screenerSelectedSymbol = screenerSelectedSymbol,
                    onSetScreenerSelectedSymbol = { symbol -> screenerSelectedSymbol = symbol },
                    tradeTarget = tradeTarget,
                    onOpenTrade = { target -> tradeTarget = target },
                    onCloseTrade = { tradeTarget = null },
                    accountOpen = accountOpen,
                    onOpenAccount = { accountOpen = true },
                    onCloseAccount = { accountOpen = false },
                    accent = DK.accent.value,
                    onSelectAccent = { theme -> selectAccent(theme) },
                    isDarkMode = DK.isDark.value,
                    onSelectTheme = { dark -> selectTheme(dark) },
                    language = LocalizationManager.current.value,
                    onSelectLanguage = { lang -> selectLanguage(lang) },
                    alertTarget = alertTarget,
                    onOpenAlert = { asset -> alertTarget = asset },
                    onCloseAlert = { alertTarget = null },
                    alertsCenterOpen = alertsCenterOpen,
                    onOpenAlertsCenter = { alertsCenterOpen = true },
                    onCloseAlertsCenter = { alertsCenterOpen = false },
                    notificationSettings = notificationSettings,
                    onUpdateNotificationSettings = { mutate -> updateNotificationSettings(mutate) },
                    sidebarSelection = sidebarSelection,
                    onSidebarSelectionChange = { sidebarSelection = it },
                )
            }
        }
    }
}

/** The open trade dialog's target: which asset, which side to pre-select, and the live price
 *  text (already `Money.amountText`-formatted) used for display and the cost estimate. */
data class TradeTarget(val asset: Asset, val side: TradeSide, val priceText: String?)

@Composable
private fun AppRoot(
    watchlistViewModel: WatchlistViewModel,
    portfolioViewModel: PortfolioViewModel,
    newsViewModel: NewsViewModel,
    calendarViewModel: CalendarViewModel,
    searchViewModel: SearchViewModel,
    addFieldViewModel: SearchViewModel,
    paletteOpen: Boolean,
    onOpenPalette: () -> Unit,
    onClosePalette: () -> Unit,
    openSymbol: String?,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    screenerSelectedSymbol: String?,
    onSetScreenerSelectedSymbol: (String?) -> Unit,
    tradeTarget: TradeTarget?,
    onOpenTrade: (TradeTarget) -> Unit,
    onCloseTrade: () -> Unit,
    accountOpen: Boolean,
    onOpenAccount: () -> Unit,
    onCloseAccount: () -> Unit,
    accent: com.aptrade.desktop.designkit.AccentTheme,
    onSelectAccent: (com.aptrade.desktop.designkit.AccentTheme) -> Unit,
    isDarkMode: Boolean,
    onSelectTheme: (Boolean) -> Unit,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    alertTarget: Asset?,
    onOpenAlert: (Asset) -> Unit,
    onCloseAlert: () -> Unit,
    alertsCenterOpen: Boolean,
    onOpenAlertsCenter: () -> Unit,
    onCloseAlertsCenter: () -> Unit,
    notificationSettings: com.aptrade.desktop.infra.AppSettings,
    onUpdateNotificationSettings: ((com.aptrade.desktop.infra.AppSettings) -> com.aptrade.desktop.infra.AppSettings) -> Unit,
    sidebarSelection: SidebarDestination,
    onSidebarSelectionChange: (SidebarDestination) -> Unit,
) {
    // Composition-scoped coroutine launcher for the export buttons below — `exportSnapshot`/
    // `exportCsv`/`exportJson` became `suspend` in M8.2 Task 11 (dividend-events fetch for
    // `projectedAnnualIncome`), so their click handlers need somewhere to launch into.
    val exportScope = rememberCoroutineScope()
    val watchState by watchlistViewModel.state.collectAsState()
    val portfolioState by portfolioViewModel.state.collectAsState()
    val newsState by newsViewModel.state.collectAsState()
    val searchState by searchViewModel.state.collectAsState()
    val addFieldState by addFieldViewModel.state.collectAsState()

    // Lazy first-visit start for the News VM: fire its single fetch only once the user opens
    // the News section, and never again. Guarded by a one-shot flag so re-entering it is free.
    // Re-keyed to `sidebarSelection` (M10.2 Task 3, was `selectedTab`).
    var newsStarted by remember { mutableStateOf(false) }
    LaunchedEffect(sidebarSelection) {
        if (sidebarSelection == SidebarDestination.Markets(MarketsSection.News) && !newsStarted) {
            newsStarted = true
            newsViewModel.start()
        }
    }
    // Mirrors newsStarted exactly: the Calendar section's single 14-day load fires only once
    // the user opens it. Re-keyed to `sidebarSelection` (M10.2 Task 3, was `selectedTab`).
    var calendarStarted by remember { mutableStateOf(false) }
    LaunchedEffect(sidebarSelection) {
        if (sidebarSelection == SidebarDestination.Markets(MarketsSection.Calendar) && !calendarStarted) {
            calendarStarted = true
            calendarViewModel.load()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AppShell(
            selection = sidebarSelection,
            onSelect = onSidebarSelectionChange,
            onOpenPalette = onOpenPalette,
            onOpenAccount = onOpenAccount,
            isDarkMode = isDarkMode,
            onToggleTheme = onSelectTheme,
        ) {
            // Every destination/section is wrapped in `key(...)` (the Swift U5 lesson,
            // pre-empted here rather than fixed later): switching `sidebarSelection` must
            // fully tear down whatever the previous section composed, not just swap the data
            // a surviving subtree reads — otherwise a future pane that pushes its own local
            // state (e.g. a Watchlist/Screener in-pane detail after Task 6, or a pushed pie
            // detail inside Plans) could keep that state alive across a section switch, the
            // exact bug Swift's `.invest(let section)` case hit when only the enum's
            // associated value changed and the surrounding NavigationStack was never rebuilt.
            when (val destination = sidebarSelection) {
                is SidebarDestination.Home -> key(destination) {
                    // Pane-owned scope (M10.2 Task 4) — mirrors ScreenerPane's own
                    // remember + DisposableEffect(scope.cancel()) idiom, just constructed
                    // here rather than inside HomePane itself (HomePane stays a plain,
                    // props-in composable like PortfolioPane/WatchlistPane). LocalAppGraph
                    // is reachable here the same way ScreenerPane/PlansPane/IncomePane read
                    // it deep in the tree — AppRoot sits inside Main's
                    // CompositionLocalProvider(LocalAppGraph provides graph).
                    val graph = LocalAppGraph.current
                    val homeScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
                    val homeViewModel = remember { graph.makeHomeViewModel(homeScope) }
                    DisposableEffect(Unit) { onDispose { homeScope.cancel() } }
                    HomePane(
                        vm = homeViewModel,
                        portfolioState = portfolioState,
                        onSetPortfolioSpan = portfolioViewModel::setSpan,
                        // The Alerts card's 2-row preview reads the raw alert list directly
                        // (its own lightweight polling loop, HomePane's own concern) via the
                        // SAME AppGraph.loadAlerts use case the Alerts center dialog's
                        // AlertsCenterViewModel also reads from (M10.2 Task 5) — one store,
                        // two independent readers, never a second cache.
                        loadAlerts = graph.loadAlerts::execute,
                        // Constraint 3: Home-row navigation writes sidebarSelection directly,
                        // no request/clear dance.
                        onNavigate = { onSidebarSelectionChange(it) },
                        onOpenAlerts = onOpenAlertsCenter,
                    )
                }
                is SidebarDestination.Markets -> key(destination.section) {
                    when (destination.section) {
                        // M10.2 Task 6: Watchlist/Screener each own their conditional
                        // master–detail split now — `openSymbol` no longer routes here (it
                        // dissolves M9.2's recorded "detail carries across tab switch" minor
                        // and T3's known-live carry-across: the two panes each read their OWN
                        // selection, so opening one's detail can never bleed into the other's
                        // render the way a single shared `openSymbol` used to). Buy/heldPosition
                        // are threaded in as plain params — the exact two arguments Main used
                        // to pass straight to the window-level `DetailScreen` for this symbol.
                        MarketsSection.Watchlist ->
                            WatchlistPane(
                                state = watchState,
                                onKindSelect = watchlistViewModel::onKindSelect,
                                onSelect = watchlistViewModel::onSelect,
                                onCloseDetail = watchlistViewModel::closeDetail,
                                onAdd = watchlistViewModel::onAdd,
                                onRemove = watchlistViewModel::onRemove,
                                onSetAlert = { symbol ->
                                    // Watchlist rows only (macOS parity: no detail-screen alert
                                    // entry) — resolve the row's name/kind into the Asset the
                                    // sheet's header needs.
                                    watchState.rows.firstOrNull { it.symbol == symbol }?.let { row ->
                                        onOpenAlert(Asset(symbol = row.symbol, name = row.name, kind = row.kind))
                                    }
                                },
                                suggestQuery = addFieldState.query,
                                suggestResults = addFieldState.results,
                                onSuggestQueryChange = addFieldViewModel::onQueryChange,
                                onSuggestReset = addFieldViewModel::reset,
                                heldPosition = portfolioState.holdings.firstOrNull { it.symbol == watchState.selectedSymbol },
                                onBuy = { asset, priceText ->
                                    onOpenTrade(TradeTarget(asset, TradeSide.Buy, priceText))
                                },
                            )
                        MarketsSection.Screener ->
                            ScreenerPane(
                                selectedSymbol = screenerSelectedSymbol,
                                onSelectSymbol = { symbol ->
                                    // Row click: toggle open/closed — the macOS "tap again to
                                    // close" affordance (mirrors WatchlistViewModel.onSelect).
                                    onSetScreenerSelectedSymbol(
                                        if (screenerSelectedSymbol == symbol) null else symbol,
                                    )
                                },
                                onCloseDetail = { onSetScreenerSelectedSymbol(null) },
                                heldPosition = portfolioState.holdings.firstOrNull { it.symbol == screenerSelectedSymbol },
                                onBuy = { asset, priceText ->
                                    onOpenTrade(TradeTarget(asset, TradeSide.Buy, priceText))
                                },
                            )
                        MarketsSection.Calendar -> CalendarPane(calendarViewModel)
                        MarketsSection.News -> NewsPane(
                            state = newsState,
                            onSetCategory = newsViewModel::setCategory,
                            onSetShowingSaved = newsViewModel::setShowingSaved,
                            onSetFilter = newsViewModel::setFilter,
                            onRefresh = newsViewModel::refresh,
                            onToggleBookmark = newsViewModel::toggleBookmark,
                        )
                    }
                }
                is SidebarDestination.Portfolio -> key(destination.section) {
                    PortfolioPane(
                        state = portfolioState,
                        section = destination.section,
                        onSetSpan = portfolioViewModel::setSpan,
                        onSetBenchmark = portfolioViewModel::setBenchmark,
                        onOpenDetail = onOpenDetail,
                        onTrade = { symbol, side ->
                            // Held-asset trades: reuse the row's name/kind + live price from state.
                            val row = portfolioState.holdings.firstOrNull { it.symbol == symbol }
                            val asset = Asset(
                                symbol = symbol,
                                name = row?.name ?: symbol,
                                kind = row?.kind ?: com.aptrade.shared.domain.AssetKind.Stock,
                            )
                            onOpenTrade(TradeTarget(asset, side, row?.priceText))
                        },
                        onReset = portfolioViewModel::reset,
                        onExportCsv = {
                            exportScope.launch { saveTextFile("portfolio.csv", portfolioViewModel.exportCsv()) }
                        },
                        onExportJson = {
                            exportScope.launch { saveTextFile("portfolio.json", portfolioViewModel.exportJson()) }
                        },
                        onExportPdf = {
                            exportScope.launch {
                                val now = System.currentTimeMillis() / 1000
                                saveBinaryFile(
                                    exportFileName("pdf", now),
                                    renderPortfolioPdf(portfolioViewModel.exportSnapshot()),
                                )
                            }
                        },
                    )
                }
                is SidebarDestination.Invest -> key(destination.section) {
                    when (destination.section) {
                        InvestSection.Plans -> PlansPane()
                        InvestSection.Income -> IncomePane(
                            notificationSettings = notificationSettings,
                            onUpdateNotificationSettings = onUpdateNotificationSettings,
                        )
                    }
                }
            }
        }

        // `openSymbol` (M10.2 Task 6): with Watchlist/Screener now owning their OWN
        // conditional-split selection (WatchlistViewModel.selectedSymbol /
        // screenerSelectedSymbol above), this window-hoisted flag's only remaining setter
        // is PortfolioPane's Holdings row click (`onOpenDetail` passed to it below). It's
        // rendered here — a section-agnostic overlay, like tradeTarget/alertTarget below,
        // rather than gated inside one `when` branch — so it shows regardless of which
        // sidebar destination is active. This is a deliberate improvement over the
        // pre-Task-6 shape: previously `openSymbol` only ever rendered under
        // `Markets(Watchlist)`/`Markets(Screener)`, so a Portfolio-row open silently did
        // nothing until the user happened to switch to one of those two sections (the
        // "general gap for every other onOpenDetail caller" the Alerts-center comment
        // below already flagged) — moving the render site here fixes that gap for free.
        if (openSymbol != null) {
            DetailScreen(
                symbol = openSymbol,
                // Pass the held position row (or null) from portfolio state — the
                // detail screen's YOUR POSITION card reads it directly; no second store.
                heldPosition = portfolioState.holdings.firstOrNull { it.symbol == openSymbol },
                onBack = onBack,
                onBuy = { asset, priceText ->
                    onOpenTrade(TradeTarget(asset, TradeSide.Buy, priceText))
                },
            )
        }

        if (paletteOpen) {
            PaletteOverlay(
                viewModel = searchViewModel,
                onAdd = { asset ->
                    watchlistViewModel.onAdd(WatchlistEntry(asset.symbol, asset.name, asset.kind))
                    // Palette-opened detail always targets the Watchlist pane's own
                    // conditional split (M10.2 Task 6) — same mapping the Alerts center
                    // uses below. `openDetail` (not `onSelect`) always sets, never
                    // toggles off, even if this exact symbol is already selected.
                    onSidebarSelectionChange(SidebarDestination.Markets(MarketsSection.Watchlist))
                    watchlistViewModel.openDetail(asset.symbol)
                },
                onClose = onClosePalette,
            )
        }

        // The account/settings panel overlays the shell. It self-consumes Esc on its own panel
        // (TradeDialog pattern), so it never competes with the window's palette Esc. M10.2 Task 7
        // (the settings-honesty pass, Swift M10.1 Task 8's desktop twin) re-homes Export Portfolio
        // Data to PortfolioPane's own summary-header button and DRIP to IncomePane's header card —
        // this panel no longer triggers either, so it needs no export/DRIP wiring of its own.
        if (accountOpen) {
            AccountPanel(
                accent = accent,
                onSelectAccent = onSelectAccent,
                isDarkMode = isDarkMode,
                onSelectTheme = onSelectTheme,
                language = language,
                onSelectLanguage = onSelectLanguage,
                onClose = onCloseAccount,
                notificationSettings = notificationSettings,
                onUpdateNotificationSettings = onUpdateNotificationSettings,
            )
        }

        // The trade dialog overlays everything (palette included), so it sits last in the Box
        // and consumes Esc on its own panel before the window-level chain ever sees the event.
        tradeTarget?.let { target ->
            // A buy/sell is fire-and-forget on the VM; it either appends a transaction (success)
            // or sets tradeError (failure). Snapshot the transaction count when the dialog opens
            // and auto-close once it grows — so the dialog stays put to show an inline error.
            val txnCountAtOpen = remember(target) { portfolioState.transactions.size }
            LaunchedEffect(target, portfolioState.transactions.size) {
                if (portfolioState.transactions.size > txnCountAtOpen) onCloseTrade()
            }
            // Snapshot at open, matching TradeSheet.swift:18 (`self.confirmTrades =
            // CompositionRoot.loadSettings().confirmTrades`) — read once when the dialog
            // opens, never re-read mid-dialog even if the user toggles the setting elsewhere
            // (they can't, since this dialog is modal, but the snapshot semantics still apply).
            val confirmTradesAtOpen = remember(target) { notificationSettings.confirmTrades }
            TradeDialog(
                asset = target.asset,
                initialSide = target.side,
                priceText = target.priceText,
                tradeError = portfolioState.tradeError,
                confirmTrades = confirmTradesAtOpen,
                onSubmit = { side, quantityText ->
                    when (side) {
                        TradeSide.Buy -> portfolioViewModel.buy(target.asset, quantityText)
                        TradeSide.Sell -> portfolioViewModel.sell(target.asset.symbol, quantityText)
                        // The manual trade dialog never offers Dividend as a selectable side
                        // today (see TradeDialog.kt's Buy/Sell-only option list) — this branch
                        // only staves off the non-exhaustive `when` warning.
                        // Real handling lands with the coordinator task.
                        TradeSide.Dividend -> Unit
                    }
                },
                onDismiss = onCloseTrade,
            )
        }

        // The price-alert sheet overlays everything like the trade dialog, and likewise
        // consumes Esc on its own panel before the window-level chain sees it. Live price
        // text comes from the watchlist row (same source TradeTarget uses for its estimate).
        alertTarget?.let { asset ->
            PriceAlertSheet(
                asset = asset,
                currentPriceText = watchState.rows.firstOrNull { it.symbol == asset.symbol }?.amountText,
                existing = watchlistViewModel.alertsFor(asset.symbol),
                onCreate = { condition -> watchlistViewModel.createAlert(asset.symbol, condition) },
                onDelete = { id -> watchlistViewModel.deleteAlert(id) },
                onDismiss = onCloseAlert,
            )
        }

        // The Alerts center overlays everything like the trade/alert-sheet dialogs above
        // (M10.2 Task 5), and likewise renders via a real Dialog that consumes its own Esc.
        if (alertsCenterOpen) {
            AlertsCenterDialog(
                onDismiss = onCloseAlertsCenter,
                onSelectSymbol = { symbol ->
                    // Tap-through always targets the Watchlist pane's own conditional split
                    // (M10.2 Task 6 — same mapping the palette uses above): routing
                    // sidebarSelection there explicitly is what makes this tap-through's
                    // detail screen actually show, rather than setting a selection underneath
                    // whatever destination happened to be active. `openDetail` (not
                    // `onSelect`) always sets, never toggles off.
                    onSidebarSelectionChange(SidebarDestination.Markets(MarketsSection.Watchlist))
                    watchlistViewModel.openDetail(symbol)
                    onCloseAlertsCenter()
                },
            )
        }
    }
}
