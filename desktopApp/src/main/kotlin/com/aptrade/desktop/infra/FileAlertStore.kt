package com.aptrade.desktop.infra

import com.aptrade.shared.application.AlertStore
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PriceAlert
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** JSON-file price alerts (alerts.json). Follows FilePortfolioStore's whole-blob
 *  philosophy, not FileWatchlistStore's per-row skip: an alert list is a single
 *  user-owned blob, so one unmappable condition type means the *entire* file is
 *  untrusted and the whole load falls back to an empty list, rather than silently
 *  dropping just the unrecognized alert. Writes are atomic (temp file + ATOMIC_MOVE),
 *  so a crash mid-save can never leave a half-written alerts file. */
class FileAlertStore(private val file: Path) : AlertStore {

    @Serializable
    private data class MoneyDTO(val amount: String, val currency: String)

    @Serializable
    private data class ConditionDTO(
        val type: String,
        val threshold: MoneyDTO? = null,
        val magnitude: Double? = null,
    )

    @Serializable
    private data class AlertDTO(
        val id: String,
        val symbol: String,
        val condition: ConditionDTO,
        val createdAtEpochSeconds: Long,
        val isTriggered: Boolean,
    )

    @Serializable
    private data class AlertsFileDTO(val alerts: List<AlertDTO>)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun load(): List<PriceAlert> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val dto = json.decodeFromString<AlertsFileDTO>(file.readText())
            dto.alerts.map { alertDto ->
                val condition = when (alertDto.condition.type) {
                    "PriceAbove" -> {
                        val threshold = alertDto.condition.threshold
                            ?: return@withContext emptyList() // malformed row corrupts the whole file
                        AlertCondition.PriceAbove(
                            Money(BigDecimal.parseString(threshold.amount), threshold.currency),
                        )
                    }
                    "PriceBelow" -> {
                        val threshold = alertDto.condition.threshold
                            ?: return@withContext emptyList()
                        AlertCondition.PriceBelow(
                            Money(BigDecimal.parseString(threshold.amount), threshold.currency),
                        )
                    }
                    "PercentChange" -> {
                        val magnitude = alertDto.condition.magnitude
                            ?: return@withContext emptyList()
                        AlertCondition.PercentChange(magnitude)
                    }
                    else -> return@withContext emptyList() // unknown condition type: whole-blob fallback
                }
                PriceAlert(
                    id = alertDto.id,
                    symbol = alertDto.symbol,
                    condition = condition,
                    createdAtEpochSeconds = alertDto.createdAtEpochSeconds,
                    isTriggered = alertDto.isTriggered,
                )
            }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    override suspend fun save(alerts: List<PriceAlert>) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dto = AlertsFileDTO(
            alerts = alerts.map { alert ->
                val condition = when (val c = alert.condition) {
                    is AlertCondition.PriceAbove -> ConditionDTO(
                        type = "PriceAbove",
                        threshold = MoneyDTO(c.threshold.amountText, c.threshold.currencyCode),
                    )
                    is AlertCondition.PriceBelow -> ConditionDTO(
                        type = "PriceBelow",
                        threshold = MoneyDTO(c.threshold.amountText, c.threshold.currencyCode),
                    )
                    is AlertCondition.PercentChange -> ConditionDTO(
                        type = "PercentChange",
                        magnitude = c.magnitude,
                    )
                }
                AlertDTO(
                    id = alert.id,
                    symbol = alert.symbol,
                    condition = condition,
                    createdAtEpochSeconds = alert.createdAtEpochSeconds,
                    isTriggered = alert.isTriggered,
                )
            },
        )
        val text = json.encodeToString(AlertsFileDTO.serializer(), dto)
        val temp = Files.createTempFile(file.parent, "alerts", ".tmp")
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
