package com.aptrade.desktop.plans

import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.shared.application.ContributeToPie
import com.aptrade.shared.application.ContributionOutcome
import com.aptrade.shared.application.DeletePie
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.LoadPies
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ReconcilePieLedgers
import com.aptrade.shared.application.RebalancePie
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityEntry
import com.aptrade.shared.domain.PieMath
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.RebalanceOrder
import com.aptrade.shared.l10n.L10n
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ZERO: BigDecimal = BigDecimal.ZERO

/** One list-card's worth of display data for a Pie. `currentValue` and `sliceWeights`
 *  (target-weight percentage points) stay RAW domain types — same "feeds further
 *  formatting" contract [PortfolioViewModel]'s `marketValueText` establishes — the view
 *  (Task 12) is responsible for money/percent text rendering. `nextContributionLabel` is
 *  the one field this VM pre-localizes, since it's a full sentence (`tr`/`trf`), not a
 *  formattable number — same split [PortfolioViewModel] draws between raw amounts and
 *  pre-formatted signed/labeled text. */
data class PieRowUi(
    val id: String,
    val name: String,
    val currentValue: Money,
    val nextContributionLabel: String?,
    /** Largest absolute drift (percentage points) across the pie's slices. The view shows
     *  a drift badge when this exceeds 5pp. */
    val maxDriftPP: BigDecimal,
    val sliceWeights: List<Pair<String, BigDecimal>>,
)

/** One slice's target-vs-actual allocation and drift, for the detail screen. */
data class PieSliceDetailUi(
    val symbol: String,
    val assetKind: AssetKind,
    val targetWeight: BigDecimal,
    val actualWeight: BigDecimal,
    val drift: BigDecimal,
    val currentValue: Money,
)

/** Full detail-screen data for one Pie. `totalValue` is the SAME reduction [PieRowUi]'s
 *  `currentValue` derives for this pie's list card — one source of truth for "how much is
 *  this pie worth" shared by the list and detail, never re-derived by the view. */
data class PieDetailUi(
    val pieId: String,
    val name: String,
    val slices: List<PieSliceDetailUi>,
    val activity: List<PieActivityEntry>,
    val schedule: ContributionSchedule?,
    val totalValue: Money,
)

data class PlansUiState(
    val rows: List<PieRowUi> = emptyList(),
    val detail: PieDetailUi? = null,
    val rebalancePreview: List<RebalanceOrder>? = null,
    val errorMessage: String? = null,
)

/** Plans tab: lists the user's investment Pies as summary cards and drives the detail
 *  screen, one-off contributions, and manual rebalancing. Transcribed from Swift
 *  `PlansViewModel` (`Sources/APTradeApp/PlansViewModel.swift`) AS-BUILT.
 *
 *  Every user-visible string this VM produces (the insufficient-cash error, the
 *  next-contribution label) is routed through `tr`/`trf` (`com.aptrade.desktop.l10n`),
 *  mirroring Swift's `tr(_:)` calls exactly.
 *
 *  Follows the house desktop-VM convention (see [PortfolioViewModel]/[CalendarViewModel]/
 *  [DetailViewModel]): a single [MutableStateFlow]-backed [state], public methods are
 *  plain (non-suspend) event handlers that internally `scope.launch`, and `scope` MUST be
 *  single-thread-confined (Dispatchers.Main on desktop) — the same contract every other
 *  desktop VM in this codebase relies on instead of locks. */
class PlansViewModel(
    private val loadPies: LoadPies,
    private val deletePieUseCase: DeletePie,
    private val contributeToPie: ContributeToPie,
    private val rebalancePie: RebalancePie,
    private val reconcileLedgers: ReconcilePieLedgers,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val calendar: MarketCalendar,
    private val scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long,
) {
    private val _state = MutableStateFlow(PlansUiState())
    val state: StateFlow<PlansUiState> = _state

    /** Reconciles every Pie's ledger against actual portfolio holdings BEFORE building
     *  rows, so a manual sell made outside a Pie can never show stale, over-claimed
     *  values here (see [ReconcilePieLedgers]'s cross-pie drift note). */
    fun onAppear() {
        scope.launch {
            reconcileLedgers.execute(nowEpochSeconds())
            reloadRows()
        }
    }

    fun openDetail(id: String) {
        // A preview from a different pie must never linger.
        _state.update { it.copy(rebalancePreview = null) }
        scope.launch { loadDetail(id) }
    }

    fun contributeNow(id: String, amount: Money) {
        _state.update { it.copy(errorMessage = null, rebalancePreview = null) }
        if (amount.amount <= ZERO) {
            _state.update { it.copy(errorMessage = tr(L10n.Key.PieInvalidAmount)) }
            return
        }
        scope.launch {
            val day = calendar.tradingDay(nowEpochSeconds())
            try {
                val outcome = contributeToPie.execute(id, amount, day, nowEpochSeconds())
                if (outcome is ContributionOutcome.SkippedInsufficientCash) {
                    _state.update { it.copy(errorMessage = tr(L10n.Key.PieInsufficientCash)) }
                }
                reloadRows()
                if (_state.value.detail?.pieId == id) loadDetail(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = tr(L10n.Key.CouldntPlaceOrder)) }
            }
        }
    }

    /** Prices the Pie's current holdings against live quotes and fills `rebalancePreview`
     *  with the trades [confirmRebalance] would place. Read-only. */
    fun requestRebalance(id: String) {
        _state.update { it.copy(errorMessage = null) }
        scope.launch {
            try {
                val preview = rebalancePie.preview(id)
                _state.update { it.copy(rebalancePreview = preview) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = tr(L10n.Key.CouldntPlaceOrder)) }
            }
        }
    }

    /** Executes the previewed orders, then clears the preview and refreshes rows/detail. */
    fun confirmRebalance(id: String) {
        _state.update { it.copy(errorMessage = null) }
        scope.launch {
            try {
                rebalancePie.execute(id, calendar.tradingDay(nowEpochSeconds()), nowEpochSeconds())
                _state.update { it.copy(rebalancePreview = null) }
                reloadRows()
                if (_state.value.detail?.pieId == id) loadDetail(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = tr(L10n.Key.CouldntPlaceOrder)) }
            }
        }
    }

    fun deletePie(id: String) {
        _state.update { it.copy(rebalancePreview = null) }
        scope.launch {
            deletePieUseCase.execute(id)
            if (_state.value.detail?.pieId == id) {
                _state.update { it.copy(detail = null) }
            }
            reloadRows()
        }
    }

    // MARK: - Row / detail construction

    private suspend fun reloadRows() {
        val pies = loadPies.execute()
        val symbols = pies.flatMap { pie -> pie.slices.map { it.symbol } }.distinct()
        val quotes = fetchQuotesSafely(symbols)
        _state.update { it.copy(rows = pies.map { pie -> buildRow(pie, quotes) }) }
    }

    /** Shared by [openDetail], [contributeNow], and [confirmRebalance]'s "reopen the
     *  currently-open detail if it's this pie" step — a private suspend helper (rather than
     *  those callers re-invoking the public, self-launching [openDetail]) so the reopen runs
     *  sequentially within the SAME coroutine as the mutation that triggered it, instead of a
     *  second, independently-scheduled `scope.launch`. */
    private suspend fun loadDetail(id: String) {
        val pie = loadPies.execute().firstOrNull { it.id == id }
        if (pie == null) {
            _state.update { it.copy(detail = null) }
            return
        }
        val quotes = fetchQuotesSafely(pie.slices.map { it.symbol })
        _state.update { it.copy(detail = buildDetail(pie, quotes)) }
    }

    private fun buildRow(pie: Pie, quotes: Map<String, Quote>): PieRowUi {
        val currentValuesMap = currentValues(pie, quotes)
        val totalValue = currentValuesMap.values.fold(Money(ZERO)) { acc, m -> acc + m }
        val drift = PieMath.drift(currentValuesMap, pie.slices)
        val maxDriftPP = drift.values.maxOfOrNull { it.abs() } ?: ZERO
        return PieRowUi(
            id = pie.id,
            name = pie.name,
            currentValue = totalValue,
            nextContributionLabel = nextContributionLabel(pie),
            maxDriftPP = maxDriftPP,
            sliceWeights = pie.slices.map { it.symbol to it.targetWeightPP },
        )
    }

    private fun buildDetail(pie: Pie, quotes: Map<String, Quote>): PieDetailUi {
        val currentValuesMap = currentValues(pie, quotes)
        // Same reduction `buildRow` uses for this pie's list-card `currentValue` — one
        // source of truth for "how much is this pie worth" shared by the list and detail.
        val totalMoney = currentValuesMap.values.fold(Money(ZERO)) { acc, m -> acc + m }
        val totalValue = totalMoney.amount
        val driftBySymbol = PieMath.drift(currentValuesMap, pie.slices)
        val sliceDetails = pie.slices.map { slice ->
            val value = currentValuesMap[slice.symbol] ?: Money(ZERO)
            val actual = if (totalValue > ZERO) {
                value.amount.divide(totalValue, MONEY_MATH) * ONE_HUNDRED
            } else {
                ZERO
            }
            PieSliceDetailUi(
                symbol = slice.symbol,
                assetKind = slice.assetKind,
                targetWeight = slice.targetWeightPP,
                actualWeight = actual,
                drift = driftBySymbol[slice.symbol] ?: ZERO,
                currentValue = value,
            )
        }
        return PieDetailUi(
            pieId = pie.id,
            name = pie.name,
            slices = sliceDetails,
            activity = pie.activity,
            schedule = pie.schedule,
            totalValue = totalMoney,
        )
    }

    /** Prices `pie`'s ledger against `quotes`; a symbol with no successful quote is simply
     *  omitted (mirrors [PieMath]'s own "0 if missing" treatment). */
    private fun currentValues(pie: Pie, quotes: Map<String, Quote>): Map<String, Money> {
        val result = LinkedHashMap<String, Money>()
        for (slice in pie.slices) {
            val quote = quotes[slice.symbol] ?: continue
            val quantity = pie.quantityOf(slice.symbol)
            result[slice.symbol] = Money(quantity * quote.price.amount, quote.price.currencyCode)
        }
        return result
    }

    /** Batch quote fetch for row/detail pricing. Unlike [PortfolioViewModel]'s
     *  poll-and-keep-last-good `refreshQuotes`, there is no "last good" cache for pie
     *  pricing — on any failure this degrades to an empty map, which [currentValues] already
     *  treats identically to "no quote for this symbol" (mirrors Swift's per-symbol
     *  `Result`-based `fetchQuotes`, which never throws). */
    private suspend fun fetchQuotesSafely(symbols: List<String>): Map<String, Quote> {
        if (symbols.isEmpty()) return emptyMap()
        return try {
            fetchMarketQuotes.execute(symbols).associateBy { it.symbol }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuoteError) {
            emptyMap()
        }
    }

    private fun nextContributionLabel(pie: Pie): String? {
        val schedule = pie.schedule ?: return null
        val epochDay = PieSchedule.parseDay(schedule.nextDueDay) ?: return null
        val formatted = LocalDate.ofEpochDay(epochDay).format(DUE_DAY_FORMATTER)
        return trf(L10n.Key.NextContributionFormat, formatted)
    }

    private companion object {
        val ONE_HUNDRED: BigDecimal = BigDecimal.parseString("100")
        val DUE_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    }
}
