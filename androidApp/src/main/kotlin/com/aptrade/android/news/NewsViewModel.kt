package com.aptrade.android.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.shared.application.FetchMarketNews
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** News tab UI state — the Android counterpart to desktop's `NewsUiState`
 *  (desktopApp/.../news/NewsViewModel.kt). Carries the Saved toggle and live headline
 *  [filter] plus the category-scoped article list, the full bookmarked-article list, and the
 *  missing-key empty state. [bookmarkError] surfaces a bookmark-persistence failure (the fetch
 *  path never throws past [FetchMarketNews], which already swallows failures to an empty
 *  list) as an [L10n.Key] rather than a resolved string, so the VM stays string-free and the
 *  UI layer resolves it against the active [com.aptrade.shared.l10n.AppLanguage]. */
data class NewsUiState(
    val articles: List<NewsArticle> = emptyList(),
    val bookmarks: List<NewsArticle> = emptyList(),
    val category: NewsCategory = NewsCategory.General,
    val showingSaved: Boolean = false,
    val filter: String = "",
    val isLoading: Boolean = false,
    val needsKey: Boolean = false,
    val bookmarkError: L10n.Key? = null,
) {
    /** Ids of bookmarked articles — derived so the UI can render a filled/hollow bookmark
     *  glyph per row without scanning the list. */
    val bookmarkedIds: Set<String> get() = bookmarks.mapTo(HashSet()) { it.id }

    /** macOS/desktop parity: base is the saved list when [showingSaved] else the fetched
     *  articles; a blank filter yields the base unchanged, otherwise a live (no-debounce),
     *  case-insensitive substring match on headline OR source. Mirrors
     *  desktopApp/.../news/NewsViewModel.kt's `visibleArticles` exactly. */
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
 *  bookmark toggling. [fetchMarketNews] is a PROVIDER (not a frozen instance) because this VM
 *  outlives tab switches in the Activity's ViewModelStore while the user can enter a Finnhub
 *  key in Settings at any time: [start] re-resolves it on every News-tab entry, so a key saved
 *  mid-session takes effect the next time the tab opens. It resolves to null while no key is
 *  configured — that becomes [NewsUiState.needsKey] and no fetch is ever attempted.
 *  Bookmarks always load regardless of key state. Structurally mirrors desktop `NewsViewModel`:
 *  a `MutableStateFlow` mutated only from `viewModelScope`-launched coroutines
 *  (single-threaded confinement via Dispatchers.Main.immediate takes the place of desktop's
 *  single-thread-confined scope). */
class NewsViewModel(
    private val fetchMarketNews: () -> FetchMarketNews?,
    private val loadBookmarks: LoadBookmarks,
    private val toggleBookmark: ToggleBookmark,
) : ViewModel() {

    private val _state = MutableStateFlow(NewsUiState(needsKey = fetchMarketNews() == null))
    val state: StateFlow<NewsUiState> = _state

    /** Loads bookmarks (always) and, when a key is configured, the current category's articles.
     *  Called on every News-tab entry (LifecycleStartEffect) — re-resolving the provider here
     *  is what picks up a key saved in Settings since the last visit. */
    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(bookmarks = loadBookmarks.execute()) }
        }
        val fetch = fetchMarketNews()
        _state.update { it.copy(needsKey = fetch == null) }
        if (fetch == null) return
        viewModelScope.launch { load(fetch, _state.value.category) }
    }

    /** Switches the active category and refetches for it. A no-op if [category] is already
     *  selected — mirrors desktop's `setCategory`. */
    fun setCategory(category: NewsCategory) {
        if (_state.value.category == category) return
        _state.update { it.copy(category = category) }
        val fetch = fetchMarketNews() ?: return
        viewModelScope.launch { load(fetch, category) }
    }

    /** Toggles the Saved-only view — mirrors desktop's `setShowingSaved`. */
    fun setShowingSaved(showingSaved: Boolean) =
        _state.update { it.copy(showingSaved = showingSaved) }

    /** Updates the live headline filter — mirrors desktop's `setFilter`. */
    fun setFilter(filter: String) = _state.update { it.copy(filter = filter) }

    /** Pull-to-refresh entry point: re-fetches the current category. No-op when no key is
     *  configured (there is nothing to refresh). */
    fun refresh() {
        val fetch = fetchMarketNews() ?: return
        viewModelScope.launch { load(fetch, _state.value.category) }
    }

    /** Toggles [article]'s bookmark state and persists the result. A persistence failure
     *  (e.g. disk full) surfaces via [NewsUiState.bookmarkError] and leaves the last-good
     *  [NewsUiState.bookmarks] untouched — the toggle simply didn't take. */
    fun toggleBookmark(article: NewsArticle) {
        viewModelScope.launch {
            try {
                val updated = toggleBookmark.execute(article)
                _state.update { it.copy(bookmarks = updated, bookmarkError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(bookmarkError = L10n.Key.CouldntUpdateBookmark) }
            }
        }
    }

    /** Clears a surfaced [NewsUiState.bookmarkError] once the UI has shown it (e.g. after the
     *  snackbar that displayed it is dismissed). */
    fun clearError() = _state.update { it.copy(bookmarkError = null) }

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
