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

/** The app's persisted preferences, framework-free. One value today (the brand accent);
 *  the DTO carries defaults so future fields can be added without breaking old files. */
data class AppSettings(
    val accent: AccentTheme = AccentTheme.ChampagneGold,
)

/** JSON-file settings, a single blob `{"accent":"ChampagneGold"}` (macOS single-blob parity).
 *  Mirrors FilePortfolioStore's philosophy: writes are atomic (temp file + ATOMIC_MOVE, so a
 *  crash mid-save can never leave a half-written file); a missing, corrupt, or unknown-enum
 *  file loads the whole-blob defaults rather than a partial merge. */
class FileSettingsStore(private val file: Path) {

    @Serializable
    private data class SettingsDTO(
        val accent: String = AccentTheme.ChampagneGold.name,
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
            AppSettings(accent = accent)
        } catch (e: SerializationException) {
            AppSettings()
        } catch (e: IllegalArgumentException) {
            AppSettings()
        }
    }

    suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dto = SettingsDTO(accent = settings.accent.name)
        val text = json.encodeToString(SettingsDTO.serializer(), dto)
        val temp = Files.createTempFile(file.parent, "settings", ".tmp")
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
