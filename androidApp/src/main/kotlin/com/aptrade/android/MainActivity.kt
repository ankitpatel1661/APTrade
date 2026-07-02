package com.aptrade.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aptrade.android.detail.DetailScreen
import com.aptrade.android.quotes.QuotesScreen
import com.aptrade.android.search.SearchScreen
import com.aptrade.android.ui.theme.APTradeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            APTradeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }
            }
        }
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "quotes") {
        composable("quotes") {
            QuotesScreen(
                onOpenSearch = { navController.navigate("search") },
                onOpenDetail = { symbol -> navController.navigate("detail/$symbol") },
            )
        }
        composable("search") {
            SearchScreen(onOpenDetail = { symbol -> navController.navigate("detail/$symbol") })
        }
        composable("detail/{symbol}") { backStackEntry ->
            DetailScreen(symbol = backStackEntry.arguments?.getString("symbol").orEmpty())
        }
    }
}
