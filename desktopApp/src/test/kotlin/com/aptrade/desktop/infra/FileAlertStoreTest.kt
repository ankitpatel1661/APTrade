package com.aptrade.desktop.infra

import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PriceAlert
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileAlertStoreTest {

    private fun tempFile() = createTempDirectory("aptrade-alerts-test").resolve("alerts.json")

    private fun sampleAlert(id: String = "alert-1") = PriceAlert(
        id = id,
        symbol = "AAPL",
        condition = AlertCondition.PriceAbove(Money(BigDecimal.parseString("200.00"), "USD")),
        createdAtEpochSeconds = 1_700_000_000L,
    )

    @Test
    fun `missing file loads empty list`() = runTest {
        val store = FileAlertStore(tempFile())
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun `round-trips a list of alerts`() = runTest {
        val file = tempFile()
        val store = FileAlertStore(file)
        val alerts = listOf(
            sampleAlert("alert-1"),
            PriceAlert(
                id = "alert-2",
                symbol = "TSLA",
                condition = AlertCondition.PercentChange(5.0),
                createdAtEpochSeconds = 1_700_000_100L,
                isTriggered = true,
            ),
        )
        store.save(alerts)
        assertTrue(file.exists())
        assertEquals(alerts, store.load())
    }

    @Test
    fun `round-trips PriceBelow condition`() = runTest {
        val file = tempFile()
        val store = FileAlertStore(file)
        val alerts = listOf(
            PriceAlert(
                id = "alert-3",
                symbol = "SPY",
                condition = AlertCondition.PriceBelow(Money(BigDecimal.parseString("400.50"), "USD")),
                createdAtEpochSeconds = 1_700_000_200L,
            ),
        )
        store.save(alerts)
        assertEquals(alerts, store.load())
    }

    @Test
    fun `corrupt json loads empty list, whole blob, not a partial row`() = runTest {
        val file = tempFile()
        file.writeText("{ not valid json at all ")
        assertEquals(emptyList(), FileAlertStore(file).load())
    }

    @Test
    fun `one unknown condition type corrupts the entire load, not just that row`() = runTest {
        // Mirrors FilePortfolioStore's anti-row-skip rationale: an alert list is a single
        // user-owned blob, so an unmappable entry means the file is untrusted as a whole,
        // rather than silently dropping just the unrecognized alert.
        val file = tempFile()
        file.writeText(
            """
            {"alerts":[
              {"id":"alert-1","symbol":"AAPL","conditionType":"PriceAbove","threshold":"200.00","magnitude":null,"createdAtEpochSeconds":1700000000,"isTriggered":false},
              {"id":"alert-2","symbol":"TSLA","conditionType":"VolumeSpike","threshold":null,"magnitude":null,"createdAtEpochSeconds":1700000100,"isTriggered":false}
            ]}
            """.trimIndent(),
        )
        assertEquals(emptyList(), FileAlertStore(file).load())
    }

    @Test
    fun `missing file then save then load works after directories are created`() = runTest {
        val dir = createTempDirectory("aptrade-alerts-nested")
        val file = dir.resolve("nested/dir/alerts.json")
        val store = FileAlertStore(file)
        store.save(listOf(sampleAlert()))
        assertTrue(file.exists())
        assertEquals(listOf(sampleAlert()), store.load())
    }

    @Test
    fun `empty list round-trips to empty list`() = runTest {
        val file = tempFile()
        val store = FileAlertStore(file)
        store.save(emptyList())
        assertEquals(emptyList(), store.load())
    }
}
