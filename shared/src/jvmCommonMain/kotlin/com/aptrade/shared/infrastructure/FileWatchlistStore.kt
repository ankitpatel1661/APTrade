package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** JSON-file watchlist. Writes are atomic: temp file + rename, so a crash mid-save
 *  can never leave a half-written watchlist. Missing or corrupt file loads empty
 *  (first-launch seeding is FetchWatchlist's job). */
class FileWatchlistStore(private val file: Path) : WatchlistStore {

    @Serializable
    private data class EntryDTO(val symbol: String, val name: String, val kind: String)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(EntryDTO.serializer())

    override suspend fun load(): List<WatchlistEntry> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(serializer, file.readText()).mapNotNull { dto ->
                val kind = AssetKind.entries.firstOrNull { it.name == dto.kind }
                    ?: return@mapNotNull null   // unknown kind from a future version: skip the row
                WatchlistEntry(dto.symbol, dto.name, kind)
            }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    override suspend fun save(entries: List<WatchlistEntry>) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val text = json.encodeToString(serializer, entries.map { EntryDTO(it.symbol, it.name, it.kind.name) })
        val temp = Files.createTempFile(file.parent, "watchlist", ".tmp")
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it
        // here — this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
