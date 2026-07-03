package com.aptrade.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.BrandWordmark
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.MagnifierIcon

/** The three top-level destinations. Only Watchlist is live this increment. */
enum class AppTab(val title: String) {
    Watchlist("Watchlist"),
    Portfolio("Portfolio"),
    News("News"),
}

/** Full-window chrome: vertical gradient, centered wordmark with a palette icon
 *  top-right, the tab row underneath, then the active tab's content filling the rest.
 *  Mirrors `RootView.macBody` — the shell holds no business logic. */
@Composable
fun AppShell(
    selectedTab: AppTab,
    onTabSelect: (AppTab) -> Unit,
    onOpenPalette: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(DK.backgroundGradient)) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.Center)) {
                BrandWordmark(height = 108.dp)
            }
            PaletteIconButton(
                onClick = onOpenPalette,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp),
            )
        }
        TabRow(selectedTab = selectedTab, onTabSelect = onTabSelect)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** Top-right search glyph that opens the Ctrl+K palette. */
@Composable
private fun PaletteIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DK.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        MagnifierIcon(tint = DK.textSecondary, modifier = Modifier.size(16.dp))
    }
}

/** Centered Watchlist / Portfolio / News row — selected gold with a gold underline,
 *  the same visual language as `TimeframeBar`. */
@Composable
private fun TabRow(selectedTab: AppTab, onTabSelect: (AppTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        for (tab in AppTab.entries) {
            val selected = tab == selectedTab
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTabSelect(tab) }
                    .padding(horizontal = 18.dp),
            ) {
                Text(
                    tab.title,
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) DK.gold else DK.textSecondary,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .height(2.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (selected) DK.gold else Color.Transparent),
                )
            }
        }
    }
}

/** Simple centered placeholder for the not-yet-built tabs. */
@Composable
fun PlaceholderPane(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = DK.textTertiary,
            ),
        )
    }
}
