package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.SchedulerState
import com.aptrade.shared.application.SchedulerStateStore
import com.aptrade.shared.domain.MarketStatus
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

/**
 * JSON-file scheduler markers (schedulerState.json) — the last-fired open/close status,
 * digest day, earnings-check day, and contribution-check day, so `MarketActivityPlanner`
 * never re-fires the same event twice across relaunches. Same atomic/whole-blob idiom as
 * `FileSettingsStore`: a
 * missing, corrupt, or unmappable-enum file loads the whole-blob default rather than a
 * partial merge (a stale marker is harmless — it only risks one duplicate notification, so
 * falling back to "no markers yet" is the safe default).
 *
 * PROMOTED to `shared/jvmCommonMain` (Task 8) from what had been a desktop-only copy at
 * `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/FileSchedulerStateStore.kt`, so
 * Android's `AppGraph` can share the exact same file format/behavior — mirrors how
 * `FileWatchlistStore`/`FileAlertStore`/`FileBookmarkStore`/`FileSettingsStore` were
 * already promoted here for the same reason. Desktop's file is now the same one-line
 * `typealias` re-export those sibling stores use, so exactly ONE implementation exists.
 */
class FileSchedulerStateStore(private val file: Path) : SchedulerStateStore {

    @Serializable
    private data class SchedulerStateDTO(
        val lastStatus: String? = null,
        val lastDigestDay: String? = null,
        val lastEarningsDay: String? = null,
        val lastContributionDay: String? = null,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun load(): SchedulerState = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext SchedulerState()
        try {
            val dto = json.decodeFromString<SchedulerStateDTO>(file.readText())
            val status = if (dto.lastStatus == null) {
                null
            } else {
                MarketStatus.entries.firstOrNull { it.name == dto.lastStatus }
                    ?: return@withContext SchedulerState() // unknown status name: whole-blob default
            }
            SchedulerState(
                lastStatus = status,
                lastDigestDay = dto.lastDigestDay,
                lastEarningsDay = dto.lastEarningsDay,
                lastContributionDay = dto.lastContributionDay,
            )
        } catch (e: SerializationException) {
            SchedulerState()
        } catch (e: IllegalArgumentException) {
            SchedulerState()
        }
    }

    override suspend fun save(state: SchedulerState) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dto = SchedulerStateDTO(
            lastStatus = state.lastStatus?.name,
            lastDigestDay = state.lastDigestDay,
            lastEarningsDay = state.lastEarningsDay,
            lastContributionDay = state.lastContributionDay,
        )
        val text = json.encodeToString(SchedulerStateDTO.serializer(), dto)
        val temp = Files.createTempFile(file.parent, "schedulerState", ".tmp")
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it
        // here — this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
