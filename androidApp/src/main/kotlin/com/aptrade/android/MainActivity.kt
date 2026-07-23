package com.aptrade.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import com.aptrade.android.detail.DetailScreen
import com.aptrade.android.invest.InvestScreen
import com.aptrade.android.invest.InvestSection
import com.aptrade.android.markets.MarketsScreen
import com.aptrade.android.markets.MarketsSection
import com.aptrade.android.portfolio.PortfolioScreen
import com.aptrade.android.portfolio.PortfolioSection
import com.aptrade.android.search.SearchScreen
import com.aptrade.android.settings.SettingsScreen
import com.aptrade.android.settings.SettingsViewModel
import com.aptrade.android.ui.theme.APTradeTheme
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

/** Tab roots (Home/Markets/Portfolio/Invest) live under the "shell" destination and are
 *  switched by [ShellTab] state, not by NavHost — the bottom bar never rebuilds the back
 *  stack. Search/detail/settings push a NavHost destination on top of the shell.
 *
 *  **Back-stack divergence (M10.3 IA restructure, constraint 2 divergence #4):** unlike
 *  desktop's conditional master–detail (a wide sidebar window keeps the list and a detail pane
 *  open side by side, so a selection can persist across a tab revisit), a phone is push-nav
 *  only — `detail/{symbol}` is a NavHost destination pushed on top of "shell", and popping it
 *  always lands back on the list with no selection remembered. This is the deliberate phone
 *  answer to desktop's persistence divergence: master–detail is N/A here (constraint 4), so
 *  there is nothing to persist — every detail visit starts fresh from whichever list row (or
 *  Home feed row, or deep link) was tapped.
 *
 *  **Section state hoisting (constraint 3):** [marketsSection]/[investSection]/
 *  [portfolioSection] are hoisted HERE, one level above [MarketsScreen]/[InvestScreen]/
 *  [PortfolioScreen] themselves, rather than each screen owning its own `remember` state. Home
 *  (Task 3) and any future deep link write these directly — no request/clear handoff to
 *  consume-and-clear on a later frame, which is exactly the bug class the Swift I-1 lesson
 *  warns about (a request set in the same transaction as a tab switch can miss the window
 *  where an `onChange`-style consumer starts listening, wedging same-section requests for the
 *  rest of the session). Writing hoisted state directly has no such window: `tab = ShellTab.X;
 *  xSection = Y` takes effect on the very next composition, first paint included. */
@Composable
fun AppNavHost(settingsViewModel: SettingsViewModel) {
    val navController = rememberNavController()
    var tab by rememberSaveable { mutableStateOf(ShellTab.Home) }
    var marketsSection by rememberSaveable { mutableStateOf(MarketsSection.Watchlist) }
    var portfolioSection by rememberSaveable { mutableStateOf(PortfolioSection.Holdings) }
    var investSection by rememberSaveable { mutableStateOf(InvestSection.Plans) }
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
                    ShellTab.Home -> Box(Modifier.fillMaxSize()) {
                        // Task 3
                    }
                    ShellTab.Markets -> MarketsScreen(
                        padding = padding,
                        section = marketsSection,
                        onSelectSection = { marketsSection = it },
                        onOpenSearch = { navController.navigate("search") },
                        onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
                    )
                    ShellTab.Portfolio -> PortfolioScreen(
                        section = portfolioSection,
                        onSelectSection = { portfolioSection = it },
                        onBack = {},                        // tab root: no back
                        onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
                        confirmTrades = settings.confirmTrades,
                    )
                    ShellTab.Invest -> InvestScreen(
                        padding = padding,
                        section = investSection,
                        onSelectSection = { investSection = it },
                        confirmTrades = settings.confirmTrades,
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
