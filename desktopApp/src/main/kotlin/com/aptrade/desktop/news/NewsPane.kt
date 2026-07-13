package com.aptrade.desktop.news

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.MagnifierIcon
import com.aptrade.desktop.infra.openUrlInBrowser
import com.aptrade.shared.l10n.L10n
import com.aptrade.desktop.l10n.tr
import com.aptrade.shared.domain.NewsCategory

/** News tab: a category pill row (General · Crypto · Merger) with a trailing Saved toggle,
 *  a live headline filter, and a scrolling list of article rows. All state comes from
 *  [NewsViewModel]; the pane owns no fetch logic.
 *
 *  When [NewsUiState.keyMissing] the whole tab is replaced by a "connect a source" panel —
 *  no pills, no filter, no list. The list root is a [LazyColumn] (this pane is a tab root,
 *  not nested in a verticalScroll). */
@Composable
fun NewsPane(
    state: NewsUiState,
    onSetCategory: (NewsCategory) -> Unit,
    onSetShowingSaved: (Boolean) -> Unit,
    onSetFilter: (String) -> Unit,
    onRefresh: () -> Unit,
    onToggleBookmark: (com.aptrade.shared.domain.NewsArticle) -> Unit,
) {
    if (state.keyMissing) {
        NoKeyState()
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        PillRow(
            category = state.category,
            showingSaved = state.showingSaved,
            onSetCategory = onSetCategory,
            onSetShowingSaved = onSetShowingSaved,
        )
        Spacer(Modifier.height(12.dp))
        FilterField(query = state.filter, onQueryChange = onSetFilter)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxSize()) {
            val visible = state.visibleArticles
            when {
                state.isLoading && visible.isEmpty() ->
                    CircularProgressIndicator(
                        color = DK.gold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                visible.isEmpty() ->
                    EmptyState(
                        showingSaved = state.showingSaved,
                        onRefresh = onRefresh,
                        modifier = Modifier.align(Alignment.Center),
                    )
                else -> {
                    // One reference `now` for every row's relative-time label this frame.
                    val now = System.currentTimeMillis() / 1000
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visible, key = { it.id }) { article ->
                            ArticleRow(
                                article = article,
                                bookmarked = state.bookmarkedIds.contains(article.id),
                                now = now,
                                onOpen = { openUrlInBrowser(article.url) },
                                onToggleBookmark = { onToggleBookmark(article) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** General · Crypto · Merger capsules (surfaceHi when selected) plus a trailing Saved
 *  bookmark toggle. When Saved is active it visually deselects the category pills — the
 *  saved list is not category-scoped. */
@Composable
private fun PillRow(
    category: NewsCategory,
    showingSaved: Boolean,
    onSetCategory: (NewsCategory) -> Unit,
    onSetShowingSaved: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in NewsCategory.entries) {
                CategoryPill(
                    label = categoryLabel(c),
                    selected = !showingSaved && c == category,
                    onClick = {
                        // Selecting a category leaves the Saved view.
                        if (showingSaved) onSetShowingSaved(false)
                        onSetCategory(c)
                    },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        SavedToggle(active = showingSaved, onClick = { onSetShowingSaved(!showingSaved) })
    }
}

/** Maps a [NewsCategory] to its localized pill text — mirrors `NewsView.swift`'s
 *  `categoryTitle(_:)` (`.general` → `newsGeneral`, `.crypto` → `cryptoLabel`, `.merger` →
 *  `newsMerger`). `NewsCategory.displayName` itself lives in `:shared` (commonMain) and stays
 *  English-only per the desktop-only retrofit scope, so the mapping happens here instead of
 *  editing the shared enum. */
private fun categoryLabel(category: NewsCategory): String = when (category) {
    NewsCategory.General -> tr(L10n.Key.NewsGeneral)
    NewsCategory.Crypto -> tr(L10n.Key.CryptoLabel)
    NewsCategory.Merger -> tr(L10n.Key.NewsMerger)
}

@Composable
private fun CategoryPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            fontFamily = InterFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) DK.textPrimary else DK.textSecondary,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) DK.surfaceHi else DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** The Saved bookmark toggle — a bookmark glyph in a rounded capsule, gold when active. */
@Composable
private fun SavedToggle(active: Boolean, onClick: () -> Unit) {
    val tint = if (active) DK.gold else DK.textSecondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) DK.surfaceHi else DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Canvas(Modifier.size(width = 11.dp, height = 13.dp)) {
            val w = size.width
            val h = size.height
            val notch = h * 0.28f
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w, h)
                lineTo(w / 2f, h - notch)
                lineTo(0f, h)
                close()
            }
            if (active) drawPath(path, color = tint)
            else drawPath(path, color = tint, style = Stroke(width = 1.3.dp.toPx()))
        }
        Text(
            tr(L10n.Key.Saved),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            ),
        )
    }
}

/** Live headline filter — magnifier + borderless field on `DK.surface`, hairline border,
 *  rounded 10dp. Matches the watchlist add-field idiom. Placeholder "Filter headlines". */
@Composable
private fun FilterField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        MagnifierIcon(tint = DK.textSecondary, modifier = Modifier.size(15.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    tr(L10n.Key.FilterHeadlinesPlaceholder),
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        color = DK.textTertiary,
                    ),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(DK.gold),
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        color = DK.textPrimary,
                    ),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Empty list state. Saved view → "No saved articles" (no Refresh); otherwise
 *  "No headlines right now" with a Refresh button. */
@Composable
private fun EmptyState(showingSaved: Boolean, onRefresh: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (showingSaved) tr(L10n.Key.NoSavedArticles) else tr(L10n.Key.NoHeadlinesRightNow),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = DK.textSecondary,
            ),
        )
        if (!showingSaved) {
            Text(
                tr(L10n.Key.Refresh),
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DK.gold,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DK.surface)
                    .border(1.dp, DK.hairline, RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onRefresh() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** No-key state — replaces the entire tab. Exact strings per spec parity. */
@Composable
private fun NoKeyState() {
    Box(Modifier.fillMaxSize().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr(L10n.Key.ConnectNewsSource),
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DK.textPrimary,
                ),
            )
            Text(
                tr(L10n.Key.FinnhubKeyInstructions),
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textSecondary,
                ),
            )
        }
    }
}
