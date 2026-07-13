package com.aptrade.shared.infrastructure

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinnhubKeyConfigTest {

    @Test
    fun validPrimaryConfigFileReturnsTrimmedKey() {
        val configDir = createTempDirectory("aptrade-test")
        configDir.resolve("config.json").writeText("""{"finnhubAPIKey": "  test-key-123  "}""")
        val home = createTempDirectory("aptrade-test-home")

        val key = FinnhubKeyConfig(configDir = configDir, userHome = home.toString()).finnhubApiKey()

        assertEquals("test-key-123", key)
    }

    @Test
    fun blankKeyInPrimaryConfigReturnsNull() {
        val configDir = createTempDirectory("aptrade-test")
        configDir.resolve("config.json").writeText("""{"finnhubAPIKey": "   "}""")
        val home = createTempDirectory("aptrade-test-home")

        val key = FinnhubKeyConfig(configDir = configDir, userHome = home.toString()).finnhubApiKey()

        assertNull(key)
    }

    @Test
    fun missingPrimaryAndFallbackFilesReturnsNull() {
        val configDir = createTempDirectory("aptrade-test")
        val home = createTempDirectory("aptrade-test-home")

        val key = FinnhubKeyConfig(configDir = configDir, userHome = home.toString()).finnhubApiKey()

        assertNull(key)
    }

    @Test
    fun corruptPrimaryConfigFileReturnsNull() {
        val configDir = createTempDirectory("aptrade-test")
        configDir.resolve("config.json").writeText("{not json[")
        val home = createTempDirectory("aptrade-test-home")

        val key = FinnhubKeyConfig(configDir = configDir, userHome = home.toString()).finnhubApiKey()

        assertNull(key)
    }

    @Test
    fun fallbackPathIsConsultedWhenPrimaryAbsent() {
        // Primary config dir is an empty temp dir (no config.json at all).
        val configDir = createTempDirectory("aptrade-test")
        // Fallback: {userHome}/.config/aptrade/config.json — planted via the injectable home seam.
        val home = createTempDirectory("aptrade-test-home")
        val fallbackDir = home.resolve(".config").resolve("aptrade").createDirectories()
        fallbackDir.resolve("config.json").writeText("""{"finnhubAPIKey": "fallback-key-456"}""")

        val key = FinnhubKeyConfig(configDir = configDir, userHome = home.toString()).finnhubApiKey()

        assertEquals("fallback-key-456", key)
    }

    @Test
    fun primaryTakesPrecedenceOverFallbackWhenBothPresent() {
        val configDir = createTempDirectory("aptrade-test")
        configDir.resolve("config.json").writeText("""{"finnhubAPIKey": "primary-key"}""")
        val home = createTempDirectory("aptrade-test-home")
        val fallbackDir = home.resolve(".config").resolve("aptrade").createDirectories()
        fallbackDir.resolve("config.json").writeText("""{"finnhubAPIKey": "fallback-key"}""")

        val key = FinnhubKeyConfig(configDir = configDir, userHome = home.toString()).finnhubApiKey()

        assertEquals("primary-key", key)
    }

    @Test
    fun unknownKeysInConfigAreIgnored() {
        val configDir = createTempDirectory("aptrade-test")
        configDir.resolve("config.json").writeText(
            """{"finnhubAPIKey": "test-key", "someOtherField": "ignored"}""",
        )
        val home = createTempDirectory("aptrade-test-home")

        val key = FinnhubKeyConfig(configDir = configDir, userHome = home.toString()).finnhubApiKey()

        assertEquals("test-key", key)
    }

    @Test
    fun absentKeyFieldInConfigReturnsNull() {
        val configDir = createTempDirectory("aptrade-test")
        configDir.resolve("config.json").writeText("""{}""")
        val home = createTempDirectory("aptrade-test-home")

        val key = FinnhubKeyConfig(configDir = configDir, userHome = home.toString()).finnhubApiKey()

        assertNull(key)
    }

    // --- saveFinnhubApiKey (the in-app settings-field write path) ---------------------

    @Test
    fun savedKeyRoundTripsThroughTheReadPath() {
        val configDir = createTempDirectory("aptrade-test")
        val home = createTempDirectory("aptrade-test-home")
        val config = FinnhubKeyConfig(configDir = configDir, userHome = home.toString())

        config.saveFinnhubApiKey("  round-trip-key  ")

        assertEquals("round-trip-key", config.finnhubApiKey())
    }

    @Test
    fun saveCreatesTheConfigDirectoryWhenMissing() {
        val configDir = createTempDirectory("aptrade-test").resolve("nested").resolve("aptrade")
        val home = createTempDirectory("aptrade-test-home")
        val config = FinnhubKeyConfig(configDir = configDir, userHome = home.toString())

        config.saveFinnhubApiKey("fresh-key")

        assertEquals("fresh-key", config.finnhubApiKey())
    }

    @Test
    fun savePreservesUnknownFieldsAlreadyInTheFile() {
        val configDir = createTempDirectory("aptrade-test")
        configDir.resolve("config.json").writeText(
            """{"finnhubAPIKey": "old-key", "someOtherField": "kept"}""",
        )
        val home = createTempDirectory("aptrade-test-home")
        val config = FinnhubKeyConfig(configDir = configDir, userHome = home.toString())

        config.saveFinnhubApiKey("new-key")

        assertEquals("new-key", config.finnhubApiKey())
        val text = configDir.resolve("config.json").toFile().readText()
        assertEquals(true, text.contains("someOtherField"), "unknown field dropped: $text")
        assertEquals(true, text.contains("kept"), "unknown field's value dropped: $text")
    }

    @Test
    fun savingBlankRemovesTheKey() {
        val configDir = createTempDirectory("aptrade-test")
        configDir.resolve("config.json").writeText("""{"finnhubAPIKey": "old-key"}""")
        val home = createTempDirectory("aptrade-test-home")
        val config = FinnhubKeyConfig(configDir = configDir, userHome = home.toString())

        config.saveFinnhubApiKey("   ")

        assertNull(config.finnhubApiKey())
    }
}
