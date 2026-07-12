package com.aptrade.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.android.l10n.LocalizationManager
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.theme.deep
import com.aptrade.android.ui.theme.light
import com.aptrade.android.ui.theme.mid
import com.aptrade.shared.l10n.AppLanguage
import com.aptrade.shared.l10n.L10n
import com.aptrade.shared.settings.AccentTheme
import com.aptrade.shared.settings.AppSettings

/** The pages reachable inside the settings destination. Root is the row list; the others
 *  are detail pages with a back affordance — the Android port of desktop AccountPanel.kt's
 *  `AccountPage`. No Export Portfolio Data page: the brief's Android menu set omits it
 *  (desktop's export row triggers a desktop file chooser that has no analog here yet). */
private enum class SettingsPage(val titleKey: L10n.Key) {
    Root(L10n.Key.Account),
    Profile(L10n.Key.Profile),
    AccountSettings(L10n.Key.AccountSettings),
    Notifications(L10n.Key.Notifications),
    Appearance(L10n.Key.Appearance),
    Language(L10n.Key.Language),
    Security(L10n.Key.SecurityAndPrivacy),
    Help(L10n.Key.HelpAndSupport),
    About(L10n.Key.AboutAPTrade),
}

/**
 * The settings destination — NavHost-pushed from the top bar's account action, replacing
 * `SettingsPlaceholder`. The page set and row anatomy mirror desktop `AccountPanel.kt`
 * (which itself ports macOS `RootView.swift`'s account sheet): a root menu of
 * Profile / Account Settings / Notifications / Appearance / Language / Security & Privacy /
 * Help & Support / About APTrade, each opening a sub-page. Every string resolves through
 * [tr] — no hardcoded English — so a language change recomposes the whole screen live.
 *
 * Own Scaffold-in-route (like SearchScreen/DetailScreen): the top bar shows the current
 * page's title with a back arrow that pops sub-page → root → [onClose]; the system back
 * gesture follows the same chain via [BackHandler].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    val settings by viewModel.settings.collectAsState()

    fun back() {
        if (page != SettingsPage.Root) page = SettingsPage.Root else onClose()
    }

    BackHandler { back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(page.titleKey)) },
                navigationIcon = {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            when (page) {
                SettingsPage.Root -> RootList(onOpen = { page = it })
                SettingsPage.Profile -> ProfilePage()
                SettingsPage.AccountSettings -> AccountSettingsPage()
                SettingsPage.Notifications -> NotificationsPage(
                    settings = settings,
                    onUpdate = viewModel::update,
                )
                SettingsPage.Appearance -> AppearancePage(
                    settings = settings,
                    onUpdate = viewModel::update,
                )
                SettingsPage.Language -> LanguagePage(
                    language = LocalizationManager.current.value,
                    onSelectLanguage = viewModel::selectLanguage,
                )
                SettingsPage.Security -> SecurityPage(
                    settings = settings,
                    onUpdate = viewModel::update,
                    onClose = onClose,
                )
                SettingsPage.Help -> HelpPage(onClose = onClose)
                SettingsPage.About -> AboutPage()
            }
        }
    }
}

// MARK: - Root

/** The account row list, in the desktop/macOS order (minus Export Portfolio Data). Labels
 *  resolve via [tr] at render time so they recompose on language change. */
@Composable
private fun RootList(onOpen: (SettingsPage) -> Unit) {
    val rows = listOf(
        SettingsPage.Profile,
        SettingsPage.AccountSettings,
        SettingsPage.Notifications,
        SettingsPage.Appearance,
        SettingsPage.Language,
        SettingsPage.Security,
        SettingsPage.Help,
        SettingsPage.About,
    )
    for (row in rows) {
        NavRow(label = tr(row.titleKey)) { onOpen(row) }
    }
}

// MARK: - Appearance

@Composable
private fun AppearancePage(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    SectionLabel(tr(L10n.Key.Theme))
    Spacer(Modifier.height(8.dp))
    // ThemeManager.toggle() semantics (desktop AccountPanel / macOS RootView.swift): tapping
    // the row that's already selected is a no-op — only the non-current row's tap fires.
    ThemeRow(
        glyph = "☾",
        title = tr(L10n.Key.Dark),
        subtitle = tr(L10n.Key.DarkSubtitle),
        selected = settings.isDarkMode,
        onClick = { if (!settings.isDarkMode) onUpdate { it.copy(isDarkMode = true) } },
    )
    ThemeRow(
        glyph = "☀",
        title = tr(L10n.Key.Light),
        subtitle = tr(L10n.Key.LightSubtitle),
        selected = !settings.isDarkMode,
        onClick = { if (settings.isDarkMode) onUpdate { it.copy(isDarkMode = false) } },
    )
    Spacer(Modifier.height(10.dp))
    SectionLabel(tr(L10n.Key.Accent))
    Spacer(Modifier.height(8.dp))
    for (option in AccentTheme.entries) {
        AccentRow(
            option = option,
            selected = option == settings.accent,
            onClick = { onUpdate { it.copy(accent = option) } },
        )
    }
}

@Composable
private fun ThemeRow(
    glyph: String,
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
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        // 26dp leading glyph slot, matching AccentRow's 26dp leading circle.
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Text(glyph, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) Checkmark()
    }
}

@Composable
private fun AccentRow(option: AccentTheme, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        // 26dp gradient circle: deep → mid → light, bottomLeft → topRight (desktop AccentRow).
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
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // displayName/tagline are the shared AccentTheme's picker copy — English-only by
            // design (same as desktop AccentRow; the identity enum predates the L10n catalog).
            Text(option.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                option.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) Checkmark()
    }
}

// MARK: - Language

/** Language picker — anatomy mirrors [AppearancePage]'s THEME rows exactly: the four
 *  [AppLanguage] entries by endonym, checkmark on the selected row, tap-to-select with a
 *  no-op re-tap. Selection flips [LocalizationManager.current] immediately (every `tr()`
 *  reader recomposes — including this screen's own title) AND persists through the one
 *  settings seam ([SettingsViewModel.selectLanguage]). */
@Composable
private fun LanguagePage(language: AppLanguage, onSelectLanguage: (AppLanguage) -> Unit) {
    for (option in AppLanguage.entries) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { if (option != language) onSelectLanguage(option) }
                .padding(vertical = 10.dp, horizontal = 4.dp),
        ) {
            Text(
                option.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (option == language) Checkmark()
        }
    }
}

// MARK: - Notifications

/** PUSH NOTIFICATIONS / EMAIL toggle page — the Android port of desktop AccountPanel's
 *  `NotificationsPage`. Every toggle persists on change via [onUpdate].
 *
 *  Wiring status (honest parity): [AppSettings.priceAlerts] gates `AppGraph.evaluateAlerts`'s
 *  notifier (wired in Task 6, same as desktop). [AppSettings.orderFills] persists but drives
 *  nothing on Android yet — no order-fill notification pipeline exists here (desktop posts
 *  tray fills; Android's trade flow has no notifier seam). [AppSettings.emailNotifications]
 *  is persisted-but-unwired by design on every platform (no email pipeline exists). */
@Composable
private fun NotificationsPage(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
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
    Spacer(Modifier.height(10.dp))
    // macOS/desktop reuse tr(.email) ("Email") for this section label — not a dedicated
    // EMAIL-section Key. Mirrored here rather than adding a duplicate Key.
    SectionLabel(tr(L10n.Key.Email))
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        title = tr(L10n.Key.EmailNotifications),
        subtitle = tr(L10n.Key.EmailNotificationsSubtitle),
        checked = settings.emailNotifications,
        onCheckedChange = { checked -> onUpdate { it.copy(emailNotifications = checked) } },
    )
}

// MARK: - Security & Privacy

/** Security & Privacy page — the Android port of desktop AccountPanel's `SecurityPage`.
 *  AUTHENTICATION and PRIVACY toggles persist through [onUpdate]; DATA is three decorative
 *  link rows (close-only, same as desktop/macOS).
 *
 *  HONEST PARITY (recorded): on desktop/macOS only [AppSettings.confirmTrades] is functional
 *  (it gates the trade dialog's confirmation layer). On Android even that consumer doesn't
 *  exist yet — `TradeSheet`'s single Confirm button has no settings-gated second step — so
 *  ALL four toggles here persist but drive nothing. Same row structure regardless, so the
 *  wiring lands without UI changes when Android's trade sheet gains the confirm layer. */
@Composable
private fun SecurityPage(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onClose: () -> Unit,
) {
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
    // "2 active" is literal on desktop/macOS too — no L10n Key exists for it there either.
    LinkRow(title = tr(L10n.Key.ManageDevices), value = "2 active", onClick = onClose)
    LinkRow(title = tr(L10n.Key.ClearLocalCache), onClick = onClose)
}

// MARK: - Profile

/** Profile page — three decorative detail rows; the values are fixed simulated-identity
 *  display data, left literal on every platform (only the labels route through tr()). */
@Composable
private fun ProfilePage() {
    DetailField(label = tr(L10n.Key.Name), value = "Ankit Patel")
    Spacer(Modifier.height(14.dp))
    DetailField(label = tr(L10n.Key.DateOfBirth), value = "January 1, 1995")
    Spacer(Modifier.height(14.dp))
    DetailField(label = tr(L10n.Key.Email), value = "ankitpatel.svnit@gmail.com")
}

// MARK: - Account Settings

/** Account Settings page — five decorative detail rows, including the static
 *  "Enabled — Touch ID" biometric row: macOS/desktop display static text here too, NOT
 *  bound to the Security page's Biometric Login toggle. Starting Balance and Display
 *  Currency values stay literal, same as both references. */
@Composable
private fun AccountSettingsPage() {
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

/** Help & Support page — RESOURCES and CONTACT are decorative link rows (close-only, same
 *  as desktop/macOS: no actual navigation target exists in the simulated app). */
@Composable
private fun HelpPage(onClose: () -> Unit) {
    SectionLabel(tr(L10n.Key.Resources))
    Spacer(Modifier.height(8.dp))
    LinkRow(title = tr(L10n.Key.Faq), onClick = onClose)
    LinkRow(title = tr(L10n.Key.UserGuide), onClick = onClose)
    LinkRow(title = tr(L10n.Key.KeyboardShortcuts), onClick = onClose)
    Spacer(Modifier.height(10.dp))
    SectionLabel(tr(L10n.Key.Contact))
    Spacer(Modifier.height(8.dp))
    // "support@aptrade.app" is literal on every platform — an email address as data, not prose.
    LinkRow(title = tr(L10n.Key.EmailSupport), value = "support@aptrade.app", onClick = onClose)
    LinkRow(title = tr(L10n.Key.ReportAProblem), onClick = onClose)
}

// MARK: - About

@Composable
private fun AboutPage() {
    Spacer(Modifier.height(40.dp))
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // No wordmark asset ships in the Android app (desktop tints brand/AppWordmark.png);
        // the brand name in the accent primary is the closest native analog.
        Text(
            "APTrade",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        // Platform-specific tagline, NOT macOS's tr(.taglineShort) ("Premium investing for
        // macOS" — wrong platform name here) — the same intentional literal divergence the
        // desktop About page records.
        Text(
            "Premium native investing for Android.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Shared rows

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            "›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Uppercases [text] like the desktop/macOS `sectionLabel` — callers pass the plain
 *  [tr]-resolved string so translated section labels uppercase correctly too. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** An uppercase secondary label over a primary-color value (desktop `DetailField`). */
@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.2.sp,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A title, an optional trailing value, and a chevron. Desktop/macOS `linkRow` always just
 *  closes the panel — none of these rows have a real destination in the simulated app — so
 *  [onClick] is always the settings close callback at the call site. */
@Composable
private fun LinkRow(title: String, value: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 11.dp, horizontal = 4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            "›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Checkmark() {
    Text(
        "✓",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}
