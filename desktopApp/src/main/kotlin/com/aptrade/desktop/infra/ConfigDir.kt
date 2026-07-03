package com.aptrade.desktop.infra

import java.nio.file.Path
import kotlin.io.path.Path

/** Platform config directory: %APPDATA%\APTrade on Windows,
 *  ~/Library/Application Support/APTrade on macOS, ~/.config/aptrade elsewhere.
 *  Parameters exist for tests; production callers use the defaults. */
fun resolveConfigDir(
    osName: String = System.getProperty("os.name") ?: "",
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
): Path {
    val os = osName.lowercase()
    return when {
        os.contains("win") -> {
            val appData = env("APPDATA")
            if (appData != null) Path(appData, "APTrade") else Path(userHome, "APTrade")
        }
        os.contains("mac") -> Path(userHome, "Library", "Application Support", "APTrade")
        else -> Path(userHome, ".config", "aptrade")
    }
}
