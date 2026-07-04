package com.aptrade.desktop.designkit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.dp
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Image as SkiaImage

/**
 * Process-wide HTTP client for thumbnail fetches.
 *
 * `desktopApp` has no Ktor dependency (deliberately removed from its compile classpath — see the
 * Task 3 refactor that made `:shared`'s Ktor client construction `internal`), so this composable
 * cannot reach for the shared HttpClient helper. Rather than add a new Gradle dependency for a
 * single "fetch some bytes" call site, this uses the JDK's built-in `java.net.http.HttpClient`
 * (available since Java 11; this project targets JVM 17), which needs nothing beyond what's
 * already on the classpath.
 *
 * A single client instance is shared across every [RemoteThumbnail] fetch and intentionally never
 * closed: `HttpClient` has no `close()`/`shutdown()` method in the JDK — its connection pool is
 * reclaimed by the JVM's internal executor threads (daemon threads) when the client becomes
 * unreachable, and for the lifetime of a desktop application there is exactly one such client, so
 * "never closed" simply means "lives as long as the process."
 */
private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

/**
 * A tiny LRU cache of decoded thumbnails, keyed by URL, capped at `MAX_ENTRIES` (64) entries.
 *
 * Backed by a [LinkedHashMap] in access-order mode: every `get` promotes an entry to
 * most-recently-used, and [LinkedHashMap.removeEldestEntry] evicts the least-recently-used entry
 * once the cache grows past capacity. Access is `synchronized` because Compose may invoke the
 * fetch side-effect from a background dispatcher while the main/UI thread reads concurrently.
 *
 * `internal` (rather than `private`) purely so `RemoteImageCacheTest` can drive it directly and
 * pin the eviction order without going through the network or Compose runtime.
 */
internal object RemoteImageCache {
    private const val MAX_ENTRIES = 64

    private val entries = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(url: String): ImageBitmap? = entries[url]

    @Synchronized
    fun put(url: String, bitmap: ImageBitmap) {
        entries[url] = bitmap
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Fetches [url]'s bytes and decodes them into an [ImageBitmap], or `null` on any failure.
 *
 * Decoding goes `Image.makeFromEncoded` → `Image.readPixels(Bitmap)` → `Bitmap.asComposeImageBitmap()`
 * rather than a direct `Image.asComposeImageBitmap()`/`toComposeImageBitmap()`: Compose's desktop
 * `ImageBitmap` wrapper (`SkiaBackedImageBitmap`) is backed by `org.jetbrains.skia.Bitmap`, and this
 * version of `androidx.compose.ui.graphics` (1.7.3) only exposes the `asComposeImageBitmap()`
 * extension on `Bitmap` — there is no overload directly on Skia's `Image`. Reading the decoded
 * `Image`'s pixels into a freshly allocated `Bitmap` is the standard bridge for this API surface.
 */
private suspend fun fetchImageBitmap(url: String): ImageBitmap? {
    RemoteImageCache.get(url)?.let { return it }

    return try {
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) return@withContext null

            val skiaImage = SkiaImage.makeFromEncoded(response.body())
            val skiaBitmap = SkiaBitmap().apply {
                allocN32Pixels(skiaImage.width, skiaImage.height)
                skiaImage.readPixels(this)
            }
            val bitmap = skiaBitmap.asComposeImageBitmap()
            RemoteImageCache.put(url, bitmap)
            bitmap
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/**
 * A thumbnail loaded asynchronously from [url] into an 8dp-rounded, [DK.surfaceHi]-backed box.
 *
 * `null` (no article image) and any fetch/decode failure both resolve to the same placeholder —
 * network errors, malformed URLs, and unsupported image formats are indistinguishable to the
 * caller by design; a news thumbnail is decorative, not essential, so this never surfaces an
 * error state.
 */
@Composable
fun RemoteThumbnail(url: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)

    if (url == null) {
        FallbackThumbnailBox(modifier, shape)
        return
    }

    val bitmap by produceState<ImageBitmap?>(initialValue = RemoteImageCache.get(url), key1 = url) {
        value = fetchImageBitmap(url)
    }

    val loaded = bitmap
    if (loaded == null) {
        FallbackThumbnailBox(modifier, shape)
    } else {
        Image(
            bitmap = loaded,
            contentDescription = null,
            modifier = modifier.clip(shape),
        )
    }
}

@Composable
private fun FallbackThumbnailBox(modifier: Modifier, shape: RoundedCornerShape) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(DK.surfaceHi)
            .fillMaxSize(),
    )
}
