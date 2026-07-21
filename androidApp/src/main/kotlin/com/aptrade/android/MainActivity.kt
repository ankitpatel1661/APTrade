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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aptrade.android.calendar.CalendarScreen
import com.aptrade.android.detail.DetailScreen
import com.aptrade.android.news.NewsScreen
import com.aptrade.android.portfolio.PortfolioScreen
import com.aptrade.android.screener.ScreenerScreen
import com.aptrade.android.search.SearchScreen
import com.aptrade.android.settings.SettingsScreen
import com.aptrade.android.settings.SettingsViewModel
import com.aptrade.android.ui.theme.APTradeTheme
import com.aptrade.android.watchlist.WatchlistScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
            // ONE SettingsViewModel scoped to the Activity (not the settings route): its
            // StateFlow drives APTradeTheme, so the whole tree — every screen, not just the
            // settings pages — re-themes instantly on a dark/light or accent change, and its
            // init load applies the persisted language/theme before the user opens settings.
            val settingsViewModel: SettingsViewModel = viewModel {
                SettingsViewModel(AppGraph.settingsStore, AppGraph.finnhubKey)
            }
            val settings by settingsViewModel.settings.collectAsState()

            // Market-activity coordinator (Task 8) — open/close + daily-digest + earnings-day
            // notifications, 60s cadence. A single-thread-confined scope (Dispatchers.Main),
            // mirroring desktop's `appScope` in Main.kt and living beside the alert-evaluation
            // loop (WatchlistViewModel's own poll, which drives EvaluateAlerts) for the same
            // process lifetime: created once here, cancelled in the DisposableEffect below.
            val marketActivityScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
            val marketActivityCoordinator = remember {
                AppGraph.marketActivityCoordinator(scope = marketActivityScope)
            }
            LaunchedEffect(Unit) { marketActivityCoordinator.start() }
            DisposableEffect(Unit) { onDispose { marketActivityScope.cancel() } }

            APTradeTheme(settings) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(settingsViewModel)
                }
            }
        }
    }
}

/** Tab roots (Watchlist/Portfolio/News) live under the "shell" destination and are
 *  switched by [ShellTab] state, not by NavHost — the bottom bar never rebuilds the back
 *  stack. Search/detail/settings push a NavHost destination on top of the shell. */
@Composable
fun AppNavHost(settingsViewModel: SettingsViewModel) {
    val navController = rememberNavController()
    var tab by rememberSaveable { mutableStateOf(ShellTab.Watchlist) }
    // The live confirmTrades flag (spec A4 — TradeSheet's confirm-layer gate). TradeSheet
    // snapshots it once when it opens (its own KDoc), so it is fine for THIS to stay live —
    // collected once, here, rather than re-plumbing SettingsViewModel through every screen.
    val settings by settingsViewModel.settings.collectAsState()
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
                        confirmTrades = settings.confirmTrades,
                    )
                    ShellTab.News -> NewsScreen(padding = padding)
                    ShellTab.Calendar -> CalendarScreen(padding = padding)
                    ShellTab.Screener -> ScreenerScreen(
                        padding = padding,
                        onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
                    )
                }
            }
        }
        composable("search") {
            SearchScreen(onOpenDetail = { symbol -> navController.navigate("detail/$symbol") })
        }
        composable("detail/{symbol}") { backStackEntry ->
            DetailScreen(
                symbol = backStackEntry.arguments?.getString("symbol").orEmpty(),
                confirmTrades = settings.confirmTrades,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = settingsViewModel,
                onClose = { navController.popBackStack() },
            )
        }
    }
}
