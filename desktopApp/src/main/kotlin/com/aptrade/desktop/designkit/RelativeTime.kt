package com.aptrade.desktop.designkit

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val absoluteDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneOffset.UTC)

/**
 * Formats [epochSeconds] relative to [nowEpochSeconds] the way a news feed timestamp reads at a
 * glance: "just now" under a minute, then minutes/hours/days (singular at exactly 1), falling back
 * to an absolute "MMM d" (UTC, `Locale.US`) once the gap reaches 7 days.
 *
 * RECORDED DIVERGENCE from macOS: the Swift original leans on `RelativeDateTimeFormatter`
 * (`.named` style), which produces locale-aware phrases like "yesterday" and can localize past
 * the 7-day mark (e.g. "last Tuesday", "3 weeks ago") before ever falling back to a calendar date.
 * This desktop port intentionally narrows that to a fixed, English-only ladder — minutes, hours,
 * days, then an absolute date — because there is no first-party equivalent to
 * `RelativeDateTimeFormatter` in the JDK, and approximating its locale/style matrix was judged
 * not worth the complexity for a news timestamp label. Revisit if desktop localization becomes
 * a real requirement.
 *
 * Both parameters are explicit (no hidden `Clock.System` call) so the function stays pure and
 * trivially testable; callers on the composable side supply `Instant.now().epochSecond`.
 */
fun relativeTimeText(epochSeconds: Long, nowEpochSeconds: Long): String {
    val deltaSeconds = (nowEpochSeconds - epochSeconds).coerceAtLeast(0)

    val minutes = deltaSeconds / 60
    val hours = deltaSeconds / 3600
    val days = deltaSeconds / 86400

    return when {
        deltaSeconds < 60 -> "just now"
        minutes < 60 -> "$minutes ${unit("minute", minutes)} ago"
        hours < 24 -> "$hours ${unit("hour", hours)} ago"
        days < 7 -> "$days ${unit("day", days)} ago"
        else -> absoluteDateFormatter.format(Instant.ofEpochSecond(epochSeconds))
    }
}

private fun unit(word: String, count: Long): String = if (count == 1L) word else "${word}s"
