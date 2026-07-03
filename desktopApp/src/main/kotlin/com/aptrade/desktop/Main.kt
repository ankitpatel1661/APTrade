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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.aptrade.desktop.designkit.APTradeDesktopTheme
import com.aptrade.desktop.detail.DetailScreen
import com.aptrade.desktop.portfolio.PortfolioPane
import com.aptrade.desktop.portfolio.PortfolioViewModel
import com.aptrade.desktop.portfolio.TradeDialog
import com.aptrade.desktop.infra.saveTextFile
import com.aptrade.desktop.search.PaletteOverlay
import com.aptrade.desktop.search.SearchViewModel
import com.aptrade.desktop.ui.AppShell
import com.aptrade.desktop.ui.AppTab
import com.aptrade.desktop.ui.PlaceholderPane
import com.aptrade.desktop.watchlist.WatchlistPane
import com.aptrade.desktop.watchlist.WatchlistViewModel
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Dimension

fun main() = application {
    // ONE AppGraph — one Ktor client — for the whole process.
    val graph = remember { AppGraph() }

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
            fetchPortfolioPerformance = graph.fetchPortfolioPerformance,
            fetchPerformanceReport = graph.fetchPerformanceReport,
            scope = appScope,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
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
    // palette so the dialog overlays the whole window. The dialog owns its own Esc handling.
    var tradeTarget by remember { mutableStateOf<TradeTarget?>(null) }
    val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)

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
        }
        DisposableEffect(Unit) { onDispose { appScope.cancel(); graph.close() } }

        CompositionLocalProvider(LocalAppGraph provides graph) {
            APTradeDesktopTheme {
                AppRoot(
                    watchlistViewModel = watchlistViewModel,
                    portfolioViewModel = portfolioViewModel,
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
) {
    var selectedTab by remember { mutableStateOf(AppTab.Watchlist) }
    val watchState by watchlistViewModel.state.collectAsState()
    val portfolioState by portfolioViewModel.state.collectAsState()
    val searchState by searchViewModel.state.collectAsState()
    val addFieldState by addFieldViewModel.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        AppShell(
            selectedTab = selectedTab,
            onTabSelect = { selectedTab = it },
            onOpenPalette = onOpenPalette,
        ) {
            when (selectedTab) {
                AppTab.Watchlist ->
                    if (openSymbol != null) {
                        DetailScreen(
                            symbol = openSymbol,
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
                            suggestQuery = addFieldState.query,
                            suggestResults = addFieldState.results,
                            onSuggestQueryChange = addFieldViewModel::onQueryChange,
                            onSuggestReset = addFieldViewModel::reset,
                        )
                    }
                AppTab.Portfolio -> PortfolioPane(
                    state = portfolioState,
                    onSetSpan = portfolioViewModel::setSpan,
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
                )
                AppTab.News -> PlaceholderPane("News — coming soon")
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
            TradeDialog(
                asset = target.asset,
                initialSide = target.side,
                priceText = target.priceText,
                tradeError = portfolioState.tradeError,
                onSubmit = { side, quantityText ->
                    when (side) {
                        TradeSide.Buy -> portfolioViewModel.buy(target.asset, quantityText)
                        TradeSide.Sell -> portfolioViewModel.sell(target.asset.symbol, quantityText)
                    }
                },
                onDismiss = onCloseTrade,
            )
        }
    }
}

/** Inverse of designkit.kindLabel — rebuilds an AssetKind from its display label so the host
 *  can construct an Asset for a trade. Stock is the safe default for any unexpected label. */
private fun assetKindFromLabel(label: String?): com.aptrade.shared.domain.AssetKind = when (label) {
    "ETF" -> com.aptrade.shared.domain.AssetKind.Etf
    "Crypto" -> com.aptrade.shared.domain.AssetKind.Crypto
    else -> com.aptrade.shared.domain.AssetKind.Stock
}
