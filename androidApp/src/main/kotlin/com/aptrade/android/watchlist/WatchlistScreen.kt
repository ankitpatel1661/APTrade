package com.aptrade.android.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.l10n.trf
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.ErrorPane
import com.aptrade.android.ui.formatPercent
import com.aptrade.android.ui.money
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.launch

/** The Watchlist tab root — replaces the hard-coded `QuotesScreen` (Task 5). Wired against
 *  [AppGraph]'s persisted watchlist + market-quote use cases; live prices poll every 15s while
 *  this screen is at least STARTED ([androidx.lifecycle.compose.LifecycleStartEffect] mirrors
 *  `PortfolioScreen`'s start()/stop() convention).
 *
 *  [padding] is [AppShell]'s Scaffold content padding — this screen (and only this screen, this
 *  task) applies it, so rows don't render underneath the bottom NavigationBar. It intentionally
 *  does NOT build its own Scaffold/TopAppBar: `AppShell` already provides the "APTrade" bar. */
@Composable
fun WatchlistScreen(
    padding: PaddingValues,
    onOpenSearch: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val viewModel: WatchlistViewModel = viewModel {
        WatchlistViewModel(
            fetchWatchlist = AppGraph.fetchWatchlist,
            addToWatchlist = AppGraph.addToWatchlist,
            removeFromWatchlist = AppGraph.removeFromWatchlist,
            fetchMarketQuotes = AppGraph.fetchMarketQuotes,
        )
    }

    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsState()
    WatchlistContent(
        state = state,
        padding = padding,
        onOpenSearch = onOpenSearch,
        onOpenDetail = onOpenDetail,
        onRefresh = viewModel::refresh,
        onRemove = viewModel::remove,
        onUndoRemove = viewModel::add,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchlistContent(
    state: WatchlistUiState,
    padding: PaddingValues,
    onOpenSearch: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onRefresh: () -> Unit,
    onRemove: suspend (String) -> Unit,
    onUndoRemove: suspend (WatchlistEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = tr(L10n.Key.Add)

    Box(Modifier.padding(padding).fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading && state.rows.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading && state.rows.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.rows.isEmpty() ->
                    ErrorPane(state.error, onRetry = onRefresh, Modifier.align(Alignment.Center))
                state.rows.isEmpty() -> EmptyWatchlist(onOpenSearch, Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.symbol }) { row ->
                        SwipeToRemoveRow(
                            row = row,
                            onClick = { onOpenDetail(row.symbol) },
                            onRemove = {
                                scope.launch {
                                    onRemove(row.symbol)
                                    val result = snackbarHostState.showSnackbar(
                                        message = trf(L10n.Key.RemovedSymbolFmt, row.symbol),
                                        actionLabel = undoLabel,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        onUndoRemove(WatchlistEntry(row.symbol, row.name, row.kind))
                                    }
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemoveRow(row: WatchRow, onClick: () -> Unit, onRemove: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onRemove()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = tr(L10n.Key.RemoveFromWatchlistHelp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        WatchlistRow(row = row, onClick = onClick)
    }
}

@Composable
private fun WatchlistRow(row: WatchRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                row.symbol,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                row.amountText?.let { money(it) } ?: "—",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                formatPercent(row.changePercent),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    row.changePercent == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    row.changePercent >= 0 -> GainGreen
                    else -> LossRed
                },
            )
        }
    }
}

/** Shown when the persisted watchlist has no entries — points the user at search, the only
 *  add-entry path this task wires up (`SearchScreen`'s trailing add button). */
@Composable
private fun EmptyWatchlist(onOpenSearch: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clickable(onClick = onOpenSearch).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            tr(L10n.Key.AddSymbolHint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
