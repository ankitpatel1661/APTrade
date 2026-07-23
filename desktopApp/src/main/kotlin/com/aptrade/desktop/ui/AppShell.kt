package com.aptrade.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.BrandWordmark
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.MagnifierIcon
import com.aptrade.desktop.designkit.MoonIcon
import com.aptrade.desktop.designkit.SunIcon
import com.aptrade.desktop.portfolio.PortfolioSection
import com.aptrade.desktop.portfolio.label
import com.aptrade.shared.l10n.L10n
import com.aptrade.desktop.l10n.tr

/** The sidebar's four top-level destinations (M10.2 Task 3 IA restructure). Home is a single
 *  item; Markets/Portfolio/Invest each carry their own section directly — the sidebar item IS
 *  the section picker, there is no separate pill row under it (mirroring
 *  `RootView.SidebarDestination` on macOS, where the same comment applies verbatim). */
sealed class SidebarDestination {
    data object Home : SidebarDestination()
    data class Markets(val section: MarketsSection) : SidebarDestination()
    data class Portfolio(val section: PortfolioSection) : SidebarDestination()
    data class Invest(val section: InvestSection) : SidebarDestination()
}

/** Mirrors macOS `MarketsView.Section`. */
enum class MarketsSection { Watchlist, Screener, Calendar, News }

/** Mirrors macOS `InvestView.Section`. */
enum class InvestSection { Plans, Income }

/** [MarketsSection]'s display label. A plain function (not an enum property) — see
 *  [PortfolioSection.label] for the same pattern and rationale: it must call [tr], which reads
 *  the active language live, and an enum constructor runs once at class-init. */
private fun MarketsSection.title(): String = when (this) {
    MarketsSection.Watchlist -> tr(L10n.Key.Watchlist)
    MarketsSection.Screener -> tr(L10n.Key.ScreenerTab)
    MarketsSection.Calendar -> tr(L10n.Key.CalendarTab)
    MarketsSection.News -> tr(L10n.Key.News)
}

/** [InvestSection]'s display label — same plain-function idiom as [MarketsSection.title]. */
private fun InvestSection.title(): String = when (this) {
    InvestSection.Plans -> tr(L10n.Key.PlansSection)
    InvestSection.Income -> tr(L10n.Key.IncomeSection)
}

/** Hand-glyph stand-ins (this codebase's established icon idiom — see [MagnifierIcon]/
 *  [MoonIcon]/[SunIcon] — avoids a material-icons dependency for a handful of one-off rail
 *  glyphs); lifted straight from the mockup's `.sitem .ic` glyphs so the rail matches the
 *  reference pixel-for-pixel in spirit. */
private fun MarketsSection.glyph(): String = when (this) {
    MarketsSection.Watchlist -> "☰"
    MarketsSection.Screener -> "◎"
    MarketsSection.Calendar -> "▦"
    MarketsSection.News -> "✎"
}

private fun PortfolioSection.glyph(): String = when (this) {
    PortfolioSection.Holdings -> "◧"
    PortfolioSection.Allocation -> "◔"
    PortfolioSection.Activity -> "≡"
    PortfolioSection.Performance -> "∿"
}

private fun InvestSection.glyph(): String = when (this) {
    InvestSection.Plans -> "◕"
    InvestSection.Income -> "$"
}

/** Left rail (208dp) + content area: replaces the old wordmark header + TabRow. Mirrors
 *  `RootView.macBody`'s `HStack { sidebar; macDestinationContent }` split. The shell holds no
 *  business logic — [selection] lives on the caller (Main.kt) and is written directly by
 *  [onSelect] (constraint 3: no request/clear dance). */
@Composable
fun AppShell(
    selection: SidebarDestination,
    onSelect: (SidebarDestination) -> Unit,
    onOpenPalette: () -> Unit,
    onOpenAccount: () -> Unit,
    isDarkMode: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize().background(DK.backgroundGradient)) {
        Sidebar(
            selection = selection,
            onSelect = onSelect,
            onOpenPalette = onOpenPalette,
            onOpenAccount = onOpenAccount,
            isDarkMode = isDarkMode,
            onToggleTheme = onToggleTheme,
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(DK.hairline))
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

/** The 208dp rail itself: compact brand mark, Home, the three grouped sections, then a footer
 *  with Search (Ctrl+K) / Settings / theme toggle. Mirrors the mockup's `.side` column and
 *  `RootView.sidebar` exactly, in DK tokens. */
@Composable
private fun Sidebar(
    selection: SidebarDestination,
    onSelect: (SidebarDestination) -> Unit,
    onOpenPalette: () -> Unit,
    onOpenAccount: () -> Unit,
    isDarkMode: Boolean,
    onToggleTheme: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(208.dp)
            .fillMaxHeight()
            .background(DK.surface.copy(alpha = 0.55f))
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        // Compact corner brand — small height, not the old centered banner (that filled the
        // whole top of the window before the sidebar existed).
        Box(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 14.dp)) {
            BrandWordmark(height = 16.dp)
        }

        SidebarRow(
            glyph = "⌂",
            label = tr(L10n.Key.HomeTab),
            selected = selection == SidebarDestination.Home,
        ) { onSelect(SidebarDestination.Home) }

        GroupLabel(tr(L10n.Key.MarketsTab))
        for (section in MarketsSection.entries) {
            SidebarRow(
                glyph = section.glyph(),
                label = section.title(),
                selected = selection == SidebarDestination.Markets(section),
            ) { onSelect(SidebarDestination.Markets(section)) }
        }

        GroupLabel(tr(L10n.Key.Portfolio))
        for (section in PortfolioSection.entries) {
            SidebarRow(
                glyph = section.glyph(),
                label = section.label(),
                selected = selection == SidebarDestination.Portfolio(section),
            ) { onSelect(SidebarDestination.Portfolio(section)) }
        }

        GroupLabel(tr(L10n.Key.InvestTab))
        for (section in InvestSection.entries) {
            SidebarRow(
                glyph = section.glyph(),
                label = section.title(),
                selected = selection == SidebarDestination.Invest(section),
            ) { onSelect(SidebarDestination.Invest(section)) }
        }

        Spacer(Modifier.weight(1f))

        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
        }

        FooterRow(
            icon = { MagnifierIcon(tint = DK.textSecondary, modifier = Modifier.size(13.dp)) },
            label = tr(L10n.Key.SidebarSearch),
            trailingHint = "Ctrl+K",
            fillWidth = true,
            onClick = onOpenPalette,
        )
        Row(
            modifier = Modifier.padding(horizontal = 10.dp).padding(top = 2.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterRow(
                icon = {
                    Text(
                        "⚙",
                        style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = DK.textSecondary),
                    )
                },
                label = tr(L10n.Key.SidebarSettings),
                trailingHint = null,
                fillWidth = false,
                horizontalPadding = 0.dp,
                onClick = onOpenAccount,
            )
            ThemeToggleButton(isDarkMode = isDarkMode, onToggle = onToggleTheme)
        }
    }
}

/** One sidebar row — Home or a section item. Selected = [DK.surfaceHi] fill with a gold
 *  (0.35 alpha) inset border, 9dp radius — mirrors the mockup's `.sitem`/`.sitem.on` and
 *  `RootView.sidebarItem` exactly, in DK tokens. */
@Composable
private fun SidebarRow(
    glyph: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(9.dp))
            .then(
                if (selected) {
                    Modifier
                        .background(DK.surfaceHi)
                        .border(1.dp, DK.gold.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            glyph,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 12.5.sp,
                color = if (selected) DK.gold else DK.textSecondary,
            ),
            modifier = Modifier.width(16.dp),
        )
        Text(
            label,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) DK.textPrimary else DK.textSecondary,
            ),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Group headers — MARKETS / PORTFOLIO / INVEST — micro-caps tertiary with tracking, mirroring
 *  the mockup's `.sglabel` and `RootView.groupLabel`'s narrower sidebar inset. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
            color = DK.textTertiary, letterSpacing = 1.3.sp,
        ),
        modifier = Modifier.padding(horizontal = 10.dp).padding(top = 14.dp, bottom = 5.dp),
    )
}

/** Search / Settings footer rows — same row shape as [SidebarRow] but never "selected" (they
 *  open overlays, not destinations) and can show a trailing hint (Ctrl+K). [fillWidth] mirrors
 *  `RootView.sidebarFooterRow`'s two call shapes: Search fills the rail so its hint lands at the
 *  far right; Settings sizes to content so the theme toggle sits right beside it, not pinned to
 *  the trailing edge. */
@Composable
private fun FooterRow(
    icon: @Composable () -> Unit,
    label: String,
    trailingHint: String?,
    fillWidth: Boolean,
    onClick: () -> Unit,
    horizontalPadding: Dp = 10.dp,
) {
    Row(
        modifier = Modifier
            .let { if (fillWidth) it.fillMaxWidth() else it }
            .padding(horizontal = horizontalPadding)
            .clip(RoundedCornerShape(9.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            label,
            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
        )
        if (fillWidth) Spacer(Modifier.weight(1f))
        if (trailingHint != null) {
            Text(
                trailingHint,
                style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = DK.textTertiary),
            )
        }
    }
}

/** Quick-access theme toggle beside Settings — mirrors `RootView.themeToggleButton` exactly:
 *  a circular gold-ringed button whose glyph reflects the CURRENT mode (moon shown while dark,
 *  sun shown while light), tapping flips it. Additive to the full Appearance page in
 *  [AccountPanel] (which keeps its own Light/Dark rows) — Swift keeps both for the same reason:
 *  a one-tap toggle here, a considered picker there. */
@Composable
private fun ThemeToggleButton(isDarkMode: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.gold.copy(alpha = 0.4f), RoundedCornerShape(50))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggle(!isDarkMode) },
        contentAlignment = Alignment.Center,
    ) {
        if (isDarkMode) {
            MoonIcon(tint = DK.gold, modifier = Modifier.size(13.dp))
        } else {
            SunIcon(tint = DK.gold, modifier = Modifier.size(13.dp))
        }
    }
}
