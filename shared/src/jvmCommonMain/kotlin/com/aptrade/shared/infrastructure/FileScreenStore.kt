package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.ScreenStore
import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.ScreenComparison
import com.aptrade.shared.domain.ScreenCondition
import com.aptrade.shared.domain.ScreenerMetric
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * JSON-file custom screens (`screens.json`) — the user's saved [CustomScreen]s. Transcribed
 * from `Sources/APTradeInfrastructure/UserDefaultsScreenStore.swift` (the shipped M9.1
 * Swift/macOS reference), adapted to this codebase's FILE-backed house pattern
 * (`FilePieStore`/`FilePortfolioStore`) rather than `UserDefaults` — an injected [Path],
 * atomic temp-file-then-rename writes, and a whole-blob philosophy: any decode failure
 * (malformed JSON, or a [ScreenerMetric]/[ScreenComparison] raw value Kotlin can't map)
 * discards the ENTIRE load and returns an empty list, never a partial/per-screen skip,
 * mirroring Swift's `try? JSONDecoder().decode([CustomScreen].self, from: data)` short-
 * circuit. Critically, a failed load NEVER writes anything: the file on disk is left
 * byte-for-byte untouched.
 *
 * Implements the NON-suspend `ScreenStore` port (Task 3's twin) — a deliberate divergence
 * from every other file store in this package (all `suspend`), so I/O here is synchronous
 * (`java.nio.file.Files`) rather than `withContext(Dispatchers.IO)`-wrapped.
 *
 * ENUM WIRE VALUES are mapped via EXPLICIT string tables ([ScreenerMetric.toRaw]/
 * [metricFromRaw], [ScreenComparison.toRaw]/[comparisonFromRaw]), NEVER Kotlin's `.name` —
 * required to stay wire-compatible with the Swift `RawRepresentable` strings
 * (`Sources/APTradeDomain/Screener.swift`). This matters most for [ScreenComparison]: its
 * Kotlin case names (`Above`/`Below`) are PascalCase while the Swift/wire values are
 * lowerCamelCase (`above`/`below`) — `.name` would silently write the wrong casing.
 * [ScreenerMetric]'s Kotlin case names already happen to equal their Swift raw values
 * (`rsi14`, `pctVsSma50`, ...), but the explicit table is kept anyway so a future case
 * rename can never silently drift the wire format.
 *
 * Lives in the `jvmCommon` intermediate source set (java.nio), shared by the JVM (desktop)
 * and Android targets — same portability shape as `FilePortfolioStore`/`FilePieStore`.
 */
class FileScreenStore(private val file: Path) : ScreenStore {

    @Serializable
    private data class ScreenConditionDto(
        val metric: String,
        val comparison: String,
        val threshold: Double,
    )

    @Serializable
    private data class CustomScreenDto(
        val id: String,
        val name: String,
        val conditions: List<ScreenConditionDto> = emptyList(),
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val dtoListSerializer = ListSerializer(CustomScreenDto.serializer())

    override fun load(): List<CustomScreen> {
        if (!file.exists()) return emptyList()
        return try {
            val dtos = json.decodeFromString(dtoListSerializer, file.readText())
            dtos.map { dto ->
                val conditions = dto.conditions.map { conditionDto ->
                    val metric = metricFromRaw(conditionDto.metric)
                        ?: return emptyList() // unrecognized metric: whole file untrusted
                    val comparison = comparisonFromRaw(conditionDto.comparison)
                        ?: return emptyList() // unrecognized comparison: whole file untrusted
                    ScreenCondition(metric = metric, comparison = comparison, threshold = conditionDto.threshold)
                }
                CustomScreen(id = dto.id, name = dto.name, conditions = conditions)
            }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    override fun save(screens: List<CustomScreen>) {
        file.parent?.createDirectories()
        val dtos = screens.map { screen ->
            CustomScreenDto(
                id = screen.id,
                name = screen.name,
                conditions = screen.conditions.map { condition ->
                    ScreenConditionDto(
                        metric = condition.metric.toRaw(),
                        comparison = condition.comparison.toRaw(),
                        threshold = condition.threshold,
                    )
                },
            )
        }
        val text = json.encodeToString(dtoListSerializer, dtos)
        val temp = Files.createTempFile(file.parent, "screens", ".tmp")
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it
        // here — this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun ScreenerMetric.toRaw(): String = when (this) {
        ScreenerMetric.price -> "price"
        ScreenerMetric.dayChangePercent -> "dayChangePercent"
        ScreenerMetric.rsi14 -> "rsi14"
        ScreenerMetric.bollingerPercentB -> "bollingerPercentB"
        ScreenerMetric.bollingerBandwidth -> "bollingerBandwidth"
        ScreenerMetric.pctTo52wHigh -> "pctTo52wHigh"
        ScreenerMetric.pctTo52wLow -> "pctTo52wLow"
        ScreenerMetric.relativeVolume -> "relativeVolume"
        ScreenerMetric.pctVsSma50 -> "pctVsSma50"
        ScreenerMetric.pctVsSma200 -> "pctVsSma200"
    }

    private fun metricFromRaw(raw: String): ScreenerMetric? = when (raw) {
        "price" -> ScreenerMetric.price
        "dayChangePercent" -> ScreenerMetric.dayChangePercent
        "rsi14" -> ScreenerMetric.rsi14
        "bollingerPercentB" -> ScreenerMetric.bollingerPercentB
        "bollingerBandwidth" -> ScreenerMetric.bollingerBandwidth
        "pctTo52wHigh" -> ScreenerMetric.pctTo52wHigh
        "pctTo52wLow" -> ScreenerMetric.pctTo52wLow
        "relativeVolume" -> ScreenerMetric.relativeVolume
        "pctVsSma50" -> ScreenerMetric.pctVsSma50
        "pctVsSma200" -> ScreenerMetric.pctVsSma200
        else -> null
    }

    private fun ScreenComparison.toRaw(): String = when (this) {
        ScreenComparison.Above -> "above"
        ScreenComparison.Below -> "below"
    }

    private fun comparisonFromRaw(raw: String): ScreenComparison? = when (raw) {
        "above" -> ScreenComparison.Above
        "below" -> ScreenComparison.Below
        else -> null
    }
}
