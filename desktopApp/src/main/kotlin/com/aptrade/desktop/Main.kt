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
import com.aptrade.desktop.search.PaletteOverlay
import com.aptrade.desktop.search.SearchViewModel
import com.aptrade.desktop.ui.AppShell
import com.aptrade.desktop.ui.AppTab
import com.aptrade.desktop.ui.PlaceholderPane
import com.aptrade.desktop.watchlist.WatchlistPane
import com.aptrade.desktop.watchlist.WatchlistViewModel
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
    // Two independent search surfaces (palette + the watchlist add-field) get their
    // own VM so their queries and result lists never bleed into each other.
    val searchViewModel = remember { SearchViewModel(graph.fetchSearch, appScope) }
    val addFieldViewModel = remember { SearchViewModel(graph.fetchSearch, appScope) }

    var paletteOpen by remember { mutableStateOf(false) }
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
            if (event.type == KeyEventType.KeyDown && isK) {
                paletteOpen = true
                true
            } else {
                false
            }
        },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1000, 680)
            watchlistViewModel.start()
        }
        DisposableEffect(Unit) { onDispose { appScope.cancel(); graph.close() } }

        CompositionLocalProvider(LocalAppGraph provides graph) {
            APTradeDesktopTheme {
                AppRoot(
                    watchlistViewModel = watchlistViewModel,
                    searchViewModel = searchViewModel,
                    addFieldViewModel = addFieldViewModel,
                    paletteOpen = paletteOpen,
                    onOpenPalette = { paletteOpen = true },
                    onClosePalette = { closePalette() },
                )
            }
        }
    }
}

@Composable
private fun AppRoot(
    watchlistViewModel: WatchlistViewModel,
    searchViewModel: SearchViewModel,
    addFieldViewModel: SearchViewModel,
    paletteOpen: Boolean,
    onOpenPalette: () -> Unit,
    onClosePalette: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(AppTab.Watchlist) }
    val watchState by watchlistViewModel.state.collectAsState()
    val searchState by searchViewModel.state.collectAsState()
    val addFieldState by addFieldViewModel.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        AppShell(
            selectedTab = selectedTab,
            onTabSelect = { selectedTab = it },
            onOpenPalette = onOpenPalette,
        ) {
            when (selectedTab) {
                AppTab.Watchlist -> WatchlistPane(
                    state = watchState,
                    onKindSelect = watchlistViewModel::onKindSelect,
                    onSelect = watchlistViewModel::onSelect,
                    onAdd = watchlistViewModel::onAdd,
                    onRemove = watchlistViewModel::onRemove,
                    suggestQuery = addFieldState.query,
                    suggestResults = addFieldState.results,
                    onSuggestQueryChange = addFieldViewModel::onQueryChange,
                    onSuggestReset = addFieldViewModel::reset,
                )
                AppTab.Portfolio -> PlaceholderPane("Portfolio — coming soon")
                AppTab.News -> PlaceholderPane("News — coming soon")
            }
        }

        if (paletteOpen) {
            PaletteOverlay(
                viewModel = searchViewModel,
                onAdd = { asset ->
                    watchlistViewModel.onAdd(WatchlistEntry(asset.symbol, asset.name, asset.kind))
                    watchlistViewModel.onSelect(asset.symbol)
                },
                onClose = onClosePalette,
            )
        }
    }
}
