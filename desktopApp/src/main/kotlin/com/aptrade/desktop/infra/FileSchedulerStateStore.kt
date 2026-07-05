package com.aptrade.desktop.infra

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

/** JSON-file scheduler markers (schedulerState.json) — the last-fired open/close
 *  status and digest day, so MarketActivityPlanner never re-fires the same event twice
 *  across relaunches. Same atomic/whole-blob idiom as FileSettingsStore: a missing,
 *  corrupt, or unmappable-enum file loads the whole-blob default rather than a partial
 *  merge (a stale marker is harmless — it only risks one duplicate notification, so
 *  falling back to "no markers yet" is the safe default, matching FileSettingsStore's
 *  unknown-enum-name handling). */
class FileSchedulerStateStore(private val file: Path) : SchedulerStateStore {

    @Serializable
    private data class SchedulerStateDTO(
        val lastStatus: String? = null,
        val lastDigestDay: String? = null,
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
            SchedulerState(lastStatus = status, lastDigestDay = dto.lastDigestDay)
        } catch (e: SerializationException) {
            SchedulerState()
        } catch (e: IllegalArgumentException) {
            SchedulerState()
        }
    }

    override suspend fun save(state: SchedulerState) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dto = SchedulerStateDTO(lastStatus = state.lastStatus?.name, lastDigestDay = state.lastDigestDay)
        val text = json.encodeToString(SchedulerStateDTO.serializer(), dto)
        val temp = Files.createTempFile(file.parent, "schedulerState", ".tmp")
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
