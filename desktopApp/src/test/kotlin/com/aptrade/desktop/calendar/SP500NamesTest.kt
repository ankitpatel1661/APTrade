package com.aptrade.desktop.calendar

import com.aptrade.shared.domain.SP500Symbols
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the desktop-only name map against the shared ticker set — both are generated from
 *  the same Wikipedia constituents snapshot and must be refreshed together; a drifted
 *  refresh would silently drop names from Calendar rows. */
class SP500NamesTest {

    @Test
    fun everyIndexSymbolHasAName() {
        val missing = SP500Symbols.set - SP500Names.keys
        assertTrue(missing.isEmpty(), "index symbols without a name: $missing")
        assertEquals(SP500Symbols.set.size, SP500Names.size, "name map carries extra symbols")
    }

    @Test
    fun namesAreNonBlankAndSpotChecksHold() {
        assertTrue(SP500Names.values.none { it.isBlank() })
        assertEquals("Apple Inc.", SP500Names["AAPL"])
        assertEquals("BNY Mellon", SP500Names["BNY"])
        assertEquals("Berkshire Hathaway", SP500Names["BRK.B"])
    }
}
