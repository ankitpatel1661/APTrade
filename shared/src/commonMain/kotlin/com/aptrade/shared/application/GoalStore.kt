package com.aptrade.shared.application

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.PortfolioGoal

/** Persists the user's portfolio goals. At most one goal per [GoalKind] is stored; enforcing
 *  that is [SaveGoal]'s job, not the adapter's.
 *
 *  Carry-notes §2.5: the design spec says goals live "in the portfolio store alongside portfolio
 *  state"; the Swift AS-BUILT put them behind their own port with their own key, and that turned
 *  out to matter — because a `PortfolioGoal` is never embedded in another serialized payload, a
 *  pre-goals file simply has no goals key and degrades to an empty list, so the lenient-decoding
 *  problem never arises at all. Port the as-built shape, not the spec sentence. */
interface GoalStore {
    suspend fun load(): List<PortfolioGoal>
    suspend fun save(goals: List<PortfolioGoal>)
}

class LoadGoals(private val store: GoalStore) {
    suspend fun execute(): List<PortfolioGoal> = store.load()
}

/** Upserts by kind — one value goal and one income goal at most. */
class SaveGoal(private val store: GoalStore) {
    suspend fun execute(goal: PortfolioGoal) {
        store.save(store.load().filter { it.kind != goal.kind } + goal)
    }
}

class RemoveGoal(private val store: GoalStore) {
    suspend fun execute(kind: GoalKind) {
        store.save(store.load().filter { it.kind != kind })
    }
}
