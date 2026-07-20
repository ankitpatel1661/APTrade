package com.aptrade.shared.application

import com.aptrade.shared.domain.Pie

/** Loads all persisted Pies. Transcribed from Swift `LoadPies`
 *  (`Sources/APTradeApplication/PieUseCases.swift`). */
class LoadPies(private val store: PieStore) {
    suspend fun execute(): List<Pie> = store.load()
}

/** Create a new Pie or replace an existing one with the same id, in place — an update
 *  keeps the pie's original position in the list rather than moving it to the end.
 *  Transcribed from Swift `SavePie`. Unlike the Swift original (which wraps the whole
 *  load-modify-save sequence in `serializer.run` alongside contribution/rebalance
 *  writes to the same store), this use case is store-injected only — Task 6 scopes
 *  persistence and CRUD; wiring pie-mutating use cases onto the shared portfolio mutex
 *  is Task 7/8's concern (Global Constraint #8). */
class SavePie(private val store: PieStore) {
    /** Saves [pie], returning the complete list of Pies after the save. */
    suspend fun execute(pie: Pie): List<Pie> {
        val pies = store.load().toMutableList()
        val index = pies.indexOfFirst { it.id == pie.id }
        if (index >= 0) {
            pies[index] = pie
        } else {
            pies.add(pie)
        }
        store.save(pies)
        return pies
    }
}

/** Delete a Pie by id. No-op if the id is not found. Transcribed from Swift `DeletePie`. */
class DeletePie(private val store: PieStore) {
    /** Deletes the Pie matching [id], returning the complete list of Pies after the
     *  delete (unchanged if [id] was not found). */
    suspend fun execute(id: String): List<Pie> {
        val pies = store.load()
        val filtered = pies.filter { it.id != id }
        store.save(filtered)
        return filtered
    }
}
