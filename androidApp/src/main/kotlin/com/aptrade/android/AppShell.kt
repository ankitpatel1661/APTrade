package com.aptrade.android

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import com.aptrade.android.l10n.tr
import com.aptrade.shared.l10n.L10n

/**
 * The four bottom-tab destinations (M10.3 IA restructure — regrouped from the five M9.3 tabs):
 * [com.aptrade.android.home.HomeScreen] (Task 3), [com.aptrade.android.markets.MarketsScreen]
 * (hosting the former Watchlist/Screener/Calendar/News tabs as an internal pill row, unchanged),
 * [com.aptrade.android.portfolio.PortfolioScreen] (slimmed — Plans/Income moved out), and
 * [com.aptrade.android.invest.InvestScreen] (hosting the former Plans/Income Portfolio sections).
 *
 * Icon substitutions (verified against `material-icons-core`'s curated ~50-icon set —
 * the brief's `RemoveRedEye`/`PieChart`/`Article`/`MoreHoriz`/`TrendingUp`/`Savings` are all
 * part of the larger material-icons-extended set, which this project deliberately does not
 * depend on):
 *  - Home: [Icons.Filled.Home] is present in core as-is, no substitution needed (filled house).
 *  - Markets: no single core icon maps to "watchlist + screener + calendar + news" as a group,
 *    so this reuses the M9.3 Screener rationale one level up → [Icons.AutoMirrored.Filled.List]
 *    (the tab's shared job across all four of its pills is browsing/narrowing the market
 *    universe down to a LIST of symbols; the AutoMirrored variant is used over the plain
 *    (deprecated) `Filled.List`, same as [Icons.AutoMirrored.Filled.ArrowBack] elsewhere in this
 *    codebase). The Watchlist/Screener/Calendar/News icons this tab used to carry individually
 *    (`Star`/`List`/`DateRange`/`Info`) are retired from the bottom bar along with their tabs —
 *    [com.aptrade.android.markets.MarketsScreen]'s pill row is text-only (matching
 *    [com.aptrade.android.portfolio.PortfolioScreen]'s `SectionSwitcher` idiom), so no clash.
 *  - Portfolio: `PieChart` → [Icons.Filled.AccountBox] (personal holdings/account) — unchanged
 *    from M9.3.
 *  - Invest: `TrendingUp`/`Savings`/`AccountBalance` (all extended-only) → [Icons.Filled.AddCircle]
 *    (Plans are periodic ADD-contribution schedules — recurring buys/DRIP — so "add" is the
 *    closest core analog to "grow your position over time"; no clash with Home/Markets/Portfolio).
 *  - Account/settings action: `MoreHoriz` → [Icons.Filled.MoreVert] (same overflow-menu
 *    affordance, vertical instead of horizontal dots — the closest core analog).
 *  - Search action: [Icons.Filled.Search] is present in core as-is, no substitution needed.
 */
enum class ShellTab(val route: String, val labelKey: L10n.Key) {
    Home("home", L10n.Key.HomeTab),
    Markets("markets", L10n.Key.MarketsTab),
    Portfolio("portfolio", L10n.Key.Portfolio),
    Invest("invest", L10n.Key.InvestTab),
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
                                ShellTab.Home -> Icons.Filled.Home
                                ShellTab.Markets -> Icons.AutoMirrored.Filled.List
                                ShellTab.Portfolio -> Icons.Filled.AccountBox
                                ShellTab.Invest -> Icons.Filled.AddCircle
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
