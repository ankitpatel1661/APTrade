package com.aptrade.desktop.infra

import com.aptrade.shared.domain.NewsArticle
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileBookmarkStoreTest {
    private val fullArticle = NewsArticle(
        id = "art-1",
        headline = "Apple unveils new chip",
        summary = "A summary of the announcement.",
        source = "Reuters",
        url = "https://example.com/apple-chip",
        imageUrl = "https://example.com/image.jpg",
        publishedAtEpochSeconds = 1688169600,
        category = "technology",
        relatedSymbol = "AAPL",
    )

    private val minimalArticle = NewsArticle(
        id = "art-2",
        headline = "Market update",
        summary = "",
        source = "Bloomberg",
        url = "https://example.com/market-update",
        imageUrl = null,
        publishedAtEpochSeconds = 1688256000,
        category = null,
        relatedSymbol = null,
    )

    @Test
    fun roundTripsFullFidelityArticleIncludingNullableFields() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("bookmarks.json")
        val store = FileBookmarkStore(file)
        store.save(listOf(fullArticle, minimalArticle))
        assertEquals(listOf(fullArticle, minimalArticle), store.load())
        assertEquals(listOf(fullArticle, minimalArticle), FileBookmarkStore(file).load())   // fresh instance, same file
    }

    @Test
    fun missingFileLoadsEmpty() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("nope.json")
        assertEquals(emptyList(), FileBookmarkStore(file).load())
    }

    @Test
    fun corruptFileLoadsEmptyWithoutOverwritingStoredBytes() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("bookmarks.json")
        val corruptBytes = "{not json["
        file.writeText(corruptBytes)
        assertEquals(emptyList(), FileBookmarkStore(file).load())
        assertEquals(corruptBytes, file.readText())   // decode failure must not trigger a save
    }

    @Test
    fun saveIsAtomicAndLeavesNoTempFile() = runTest {
        val dir = createTempDirectory("aptrade-test").resolve("deep").resolve("nested")
        val file = dir.resolve("bookmarks.json")
        FileBookmarkStore(file).save(listOf(fullArticle))
        assertTrue(file.exists())
        assertTrue(file.readText().contains("Apple unveils new chip"))
        assertEquals(listOf("bookmarks.json"), dir.toFile().list()!!.toList())   // temp file was renamed away
    }

    @Test
    fun saveOverwritesPreviousContentAtomically() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("bookmarks.json")
        val store = FileBookmarkStore(file)
        store.save(listOf(fullArticle))
        store.save(listOf(minimalArticle))
        assertEquals(listOf(minimalArticle), store.load())
    }

    @Test
    fun emptyListRoundTrips() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("bookmarks.json")
        val store = FileBookmarkStore(file)
        store.save(emptyList())
        assertEquals(emptyList(), store.load())
    }
}
