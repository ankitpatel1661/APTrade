package com.aptrade.desktop.news

import com.aptrade.shared.application.FetchMarketNews
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewsUiState(
    val articles: List<NewsArticle> = emptyList(),
    val bookmarks: List<NewsArticle> = emptyList(),
    val category: NewsCategory = NewsCategory.General,
    val showingSaved: Boolean = false,
    val filter: String = "",
    val isLoading: Boolean = false,
    val keyMissing: Boolean = false,
) {
    /** Ids of bookmarked articles — derived so the UI can render a filled/hollow bookmark
     *  glyph per row without scanning the list. */
    val bookmarkedIds: Set<String> get() = bookmarks.mapTo(HashSet()) { it.id }

    /** macOS parity: base is the saved list when [showingSaved] else the fetched articles;
     *  a blank filter yields the base unchanged, otherwise a live (no-debounce),
     *  case-insensitive substring match on headline OR source. */
    val visibleArticles: List<NewsArticle>
        get() {
            val base = if (showingSaved) bookmarks else articles
            val query = filter.trim()
            if (query.isEmpty()) return base
            val needle = query.lowercase()
            return base.filter {
                it.headline.lowercase().contains(needle) || it.source.lowercase().contains(needle)
            }
        }
}

/** Owns the News tab: category-scoped market news, the Saved toggle, live text filter, and
 *  bookmark toggling. [fetchMarketNews] is null when no news key is configured (AppGraph
 *  passes null) — that becomes [NewsUiState.keyMissing] and no fetch is ever attempted.
 *  Bookmarks always load regardless of key state.
 *  `scope` MUST be single-thread-confined (Dispatchers.Main on desktop): state updates
 *  rely on that confinement instead of locks. */
class NewsViewModel(
    private val fetchMarketNews: FetchMarketNews?,
    private val loadBookmarks: LoadBookmarks,
    private val toggleBookmark: ToggleBookmark,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(NewsUiState(keyMissing = fetchMarketNews == null))
    val state: StateFlow<NewsUiState> = _state

    fun start() {
        scope.launch {
            _state.update { it.copy(bookmarks = loadBookmarks.execute()) }
        }
        val fetch = fetchMarketNews ?: return
        scope.launch { load(fetch, _state.value.category) }
    }

    fun setCategory(category: NewsCategory) {
        if (_state.value.category == category) return
        _state.update { it.copy(category = category) }
        val fetch = fetchMarketNews ?: return
        scope.launch { load(fetch, category) }
    }

    fun setShowingSaved(showingSaved: Boolean) =
        _state.update { it.copy(showingSaved = showingSaved) }

    fun setFilter(filter: String) = _state.update { it.copy(filter = filter) }

    fun refresh() {
        val fetch = fetchMarketNews ?: return
        scope.launch { load(fetch, _state.value.category) }
    }

    fun toggleBookmark(article: NewsArticle) {
        scope.launch {
            try {
                val updated = toggleBookmark.execute(article)
                _state.update { it.copy(bookmarks = updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // bookmark persistence is best-effort; a failure leaves the last-good list
            }
        }
    }

    private suspend fun load(fetch: FetchMarketNews, category: NewsCategory) {
        _state.update { it.copy(isLoading = true) }
        // FetchMarketNews already swallows failures to an empty list; the only throw that
        // escapes it is CancellationException, which must propagate to cancel this load.
        try {
            val articles = fetch.execute(category)
            _state.update { it.copy(articles = articles, isLoading = false) }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
