package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M11.2 Task 14. Carry-notes §2.7: the Swift wave found TWO hardcoded balances that neither the
 * spec nor the plan anticipated — a defaulted `seedCash` closure and a no-op test double returning
 * a fabricated $100,000. Both hiding places are structural, not textual, so a grep alone will not
 * find them. This pins the ONE value every legitimate default must agree on, so a second literal
 * that drifts shows up as a failing assertion rather than as a user's wrong opening balance.
 */
class StartingBalanceLiteralTest {

    @Test
    fun theOnePermittedLiteralIsSharedByBothSanctionedDefaults() {
        assertEquals(Portfolio.DEFAULT_STARTING_CASH, Portfolio.starting().cash)
        assertEquals(
            Portfolio.DEFAULT_STARTING_CASH,
            com.aptrade.shared.settings.AppSettings().defaultStartingCash,
        )
    }
}
