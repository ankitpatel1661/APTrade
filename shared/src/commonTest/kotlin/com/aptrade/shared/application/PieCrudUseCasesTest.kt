package com.aptrade.shared.application

import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieSlice
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun pp(s: String): BigDecimal = BigDecimal.parseString(s)

private fun pie(id: String, name: String = id) = Pie.create(
    id = id,
    name = name,
    slices = listOf(PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = pp("100"))),
    schedule = null,
    createdDay = "2026-07-20",
)

private class FakePieStore(initial: List<Pie> = emptyList()) : PieStore {
    var saved: List<Pie>? = null
        private set
    var saveCallCount: Int = 0
        private set
    private var stored: List<Pie> = initial

    override suspend fun load(): List<Pie> = stored

    override suspend fun save(pies: List<Pie>) {
        stored = pies
        saved = pies
        saveCallCount += 1
    }
}

class PieCrudUseCasesTest {

    @Test
    fun loadPiesDelegatesToStore() = runTest {
        val stored = listOf(pie("pie-1"), pie("pie-2"))
        val useCase = LoadPies(FakePieStore(initial = stored))

        assertEquals(stored, useCase.execute())
    }

    @Test
    fun loadPiesReturnsEmptyListWhenStoreIsEmpty() = runTest {
        val useCase = LoadPies(FakePieStore())

        assertEquals(emptyList(), useCase.execute())
    }

    @Test
    fun savePieAppendsNewPieAndPersists() = runTest {
        val existing = listOf(pie("pie-1"))
        val store = FakePieStore(initial = existing)
        val useCase = SavePie(store, Mutex())
        val newPie = pie("pie-2")

        val result = useCase.execute(newPie)

        assertEquals(listOf(pie("pie-1"), newPie), result)
        assertEquals(result, store.saved)
        assertEquals(1, store.saveCallCount)
    }

    @Test
    fun savePieWithExistingIdReplacesInPlacePreservingPosition() = runTest {
        val pieA = pie("pie-a")
        val pieB = pie("pie-b")
        val pieC = pie("pie-c")
        val store = FakePieStore(initial = listOf(pieA, pieB, pieC))
        val useCase = SavePie(store, Mutex())

        // Replace the MIDDLE pie with a same-id pie that has a different name — the
        // updated pie must land at index 1, not be moved to the end.
        val updatedB = pie("pie-b", name = "Renamed B")
        val result = useCase.execute(updatedB)

        assertEquals(listOf(pieA, updatedB, pieC), result)
        assertEquals("pie-a", result[0].id)
        assertEquals("pie-b", result[1].id)
        assertEquals("Renamed B", result[1].name)
        assertEquals("pie-c", result[2].id)
    }

    @Test
    fun deletePieRemovesMatchingId() = runTest {
        val pieA = pie("pie-a")
        val pieB = pie("pie-b")
        val store = FakePieStore(initial = listOf(pieA, pieB))
        val useCase = DeletePie(store, Mutex())

        val result = useCase.execute("pie-a")

        assertEquals(listOf(pieB), result)
        assertEquals(listOf(pieB), store.saved)
    }

    @Test
    fun deletePieWithUnknownIdIsANoOp() = runTest {
        val pieA = pie("pie-a")
        val store = FakePieStore(initial = listOf(pieA))
        val useCase = DeletePie(store, Mutex())

        val result = useCase.execute("does-not-exist")

        assertEquals(listOf(pieA), result)
        // Mirrors the Swift `DeletePie` exactly: the filtered (unchanged) list is still
        // persisted via `store.save`, it's just that nothing about its content changed.
        assertEquals(listOf(pieA), store.saved)
    }
}
