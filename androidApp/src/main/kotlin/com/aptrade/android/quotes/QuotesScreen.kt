package com.aptrade.android.quotes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.ui.ErrorPane
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import java.util.Locale

@Composable
fun QuotesScreen(
    onOpenSearch: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenPortfolio: () -> Unit,
) {
    val viewModel: QuotesViewModel = viewModel {
        QuotesViewModel(AppGraph.fetchMarketQuotes, AppGraph.defaultSymbols)
    }
    val state by viewModel.state.collectAsState()
    QuotesContent(state, viewModel::refresh, onOpenSearch, onOpenDetail, onOpenPortfolio)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuotesContent(
    state: QuotesUiState,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenPortfolio: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APTrade") },
                actions = {
                    IconButton(onClick = onOpenPortfolio) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Portfolio")
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null ->
                    ErrorPane(state.error, onRetry = onRefresh, Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.symbol }) { row ->
                        QuoteRowItem(row, onClick = { onOpenDetail(row.symbol) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteRowItem(row: QuoteRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.symbol, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(row.priceText, style = MaterialTheme.typography.bodyLarge)
            val up = row.changePercent >= 0
            Text(
                text = String.format(Locale.US, "%+.2f%%", row.changePercent),
                color = if (up) GainGreen else LossRed,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
