package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.ScreenerSnapshotStore
import com.aptrade.shared.domain.ScreenerSnapshot
import com.aptrade.shared.domain.ScreenerSnapshotRow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * JSON-file screener snapshot (`screener-snapshot.json`) — the most recent full-universe
 * scan result. Transcribed from `Sources/APTradeInfrastructure/FileScreenerSnapshotStore.swift`
 * (the shipped M9.1 Swift/macOS reference), adapted to this codebase's file-store house
 * pattern (`FilePieStore`/`FilePortfolioStore`): an injected [Path], atomic
 * temp-file-then-rename writes, and a whole-blob philosophy — any decode failure
 * (malformed JSON or a schema Kotlin's serializer can't parse) discards the ENTIRE load and
 * returns `null`, mirroring Swift's `try? JSONDecoder().decode(...)` short-circuit. Critically,
 * a failed load NEVER writes anything: the file on disk is left byte-for-byte untouched.
 *
 * Implements the NON-suspend `ScreenerSnapshotStore` port (Task 3's
 * `Sources/APTradeApplication/ScreenerUseCases.swift` twin) — a deliberate divergence from
 * every other file store in this package (all `suspend`), so I/O here is synchronous
 * (`java.nio.file.Files`) rather than `withContext(Dispatchers.IO)`-wrapped.
 *
 * DTO FIELD NAMES match [ScreenerSnapshot]/[ScreenerSnapshotRow]'s own Kotlin property names
 * (including `scannedAtEpochSeconds`, a `Long`) rather than converging with Swift's
 * `scannedAt` `Date` key — cross-platform file exchange between the Swift and Kotlin stores
 * is explicitly NOT a requirement (same precedent `FilePieStore` documents for its own value
 * encoding). Every nullable metric on [ScreenerSnapshotRowDto] defaults to `null`, so a
 * legacy/hand-written payload missing a key (e.g. a field added after the file was written)
 * decodes leniently instead of failing the whole load.
 *
 * Lives in the `jvmCommon` intermediate source set (java.nio), shared by the JVM (desktop)
 * and Android targets — same portability shape as `FilePortfolioStore`/`FilePieStore`.
 */
class FileScreenerSnapshotStore(private val file: Path) : ScreenerSnapshotStore {

    @Serializable
    private data class ScreenerSnapshotRowDto(
        val symbol: String,
        val name: String,
        val close: Double,
        val dayChangePercent: Double? = null,
        val rsi14: Double? = null,
        val macd: Double? = null,
        val macdSignal: Double? = null,
        val macdHistogram: Double? = null,
        val sma50: Double? = null,
        val sma200: Double? = null,
        val ema20: Double? = null,
        val pctVsSma50: Double? = null,
        val pctVsSma200: Double? = null,
        val bollingerPercentB: Double? = null,
        val bollingerBandwidth: Double? = null,
        val week52High: Double? = null,
        val week52Low: Double? = null,
        val pctTo52wHigh: Double? = null,
        val pctTo52wLow: Double? = null,
        val relativeVolume: Double? = null,
        val macdCrossedUp: Boolean = false,
        val macdCrossedDown: Boolean = false,
        val goldenCross: Boolean = false,
        val deathCross: Boolean = false,
    )

    @Serializable
    private data class ScreenerSnapshotDto(
        val tradingDay: String,
        val scannedAtEpochSeconds: Long,
        val rows: List<ScreenerSnapshotRowDto> = emptyList(),
        val failedSymbols: List<String> = emptyList(),
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun load(): ScreenerSnapshot? {
        if (!file.exists()) return null
        return try {
            val dto = json.decodeFromString<ScreenerSnapshotDto>(file.readText())
            ScreenerSnapshot(
                tradingDay = dto.tradingDay,
                scannedAtEpochSeconds = dto.scannedAtEpochSeconds,
                rows = dto.rows.map { row ->
                    ScreenerSnapshotRow(
                        symbol = row.symbol,
                        name = row.name,
                        close = row.close,
                        dayChangePercent = row.dayChangePercent,
                        rsi14 = row.rsi14,
                        macd = row.macd,
                        macdSignal = row.macdSignal,
                        macdHistogram = row.macdHistogram,
                        sma50 = row.sma50,
                        sma200 = row.sma200,
                        ema20 = row.ema20,
                        pctVsSma50 = row.pctVsSma50,
                        pctVsSma200 = row.pctVsSma200,
                        bollingerPercentB = row.bollingerPercentB,
                        bollingerBandwidth = row.bollingerBandwidth,
                        week52High = row.week52High,
                        week52Low = row.week52Low,
                        pctTo52wHigh = row.pctTo52wHigh,
                        pctTo52wLow = row.pctTo52wLow,
                        relativeVolume = row.relativeVolume,
                        macdCrossedUp = row.macdCrossedUp,
                        macdCrossedDown = row.macdCrossedDown,
                        goldenCross = row.goldenCross,
                        deathCross = row.deathCross,
                    )
                },
                failedSymbols = dto.failedSymbols,
            )
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override fun save(snapshot: ScreenerSnapshot) {
        file.parent?.createDirectories()
        val dto = ScreenerSnapshotDto(
            tradingDay = snapshot.tradingDay,
            scannedAtEpochSeconds = snapshot.scannedAtEpochSeconds,
            rows = snapshot.rows.map { row ->
                ScreenerSnapshotRowDto(
                    symbol = row.symbol,
                    name = row.name,
                    close = row.close,
                    dayChangePercent = row.dayChangePercent,
                    rsi14 = row.rsi14,
                    macd = row.macd,
                    macdSignal = row.macdSignal,
                    macdHistogram = row.macdHistogram,
                    sma50 = row.sma50,
                    sma200 = row.sma200,
                    ema20 = row.ema20,
                    pctVsSma50 = row.pctVsSma50,
                    pctVsSma200 = row.pctVsSma200,
                    bollingerPercentB = row.bollingerPercentB,
                    bollingerBandwidth = row.bollingerBandwidth,
                    week52High = row.week52High,
                    week52Low = row.week52Low,
                    pctTo52wHigh = row.pctTo52wHigh,
                    pctTo52wLow = row.pctTo52wLow,
                    relativeVolume = row.relativeVolume,
                    macdCrossedUp = row.macdCrossedUp,
                    macdCrossedDown = row.macdCrossedDown,
                    goldenCross = row.goldenCross,
                    deathCross = row.deathCross,
                )
            },
            failedSymbols = snapshot.failedSymbols,
        )
        val text = json.encodeToString(ScreenerSnapshotDto.serializer(), dto)
        val temp = Files.createTempFile(file.parent, "screener-snapshot", ".tmp")
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it
        // here — this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
