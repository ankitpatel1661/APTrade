package com.aptrade.android.news

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.l10n.tr
import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import com.aptrade.shared.l10n.L10n

/** The News tab root. Wired against [AppGraph]'s news use cases (null `fetchMarketNews` when
 *  no Finnhub key is configured — see `AppGraph.kt`'s `finnhubKeyConfig` wiring, rooted at the
 *  Android sandbox `configDir()` rather than a desktop-style path). Category articles load on
 *  demand (start + category switch + pull-to-refresh) — no poll, per the brief.
 *
 *  [padding] is [AppShell]'s Scaffold content padding — consumed here (like `WatchlistScreen`)
 *  so rows never render underneath the bottom NavigationBar. */
@Composable
fun NewsScreen(padding: PaddingValues) {
    val viewModel: NewsViewModel = viewModel {
        NewsViewModel(
            fetchMarketNews = AppGraph.fetchMarketNews,
            loadBookmarks = AppGraph.loadBookmarks,
            toggleBookmark = AppGraph.toggleBookmark,
        )
    }

    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { }
    }

    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    NewsContent(
        state = state,
        padding = padding,
        onSetCategory = viewModel::setCategory,
        onSetShowingSaved = viewModel::setShowingSaved,
        onSetFilter = viewModel::setFilter,
        onRefresh = viewModel::refresh,
        onToggleBookmark = viewModel::toggleBookmark,
        onOpenArticle = { url -> openInCustomTab(context, url) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewsContent(
    state: NewsUiState,
    padding: PaddingValues,
    onSetCategory: (NewsCategory) -> Unit,
    onSetShowingSaved: (Boolean) -> Unit,
    onSetFilter: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleBookmark: (NewsArticle) -> Unit,
    onOpenArticle: (String) -> Unit,
) {
    Box(Modifier.padding(padding).fillMaxSize()) {
        if (state.needsKey) {
            NoKeyState(Modifier.align(Alignment.Center))
        } else {
            Column(Modifier.fillMaxSize()) {
                CategoryChipRow(
                    category = state.category,
                    showingSaved = state.showingSaved,
                    onSetCategory = onSetCategory,
                    onSetShowingSaved = onSetShowingSaved,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                FilterField(
                    query = state.filter,
                    onQueryChange = onSetFilter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                )
                val visible = state.visibleArticles
                PullToRefreshBox(
                    isRefreshing = state.isLoading && state.articles.isNotEmpty(),
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when {
                        state.isLoading && visible.isEmpty() ->
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        visible.isEmpty() ->
                            Text(
                                if (state.showingSaved) {
                                    tr(L10n.Key.NoSavedArticles)
                                } else {
                                    tr(L10n.Key.NoHeadlinesRightNow)
                                },
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(visible, key = { it.id }) { article ->
                                ArticleRow(
                                    article = article,
                                    bookmarked = state.bookmarkedIds.contains(article.id),
                                    onOpen = { onOpenArticle(article.url) },
                                    onToggleBookmark = { onToggleBookmark(article) },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** General · Crypto · Merger capsules plus a trailing Saved toggle — the Android counterpart
 *  to desktop `NewsPane.kt`'s `PillRow`, rendered as Material3 [FilterChip]s. Selecting a
 *  category while Saved is active leaves the Saved view, mirroring desktop's `PillRow`. */
@Composable
private fun CategoryChipRow(
    category: NewsCategory,
    showingSaved: Boolean,
    onSetCategory: (NewsCategory) -> Unit,
    onSetShowingSaved: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in NewsCategory.entries) {
            FilterChip(
                selected = !showingSaved && c == category,
                onClick = {
                    if (showingSaved) onSetShowingSaved(false)
                    onSetCategory(c)
                },
                label = { Text(categoryLabel(c)) },
            )
        }
        FilterChip(
            selected = showingSaved,
            onClick = { onSetShowingSaved(!showingSaved) },
            label = { Text(tr(L10n.Key.Saved)) },
        )
    }
}

/** Live headline filter — the Android counterpart to desktop `NewsPane.kt`'s `FilterField`,
 *  rendered as a compact single-line [OutlinedTextField]. */
@Composable
private fun FilterField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(tr(L10n.Key.FilterHeadlinesPlaceholder)) },
        singleLine = true,
    )
}

/** Maps a [NewsCategory] to its localized chip text — mirrors desktop `NewsPane.kt`'s
 *  `categoryLabel`. `NewsCategory.displayName` itself lives in `:shared` (commonMain) and stays
 *  English-only per the desktop-only retrofit scope, so the mapping happens here too. */
private fun categoryLabel(category: NewsCategory): String = when (category) {
    NewsCategory.General -> tr(L10n.Key.NewsGeneral)
    NewsCategory.Crypto -> tr(L10n.Key.CryptoLabel)
    NewsCategory.Merger -> tr(L10n.Key.NewsMerger)
}

/** No-key empty state — replaces the entire tab body (chips, list) when no Finnhub key is
 *  configured. Exact string parity with desktop's `NoKeyState`. */
@Composable
private fun NoKeyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            tr(L10n.Key.ConnectNewsSource),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            tr(L10n.Key.FinnhubKeyInstructions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Opens [url] in a Chrome Custom Tab — a lightweight in-app browser rather than handing off to
 *  a full external browser app, per the brief. */
private fun openInCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
}
