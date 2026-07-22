package com.aptrade.desktop.home

import com.aptrade.shared.application.HomeFeedAssembler
import com.aptrade.shared.application.HomeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Home dashboard: a thin desktop wrapper over the shared [HomeFeedAssembler] — every
 * hero-stat/feed computation lives in the assembler (`:shared`), so this VM is pure
 * plumbing: publish whatever the assembler returns, in a [StateFlow] the Compose layer
 * collects. `null` [state] means "no successful refresh yet" (first mount, pre-load).
 *
 * REFRESH CADENCE (M10.2 Global Constraint 1): [refresh] is `suspend` rather than the
 * self-launching, non-suspend event-handler shape most other desktop VMs in this codebase
 * use (see [com.aptrade.desktop.plans.PlansViewModel.onAppear]) — deliberately, so the
 * CALLER can own the single sequential 15s polling loop
 * (`while (isActive) { refresh(); delay(15_000) }`, HomePane's `LaunchedEffect`, Task 4)
 * and `await` each refresh's completion before scheduling the next `delay`. That is the
 * only way one coroutine can guarantee overlapping refreshes are impossible — a
 * fire-and-forget `scope.launch` per call (this class's own [start] included) cannot make
 * that guarantee if called again before the previous launch completes. [start] exists only
 * as the one non-suspend, self-launching entry point (mirroring
 * [com.aptrade.desktop.plans.PlansViewModel.onAppear]'s shape) for a first-mount kick that
 * doesn't need to wait on anything — e.g. a bare `viewModel.start()` at construction time,
 * standing in for the polling loop's own first iteration.
 *
 * ERROR HANDLING: mirrors [HomeFeedAssembler]'s own per-source isolation contract one
 * level up. [CancellationException] is always rethrown FIRST — never swallowed. Any OTHER
 * failure during the WHOLE [HomeFeedAssembler.refresh] call (as opposed to a single
 * source's failure, which the assembler already isolates into a zeroed stat or an absent
 * feed row — see that class's FAILURE ISOLATION doc) leaves [state] untouched: the
 * previous [HomeState] snapshot keeps publishing rather than flashing to empty. This VM
 * only ever sees a whole-call failure if something outside every per-source guard breaks
 * (there is no such path in the assembler today, but this VM must never assume that stays
 * true forever) — the failure mode this class exists to guard is "the dashboard goes
 * blank because of a transient blip," not "one row is temporarily missing."
 *
 * `scope` MUST be single-thread-confined (Dispatchers.Main on desktop): the [_state]
 * publish relies on that confinement instead of locks — the same contract every other
 * desktop VM in this codebase relies on.
 */
class HomeViewModel(
    private val assembler: HomeFeedAssembler,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<HomeState?>(null)
    val state: StateFlow<HomeState?> = _state

    /** Fire-and-forget first-mount kick — mirrors
     *  [com.aptrade.desktop.plans.PlansViewModel.onAppear]'s scope-launched shape. Callers
     *  that also run the sequential polling loop (Task 4) don't strictly need this (the
     *  loop's own first iteration already calls [refresh]), but it lets a bare
     *  `viewModel.start()` behave like every other desktop VM's `onAppear`/`load` entry
     *  point for callers that construct this VM without the loop (e.g. a preview/test
     *  harness). */
    fun start() {
        scope.launch { refresh() }
    }

    /** Suspend so callers can sequence it inside their own polling loop (see class doc's
     *  REFRESH CADENCE section). Publishes the assembler's fresh [HomeState] on success;
     *  on any non-cancellation failure, [state] is left exactly as it was. */
    suspend fun refresh() {
        try {
            _state.value = assembler.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the previous state — see class doc's ERROR HANDLING section.
        }
    }
}
