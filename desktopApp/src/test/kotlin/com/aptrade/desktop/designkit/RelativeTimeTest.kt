package com.aptrade.desktop.designkit

import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeTest {
    private val now = 1_700_000_000L // fixed reference instant (2023-11-14T22:13:20Z)

    @Test fun justNowAtZeroSeconds() =
        assertEquals("just now", relativeTimeText(now, now))

    @Test fun justNowAt59Seconds() =
        assertEquals("just now", relativeTimeText(now - 59, now))

    @Test fun oneMinuteAgoAt60Seconds() =
        assertEquals("1 minute ago", relativeTimeText(now - 60, now))

    @Test fun pluralMinutesAgo() =
        assertEquals("5 minutes ago", relativeTimeText(now - 5 * 60, now))

    @Test fun boundaryAt59Minutes() =
        assertEquals("59 minutes ago", relativeTimeText(now - 59 * 60, now))

    @Test fun oneHourAgoAt60Minutes() =
        assertEquals("1 hour ago", relativeTimeText(now - 60 * 60, now))

    @Test fun pluralHoursAgo() =
        assertEquals("5 hours ago", relativeTimeText(now - 5 * 3600, now))

    @Test fun boundaryAt23Hours() =
        assertEquals("23 hours ago", relativeTimeText(now - 23 * 3600, now))

    @Test fun oneDayAgoAt24Hours() =
        assertEquals("1 day ago", relativeTimeText(now - 24 * 3600, now))

    @Test fun pluralDaysAgo() =
        assertEquals("3 days ago", relativeTimeText(now - 3 * 86400, now))

    @Test fun boundaryAt6Days() =
        assertEquals("6 days ago", relativeTimeText(now - 6 * 86400, now))

    @Test fun absoluteFallbackAt7Days() =
        assertEquals("Nov 7", relativeTimeText(now - 7 * 86400, now))

    @Test fun absoluteFallbackFarPast() =
        // A fixed, distant past instant — verifies the absolute-date branch away from any boundary.
        assertEquals("Oct 10", relativeTimeText(1_665_440_000L, now))

    @Test fun futureTimestampClampsToJustNow() =
        assertEquals("just now", relativeTimeText(now + 120, now))
}
