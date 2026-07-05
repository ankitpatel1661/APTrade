package com.aptrade.shared.domain

enum class Timeframe {
    OneDay, OneWeek, OneMonth, OneYear;

    val yahooRange: String
        get() = when (this) {
            OneDay -> "5d"
            OneWeek -> "1mo"
            OneMonth -> "3mo"
            OneYear -> "1y"
        }

    val yahooInterval: String
        get() = when (this) {
            OneDay -> "5m"
            OneWeek -> "60m"
            OneMonth -> "1d"
            OneYear -> "1d"
        }

    /** Rolling window to clamp raw fetched points to, anchored to the newest bar. */
    val windowDurationSeconds: Long
        get() = when (this) {
            OneDay -> 24L * 3600
            OneWeek -> 7L * 24 * 3600
            OneMonth -> 30L * 24 * 3600
            OneYear -> 365L * 24 * 3600
        }

    /** Bar spacing in seconds, matching [yahooInterval] exactly (5m / 60m / 1d / 1d). Used to
     *  size the indicator warm-up lookback pad in [com.aptrade.shared.application.FetchChartWindow]. */
    val intervalSeconds: Long
        get() = when (this) {
            OneDay -> 300L
            OneWeek -> 3600L
            OneMonth -> 86_400L
            OneYear -> 86_400L
        }
}
