package com.aptrade.shared.infrastructure

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Reads the user's Finnhub API key from a small local config file, never from source.
 *
 *  Lookup order: [configDir]/config.json first (the app's own config directory, same
 *  one `resolveConfigDir()` resolves to in production), then a fixed macOS-style
 *  fallback path `{userHome}/.config/aptrade/config.json` (spec decision 3) — so a key
 *  dropped in the conventional dotfile location is honored even when the primary,
 *  platform-specific config dir doesn't have one.
 *
 *  `configDir` and `userHome` are injectable seams purely for testing: production
 *  callers use the defaults and tests never touch the real filesystem outside a temp
 *  directory. The key is trimmed; blank, absent, missing-file, or corrupt-JSON all
 *  resolve to `null`. The key value itself is never logged.
 */
class FinnhubKeyConfig(
    private val configDir: Path = resolveConfigDir(),
    private val userHome: String = System.getProperty("user.home") ?: ".",
) {
    @Serializable
    private data class ConfigDTO(val finnhubAPIKey: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun finnhubApiKey(): String? =
        keyFrom(configDir.resolve("config.json"))
            ?: keyFrom(Path(userHome, ".config", "aptrade", "config.json"))

    private fun keyFrom(file: Path): String? {
        if (!file.exists()) return null
        return try {
            val dto = json.decodeFromString(ConfigDTO.serializer(), file.readText())
            dto.finnhubAPIKey?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
