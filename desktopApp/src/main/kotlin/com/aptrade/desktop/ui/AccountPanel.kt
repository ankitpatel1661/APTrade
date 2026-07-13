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
import com.aptrade.desktop.designkit.MoonIcon
import com.aptrade.desktop.designkit.SunIcon
import com.aptrade.desktop.designkit.deep
import com.aptrade.desktop.designkit.light
import com.aptrade.desktop.designkit.mid
import com.aptrade.desktop.infra.AppSettings
import com.aptrade.desktop.l10n.AppLanguage
import com.aptrade.shared.l10n.L10n
import com.aptrade.desktop.l10n.tr

/** The pages reachable inside the account panel. Root is the row list; the others are
 *  detail pages with a back affordance. */
private enum class AccountPage { Root, Appearance, Language, About, Notifications, Security, Profile, AccountSettings, Help }

/** Right-anchored settings overlay opened from the shell's ⋯ button. Full-window scrim
 *  (click closes, PaletteOverlay idiom), a 360dp panel pinned to the trailing edge with a
 *  DK surface + hairline border. Esc is self-consumed on the panel's own onPreviewKeyEvent
 *  (TradeDialog pattern) so it never reaches — and never steals — the window's palette Esc.
 *
 *  Row behavior mirrors the macOS account sheet: Appearance → accent page, Language → the
 *  real language picker (increment 6e Task 5), Notifications → the real push/email toggle
 *  page (increment 6d.1), Security & Privacy / Profile / Account Settings / Help & Support →
 *  their real pages (increment 6d.2 Task 3), Export Portfolio Data → [onExportPortfolio],
 *  About → logo + tagline page. Every row is now functional — no page still falls through to
 *  a placeholder. No Sign Out row: desktop has no auth. */
@Composable
fun AccountPanel(
    accent: AccentTheme,
    onSelectAccent: (AccentTheme) -> Unit,
    isDarkMode: Boolean,
    onSelectTheme: (Boolean) -> Unit,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onExportPortfolio: () -> Unit,
    onClose: () -> Unit,
    notificationSettings: AppSettings,
    onUpdateNotificationSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    var page by remember { mutableStateOf(AccountPage.Root) }
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
                    onLanguage = { page = AccountPage.Language },
                    onAbout = { page = AccountPage.About },
                    onNotifications = { page = AccountPage.Notifications },
                    onSecurity = { page = AccountPage.Security },
                    onProfile = { page = AccountPage.Profile },
                    onAccountSettings = { page = AccountPage.AccountSettings },
                    onHelp = { page = AccountPage.Help },
                    onExport = onExportPortfolio,
                    onClose = onClose,
                )
                AccountPage.Appearance -> AppearancePage(
                    accent = accent,
                    onSelectAccent = onSelectAccent,
                    isDarkMode = isDarkMode,
                    onSelectTheme = onSelectTheme,
                    onBack = { page = AccountPage.Root },
                )
                AccountPage.Language -> LanguagePage(
                    language = language,
                    onSelectLanguage = onSelectLanguage,
                    onBack = { page = AccountPage.Root },
                )
                AccountPage.About -> AboutPage(onBack = { page = AccountPage.Root })
                AccountPage.Notifications -> NotificationsPage(
                    settings = notificationSettings,
                    onUpdate = onUpdateNotificationSettings,
                    onBack = { page = AccountPage.Root },
                )
                AccountPage.Security -> SecurityPage(
                    settings = notificationSettings,
                    onUpdate = onUpdateNotificationSettings,
                    onBack = { page = AccountPage.Root },
                    onClose = onClose,
                )
                AccountPage.Profile -> ProfilePage(onBack = { page = AccountPage.Root })
                AccountPage.AccountSettings -> AccountSettingsPage(onBack = { page = AccountPage.Root })
                AccountPage.Help -> HelpPage(onBack = { page = AccountPage.Root }, onClose = onClose)
            }
        }
    }
    // Focus the panel on open so its onPreviewKeyEvent receives Esc.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

// MARK: - Root

/** The account row list, in macOS order. Every row is functional as of increment 6e Task 5
 *  (Language is the last one to gain a real destination — see the [AccountPanel] doc
 *  comment). Labels resolve via [tr] at render time (a `val ... get() =`, not a constructor
 *  literal) so they recompose on language change, matching [com.aptrade.desktop.detail
 *  .IndicatorOverlays]'s `Indicator.label` pattern from Wave 2. */
private enum class AccountRow(private val key: L10n.Key) {
    Profile(L10n.Key.Profile),
    AccountSettings(L10n.Key.AccountSettings),
    Notifications(L10n.Key.Notifications),
    Appearance(L10n.Key.Appearance),
    Language(L10n.Key.Language),
    SecurityPrivacy(L10n.Key.SecurityAndPrivacy),
    ExportPortfolioData(L10n.Key.ExportPortfolioData),
    HelpSupport(L10n.Key.HelpAndSupport),
    AboutAPTrade(L10n.Key.AboutAPTrade),
    ;

    val label: String get() = tr(key)
}

@Composable
private fun RootList(
    onAppearance: () -> Unit,
    onLanguage: () -> Unit,
    onAbout: () -> Unit,
    onNotifications: () -> Unit,
    onSecurity: () -> Unit,
    onProfile: () -> Unit,
    onAccountSettings: () -> Unit,
    onHelp: () -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
) {
    PanelHeader(title = tr(L10n.Key.Account), onLeading = onClose, leadingGlyph = "✕")
    Spacer(Modifier.height(12.dp))
    for (row in AccountRow.entries) {
        NavRow(label = row.label) {
            when (row) {
                AccountRow.Appearance -> onAppearance()
                AccountRow.Language -> onLanguage()
                AccountRow.AboutAPTrade -> onAbout()
                AccountRow.Notifications -> onNotifications()
                AccountRow.SecurityPrivacy -> onSecurity()
                AccountRow.Profile -> onProfile()
                AccountRow.AccountSettings -> onAccountSettings()
                AccountRow.HelpSupport -> onHelp()
                AccountRow.ExportPortfolioData -> onExport()
            }
        }
    }
}

// MARK: - Appearance

@Composable
private fun AppearancePage(
    accent: AccentTheme,
    onSelectAccent: (AccentTheme) -> Unit,
    isDarkMode: Boolean,
    onSelectTheme: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    PanelHeader(title = tr(L10n.Key.Appearance), onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(16.dp))
    SectionLabel(tr(L10n.Key.Theme))
    Spacer(Modifier.height(8.dp))
    // ThemeManager.toggle() semantics (macOS RootView.swift appearancePage): tapping the
    // row that's already selected is a no-op — only the non-current row's tap fires.
    ThemeRow(
        icon = { tint -> MoonIcon(tint = tint, modifier = Modifier.size(15.dp)) },
        title = tr(L10n.Key.Dark),
        subtitle = tr(L10n.Key.DarkSubtitle),
        selected = isDarkMode,
        onClick = { if (!isDarkMode) onSelectTheme(true) },
    )
    ThemeRow(
        icon = { tint -> SunIcon(tint = tint, modifier = Modifier.size(15.dp)) },
        title = tr(L10n.Key.Light),
        subtitle = tr(L10n.Key.LightSubtitle),
        selected = !isDarkMode,
        onClick = { if (isDarkMode) onSelectTheme(false) },
    )
    Spacer(Modifier.height(10.dp))
    SectionLabel(tr(L10n.Key.Accent))
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
private fun ThemeRow(
    icon: @Composable (tint: Color) -> Unit,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
        // 26dp leading glyph slot, matching AccentRow's 26dp leading circle.
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            icon(DK.gold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
                ),
            )
            Text(
                subtitle,
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

// MARK: - Language

/** Language picker (increment 6e Task 5) — replaces the earlier "Not available on desktop
 *  yet" placeholder. Anatomy mirrors [AppearancePage]'s THEME rows exactly: a single-column
 *  list of the four [AppLanguage] entries, each showing its endonym [AppLanguage.displayName],
 *  a checkmark on the selected row, tap-to-select (a no-op re-tap on the already-selected row,
 *  same as [ThemeRow]'s toggle semantics). Selecting flips [LocalizationManager.current]
 *  immediately (every `tr()` reader recomposes) and persists through the one settings seam. */
@Composable
private fun LanguagePage(
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onBack: () -> Unit,
) {
    PanelHeader(title = tr(L10n.Key.Language), onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(16.dp))
    for (option in AppLanguage.entries) {
        LanguageRow(
            option = option,
            selected = option == language,
            onClick = { if (option != language) onSelectLanguage(option) },
        )
    }
}

@Composable
private fun LanguageRow(option: AppLanguage, selected: Boolean, onClick: () -> Unit) {
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
        Text(
            option.displayName,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
            ),
            modifier = Modifier.weight(1f),
        )
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
    // macOS's aboutPage header is tr(.aboutAPTrade) = "About APTrade" (RootView.swift:587),
    // not a generic "About" — matching it here (visible-text parity fix).
    PanelHeader(title = tr(L10n.Key.AboutAPTrade), onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(40.dp))
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandWordmark(height = 96.dp)
        // Desktop-specific tagline, NOT macOS's tr(.taglineShort) ("Premium investing for
        // macOS" — wrong platform name here) — an intentional divergence, left literal, same
        // as documented in prior waves for platform-specific copy.
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
    PanelHeader(title = tr(L10n.Key.Notifications), onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(16.dp))
    SectionLabel(tr(L10n.Key.PushNotifications))
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        title = tr(L10n.Key.PriceAlerts),
        subtitle = tr(L10n.Key.PriceAlertsSubtitle),
        checked = settings.priceAlerts,
        onCheckedChange = { checked -> onUpdate { it.copy(priceAlerts = checked) } },
    )
    ToggleRow(
        title = tr(L10n.Key.OrderFills),
        subtitle = tr(L10n.Key.OrderFillsSubtitle),
        checked = settings.orderFills,
        onCheckedChange = { checked -> onUpdate { it.copy(orderFills = checked) } },
    )
    ToggleRow(
        title = tr(L10n.Key.MarketOpenAndClose),
        subtitle = tr(L10n.Key.MarketOpenAndCloseSubtitle),
        checked = settings.marketOpenClose,
        onCheckedChange = { checked -> onUpdate { it.copy(marketOpenClose = checked) } },
    )
    ToggleRow(
        title = tr(L10n.Key.DailyNewsDigest),
        subtitle = tr(L10n.Key.DailyNewsDigestSubtitle),
        checked = settings.newsDigest,
        onCheckedChange = { checked -> onUpdate { it.copy(newsDigest = checked) } },
    )
    ToggleRow(
        title = tr(L10n.Key.EarningsReportsToggle),
        subtitle = tr(L10n.Key.EarningsReportsSubtitle),
        checked = settings.earningsReports,
        onCheckedChange = { checked -> onUpdate { it.copy(earningsReports = checked) } },
    )
    Spacer(Modifier.height(10.dp))
    // macOS reuses tr(.email) ("Email") for this section label too (RootView.swift:426) —
    // not a dedicated EMAIL-section Key. Mirrored here rather than adding a duplicate Key.
    SectionLabel(tr(L10n.Key.Email))
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        title = tr(L10n.Key.EmailNotifications),
        subtitle = tr(L10n.Key.EmailNotificationsSubtitle),
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

// MARK: - Security & Privacy

/** Security & Privacy page — the Compose port of `Sources/APTradeApp/RootView.swift`'s
 *  `securityPage` (lines 535-563). AUTHENTICATION and PRIVACY toggles persist through
 *  [onUpdate] like the Notifications page; DATA is three decorative link rows.
 *
 *  HONEST PARITY (recorded): only [AppSettings.confirmTrades] is functional — it gates the
 *  in-dialog trade confirmation layer in [com.aptrade.desktop.portfolio.TradeDialog].
 *  [AppSettings.biometricLogin], [AppSettings.requireAuthOnLaunch], and
 *  [AppSettings.analyticsSharing] persist but drive nothing yet, same as macOS: this is a
 *  simulated paper-trading app with no real biometric/auth/analytics pipeline. */
@Composable
private fun SecurityPage(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    PanelHeader(title = tr(L10n.Key.SecurityAndPrivacy), onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(16.dp))
    SectionLabel(tr(L10n.Key.Authentication))
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        title = tr(L10n.Key.BiometricLogin),
        subtitle = tr(L10n.Key.BiometricLoginSubtitle),
        checked = settings.biometricLogin,
        onCheckedChange = { checked -> onUpdate { it.copy(biometricLogin = checked) } },
    )
    ToggleRow(
        title = tr(L10n.Key.RequireAuthOnLaunch),
        subtitle = tr(L10n.Key.RequireAuthOnLaunchSubtitle),
        checked = settings.requireAuthOnLaunch,
        onCheckedChange = { checked -> onUpdate { it.copy(requireAuthOnLaunch = checked) } },
    )
    ToggleRow(
        title = tr(L10n.Key.ConfirmTrades),
        subtitle = tr(L10n.Key.ConfirmTradesSubtitle),
        checked = settings.confirmTrades,
        onCheckedChange = { checked -> onUpdate { it.copy(confirmTrades = checked) } },
    )
    Spacer(Modifier.height(10.dp))
    SectionLabel(tr(L10n.Key.Privacy))
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        title = tr(L10n.Key.ShareUsageAnalytics),
        subtitle = tr(L10n.Key.ShareUsageAnalyticsSubtitle),
        checked = settings.analyticsSharing,
        onCheckedChange = { checked -> onUpdate { it.copy(analyticsSharing = checked) } },
    )
    Spacer(Modifier.height(10.dp))
    SectionLabel(tr(L10n.Key.DataSection))
    Spacer(Modifier.height(8.dp))
    LinkRow(title = tr(L10n.Key.ChangePassword), onClick = onClose)
    // "2 active" is literal on macOS too (RootView.swift:555 passes it as a raw value: —
    // no L10n Key exists for it there either).
    LinkRow(title = tr(L10n.Key.ManageDevices), value = "2 active", onClick = onClose)
    LinkRow(title = tr(L10n.Key.ClearLocalCache), onClick = onClose)
}

// MARK: - Profile

/** Profile page — the Compose port of `Sources/APTradeApp/RootView.swift`'s `profilePage`
 *  (lines 373-390). Three decorative detail rows; no persisted state (macOS has none here
 *  either — this simulated account has fixed identity fields). */
@Composable
private fun ProfilePage(onBack: () -> Unit) {
    // Values (name/DOB/email) are fixed simulated-identity display data, left literal — macOS
    // does the same (RootView.swift:378-380 passes raw string literals as detailField's
    // value: argument, only the labels route through tr()).
    PanelHeader(title = tr(L10n.Key.Profile), onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(16.dp))
    DetailField(label = tr(L10n.Key.Name), value = "Ankit Patel")
    Spacer(Modifier.height(14.dp))
    DetailField(label = tr(L10n.Key.DateOfBirth), value = "January 1, 1995")
    Spacer(Modifier.height(14.dp))
    DetailField(label = tr(L10n.Key.Email), value = "ankitpatel.svnit@gmail.com")
}

// MARK: - Account Settings

/** Account Settings page — the Compose port of `Sources/APTradeApp/RootView.swift`'s
 *  `accountSettingsPage` (lines 392-411). Five decorative detail rows, including the static
 *  "Enabled — Touch ID" biometric row: macOS displays static text here too, NOT bound to the
 *  Security page's Biometric Login toggle (verified against `RootView.swift:403`, which reads
 *  `tr(.enabledTouchID)` — a fixed L10n string, not `settingsVM.settings.biometricLogin`). */
@Composable
private fun AccountSettingsPage(onBack: () -> Unit) {
    // Trading Mode / Default Tab / Biometric Login VALUES route through tr() too, matching
    // macOS exactly (RootView.swift:398-403: tr(.simulatedPaperTrading), tr(.watchlist),
    // tr(.enabledTouchID)) — these are keyed display strings, not free-form personal data.
    // Starting Balance and Display Currency values stay literal, same as macOS
    // (RootView.swift:399-400 pass raw "$100,000.00" / "USD ($)" literals).
    PanelHeader(title = tr(L10n.Key.AccountSettings), onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(16.dp))
    DetailField(label = tr(L10n.Key.TradingMode), value = tr(L10n.Key.SimulatedPaperTrading))
    Spacer(Modifier.height(14.dp))
    DetailField(label = tr(L10n.Key.StartingBalance), value = "$100,000.00")
    Spacer(Modifier.height(14.dp))
    DetailField(label = tr(L10n.Key.DisplayCurrency), value = "USD ($)")
    Spacer(Modifier.height(14.dp))
    DetailField(label = tr(L10n.Key.DefaultTab), value = tr(L10n.Key.Watchlist))
    Spacer(Modifier.height(14.dp))
    DetailField(label = tr(L10n.Key.BiometricLogin), value = tr(L10n.Key.EnabledTouchID))
}

// MARK: - Help & Support

/** Help & Support page — the Compose port of `Sources/APTradeApp/RootView.swift`'s
 *  `helpPage` (lines 565-583). RESOURCES and CONTACT are decorative link rows (close-only,
 *  same as macOS's `linkRow`, which just dismisses the sheet — no actual navigation target
 *  exists in either app). */
@Composable
private fun HelpPage(onBack: () -> Unit, onClose: () -> Unit) {
    PanelHeader(title = tr(L10n.Key.HelpAndSupport), onLeading = onBack, leadingGlyph = "‹")
    Spacer(Modifier.height(16.dp))
    SectionLabel(tr(L10n.Key.Resources))
    Spacer(Modifier.height(8.dp))
    // macOS renders tr(.faq) = "Frequently Asked Questions" in English (L10n.swift:71) —
    // the full phrase, not the "FAQ" shorthand the task brief used.
    LinkRow(title = tr(L10n.Key.Faq), onClick = onClose)
    LinkRow(title = tr(L10n.Key.UserGuide), onClick = onClose)
    LinkRow(title = tr(L10n.Key.KeyboardShortcuts), onClick = onClose)
    Spacer(Modifier.height(10.dp))
    SectionLabel(tr(L10n.Key.Contact))
    Spacer(Modifier.height(8.dp))
    // "support@aptrade.app" is literal on macOS too (RootView.swift:576's linkRow value:) —
    // an email address as data, not prose.
    LinkRow(title = tr(L10n.Key.EmailSupport), value = "support@aptrade.app", onClick = onClose)
    LinkRow(title = tr(L10n.Key.ReportAProblem), onClick = onClose)
}

// MARK: - Shared detail/link rows

/** The Compose port of `RootView.swift`'s `detailField`: an uppercase tertiary label over a
 *  primary-color value, used by Profile and Account Settings. */
@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label.uppercase(),
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = DK.textTertiary, letterSpacing = 1.2.sp,
            ),
        )
        Text(
            value,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, color = DK.textPrimary,
            ),
        )
    }
}

/** The Compose port of `RootView.swift`'s `linkRow`: a title, an optional trailing value, and
 *  a chevron. macOS's `linkRow` always just calls `close()` regardless of which row was
 *  tapped — none of these rows have a real destination in the simulated app — so [onClick] is
 *  always the panel's close callback at the call site. */
@Composable
private fun LinkRow(title: String, value: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 11.dp, horizontal = 4.dp),
    ) {
        Text(
            title,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, color = DK.textPrimary,
            ),
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                value,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 12.sp,
                    fontWeight = FontWeight.Normal, color = DK.textTertiary,
                    fontFeatureSettings = "tnum",
                ),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            "›",
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = DK.textTertiary,
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

/** Uppercases [text] like macOS's `RootView.swift` `sectionLabel(_:)` (`text.uppercased()`) —
 *  callers pass the plain [tr]-resolved string, not a pre-uppercased literal, so translated
 *  section labels uppercase correctly too (not just the English ALL-CAPS literals this
 *  replaced). */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
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
