package com.aptrade.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.aptrade.android.search.SearchScreen
import com.aptrade.android.ui.theme.APTradeTheme
import com.aptrade.android.watchlist.WatchlistScreen

class MainActivity : ComponentActivity() {

    // Registered as a property (not inside onCreate) — ActivityResultCaller requires
    // registration before the Activity reaches CREATED. Denial is non-fatal: price alerts
    // still evaluate and update the in-app badge/list, only the system notification is
    // skipped (see AndroidAlertNotifier's permission check before each post).
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* denial is non-fatal — nothing to do either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Provide app-private storage + an application Context to the composition root
        // BEFORE any screen composes, so AppGraph.portfolio/alertNotifier are materialized
        // against the right directory/context.
        AppGraph.initialize(this)

        // POST_NOTIFICATIONS is a runtime permission only from API 33 (Tiramisu) onward;
        // below that, notifications need no explicit grant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

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
                    ShellTab.Watchlist -> WatchlistScreen(
                        padding = padding,
                        onOpenSearch = { navController.navigate("search") },
                        onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
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
