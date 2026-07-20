package com.aptrade.android.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.SavePie
import com.aptrade.shared.application.SimulateDCA
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.BacktestReport
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieMath
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.generatePieId
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val ZERO: BigDecimal = BigDecimal.ZERO
private val ONE_HUNDRED: BigDecimal = BigDecimal.parseString("100")

/** Live wizard form state: name/slice allocation, the optional recurring-contribution
 *  schedule, live weight-sum validation, the debounced slice-search results, and the
 *  on-demand DCA backtest preview. Android twin of desktop `PieWizardUiState`, aggregated
 *  into one state object per the house VM convention (see [PlansUiState]/
 *  [com.aptrade.android.calendar.CalendarUiState]/[com.aptrade.android.search.SearchUiState]). */
data class PieWizardUiState(
    val name: String = "",
    val slices: List<PieSlice> = emptyList(),
    val scheduleAmountText: String = "",
    val cadence: PieCadence = PieCadence.Monthly,
    val scheduleEnabled: Boolean = false,
    /** The day (`yyyy-MM-dd`) a NEW or re-anchored schedule's first contribution should
     *  land on. Defaults to today's trading day at init; when editing an already-scheduled
     *  pie, pre-fills from the existing `anchorDay` for display — but per [buildSchedule]'s
     *  rule, that pre-filled value is only actually HONORED when a new schedule is being
     *  (re)created (new pie, previously-unscheduled, or a cadence change), never on a
     *  cadence-unchanged edit, which preserves the existing anchor/cursor untouched. */
    val scheduleStartDay: String = "",
    /** Live sum of every slice's target weight, in percentage points. [canSave] requires
     *  this to be exactly 100. */
    val weightSumPP: BigDecimal = ZERO,
    val canSave: Boolean = false,
    /** `null` until [PieWizardViewModel.runBacktest] completes, and again if the run finds
     *  insufficient history or fails outright (mirrors [SimulateDCA]'s own non-throwing
     *  degrade-to-null). */
    val backtest: BacktestReport? = null,
    /** Debounced asset-search results for the slice-editor step, already filtered to
     *  exclude symbols that are already slices. Populated by
     *  [PieWizardViewModel.updateSearchQuery]. */
    val searchResults: List<Asset> = emptyList(),
)

/** Drives the pie creation/edit wizard: name, slice allocation (with a live 100%
 *  weight-sum check), an optional recurring contribution schedule, and an on-demand DCA
 *  backtest preview before saving. Android twin of desktop `PieWizardViewModel`
 *  (desktopApp/.../plans/PieWizardViewModel.kt), transcribed near-verbatim — the desktop
 *  VM is pure-Kotlin StateFlow over shared use cases with no desktop-only imports. The one
 *  adaptation is construction/scope: this VM extends androidx [ViewModel] and uses
 *  [viewModelScope] internally (Dispatchers.Main.immediate), mirroring the house Android VM
 *  convention (see [com.aptrade.android.search.SearchViewModel]'s identical debounce/cancel
 *  pattern) instead of taking a constructor-injected `CoroutineScope`.
 *
 *  Follows the house VM convention for every SYNCHRONOUS setter (`setName`, `addSlice`,
 *  `setWeight`, `equalSplit`, ...): plain functions that update [state] directly, no
 *  coroutine involved at all. [updateSearchQuery] is the one exception that needs
 *  [viewModelScope] — its 250ms debounce mirrors [com.aptrade.android.search.SearchViewModel]'s
 *  identical debounce/cancel pattern.
 *
 *  [runBacktest] and [save], by contrast, are `suspend fun`s the CALLER awaits directly
 *  rather than firing an internal `viewModelScope.launch`: both need to report a real
 *  result back to the caller (a [BacktestReport] that only exists once the network call
 *  resolves; a `Boolean` save outcome the UI acts on immediately) — a fire-and-forget event
 *  handler has nothing to hand that result to. The UI invokes these from its own
 *  `LaunchedEffect`/click-handler coroutine, the same way it would await any other suspend
 *  call. */
class PieWizardViewModel(
    private val existingPie: Pie? = null,
    private val savePie: SavePie,
    private val simulateDCA: SimulateDCA,
    private val searchAssets: FetchSearch,
    private val calendar: MarketCalendar,
    private val nowEpochSeconds: () -> Long,
    private val searchDebounceMillis: Long = 250,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PieWizardUiState> = _state
    private var searchJob: Job? = null

    private fun initialState(): PieWizardUiState {
        val schedule = existingPie?.schedule
        val base = PieWizardUiState(
            name = existingPie?.name ?: "",
            slices = existingPie?.slices ?: emptyList(),
            scheduleAmountText = schedule?.let { it.amount.amountText } ?: "",
            cadence = schedule?.cadence ?: PieCadence.Monthly,
            scheduleEnabled = schedule != null,
            scheduleStartDay = schedule?.anchorDay ?: calendar.tradingDay(nowEpochSeconds()),
        )
        return recompute(base)
    }

    fun setName(name: String) = _state.update { recompute(it.copy(name = name)) }

    /** Replaces `slices` wholesale — used by [addSlice]/[removeSlice]/[setWeight]/
     *  [equalSplit] internally, and exposed publicly for callers (tests, or a future bulk
     *  reorder UI) that need to bypass [addSlice]'s own dedupe guard. */
    fun setSlices(slices: List<PieSlice>) = _state.update { recompute(it.copy(slices = slices)) }

    fun setScheduleAmountText(text: String) = _state.update { recompute(it.copy(scheduleAmountText = text)) }

    /** A cadence change flips [willCreateNewSchedule] (see [buildSchedule]), which changes
     *  whether `scheduleStartDay` is even consulted for `canSave` — so a cadence change must
     *  re-run validation exactly like the other schedule fields. */
    fun setCadence(cadence: PieCadence) = _state.update { recompute(it.copy(cadence = cadence)) }

    fun setScheduleEnabled(enabled: Boolean) = _state.update { recompute(it.copy(scheduleEnabled = enabled)) }

    fun setScheduleStartDay(day: String) = _state.update { recompute(it.copy(scheduleStartDay = day)) }

    fun addSlice(asset: Asset) {
        _state.update { s ->
            if (s.slices.any { it.symbol == asset.symbol }) return@update s
            recompute(s.copy(slices = s.slices + PieSlice(asset.symbol, asset.kind, ZERO)))
        }
        clearSearch()
    }

    fun removeSlice(symbol: String) =
        _state.update { recompute(it.copy(slices = it.slices.filterNot { slice -> slice.symbol == symbol })) }

    fun setWeight(symbol: String, pp: BigDecimal) {
        _state.update { s ->
            val index = s.slices.indexOfFirst { it.symbol == symbol }
            if (index < 0) return@update s
            // A slice's ceiling is whatever the OTHER slices leave of the 100pp budget,
            // so the wizard's total can never run past Pie.create's exact-sum rule —
            // the last slice tops out at the remainder instead of pushing the footer to 105%.
            val othersSum = s.slices.foldIndexed(ZERO) { i, acc, slice ->
                if (i == index) acc else acc + slice.targetWeightPP
            }
            val ceiling = (ONE_HUNDRED - othersSum).let { if (it < ZERO) ZERO else it }
            val clamped = when {
                pp < ZERO -> ZERO
                pp > ceiling -> ceiling
                else -> pp
            }
            val updated = s.slices.toMutableList()
            updated[index] = updated[index].copy(targetWeightPP = clamped)
            recompute(s.copy(slices = updated))
        }
    }

    /** Splits 100pp evenly across every current slice via [PieMath.equalWeights]
     *  (largest-remainder method, so the weights always sum to exactly 100). */
    fun equalSplit() {
        _state.update { s ->
            val weights = PieMath.equalWeights(s.slices.size)
            val updated = s.slices.zip(weights).map { (slice, weight) -> slice.copy(targetWeightPP = weight) }
            recompute(s.copy(slices = updated))
        }
    }

    /** Debounced (250ms) autocomplete for the slice-editor step's search field, reusing the
     *  same [FetchSearch] use case the watchlist/command palette use. Cancels any in-flight
     *  search on each keystroke (mirrors [com.aptrade.android.search.SearchViewModel.onQueryChange]'s
     *  identical debounce/cancel pattern) and excludes symbols already added as slices, so a
     *  result never invites a duplicate [addSlice] no-op. */
    fun updateSearchQuery(text: String) {
        searchJob?.cancel()
        val query = text.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(searchDebounceMillis)
            val results = try {
                searchAssets.execute(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                emptyList()
            }
            val existingSymbols = _state.value.slices.map { it.symbol }.toSet()
            _state.update { it.copy(searchResults = results.filterNot { asset -> asset.symbol in existingSymbols }) }
        }
    }

    /** Cancels any in-flight search and clears the results — called after a slice is added
     *  (the query text becomes stale) or when the view wants to dismiss the list. */
    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchResults = emptyList()) }
    }

    /** Runs a DCA backtest over the wizard's current slices/amount/cadence. Requires at
     *  least one slice and a valid (positive) schedule amount; otherwise clears `backtest`
     *  without calling [simulateDCA] — see class doc for why this is a directly-awaited
     *  `suspend fun` rather than a fire-and-forget event handler. */
    suspend fun runBacktest(years: Int) {
        val s = _state.value
        val amount = parsedScheduleAmount(s.scheduleAmountText)
        if (s.slices.isEmpty() || amount == null) {
            _state.update { it.copy(backtest = null) }
            return
        }
        val report = simulateDCA.execute(s.slices, amount, s.cadence, years, nowEpochSeconds())
        _state.update { it.copy(backtest = report) }
    }

    /** Constructs (or updates) the Pie and persists it via [SavePie]. Returns `false`
     *  without saving when `canSave` is false, or if [Pie.create]'s own validation rejects
     *  the result — unreachable in practice since `canSave` mirrors [Pie.create]'s
     *  validation rules exactly, but handled defensively rather than force-tried. See class
     *  doc for why this is a directly-awaited `suspend fun`. */
    suspend fun save(): Boolean {
        val s = _state.value
        if (!s.canSave) return false

        var schedule: ContributionSchedule? = null
        if (s.scheduleEnabled) {
            val amount = parsedScheduleAmount(s.scheduleAmountText)
            if (amount != null) schedule = buildSchedule(s, amount)
        }

        return try {
            val survivingSymbols = s.slices.map { it.symbol }.toSet()
            // A slice removed in this edit leaves its ledger entry (and only its ledger
            // entry — activity history is left untouched, an immutable audit log) with
            // nothing left to attribute to: the pie no longer targets that symbol at all, so
            // carrying the stale claim forward would let it silently keep counting toward
            // this pie's totals/reconciliation forever. Dropping it here reverts those
            // shares to plain, unattributed manual holdings in the portfolio — consistent
            // with the attribution model, where a pie's ledger is a CLAIM on a portfolio
            // position, not the position itself; the portfolio is untouched.
            val survivingLedger = (existingPie?.ledger ?: emptyList()).filter { it.symbol in survivingSymbols }
            val pie = Pie.create(
                id = existingPie?.id ?: generatePieId(),
                name = s.name,
                slices = s.slices,
                schedule = schedule,
                createdDay = existingPie?.createdDay ?: calendar.tradingDay(nowEpochSeconds()),
                ledger = survivingLedger,
                activity = existingPie?.activity ?: emptyList(),
            )
            savePie.execute(pie)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Decides the [ContributionSchedule] to save, per this rule (avoids a bug where any
     *  edit — even a name-only one — silently re-anchored a pie's cadence, corrupting
     *  [ContributionSchedule.anchorDay]'s "fixed for the schedule's entire lifetime"
     *  invariant, since [PieSchedule]'s monthly stepping must always derive from that one
     *  original anchor to avoid the Jan 31 -> Feb 28 -> Mar 28 (should be Mar 31) drift
     *  described there):
     *
     *  - New pie, or an existing pie that previously had NO schedule: start a fresh
     *    schedule, `anchorDay` = the user-chosen `scheduleStartDay`, rolled to a trading day.
     *  - Existing pie that already has a schedule, cadence UNCHANGED: preserve `anchorDay`
     *    AND the `nextDueDay` cursor exactly — only `amount` may differ. `scheduleStartDay`
     *    is NOT consulted on this path (see its doc comment).
     *  - Existing pie that already has a schedule, cadence CHANGED: start a fresh schedule
     *    anchored on `scheduleStartDay` — a new rhythm legitimately restarts the cycle. */
    private fun buildSchedule(s: PieWizardUiState, amount: Money): ContributionSchedule {
        val previous = existingPie?.schedule
        if (previous != null && previous.cadence == s.cadence) {
            return ContributionSchedule(amount, s.cadence, previous.anchorDay, previous.nextDueDay)
        }

        // A NEW schedule anchors on the user-chosen start day (defaults to today, but the
        // wizard lets it be moved into the future), rolled forward to the next trading day
        // if it lands on a weekend/holiday — `canSave`'s `isValidScheduleStartDay` check
        // already guarantees this parses and is not in the past.
        val anchor = PieSchedule.rollToTradingDay(s.scheduleStartDay, calendar)
        return ContributionSchedule(amount, s.cadence, anchor, anchor)
    }

    // MARK: - Validation

    private fun recompute(s: PieWizardUiState): PieWizardUiState {
        val weightSum = s.slices.fold(ZERO) { acc, slice -> acc + slice.targetWeightPP }
        val uniqueSymbols = s.slices.map { it.symbol }.toSet().size == s.slices.size
        val trimmedName = s.name.trim()
        val amountValid = !s.scheduleEnabled || parsedScheduleAmount(s.scheduleAmountText) != null
        // `scheduleStartDay` only needs to be valid when it will actually be USED to build a
        // schedule (see `buildSchedule`'s doc comment): a cadence-unchanged edit of an
        // already-scheduled pie preserves the existing anchor/cursor untouched and never
        // reads `scheduleStartDay` at all, so an unrelated (e.g. long-past) pre-filled value
        // there must never block saving that edit.
        val startDayValid = !willCreateNewSchedule(s) || isValidScheduleStartDay(s)
        val canSave = s.slices.isNotEmpty() && uniqueSymbols && trimmedName.isNotEmpty() &&
            weightSum.compareTo(ONE_HUNDRED) == 0 && amountValid && startDayValid
        return s.copy(weightSumPP = weightSum, canSave = canSave)
    }

    private fun parsedScheduleAmount(text: String): Money? {
        val value = try {
            BigDecimal.parseString(text)
        } catch (e: Exception) {
            null
        } ?: return null
        if (value <= ZERO) return null
        return Money(value)
    }

    /** `true` when saving would build a NEW schedule anchor from `scheduleStartDay` (see
     *  [buildSchedule]): a new pie, an existing pie that had no schedule, or a cadence
     *  change on an already-scheduled pie. `false` for a cadence-unchanged edit, which
     *  preserves the existing anchor/cursor and ignores `scheduleStartDay` entirely. */
    private fun willCreateNewSchedule(s: PieWizardUiState): Boolean {
        if (!s.scheduleEnabled) return false
        val previous = existingPie?.schedule ?: return true
        return previous.cadence != s.cadence
    }

    /** `scheduleStartDay` must parse as a real calendar day and be no earlier than today's
     *  trading day — a schedule cannot be anchored in the past. */
    private fun isValidScheduleStartDay(s: PieWizardUiState): Boolean {
        if (PieSchedule.parseDay(s.scheduleStartDay) == null) return false
        return s.scheduleStartDay >= calendar.tradingDay(nowEpochSeconds())
    }
}
