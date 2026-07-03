package com.aptrade.desktop.infra

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigDirTest {
    @Test
    fun windowsUsesAppData() {
        val dir = resolveConfigDir(
            osName = "Windows 11",
            env = { if (it == "APPDATA") "C:\\Users\\ap\\AppData\\Roaming" else null },
            userHome = "C:\\Users\\ap",
        )
        assertEquals(Path("C:\\Users\\ap\\AppData\\Roaming", "APTrade"), dir)
    }

    @Test
    fun windowsFallsBackToHomeWhenAppDataMissing() {
        val dir = resolveConfigDir(osName = "Windows 11", env = { null }, userHome = "C:\\Users\\ap")
        assertEquals(Path("C:\\Users\\ap", "APTrade"), dir)
    }

    @Test
    fun macUsesApplicationSupport() {
        val dir = resolveConfigDir(osName = "Mac OS X", env = { null }, userHome = "/Users/ap")
        assertEquals(Path("/Users/ap", "Library", "Application Support", "APTrade"), dir)
    }

    @Test
    fun linuxUsesDotConfig() {
        val dir = resolveConfigDir(osName = "Linux", env = { null }, userHome = "/home/ap")
        assertEquals(Path("/home/ap", ".config", "aptrade"), dir)
    }
}
