package com.aptrade.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.aptrade.desktop.designkit.APTradeDesktopTheme
import com.aptrade.desktop.detail.DetailScreen
import com.aptrade.desktop.news.NewsPane
import com.aptrade.desktop.news.NewsViewModel
import com.aptrade.desktop.portfolio.PortfolioPane
import com.aptrade.desktop.portfolio.PortfolioViewModel
import com.aptrade.desktop.portfolio.TradeDialog
import com.aptrade.desktop.infra.exportFileName
import com.aptrade.desktop.infra.renderPortfolioPdf
import com.aptrade.desktop.infra.saveBinaryFile
import com.aptrade.desktop.infra.saveTextFile
import com.aptrade.desktop.search.PaletteOverlay
import com.aptrade.desktop.search.SearchViewModel
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.l10n.AppLanguage
import com.aptrade.desktop.l10n.LocalizationManager
import com.aptrade.desktop.ui.AccountPanel
import com.aptrade.desktop.ui.AppShell
import com.aptrade.desktop.ui.AppTab
import com.aptrade.desktop.ui.assetKindFromLabel
import com.aptrade.desktop.watchlist.PriceAlertSheet
import com.aptrade.desktop.watchlist.WatchlistPane
import com.aptrade.desktop.watchlist.WatchlistViewModel
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.Dimension

fun main() = application {
    // ONE AppGraph — one Ktor client — for the whole process.
    val graph = remember { AppGraph() }

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
            fetchWatchlist = graph.fetchWatchlist,
            fetchMarketQuotes = graph.fetchMarketQuotes,
            scope = appScope,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
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
    // Two independent search surfaces (palette + the watchlist add-field) get their
    // own VM so their queries and result lists never bleed into each other.
    val searchViewModel = remember { SearchViewModel(graph.fetchSearch, appScope) }
    val addFieldViewModel = remember { SearchViewModel(graph.fetchSearch, appScope) }

    var paletteOpen by remember { mutableStateOf(false) }
    // Navigation state for the Watchlist tab: the open detail symbol (null = list).
    // Hoisted here so window-level Esc can pop it.
    var openSymbol by remember { mutableStateOf<String?>(null) }
    // The open trade dialog's target (asset + side + live price text), hoisted here like the
    // palette so the dialog overlays the whole window. The dialog owns its own Esc handling —
    // as do the Portfolio pane's own overlays (the reset-confirm dialog and the export chooser)
    // and the account/settings panel, each consuming Esc on its own onPreviewKeyEvent before the
    // window chain below sees it.
    var tradeTarget by remember { mutableStateOf<TradeTarget?>(null) }
    // The right-anchored account/settings panel (⋯ button). Hoisted here so it overlays the
    // whole window like the palette; it self-consumes Esc on its own panel, never the window's.
    var accountOpen by remember { mutableStateOf(false) }
    // One-shot trigger for the ⋯ panel's "Export Portfolio Data" row: flipping it true switches
    // to the Portfolio tab AND has PortfolioPane auto-open the export chooser (the pane consumes
    // and clears it). Hoisted here so the account panel and the pane share one signal.
    val pendingExport = remember { mutableStateOf(false) }
    // The open price-alert sheet's target symbol, hoisted like tradeTarget so it overlays the
    // whole window. The sheet self-consumes Esc on its own panel (TradeDialog pattern).
    var alertTarget by remember { mutableStateOf<Asset?>(null) }
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
                event.key == Key.Escape && openSymbol != null -> {
                    // Window-level Esc ownership: preview handling wins over any focused child.
                    // If a future detail-screen field needs Esc, rework this precedence first.
                    openSymbol = null
                    true
                }
                else -> false
            }
        },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1000, 680)
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
                    searchViewModel = searchViewModel,
                    addFieldViewModel = addFieldViewModel,
                    paletteOpen = paletteOpen,
                    onOpenPalette = { paletteOpen = true },
                    onClosePalette = { closePalette() },
                    openSymbol = openSymbol,
                    onOpenDetail = { symbol -> openSymbol = symbol },
                    onBack = { openSymbol = null },
                    tradeTarget = tradeTarget,
                    onOpenTrade = { target -> tradeTarget = target },
                    onCloseTrade = { tradeTarget = null },
                    accountOpen = accountOpen,
                    onOpenAccount = { accountOpen = true },
                    onCloseAccount = { accountOpen = false },
                    pendingExport = pendingExport,
                    accent = DK.accent.value,
                    onSelectAccent = { theme -> selectAccent(theme) },
                    isDarkMode = DK.isDark.value,
                    onSelectTheme = { dark -> selectTheme(dark) },
                    language = LocalizationManager.current.value,
                    onSelectLanguage = { lang -> selectLanguage(lang) },
                    alertTarget = alertTarget,
                    onOpenAlert = { asset -> alertTarget = asset },
                    onCloseAlert = { alertTarget = null },
                    notificationSettings = notificationSettings,
                    onUpdateNotificationSettings = { mutate -> updateNotificationSettings(mutate) },
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
    searchViewModel: SearchViewModel,
    addFieldViewModel: SearchViewModel,
    paletteOpen: Boolean,
    onOpenPalette: () -> Unit,
    onClosePalette: () -> Unit,
    openSymbol: String?,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    tradeTarget: TradeTarget?,
    onOpenTrade: (TradeTarget) -> Unit,
    onCloseTrade: () -> Unit,
    accountOpen: Boolean,
    onOpenAccount: () -> Unit,
    onCloseAccount: () -> Unit,
    pendingExport: androidx.compose.runtime.MutableState<Boolean>,
    accent: com.aptrade.desktop.designkit.AccentTheme,
    onSelectAccent: (com.aptrade.desktop.designkit.AccentTheme) -> Unit,
    isDarkMode: Boolean,
    onSelectTheme: (Boolean) -> Unit,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    alertTarget: Asset?,
    onOpenAlert: (Asset) -> Unit,
    onCloseAlert: () -> Unit,
    notificationSettings: com.aptrade.desktop.infra.AppSettings,
    onUpdateNotificationSettings: ((com.aptrade.desktop.infra.AppSettings) -> com.aptrade.desktop.infra.AppSettings) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(AppTab.Watchlist) }
    val watchState by watchlistViewModel.state.collectAsState()
    val portfolioState by portfolioViewModel.state.collectAsState()
    val newsState by newsViewModel.state.collectAsState()
    val searchState by searchViewModel.state.collectAsState()
    val addFieldState by addFieldViewModel.state.collectAsState()

    // Lazy first-visit start for the News VM: fire its single fetch only once the user opens
    // the News tab, and never again. Guarded by a one-shot flag so re-entering the tab is free.
    var newsStarted by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.News && !newsStarted) {
            newsStarted = true
            newsViewModel.start()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AppShell(
            selectedTab = selectedTab,
            onTabSelect = { selectedTab = it },
            onOpenPalette = onOpenPalette,
            onOpenAccount = onOpenAccount,
        ) {
            when (selectedTab) {
                AppTab.Watchlist ->
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
                    } else {
                        WatchlistPane(
                            state = watchState,
                            onKindSelect = watchlistViewModel::onKindSelect,
                            onSelect = { symbol ->
                                watchlistViewModel.onSelect(symbol)
                                onOpenDetail(symbol)
                            },
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
                        )
                    }
                AppTab.Portfolio -> PortfolioPane(
                    state = portfolioState,
                    onSetSpan = portfolioViewModel::setSpan,
                    onSetBenchmark = portfolioViewModel::setBenchmark,
                    onOpenDetail = onOpenDetail,
                    onTrade = { symbol, side ->
                        // Held-asset trades: reuse the row's name/kind + live price from state.
                        val row = portfolioState.holdings.firstOrNull { it.symbol == symbol }
                        val asset = Asset(
                            symbol = symbol,
                            name = row?.name ?: symbol,
                            kind = assetKindFromLabel(row?.kindLabel),
                        )
                        onOpenTrade(TradeTarget(asset, side, row?.priceText))
                    },
                    onReset = portfolioViewModel::reset,
                    onExportCsv = { saveTextFile("portfolio.csv", portfolioViewModel.exportCsv()) },
                    onExportJson = { saveTextFile("portfolio.json", portfolioViewModel.exportJson()) },
                    onExportPdf = {
                        val now = System.currentTimeMillis() / 1000
                        saveBinaryFile(
                            exportFileName("pdf", now),
                            renderPortfolioPdf(portfolioViewModel.exportSnapshot()),
                        )
                    },
                    pendingExport = pendingExport,
                )
                AppTab.News -> NewsPane(
                    state = newsState,
                    onSetCategory = newsViewModel::setCategory,
                    onSetShowingSaved = newsViewModel::setShowingSaved,
                    onSetFilter = newsViewModel::setFilter,
                    onRefresh = newsViewModel::refresh,
                    onToggleBookmark = newsViewModel::toggleBookmark,
                )
            }
        }

        if (paletteOpen) {
            PaletteOverlay(
                viewModel = searchViewModel,
                onAdd = { asset ->
                    watchlistViewModel.onAdd(WatchlistEntry(asset.symbol, asset.name, asset.kind))
                    watchlistViewModel.onSelect(asset.symbol)
                    onOpenDetail(asset.symbol)
                },
                onClose = onClosePalette,
            )
        }

        // The account/settings panel overlays the shell. It self-consumes Esc on its own panel
        // (TradeDialog pattern), so it never competes with the window's palette Esc. The Export
        // Portfolio Data row switches to the Portfolio tab AND raises the one-shot pendingExport
        // trigger, which PortfolioPane consumes to auto-open its Export… chooser — the earlier
        // DEVIATION (row only switched tabs) is now resolved.
        if (accountOpen) {
            AccountPanel(
                accent = accent,
                onSelectAccent = onSelectAccent,
                isDarkMode = isDarkMode,
                onSelectTheme = onSelectTheme,
                language = language,
                onSelectLanguage = onSelectLanguage,
                onExportPortfolio = {
                    selectedTab = AppTab.Portfolio
                    pendingExport.value = true
                    onCloseAccount()
                },
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
    }
}
