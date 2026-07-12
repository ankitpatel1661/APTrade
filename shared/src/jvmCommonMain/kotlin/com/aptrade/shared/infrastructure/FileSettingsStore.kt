package com.aptrade.shared.infrastructure

import com.aptrade.shared.l10n.AppLanguage
import com.aptrade.shared.settings.AccentTheme
import com.aptrade.shared.settings.AppSettings
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
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it
        // here — this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
