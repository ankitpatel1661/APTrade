package com.aptrade.android.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.shared.application.FetchMarketNews
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** News tab UI state — the Android counterpart to desktop's `NewsUiState`
 *  (desktopApp/.../news/NewsViewModel.kt), trimmed to what this tab actually renders: no
 *  headline filter, no Saved-only view (out of scope for this task) — just the category-scoped
 *  article list, per-article bookmark membership, and the missing-key empty state. [error]
 *  surfaces a bookmark-persistence failure (the fetch path never throws past
 *  [FetchMarketNews], which already swallows failures to an empty list). */
data class NewsUiState(
    val articles: List<NewsArticle> = emptyList(),
    val category: NewsCategory = NewsCategory.General,
    val bookmarkedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val needsKey: Boolean = false,
)

/** Owns the News tab: category-scoped market news plus bookmark toggling. [fetchMarketNews]
 *  is null when no Finnhub key is configured (AppGraph passes null) — that becomes
 *  [NewsUiState.needsKey] and no fetch is ever attempted. Bookmarks always load regardless of
 *  key state. Structurally mirrors desktop `NewsViewModel`: a `MutableStateFlow` mutated only
 *  from `viewModelScope`-launched coroutines (single-threaded confinement via
 *  Dispatchers.Main.immediate takes the place of desktop's single-thread-confined scope). */
class NewsViewModel(
    private val fetchMarketNews: FetchMarketNews?,
    private val loadBookmarks: LoadBookmarks,
    private val toggleBookmark: ToggleBookmark,
) : ViewModel() {

    private val _state = MutableStateFlow(NewsUiState(needsKey = fetchMarketNews == null))
    val state: StateFlow<NewsUiState> = _state

    // The full bookmarked-article list, kept internally so a toggle can derive the published
    // `bookmarkedIds` set without re-reading the store on every call.
    private var bookmarks: List<NewsArticle> = emptyList()

    /** Loads bookmarks (always) and, when a key is configured, the current category's articles.
     *  Call once when the screen starts composing/observing. */
    fun start() {
        viewModelScope.launch {
            bookmarks = loadBookmarks.execute()
            _state.update { it.copy(bookmarkedIds = bookmarkIds()) }
        }
        val fetch = fetchMarketNews ?: return
        viewModelScope.launch { load(fetch, _state.value.category) }
    }

    /** Switches the active category and refetches for it. A no-op if [category] is already
     *  selected — mirrors desktop's `setCategory`. */
    fun setCategory(category: NewsCategory) {
        if (_state.value.category == category) return
        _state.update { it.copy(category = category) }
        val fetch = fetchMarketNews ?: return
        viewModelScope.launch { load(fetch, category) }
    }

    /** Pull-to-refresh entry point: re-fetches the current category. No-op when no key is
     *  configured (there is nothing to refresh). */
    fun refresh() {
        val fetch = fetchMarketNews ?: return
        viewModelScope.launch { load(fetch, _state.value.category) }
    }

    /** Toggles [article]'s bookmark state and persists the result. A persistence failure
     *  (e.g. disk full) surfaces via [NewsUiState.error] and leaves the last-good
     *  [NewsUiState.bookmarkedIds] untouched — the toggle simply didn't take. */
    fun toggleBookmark(article: NewsArticle) {
        viewModelScope.launch {
            try {
                bookmarks = toggleBookmark.execute(article)
                _state.update { it.copy(bookmarkedIds = bookmarkIds(), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't update bookmark.") }
            }
        }
    }

    private fun bookmarkIds(): Set<String> = bookmarks.mapTo(mutableSetOf()) { it.id }

    private suspend fun load(fetch: FetchMarketNews, category: NewsCategory) {
        _state.update { it.copy(isLoading = true) }
        // FetchMarketNews already swallows failures to an empty list; the only throw that
        // escapes it is CancellationException, which must propagate to cancel this load.
        try {
            val articles = fetch.execute(category)
            _state.update { it.copy(articles = articles, isLoading = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
