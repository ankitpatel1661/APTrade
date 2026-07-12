package com.aptrade.shared.settings

/** The selectable brand accent — identity only (name + picker copy). The per-platform
 *  color ramps (deep/mid/light stops) live in each platform's design kit; commonMain
 *  must stay free of any UI framework so the Apple xcframework stays clean. Constant
 *  names are the persisted enum names in settings.json — never rename. */
enum class AccentTheme(val displayName: String, val tagline: String) {
    ChampagneGold(displayName = "Champagne Gold", tagline = "Default — gold on black"),
    RoseGold(displayName = "Rose Gold", tagline = "Warm copper blush"),
    Sapphire(displayName = "Sapphire", tagline = "Deep cobalt blue"),
    Amethyst(displayName = "Amethyst", tagline = "Regal violet"),
    Platinum(displayName = "Platinum", tagline = "Cool brushed silver"),
}
