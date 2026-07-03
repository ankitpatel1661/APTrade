package com.aptrade.desktop

import androidx.compose.runtime.staticCompositionLocalOf

/** Provides the single [AppGraph] down the tree so panes that build their own
 *  per-selection ViewModels (e.g. the detail pane) can reach the use cases without
 *  threading them through every composable. Set once in main() via a provider. */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> {
    error("AppGraph not provided — wrap the UI in CompositionLocalProvider(LocalAppGraph provides graph)")
}
