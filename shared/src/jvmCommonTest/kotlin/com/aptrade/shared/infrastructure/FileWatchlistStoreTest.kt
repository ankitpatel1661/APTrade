package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileWatchlistStoreTest {
    private val entries = listOf(
        WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
        WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto),
    )

    @Test
    fun roundTripsEntries() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("watchlist.json")
        val store = FileWatchlistStore(file)
        store.save(entries)
        assertEquals(entries, store.load())
        assertEquals(entries, FileWatchlistStore(file).load())   // fresh instance, same file
    }

    @Test
    fun missingFileLoadsEmpty() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("nope.json")
        assertEquals(emptyList(), FileWatchlistStore(file).load())
    }

    @Test
    fun corruptFileLoadsEmpty() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("watchlist.json")
        file.writeText("{not json[")
        assertEquals(emptyList(), FileWatchlistStore(file).load())
    }

    @Test
    fun saveCreatesParentDirsAndLeavesNoTempFile() = runTest {
        val dir = createTempDirectory("aptrade-test").resolve("deep").resolve("nested")
        val file = dir.resolve("watchlist.json")
        FileWatchlistStore(file).save(entries)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("AAPL"))
        assertEquals(listOf("watchlist.json"), dir.toFile().list()!!.toList())  // temp file was renamed away
    }

    @Test
    fun unknownKindSkipsThatRowOnly() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("watchlist.json")
        file.writeText(
            """[{"symbol":"AAPL","name":"Apple Inc.","kind":"Stock"},
                {"symbol":"EURUSD=X","name":"EUR/USD","kind":"Forex"}]""",
        )
        assertEquals(
            listOf(WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)),
            FileWatchlistStore(file).load(),
        )
    }
}
