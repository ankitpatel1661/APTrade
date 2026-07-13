package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.BookmarkStore
import com.aptrade.shared.domain.NewsArticle
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

/** JSON-file bookmarks, full-fidelity (every [NewsArticle] field, including the
 *  nullable ones). Writes are atomic: temp file + rename, so a crash mid-save can
 *  never leave a half-written bookmarks file.
 *
 *  Deviation from FilePortfolioStore/FileWatchlistStore's contract, per the spec
 *  (macOS parity): on a missing OR corrupt file, [load] returns an empty list
 *  WITHOUT touching the file on disk — a decode failure never triggers an
 *  overwrite. (FilePortfolioStore also never overwrites on corrupt load — its
 *  `load()` simply returns null and does not call `save()` — so there is no actual
 *  conflict between the sibling stores and the brief here; noted only because the
 *  brief called out the possibility explicitly.)
 */
class FileBookmarkStore(private val file: Path) : BookmarkStore {

    @Serializable
    private data class ArticleDTO(
        val id: String,
        val headline: String,
        val summary: String,
        val source: String,
        val url: String,
        val imageUrl: String?,
        val publishedAtEpochSeconds: Long,
        val category: String?,
        val relatedSymbol: String?,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ArticleDTO.serializer())

    override suspend fun load(): List<NewsArticle> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(serializer, file.readText()).map { dto ->
                NewsArticle(
                    id = dto.id,
                    headline = dto.headline,
                    summary = dto.summary,
                    source = dto.source,
                    url = dto.url,
                    imageUrl = dto.imageUrl,
                    publishedAtEpochSeconds = dto.publishedAtEpochSeconds,
                    category = dto.category,
                    relatedSymbol = dto.relatedSymbol,
                )
            }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    override suspend fun save(articles: List<NewsArticle>) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dtos = articles.map { article ->
            ArticleDTO(
                id = article.id,
                headline = article.headline,
                summary = article.summary,
                source = article.source,
                url = article.url,
                imageUrl = article.imageUrl,
                publishedAtEpochSeconds = article.publishedAtEpochSeconds,
                category = article.category,
                relatedSymbol = article.relatedSymbol,
            )
        }
        val text = json.encodeToString(serializer, dtos)
        val temp = Files.createTempFile(file.parent, "bookmarks", ".tmp")
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it
        // here — this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
