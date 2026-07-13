package com.aptrade.android.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.l10n.trf
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.ErrorPane
import com.aptrade.android.ui.localizedLabel
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(onOpenDetail: (String) -> Unit) {
    val viewModel: SearchViewModel = viewModel { SearchViewModel(AppGraph.fetchSearch) }
    val state by viewModel.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { scaffoldPadding ->
        Column(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(tr(L10n.Key.SearchTickerPlaceholder)) },
                singleLine = true,
            )
            Box(Modifier.fillMaxSize()) {
                when {
                    state.isSearching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error != null ->
                        ErrorPane(state.error!!, onRetry = viewModel::retry, Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.results, key = { it.symbol }) { row ->
                            ResultRow(
                                row = row,
                                onClick = { onOpenDetail(row.symbol) },
                                onAdd = {
                                    scope.launch {
                                        AppGraph.addToWatchlist.execute(
                                            WatchlistEntry(row.symbol, row.name, row.kind),
                                        )
                                        snackbarHostState.showSnackbar(trf(L10n.Key.AddedSymbolFmt, row.symbol))
                                    }
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun ResultRow(row: AssetRow, onClick: () -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.symbol, style = MaterialTheme.typography.titleMedium)
            Text(
                row.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AssistChip(onClick = onClick, label = { Text(row.kind.localizedLabel()) })
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = tr(L10n.Key.Add))
        }
    }
}
