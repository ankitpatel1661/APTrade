package com.aptrade.desktop

import com.aptrade.shared.domain.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun sharedCoreIsOnTheClasspath() {
        // NOTE: value chosen with a non-zero last cent — Money.formatted drops
        // trailing zeros (known M1 debt), so "$1.50" would fail here.
        assertEquals("$1.25", Money.usd("1.25").formatted)
    }
}
