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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.AccentTheme
import com.aptrade.desktop.designkit.BrandWordmark
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.DKSwitch
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.infra.AppSettings

/** The pages reachable inside the account panel. Root is the row list; the others are
 *  detail pages with a back affordance. */
private enum class AccountPage { Root, Appearance, About, Notifications, Placeholder }

/** Right-anchored settings overlay opened from the shell's ⋯ button. Full-window scrim
 *  (click closes, PaletteOverlay idiom), a 360dp panel pinned to the trailing edge with a
 *  DK surface + hairline border. Esc is self-consumed on the panel's own onPreviewKeyEvent
 *  (TradeDialog pattern) so it never reaches — and never steals — the window's palette Esc.
 *
 *  Row behavior mirrors the macOS account sheet: Appearance → accent page, Notifications →
 *  the real push/email toggle page (increment 6d.1), Export Portfolio Data →
 *  [onExportPortfolio], About → logo + tagline page; every other row renders a shared
 *  "Not available on desktop yet" placeholder (RECORDED DIVERGENCE — macOS has functional
 *  pages; desktop adopts them later. No Sign Out row: desktop has no auth). */
@Composable
fun AccountPanel(
    accent: AccentTheme,
    onSelectAccent: (AccentTheme) -> Unit,
    onExportPortfolio: () -> Unit,
    onClose: () -> Unit,
    notificationSettings: AppSettings,
    onUpdateNotificationSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    var page by remember { mutableStateOf(AccountPage.Root) }
    var placeholderTitle by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(360.dp)
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(0.dp))
                // Swallow clicks inside the panel so the scrim's close doesn't fire.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                // Consume Esc on the panel's own preview handler (TradeDialog pattern): dismiss
                // the panel before the window's Esc-priority chain ever sees the event.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onClose(); true
                    } else {
                        false
                    }
                }
                .focusRequester(focusRequester)
                .focusable()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            when (page) {
                AccountPage.Root -> RootList(
                    onAppearance = { page = AccountPage.Appearance },
                    onAbout = { page = AccountPage.About },
                    onNotifications = { page = AccountPage.Notifications },
                    onExport = onExportPortfolio,
                    onPlaceholder = { title -> placeholderTitle = title; page = AccountPage.Placeholder },
                    onClose = onClose,
                )
                AccountPage.Appearance -> AppearancePage(
                    accent = accent,
                    onSelectAccent = onSelectAccent,
                    onBack = { page = AccountPage.Root },
                )
                AccountPage.About -> AboutPage(onBack = { page = AccountPage.Root })
                AccountPage.Notifications -> NotificationsPage(
                    settings = notificationSettings,
                    onUpdate = onUpdateNotificationSettings,
                    onBack = { page = AccountPage.Root },
                )
                AccountPage.Placeholder -> PlaceholderPage(
                    title = placeholderTitle,
                    onBack = { page = AccountPage.Root },
                )
            }
        }
    }
    // Focus the panel on open so its onPreviewKeyEvent receives Esc.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

// MARK: - Root

/** The account row list, in macOS order. Only Appearance / Export / About are functional;
 *  the rest fall through to the shared placeholder page. */
private enum class AccountRow(val label: String) {
    Profile("Profile"),
    AccountSettings("Account Settings"),
    Notifications("Notifications"),
    Appearance("Appearance"),
    Language("Language"),
    SecurityPrivacy("Security & Privacy"),
    ExportPortfolioData("Export Portfolio Data"),
    HelpSupport("Help & Support"),
    AboutAPTrade("About APTrade"),
}

@Composable
private fun RootList(
    onAppearance: () -> Unit,
    onAbout: () -> Unit,
    onNotifications: () -> Unit,
    onExport: () -> Unit,
    onPlaceholder: (String) -> Unit,
    onClose: () -> Unit,
) {
    PanelHeader(title = "Account", onLeading = onClose, leadingGlyph = "✕")
    Spacer(Modifier.height(12.dp))
    for (row in AccountRow.entries) {
        NavRow(label = row.label) {
            when (row) {
                AccountRow.Appearance -> onAppearance()
                AccountRow.AboutAPTrade -> onAbout()
                AccountRow.Notifications -> onNotifications()
                AccountRow.ExportPortfolioData -> onExport()
                else -> onPlaceholder(row.label)
            }
        }
    }
}

// MARK: - Appearance

@Composable
private fun AppearancePage(
    accent: AccentTheme,
    onSelectAccent: (AccentTheme) -> Unit,
    onBack: () -> Unit,
) {
    PanelHeader(title = "Appearance", onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(16.dp))
    SectionLabel("ACCENT")
    Spacer(Modifier.height(8.dp))
    for (option in AccentTheme.entries) {
        AccentRow(
            option = option,
            selected = option == accent,
            onClick = { onSelectAccent(option) },
        )
    }
}

@Composable
private fun AccentRow(option: AccentTheme, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        // 26dp gradient circle: deep → mid → light, bottomLeft → topRight.
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(option.deep, option.mid, option.light),
                        start = Offset(0f, Float.POSITIVE_INFINITY),
                        end = Offset(Float.POSITIVE_INFINITY, 0f),
                    )
                )
                .border(1.dp, DK.hairline, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                option.displayName,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
                ),
            )
            Text(
                option.tagline,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, color = DK.textSecondary,
                ),
            )
        }
        if (selected) {
            Text(
                "✓",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, color = DK.gold,
                ),
            )
        }
    }
}

// MARK: - About

@Composable
private fun AboutPage(onBack: () -> Unit) {
    PanelHeader(title = "About", onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(40.dp))
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandWordmark(height = 96.dp)
        Text(
            "Premium native investing for desktop.",
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, color = DK.textSecondary,
            ),
        )
    }
}

// MARK: - Notifications

/** PUSH NOTIFICATIONS / EMAIL toggle page — the Compose port of
 *  `Sources/APTradeApp/RootView.swift`'s `notificationsPage`. Every toggle persists on
 *  change via [onUpdate] (increment 6d.1's `AppSettings` fields); [AppSettings
 *  .emailNotifications] is persisted-but-unwired, same macOS-parity note as the Swift
 *  source ("Send a copy to ankitpatel.svnit@gmail.com" — no delivery pipeline exists yet). */
@Composable
private fun NotificationsPage(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    PanelHeader(title = "Notifications", onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(16.dp))
    SectionLabel("PUSH NOTIFICATIONS")
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        title = "Price Alerts",
        subtitle = "When a watchlist alert is triggered",
        checked = settings.priceAlerts,
        onCheckedChange = { checked -> onUpdate { it.copy(priceAlerts = checked) } },
    )
    ToggleRow(
        title = "Order Fills",
        subtitle = "Buy and sell confirmations",
        checked = settings.orderFills,
        onCheckedChange = { checked -> onUpdate { it.copy(orderFills = checked) } },
    )
    ToggleRow(
        title = "Market Open & Close",
        subtitle = "Daily session reminders",
        checked = settings.marketOpenClose,
        onCheckedChange = { checked -> onUpdate { it.copy(marketOpenClose = checked) } },
    )
    ToggleRow(
        title = "Daily News Digest",
        subtitle = "Top stories for your holdings",
        checked = settings.newsDigest,
        onCheckedChange = { checked -> onUpdate { it.copy(newsDigest = checked) } },
    )
    Spacer(Modifier.height(10.dp))
    SectionLabel("EMAIL")
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        title = "Email Notifications",
        subtitle = "Send a copy to ankitpatel.svnit@gmail.com",
        checked = settings.emailNotifications,
        onCheckedChange = { checked -> onUpdate { it.copy(emailNotifications = checked) } },
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, color = DK.textPrimary,
                ),
            )
            Text(
                subtitle,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 11.sp,
                    fontWeight = FontWeight.Normal, color = DK.textTertiary,
                ),
            )
        }
        DKSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// MARK: - Placeholder

/** RECORDED DIVERGENCE: macOS has functional pages for these rows; desktop adopts them in a
 *  later increment. Until then every non-functional row lands here. */
@Composable
private fun PlaceholderPage(title: String, onBack: () -> Unit) {
    PanelHeader(title = title, onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(60.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Not available on desktop yet",
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, color = DK.textTertiary,
            ),
        )
    }
}

// MARK: - Shared

@Composable
private fun PanelHeader(title: String, onLeading: () -> Unit, leadingGlyph: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onLeading() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                leadingGlyph,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textSecondary,
                ),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, color = DK.textPrimary,
            ),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, color = DK.textTertiary, letterSpacing = 1.sp,
        ),
    )
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, color = DK.textPrimary,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            "›",
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, color = DK.textTertiary,
            ),
        )
    }
}
