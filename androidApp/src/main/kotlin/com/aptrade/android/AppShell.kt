package com.aptrade.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aptrade.android.l10n.tr
import com.aptrade.shared.l10n.L10n

/**
 * The three bottom-tab destinations, all backed by real content: [com.aptrade.android.watchlist.WatchlistScreen],
 * [com.aptrade.android.portfolio.PortfolioScreen], and [com.aptrade.android.news.NewsScreen].
 *
 * Icon substitutions (verified against `material-icons-core`'s curated ~50-icon set —
 * the brief's `RemoveRedEye`/`PieChart`/`Article`/`MoreHoriz` are all part of the larger
 * material-icons-extended set, which this project deliberately does not depend on):
 *  - Watchlist: `RemoveRedEye` → [Icons.Filled.Star] (starred/followed symbols).
 *  - Portfolio: `PieChart` → [Icons.Filled.AccountBox] (personal holdings/account).
 *  - News: `Article` → [Icons.Filled.Info] (informational feed).
 *  - Account/settings action: `MoreHoriz` → [Icons.Filled.MoreVert] (same overflow-menu
 *    affordance, vertical instead of horizontal dots — the closest core analog).
 *  - Search action: [Icons.Filled.Search] is present in core as-is, no substitution needed.
 */
enum class ShellTab(val route: String, val labelKey: L10n.Key) {
    Watchlist("watchlist", L10n.Key.Watchlist),
    Portfolio("portfolio", L10n.Key.Portfolio),
    News("news", L10n.Key.News),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    selected: ShellTab,
    onSelectTab: (ShellTab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APTrade") },
                actions = {
                    // L10n.Key.Search does not exist in the shared catalog (only
                    // SearchTickerPlaceholder/SearchAssetsPlaceholder do); this top-level
                    // search entry point — which opens a cross-tab search, matching
                    // SearchAssetsPlaceholder's "or jump to a tab" wording — uses the
                    // nearest existing key.
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = tr(L10n.Key.SearchAssetsPlaceholder))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.MoreVert, contentDescription = tr(L10n.Key.Account))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                ShellTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { onSelectTab(tab) },
                        icon = {
                            val icon = when (tab) {
                                ShellTab.Watchlist -> Icons.Filled.Star
                                ShellTab.Portfolio -> Icons.Filled.AccountBox
                                ShellTab.News -> Icons.Filled.Info
                            }
                            Icon(icon, contentDescription = tr(tab.labelKey))
                        },
                        label = { Text(tr(tab.labelKey)) },
                    )
                }
            }
        },
    ) { padding -> content(padding) }
}

/** Placeholder destination — Task 7 replaces this with the real Settings/Account UI. */
@Composable
fun SettingsPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(tr(L10n.Key.Account))
    }
}
