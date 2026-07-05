package com.aptrade.desktop.infra

import com.aptrade.desktop.designkit.AccentTheme
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
 *  flash into an unrequested light mode. */
data class AppSettings(
    val accent: AccentTheme = AccentTheme.ChampagneGold,
    val priceAlerts: Boolean = true,
    val orderFills: Boolean = true,
    val marketOpenClose: Boolean = false,
    val newsDigest: Boolean = true,
    val emailNotifications: Boolean = false,
    val isDarkMode: Boolean = true,
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
        )
        val text = json.encodeToString(SettingsDTO.serializer(), dto)
        val temp = Files.createTempFile(file.parent, "settings", ".tmp")
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
