package com.aptrade.desktop.infra

import com.aptrade.desktop.designkit.AccentTheme
import com.aptrade.desktop.l10n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** The app's persisted preferences, framework-free. The DTO carries defaults so future
 *  fields can be added without breaking old files.
 *
 *  Notification flags (increment 6d.1) mirror macOS's `AppSettings` defaults exactly:
 *  price alerts and order fills on by default, market open/close off (noisy for most
 *  users), the daily digest on, and email notifications off. [emailNotifications] is
 *  persisted-but-unwired by design — same as macOS, where no email delivery pipeline
 *  exists yet; the toggle exists for settings-screen parity only.
 *
 *  [isDarkMode] (increment 6d.2) defaults to true — dark is the shipped identity, and an
 *  old settings file written before this field existed must still load dark rather than
 *  flash into an unrequested light mode.
 *
 *  Security/privacy fields (increment 6d.2 Task 3) mirror macOS `AppSettings` exactly:
 *  [biometricLogin], [requireAuthOnLaunch], and [confirmTrades] default to true (matching
 *  the Swift `AppSettings`'s security posture), [analyticsSharing] defaults to false. HONEST
 *  PARITY (recorded): only [confirmTrades] is functional on desktop — it gates the
 *  in-dialog trade confirmation layer (see `TradeConfirm.kt` / `TradeDialog.kt`).
 *  [biometricLogin], [requireAuthOnLaunch], and [analyticsSharing] persist but drive nothing
 *  yet, same as macOS's SecurityPage rows for those three toggles are simulated-app-only.
 *
 *  [language] (increment 6e Task 5) defaults to [AppLanguage.English] — the shipped default;
 *  an old settings file written before this field existed must still load English rather than
 *  an unrequested language. */
data class AppSettings(
    val accent: AccentTheme = AccentTheme.ChampagneGold,
    val priceAlerts: Boolean = true,
    val orderFills: Boolean = true,
    val marketOpenClose: Boolean = false,
    val newsDigest: Boolean = true,
    val emailNotifications: Boolean = false,
    val isDarkMode: Boolean = true,
    val biometricLogin: Boolean = true,
    val requireAuthOnLaunch: Boolean = true,
    val confirmTrades: Boolean = true,
    val analyticsSharing: Boolean = false,
    val language: AppLanguage = AppLanguage.English,
)

/** JSON-file settings, a single blob (macOS single-blob parity).
 *  Mirrors FilePortfolioStore's philosophy: writes are atomic (temp file + ATOMIC_MOVE, so a
 *  crash mid-save can never leave a half-written file); a missing, corrupt, or unknown-enum
 *  file loads the whole-blob defaults rather than a partial merge. Individual notification
 *  flags decode leniently (each key defaults independently), so a pre-6d.1 file containing
 *  only `{"accent":...}` still loads successfully with the new flags at their defaults —
 *  this is the one place field-level lenient decode is intentional, since the boolean flags
 *  are independent user preferences rather than a structurally-interdependent blob (unlike
 *  the accent enum, which still triggers whole-blob fallback on an unknown name). */
class FileSettingsStore(private val file: Path) {

    @Serializable
    private data class SettingsDTO(
        val accent: String = AccentTheme.ChampagneGold.name,
        val priceAlerts: Boolean = true,
        val orderFills: Boolean = true,
        val marketOpenClose: Boolean = false,
        val newsDigest: Boolean = true,
        val emailNotifications: Boolean = false,
        val isDarkMode: Boolean = true,
        val biometricLogin: Boolean = true,
        val requireAuthOnLaunch: Boolean = true,
        val confirmTrades: Boolean = true,
        val analyticsSharing: Boolean = false,
        val language: String = AppLanguage.English.code,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext AppSettings()
        try {
            val dto = json.decodeFromString<SettingsDTO>(file.readText())
            // Unknown accent name → whole-blob default (matching FilePortfolioStore: a value we
            // can't map means the file is untrusted, so fall back rather than partially apply).
            val accent = AccentTheme.entries.firstOrNull { it.name == dto.accent }
                ?: return@withContext AppSettings()
            AppSettings(
                accent = accent,
                priceAlerts = dto.priceAlerts,
                orderFills = dto.orderFills,
                marketOpenClose = dto.marketOpenClose,
                newsDigest = dto.newsDigest,
                emailNotifications = dto.emailNotifications,
                isDarkMode = dto.isDarkMode,
                biometricLogin = dto.biometricLogin,
                requireAuthOnLaunch = dto.requireAuthOnLaunch,
                confirmTrades = dto.confirmTrades,
                analyticsSharing = dto.analyticsSharing,
                // Unlike accent, an unmappable language code doesn't fail the whole blob — it's
                // an independent user preference (same family as the notification/security
                // flags), so a bad or missing code just falls back to English on its own.
                language = AppLanguage.entries.firstOrNull { it.code == dto.language }
                    ?: AppLanguage.English,
            )
        } catch (e: SerializationException) {
            AppSettings()
        } catch (e: IllegalArgumentException) {
            AppSettings()
        }
    }

    suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dto = SettingsDTO(
            accent = settings.accent.name,
            priceAlerts = settings.priceAlerts,
            orderFills = settings.orderFills,
            marketOpenClose = settings.marketOpenClose,
            newsDigest = settings.newsDigest,
            emailNotifications = settings.emailNotifications,
            isDarkMode = settings.isDarkMode,
            biometricLogin = settings.biometricLogin,
            requireAuthOnLaunch = settings.requireAuthOnLaunch,
            confirmTrades = settings.confirmTrades,
            analyticsSharing = settings.analyticsSharing,
            language = settings.language.code,
        )
        val text = json.encodeToString(SettingsDTO.serializer(), dto)
        val temp = Files.createTempFile(file.parent, "settings", ".tmp")
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
