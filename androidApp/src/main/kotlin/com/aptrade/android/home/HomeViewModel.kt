package com.aptrade.android.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.shared.application.HomeFeedAssembler
import com.aptrade.shared.application.HomeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Home dashboard: Android twin of desktop `HomeViewModel`
 * (desktopApp/src/main/kotlin/com/aptrade/desktop/home/HomeViewModel.kt), transcribed
 * near-verbatim — a thin wrapper over the shared [HomeFeedAssembler] — every hero-stat/feed
 * computation lives in the assembler (`:shared`), so this VM is pure plumbing: publish
 * whatever the assembler returns, in a [StateFlow] the Compose layer collects. `null` [state]
 * means "no successful refresh yet" (first mount, pre-load). The one adaptation from the
 * desktop twin is construction/scope (mirroring the house Android VM convention — see
 * [com.aptrade.android.plans.PlansViewModel]/[com.aptrade.android.portfolio.PortfolioViewModel]):
 * this VM extends androidx [ViewModel] and uses [viewModelScope] internally
 * (`Dispatchers.Main.immediate`) rather than taking a constructor-injected `CoroutineScope`.
 *
 * REFRESH CADENCE (M10.3 Global Constraint 1, carried from the M10.2 desktop twin): [refresh]
 * stays `suspend` rather than a self-launching, non-suspend event-handler (unlike this
 * codebase's other Android VMs, e.g. [com.aptrade.android.plans.PlansViewModel.onAppear]) —
 * deliberately, so the CALLER can own the single sequential 15s polling loop
 * (`repeatOnLifecycle(STARTED) { while (isActive) { vm.refresh(); delay(15_000) } }`, Task 3's
 * `HomeScreen`) and `await` each refresh's completion before scheduling the next `delay`. That
 * is the only way one coroutine can guarantee overlapping refreshes are impossible — a
 * fire-and-forget `viewModelScope.launch` per call (this class's own [start] included) cannot
 * make that guarantee if called again before the previous launch completes. [start] exists
 * only as the one non-suspend, self-launching entry point for a first-mount kick that doesn't
 * need to wait on anything — e.g. a bare `viewModel.start()` at construction time, standing in
 * for the polling loop's own first iteration.
 *
 * ERROR HANDLING: mirrors [HomeFeedAssembler]'s own per-source isolation contract one level
 * up. [CancellationException] is always rethrown FIRST — never swallowed. Any OTHER failure
 * during the WHOLE [HomeFeedAssembler.refresh] call (as opposed to a single source's failure,
 * which the assembler already isolates into a zeroed stat or an absent feed row) leaves
 * [state] untouched: the previous [HomeState] snapshot keeps publishing rather than flashing
 * to empty.
 */
class HomeViewModel(
    private val assembler: HomeFeedAssembler,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeState?>(null)
    val state: StateFlow<HomeState?> = _state

    /** Fire-and-forget first-mount kick — mirrors
     *  [com.aptrade.android.plans.PlansViewModel.onAppear]'s scope-launched shape. Callers
     *  that also run the sequential polling loop (Task 3) don't strictly need this (the
     *  loop's own first iteration already calls [refresh]), but it lets a bare
     *  `viewModel.start()` behave like every other Android VM's `onAppear`/`load` entry point
     *  for callers that construct this VM without the loop (e.g. a preview/test harness). */
    fun start() {
        viewModelScope.launch { refresh() }
    }

    /** Suspend so callers can sequence it inside their own polling loop (see class doc's
     *  REFRESH CADENCE section). Publishes the assembler's fresh [HomeState] on success; on
     *  any non-cancellation failure, [state] is left exactly as it was. */
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
