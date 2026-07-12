package com.aptrade.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aptrade.android.detail.DetailScreen
import com.aptrade.android.portfolio.PortfolioScreen
import com.aptrade.android.quotes.QuotesScreen
import com.aptrade.android.search.SearchScreen
import com.aptrade.android.ui.theme.APTradeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Provide app-private storage to the composition root BEFORE any screen composes,
        // so AppGraph.portfolio is materialized against the right directory.
        AppGraph.initialize(filesDir)
        setContent {
            APTradeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }
            }
        }
    }
}

/** Tab roots (Watchlist/Portfolio/News) live under the "shell" destination and are
 *  switched by [ShellTab] state, not by NavHost — the bottom bar never rebuilds the back
 *  stack. Search/detail/settings push a NavHost destination on top of the shell. */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    var tab by rememberSaveable { mutableStateOf(ShellTab.Watchlist) }
    NavHost(navController = navController, startDestination = "shell") {
        composable("shell") {
            AppShell(
                selected = tab,
                onSelectTab = { tab = it },
                onOpenSearch = { navController.navigate("search") },
                onOpenSettings = { navController.navigate("settings") },
            ) { padding ->
                when (tab) {
                    ShellTab.Watchlist -> QuotesScreen(   // Task 5 swaps in WatchlistScreen
                        onOpenSearch = { navController.navigate("search") },
                        onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
                        onOpenPortfolio = {},              // superseded by the tab bar
                    )
                    ShellTab.Portfolio -> PortfolioScreen(
                        onBack = {},                        // tab root: no back
                        onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
                    )
                    ShellTab.News -> NewsPlaceholder()     // Task 6 replaces
                }
            }
        }
        composable("search") {
            SearchScreen(onOpenDetail = { symbol -> navController.navigate("detail/$symbol") })
        }
        composable("detail/{symbol}") { backStackEntry ->
            DetailScreen(symbol = backStackEntry.arguments?.getString("symbol").orEmpty())
        }
        composable("settings") { SettingsPlaceholder() }   // Task 7 replaces
    }
}
