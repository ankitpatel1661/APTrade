package com.aptrade.shared.application

import com.aptrade.shared.domain.Pie
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Loads all persisted Pies. Transcribed from Swift `LoadPies`
 *  (`Sources/APTradeApplication/PieUseCases.swift`). Deliberately lock-free — a plain read
 *  of whatever is currently persisted, exactly like Swift's un-serialized `LoadPies`. */
class LoadPies(private val store: PieStore) {
    suspend fun execute(): List<Pie> = store.load()
}

/** Create a new Pie or replace an existing one with the same id, in place — an update
 *  keeps the pie's original position in the list rather than moving it to the end.
 *  Transcribed from Swift `SavePie`, whose doc comment reads: "the whole load-modify-save
 *  sequence runs inside `serializer.run` so a wizard save can never interleave with (and
 *  clobber, or be clobbered by) a concurrent contribution, rebalance, or catch-up write to
 *  the same store." [portfolioMutex] is that same serialization, Kotlin-side — the SAME
 *  [Mutex] instance [BuyAsset]/[SellAsset]/[ContributeToPie]/[ExecuteDueContributions]/
 *  [RebalancePie]/[ReconcilePieLedgers] hold (see [BuyAsset]'s doc comment for the full
 *  co-holder list). Unlike those use cases' network-outside-lock split, [PieStore.load]/
 *  [PieStore.save] are pure local I/O with no network fetch to keep outside the lock, so —
 *  exactly like the Swift original — the ENTIRE load-modify-save sequence runs inside
 *  [withLock], not just the mutation. */
class SavePie(private val store: PieStore, private val portfolioMutex: Mutex) {
    /** Saves [pie], returning the complete list of Pies after the save. */
    suspend fun execute(pie: Pie): List<Pie> = portfolioMutex.withLock {
        val pies = store.load().toMutableList()
        val index = pies.indexOfFirst { it.id == pie.id }
        if (index >= 0) {
            pies[index] = pie
        } else {
            pies.add(pie)
        }
        store.save(pies)
        pies
    }
}

/** Delete a Pie by id. No-op if the id is not found. Transcribed from Swift `DeletePie` —
 *  serialized like [SavePie]; see its doc comment. */
class DeletePie(private val store: PieStore, private val portfolioMutex: Mutex) {
    /** Deletes the Pie matching [id], returning the complete list of Pies after the
     *  delete (unchanged if [id] was not found). */
    suspend fun execute(id: String): List<Pie> = portfolioMutex.withLock {
        val pies = store.load()
        val filtered = pies.filter { it.id != id }
        store.save(filtered)
        filtered
    }
}
