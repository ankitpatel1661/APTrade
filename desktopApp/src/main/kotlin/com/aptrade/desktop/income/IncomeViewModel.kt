package com.aptrade.desktop.income

import com.aptrade.desktop.goals.GoalCardUi
import com.aptrade.desktop.goals.goalCardUi
import com.aptrade.shared.application.LoadGoals
import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveGoal
import com.aptrade.shared.application.SaveGoal
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.DividendMath
import com.aptrade.shared.domain.ForecastYear
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalMath
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioGoal
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.ScheduledDividend
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.Transaction
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SECONDS_PER_DAY = 86_400L

/** One summary tile's worth of income math: this year's cash received, the projected
 *  forward-annual rate, and its two yield readings. `portfolioYield`/`yieldOnCost` are raw
 *  fractions (0.031 = 3.1%) -- the view (Task 7) owns percent formatting, same "feeds
 *  further formatting" contract every other desktop VM state in this codebase follows. */
data class SummaryCards(
    val projectedAnnual: Money,
    val receivedYTD: Money,
    val portfolioYield: Double,
    val yieldOnCost: Double,
)

/** One bar on the monthly income chart. `id` is the UTC `"yyyy-MM"` bucket key. */
data class MonthBar(
    val id: String,
    val amount: Money,
    val isProjected: Boolean,
)

/** One projected future payout for a held (non-crypto) symbol. */
data class UpcomingRow(
    val id: String,
    val symbol: String,
    val estimatedExDateEpochSeconds: Long,
    val estimatedAmount: Money,
)

/** One per-holding income row. */
data class HoldingRow(
    val id: String,
    val symbol: String,
    val shares: BigDecimal,
    val annualIncome: Money,
    val yieldOnCost: Double,
    val lastPayment: Money?,
)

/** One past dividend payout, ledger-derived (never depends on a network call). */
data class HistoryEntry(
    val id: String,
    val epochSeconds: Long,
    val symbol: String,
    val amountPerShare: Money,
    val shares: BigDecimal,
    val total: Money,
    val wasReinvested: Boolean,
)

/** Forecast chart horizon. Presented as pills (5/10/20/30) — no free slider. */
enum class ForecastHorizon(val years: Int) {
    Five(5), Ten(10), Twenty(20), Thirty(30);

    val label: String get() = "${years}y"
}

/** One month's worth of estimated upcoming dividend payments.
 *
 *  [rows] are [ScheduledDividend]s — cadence projections rolled forward from each holding's last
 *  REAL event, never announced ex-dates (carry-notes §3.7). Every row the UI renders from this
 *  must be labeled an estimate.
 *
 *  [id] is the UTC `"yyyy-MM"` bucket key; the pane formats the human month title from it, the
 *  same division of labour `MonthBar.id`/`monthLabel` already uses. There can be up to THIRTEEN
 *  entries: the window is a fixed 365 days, so with "now" mid-month the first and last buckets are
 *  both partial — and across a year boundary they share a month NAME with different years. The UI
 *  must therefore render the year too and must not assume a 12-item grid. */
data class CalendarMonth(
    val id: String,
    val rows: List<ScheduledDividend>,
    val total: Money,
)

data class State(
    val cards: SummaryCards? = null,
    /** Last 12 received months + up to 3 projected. */
    val months: List<MonthBar> = emptyList(),
    /** Sorted by `estimatedExDateEpochSeconds` ascending. */
    val upcoming: List<UpcomingRow> = emptyList(),
    /** Sorted by `annualIncome` descending. */
    val holdings: List<HoldingRow> = emptyList(),
    /** Newest first. */
    val history: List<HistoryEntry> = emptyList(),
    val isLoading: Boolean = false,
    /** Estimated payouts over the next 365 days, grouped by UTC month, ascending. */
    val calendarMonths: List<CalendarMonth> = emptyList(),
    /** Per-holding-summed annual income projected [horizon] years forward. `forecast[0]`
     *  (yearOffset 1) is the trailing-twelve-month rate with NO growth — and is also exactly what
     *  the income goal's progress is measured against. */
    val forecast: List<ForecastYear> = emptyList(),
    val horizon: ForecastHorizon = ForecastHorizon.Ten,
    /** Whether [forecast] holds any actual income. `incomeForecast` always returns `horizon`
     *  entries — for a portfolio holding no dividend payer, every entry is a zero `Money` — so
     *  `forecast.isNotEmpty()` can never answer "is there anything to chart?". Exposed here rather
     *  than recomputed in the view so no pane has to reason about DividendMath's internals. */
    val hasForecastIncome: Boolean = false,
    /** True when at least one holding that actually contributes to [forecast] (positive trailing
     *  income, positive quantity — the same inclusion test `incomeForecast` applies internally)
     *  has no entry in the last-loaded quote map. When that happens, `incomeForecast`'s own
     *  per-symbol `?: averageCost` fallback silently reinvests THAT holding's DRIP compounding at
     *  cost basis rather than its live price — the same overstatement mechanism an omitted
     *  `pricesBySymbol` argument would cause (carry-notes §1.1), reached instead by an ordinary
     *  offline/rate-limited quote-fetch failure. `hasForecastIncome` cannot signal this (the
     *  forecast is still populated, just wrong), so this flag exists for Task 12 to caption the
     *  DRIP chart with — mirrors [com.aptrade.shared.domain.goalCurrentValueFloor]'s KDoc
     *  precedent of naming the "silent fallback vs. genuine data" distinction explicitly rather
     *  than letting a caller conflate them. False whenever there is nothing forecastable at all
     *  (an empty/all-zero-income portfolio has nothing to caption either). */
    val forecastPricesAreEstimated: Boolean = false,
    /** `null` when no income goal is set — the card still RENDERS (carry-notes §1.3), showing its
     *  "Set a goal" affordance. */
    val incomeGoal: GoalCardUi? = null,
)

/** Income tab: dividend summary cards, monthly bars (received + projected), upcoming
 *  payouts, per-holding breakdown, and payment history. Transcribed from
 *  `Sources/APTradeApp/IncomeViewModel.swift` AS-BUILT — including the `buildUpcoming`
 *  stale-projection guard (`projected.exDate > asOf`).
 *
 *  Follows the house desktop-VM convention exactly (see
 *  [com.aptrade.desktop.plans.PlansViewModel.onAppear]): `load` is a plain (non-suspend)
 *  event handler that internally `scope.launch`s, and `scope` MUST be single-thread-confined
 *  (Dispatchers.Main on desktop) — the same contract every other desktop VM in this codebase
 *  relies on instead of locks.
 *
 *  Failure isolation: a dividend-event fetch failure for one symbol degrades only that
 *  symbol's projections (to empty/zero) -- it never blocks another symbol's events, and
 *  never blocks the ledger-derived pieces (`history`, `cards.receivedYTD`). A missing quote
 *  degrades market-value-dependent math to its cost-basis fallback; if that fallback is
 *  also zero, the affected yield fraction degrades to zero rather than dividing by zero. */
class IncomeViewModel(
    private val portfolioStore: PortfolioStore,
    private val marketDataRepository: MarketDataRepository,
    private val calendar: MarketCalendar = MarketCalendar(),
    private val scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long,
    private val loadGoals: LoadGoals,
    private val saveGoal: SaveGoal,
    private val removeGoal: RemoveGoal,
    /** Reads the persisted DRIP toggle LIVE on each load — never captured once at construction,
     *  since the user can flip it at any point during a run. Suspend because Kotlin's settings
     *  store is real file I/O (the same recorded divergence `ProcessDueDividends` documents). */
    private val isDripEnabled: suspend () -> Boolean,
) {
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // Inputs the forecast rebuild needs, cached during load() so changing the horizon or flipping
    // DRIP recomputes without a network round trip.
    private var lastPositions: List<Position> = emptyList()
    private var lastEventsBySymbol: Map<String, List<DividendEvent>> = emptyMap()

    /** Quotes from the last load, as `[symbol: price]`. Passed to `DividendMath.incomeForecast` so
     *  DRIP reinvestment compounds at the REAL quoted price rather than silently falling back to
     *  cost basis for every symbol. That fallback is `incomeForecast`'s own per-symbol behaviour
     *  for a symbol genuinely missing a quote — it must never become the behaviour for ALL symbols
     *  via an omitted argument (carry-notes §1.1). */
    private var lastPricesBySymbol: Map<String, Money> = emptyMap()

    private var dripEnabled: Boolean = false
    private var incomeGoal: PortfolioGoal? = null

    fun load() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // Mirrors FetchPortfolio.execute()'s fallback -- the starting portfolio is
                // not persisted here, only synthesized for a store that has never been saved
                // to.
                val portfolio = portfolioStore.load() ?: Portfolio.starting()
                val transactions = portfolio.transactions
                val asOf = nowEpochSeconds()

                // Ledger-derived pieces never depend on network calls, so they populate even
                // when every remote fetch below fails.
                val history = buildHistory(transactions, calendar)
                val receivedYTDValue = receivedYTD(transactions, asOf)

                // Dividend events, fetched per non-crypto holding. A single symbol's failure
                // is caught locally and contributes an empty history for that symbol only.
                val nonCryptoPositions = portfolio.positions.filter { it.asset.kind != AssetKind.Crypto }
                val eventsBySymbol = mutableMapOf<String, List<DividendEvent>>()
                for (position in nonCryptoPositions) {
                    val symbol = position.asset.symbol
                    eventsBySymbol[symbol] = try {
                        marketDataRepository.dividendEvents(symbol, lookbackStart(asOf))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: QuoteError) {
                        emptyList()
                    }
                }

                // Quotes for every held symbol (including crypto) so market value mirrors
                // `Portfolio.valuation`'s total; a missing quote falls back to cost basis
                // there.
                val quotes: Map<String, Quote> = try {
                    marketDataRepository.quotes(portfolio.positions.map { it.asset.symbol })
                        .associateBy { it.symbol }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: QuoteError) {
                    emptyMap()
                }

                val cards = buildCards(portfolio, eventsBySymbol, quotes, receivedYTDValue, asOf)
                val months = buildMonths(transactions, nonCryptoPositions, eventsBySymbol, asOf)
                val upcoming = buildUpcoming(nonCryptoPositions, eventsBySymbol, asOf)
                    .sortedBy { it.estimatedExDateEpochSeconds }
                val holdings = buildHoldings(nonCryptoPositions, eventsBySymbol, transactions, asOf)
                    .sortedByDescending { it.annualIncome.amount }

                _state.update {
                    it.copy(
                        cards = cards,
                        months = months,
                        upcoming = upcoming,
                        holdings = holdings,
                        history = history,
                    )
                }

                lastPositions = portfolio.positions
                lastEventsBySymbol = eventsBySymbol
                lastPricesBySymbol = quotes.mapValues { (_, quote) -> quote.price }
                dripEnabled = isDripEnabled()
                // Re-read on EVERY load, never gated on a first-load flag: a portfolio reset
                // clears goals as a side effect, and a screen that trusted its first read would
                // keep showing a deleted goal with a progress bar and an ETA computed against the
                // pre-reset curve (carry-notes §3.4).
                incomeGoal = loadGoals.execute().firstOrNull { it.kind == GoalKind.Income }
                _state.update { it.copy(calendarMonths = buildCalendar(portfolio.positions, eventsBySymbol, asOf)) }
                rebuildForecast()
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    // MARK: - Forecast & income goal

    fun setHorizon(horizon: ForecastHorizon) {
        if (_state.value.horizon == horizon) return
        _state.update { it.copy(horizon = horizon) }
        rebuildForecast()
    }

    /** Call after the persisted DRIP setting changes.
     *
     *  BINDING (carry-notes §2.2): the toggle and the forecast chart live on the SAME screen, and
     *  the chart's caption actively promises DRIP compounding. Without this, flipping the toggle
     *  left every displayed year understated until the user happened to tap a horizon pill. It
     *  refreshes the income-goal projection too — that ETA reads the same curve, so rebuilding
     *  only the chart would leave it stale against a curve that just changed.
     *
     *  Takes the new value directly rather than re-reading the suspend settings store, so the
     *  rebuild cannot race the persist. */
    fun dripDidChange(enabled: Boolean) {
        dripEnabled = enabled
        rebuildForecast()
    }

    fun setIncomeGoal(target: Money) {
        val goal = PortfolioGoal(GoalKind.Income, target, nowEpochSeconds())
        scope.launch {
            saveGoal.execute(goal)
            incomeGoal = goal
            refreshGoalProjection()
        }
    }

    fun removeIncomeGoal() {
        scope.launch {
            removeGoal.execute(GoalKind.Income)
            incomeGoal = null
            _state.update { it.copy(incomeGoal = null) }
        }
    }

    private fun rebuildForecast() {
        val asOf = nowEpochSeconds()
        val forecast = DividendMath.incomeForecast(
            positions = lastPositions,
            pricesBySymbol = lastPricesBySymbol,
            eventsBySymbol = lastEventsBySymbol,
            years = _state.value.horizon.years,
            dripEnabled = dripEnabled,
            asOfEpochSeconds = asOf,
        )
        _state.update {
            it.copy(
                forecast = forecast,
                hasForecastIncome = forecast.any { year -> year.income.amount > BigDecimal.ZERO },
                forecastPricesAreEstimated = forecastPricesAreEstimated(lastPositions, lastEventsBySymbol, asOf),
            )
        }
        refreshGoalProjection()
    }

    /** True when a holding [incomeForecast][DividendMath.incomeForecast] would actually project
     *  (positive trailing income, positive quantity — its own `trailing.amount <= ZERO ||
     *  quantity <= ZERO` skip condition, reproduced here so this flag matches EXACTLY which
     *  holdings the forecast includes) has no entry in [lastPricesBySymbol]. Calls
     *  [DividendMath.trailingAnnualPerShare] directly — the same shared, pure function this file
     *  already calls elsewhere (`buildHoldings`) — rather than inventing new arithmetic; this is
     *  a membership check, not a re-derivation of the forecast's math. */
    private fun forecastPricesAreEstimated(
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        asOfEpochSeconds: Long,
    ): Boolean {
        val forecastable = positions.filter { position ->
            val events = eventsBySymbol[position.asset.symbol] ?: emptyList()
            DividendMath.trailingAnnualPerShare(events, asOfEpochSeconds).amount > BigDecimal.ZERO &&
                position.quantity > BigDecimal.ZERO
        }
        return forecastable.any { lastPricesBySymbol[it.asset.symbol] == null }
    }

    private fun refreshGoalProjection() {
        val goal = incomeGoal
        if (goal == null) {
            _state.update { it.copy(incomeGoal = null) }
            return
        }
        val asOf = nowEpochSeconds()
        // ALWAYS a full-horizon curve, independent of the chart's 5/10/20/30 pill (carry-notes
        // §3.3): a truncated forecast makes an unreachable goal indistinguishable from one reached
        // in year 31, i.e. "not on track" where "beyond horizon" is correct.
        val fullHorizon = DividendMath.incomeForecast(
            positions = lastPositions,
            pricesBySymbol = lastPricesBySymbol,
            eventsBySymbol = lastEventsBySymbol,
            years = GoalMath.HORIZON_YEARS.toInt(),
            dripEnabled = dripEnabled,
            asOfEpochSeconds = asOf,
        )
        // The SAME measure as forecast year 1 — both are projectedAnnualIncome's sum of
        // trailingAnnualPerShare x shares — so the card's progress % and ETA agree with what the
        // chart beside it shows for year 1 (carry-notes §3.1).
        val current = DividendMath.projectedAnnualIncome(lastPositions, lastEventsBySymbol, asOf)
        val projection = GoalMath.incomeProjection(current, goal.target, fullHorizon)
        _state.update { it.copy(incomeGoal = goalCardUi(goal, current, projection)) }
    }

    // MARK: - Dividend calendar

    /** Groups the next 365 days of ESTIMATED payouts into ascending UTC month buckets. */
    private fun buildCalendar(
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        asOfEpochSeconds: Long,
    ): List<CalendarMonth> {
        val scheduled = DividendMath.projectedSchedule(
            positions = positions,
            eventsBySymbol = eventsBySymbol,
            throughEpochSeconds = asOfEpochSeconds + 365 * SECONDS_PER_DAY,
            asOfEpochSeconds = asOfEpochSeconds,
        )
        if (scheduled.isEmpty()) return emptyList()

        val grouped = linkedMapOf<String, MutableList<ScheduledDividend>>()
        for (row in scheduled) {
            grouped.getOrPut(monthKey(row.exDateEpochSeconds)) { mutableListOf() } += row
        }
        return grouped.entries.sortedBy { it.key }.map { (key, rows) ->
            val currency = rows.first().estimatedAmount.currencyCode
            CalendarMonth(
                id = key,
                rows = rows,
                total = Money(rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.estimatedAmount.amount }, currency),
            )
        }
    }

    // MARK: - History

    private fun buildHistory(transactions: List<Transaction>, calendar: MarketCalendar): List<HistoryEntry> {
        val dividendTxns = transactions.filter { it.side == TradeSide.Dividend }
        return dividendTxns.map { txn ->
            val total = Money(txn.price.amount * txn.quantity, txn.price.currencyCode)
            val tradingDay = calendar.tradingDay(txn.epochSeconds)
            val wasReinvested = transactions.any { other ->
                other.side == TradeSide.Buy && other.isDrip && other.symbol == txn.symbol &&
                    calendar.tradingDay(other.epochSeconds) == tradingDay
            }
            HistoryEntry(
                id = txn.id,
                epochSeconds = txn.epochSeconds,
                symbol = txn.symbol,
                amountPerShare = txn.price,
                shares = txn.quantity,
                total = total,
                wasReinvested = wasReinvested,
            )
        }.sortedByDescending { it.epochSeconds }
    }

    // MARK: - Cards

    /** Sum of `Dividend` transaction cash in the current calendar year, UTC. */
    private fun receivedYTD(transactions: List<Transaction>, asOfEpochSeconds: Long): Money {
        val currentYear = utcYear(asOfEpochSeconds)
        var total = Money(BigDecimal.ZERO, "USD")
        for (txn in transactions) {
            if (txn.side != TradeSide.Dividend) continue
            if (utcYear(txn.epochSeconds) != currentYear) continue
            total += Money(txn.price.amount * txn.quantity, txn.price.currencyCode)
        }
        return total
    }

    private fun buildCards(
        portfolio: Portfolio,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        quotes: Map<String, Quote>,
        receivedYTD: Money,
        asOfEpochSeconds: Long,
    ): SummaryCards {
        val projectedAnnual = DividendMath.projectedAnnualIncome(
            portfolio.positions, eventsBySymbol, asOfEpochSeconds,
        )

        // Mirrors `Portfolio.valuation`'s quote-with-cost-basis-fallback treatment.
        var marketValue = BigDecimal.ZERO
        var costBasis = BigDecimal.ZERO
        for (position in portfolio.positions) {
            val q = position.quantity
            val quote = quotes[position.asset.symbol]
            marketValue += if (quote != null) quote.price.amount * q else position.averageCost.amount * q
            costBasis += position.averageCost.amount * q
        }

        val portfolioYield = if (marketValue > BigDecimal.ZERO) {
            projectedAnnual.amount.divide(marketValue, MONEY_MATH).doubleValue(false)
        } else {
            0.0
        }
        val yieldOnCost = if (costBasis > BigDecimal.ZERO) {
            projectedAnnual.amount.divide(costBasis, MONEY_MATH).doubleValue(false)
        } else {
            0.0
        }

        return SummaryCards(
            projectedAnnual = projectedAnnual,
            receivedYTD = receivedYTD,
            portfolioYield = portfolioYield,
            yieldOnCost = yieldOnCost,
        )
    }

    // MARK: - Months

    private fun buildMonths(
        transactions: List<Transaction>,
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        asOfEpochSeconds: Long,
    ): List<MonthBar> {
        val monthlyReceived = DividendMath.monthlyReceived(transactions)
        val currency = transactions.firstOrNull()?.price?.currencyCode ?: "USD"
        val receivedKeys = last12MonthKeys(asOfEpochSeconds)
        val receivedBars = receivedKeys.map { key ->
            MonthBar(
                id = key,
                amount = monthlyReceived[key] ?: Money(BigDecimal.ZERO, currency),
                isProjected = false,
            )
        }

        // Up to 3 distinct future months, aggregating each holding's single next-projected
        // payout (DividendMath.nextProjected only projects one step ahead per holding).
        val currentMonthKey = monthKey(asOfEpochSeconds)
        val projectedByMonth = linkedMapOf<String, BigDecimal>()
        var projectedCurrency = currency
        for (position in positions) {
            val events = eventsBySymbol[position.asset.symbol] ?: emptyList()
            val projected = DividendMath.nextProjected(events) ?: continue
            if (projected.exDateEpochSeconds <= asOfEpochSeconds) continue
            val key = monthKey(projected.exDateEpochSeconds)
            if (key <= currentMonthKey) continue
            val contribution = projected.amountPerShare.amount * position.quantity
            projectedByMonth[key] = (projectedByMonth[key] ?: BigDecimal.ZERO) + contribution
            projectedCurrency = projected.amountPerShare.currencyCode
        }
        val projectedBars = projectedByMonth.keys.sorted().take(3).map { key ->
            MonthBar(
                id = key,
                amount = Money(projectedByMonth[key] ?: BigDecimal.ZERO, projectedCurrency),
                isProjected = true,
            )
        }

        return receivedBars + projectedBars
    }

    // MARK: - Upcoming

    private fun buildUpcoming(
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        asOfEpochSeconds: Long,
    ): List<UpcomingRow> = positions.mapNotNull { position ->
        val events = eventsBySymbol[position.asset.symbol] ?: emptyList()
        val projected = DividendMath.nextProjected(events) ?: return@mapNotNull null
        if (projected.exDateEpochSeconds <= asOfEpochSeconds) return@mapNotNull null
        val amount = Money(
            projected.amountPerShare.amount * position.quantity,
            projected.amountPerShare.currencyCode,
        )
        UpcomingRow(
            id = position.asset.symbol,
            symbol = position.asset.symbol,
            estimatedExDateEpochSeconds = projected.exDateEpochSeconds,
            estimatedAmount = amount,
        )
    }

    // MARK: - Holdings

    private fun buildHoldings(
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        transactions: List<Transaction>,
        asOfEpochSeconds: Long,
    ): List<HoldingRow> = positions.map { position ->
        val symbol = position.asset.symbol
        val events = eventsBySymbol[symbol] ?: emptyList()
        val perShare = DividendMath.trailingAnnualPerShare(events, asOfEpochSeconds)
        val annualIncome = Money(perShare.amount * position.quantity, perShare.currencyCode)

        val costBasis = position.averageCost.amount * position.quantity
        val yieldOnCost = if (costBasis > BigDecimal.ZERO) {
            annualIncome.amount.divide(costBasis, MONEY_MATH).doubleValue(false)
        } else {
            0.0
        }

        val lastPaymentTxn = transactions
            .filter { it.side == TradeSide.Dividend && it.symbol == symbol }
            .maxByOrNull { it.epochSeconds }
        val lastPayment = lastPaymentTxn?.let { Money(it.price.amount * it.quantity, it.price.currencyCode) }

        HoldingRow(
            id = symbol,
            symbol = symbol,
            shares = position.quantity,
            annualIncome = annualIncome,
            yieldOnCost = yieldOnCost,
            lastPayment = lastPayment,
        )
    }

    // MARK: - Date helpers

    /** How far back to fetch dividend events: two years covers the trailing-annual window
     *  (365d) plus enough history for cadence inference on slower-paying assets. */
    private fun lookbackStart(asOfEpochSeconds: Long): Long = asOfEpochSeconds - 730 * SECONDS_PER_DAY

    /** Ascending `"yyyy-MM"` keys for the 12 months ending at (and including) `asOf`'s
     *  month. Derived by decomposing `asOf` into a UTC civil year/month once, then walking
     *  the month index backward -- no repeated epoch-day round-trips needed since only the
     *  bucket KEY (not a full date) is required. */
    private fun last12MonthKeys(asOfEpochSeconds: Long): List<String> {
        val (year, month) = utcYearMonth(asOfEpochSeconds)
        return (11 downTo 0).map { offset ->
            var m = month - offset
            var y = year
            while (m <= 0) {
                m += 12
                y -= 1
            }
            formatMonthKey(y, m)
        }
    }

    // --- UTC epoch-day civil-date math (private copy) -------------------------------------
    // DividendMath.monthKey (shared/src/commonMain/kotlin/com/aptrade/shared/domain/
    // DividendMath.kt) already implements this exact Hinnant civil-date algorithm, privately
    // -- but per that file's own doc, the house precedent is each type keeps its OWN private
    // copy rather than widening visibility to share it (see also MarketCalendar.kt's
    // civilFromDays, PieSchedule.kt's, and PieContributionUseCases.kt's private
    // `fetchClosesByDay`, all replicated rather than shared for the same reason). Only the
    // minimal pieces this file needs (UTC epoch-seconds -> epoch-day -> civil year/month) are
    // reproduced here.

    private fun utcYear(epochSeconds: Long): Long = utcYearMonth(epochSeconds).first

    private fun monthKey(epochSeconds: Long): String {
        val (year, month) = utcYearMonth(epochSeconds)
        return formatMonthKey(year, month)
    }

    private fun formatMonthKey(year: Long, month: Int): String =
        "$year-${month.toString().padStart(2, '0')}"

    private fun utcYearMonth(epochSeconds: Long): Pair<Long, Int> {
        val epochDay = floorDiv(epochSeconds, SECONDS_PER_DAY)
        val (year, month, _) = civilFromDays(epochDay)
        return year to month
    }

    private fun floorDiv(x: Long, y: Long): Long {
        val q = x / y
        return if ((x xor y) < 0 && q * y != x) q - 1 else q
    }

    private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097L // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
        val mp = (5 * doy + 2) / 153 // [0, 11]
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt() // [1, 31]
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt() // [1, 12]
        val year = if (m <= 2) y + 1 else y
        return Triple(year, m, d)
    }
}
