package com.aptrade.desktop.portfolio

import com.aptrade.desktop.designkit.kindLabel
import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.FetchPortfolioPerformance
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AllocationSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioExport
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.TradeError
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.allocationByHolding
import com.aptrade.shared.domain.allocationByKind
import com.aptrade.shared.domain.realizedPnL
import com.aptrade.shared.domain.renderCsv
import com.aptrade.shared.domain.renderJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The portfolio P&L chart's time span. Mirrors the asset-detail timeframes but adds a
 *  **Max** option that runs since the portfolio's first purchase. */
enum class PortfolioSpan(val label: String) {
    Day("1D"), Week("1W"), Month("1M"), Year("1Y"), Max("MAX");

    val timeframe: Timeframe
        get() = when (this) {
            Day -> Timeframe.OneDay
            Week -> Timeframe.OneWeek
            Month -> Timeframe.OneMonth
            Year, Max -> Timeframe.OneYear
        }

    /** Max trims the curve to begin at the first transaction (the portfolio's inception). */
    val sinceInception: Boolean get() = this == Max
}

data class HoldingRowUi(
    val symbol: String,
    val name: String,
    val kindLabel: String,
    val quantityText: String,
    val averageCostText: String,
    val marketValueText: String,
    val unrealizedText: String,
    val unrealizedPositive: Boolean?,
    val priceText: String?,
)

data class TransactionRowUi(
    val id: String,
    val symbol: String,
    val sideLabel: String,
    val isBuy: Boolean,
    val quantityText: String,
    val priceText: String,
    val epochSeconds: Long,
)

data class PortfolioUiState(
    val isLoading: Boolean = true,
    val totalValueText: String? = null,
    val dayChangeText: String? = null,
    val dayChangePositive: Boolean? = null,
    val cashText: String? = null,
    val holdingsValueText: String? = null,
    val unrealizedText: String? = null,
    val unrealizedPositive: Boolean? = null,
    val realizedText: String? = null,
    val realizedPositive: Boolean? = null,
    val holdings: List<HoldingRowUi> = emptyList(),
    val allocationByHolding: List<AllocationSlice> = emptyList(),
    val allocationByKind: List<AllocationSlice> = emptyList(),
    val transactions: List<TransactionRowUi> = emptyList(),
    val performance: List<Double> = emptyList(),
    val isLoadingPerformance: Boolean = false,
    val span: PortfolioSpan = PortfolioSpan.Month,
    val error: String? = null,
    val tradeError: String? = null,
)

private fun sideLabel(side: TradeSide): String = when (side) {
    TradeSide.Buy -> "Buy"
    TradeSide.Sell -> "Sell"
}

/** Owns the current paper-trading portfolio: valuation, allocation, trade execution, the
 *  performance chart, and export. Polls held symbols' quotes every `tickMillis` (the
 *  standard 15s app-wide cadence) so open positions stay live. `scope` MUST be
 *  single-thread-confined (Dispatchers.Main on desktop): the internal portfolio/quotes vars
 *  rely on that confinement instead of locks. */
class PortfolioViewModel(
    private val fetchPortfolio: FetchPortfolio,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val buyAsset: BuyAsset,
    private val sellAsset: SellAsset,
    private val resetPortfolio: ResetPortfolio,
    private val fetchPortfolioPerformance: FetchPortfolioPerformance,
    private val scope: CoroutineScope,
    private val tickMillis: Long = 15_000,
    private val nowEpochSeconds: () -> Long,
) {
    private val _state = MutableStateFlow(PortfolioUiState())
    val state: StateFlow<PortfolioUiState> = _state

    private var portfolio: Portfolio = Portfolio.starting()
    private var quotes: Map<String, Quote> = emptyMap()
    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            portfolio = fetchPortfolio.execute()
            var tick = 0
            while (isActive) {
                refreshQuotes()
                publish(loading = false)
                if (tick == 0) loadPerformance()
                tick++
                delay(tickMillis)
            }
        }
    }

    fun refresh() {
        scope.launch { refreshQuotes(); publish(loading = false) }
    }

    fun setSpan(span: PortfolioSpan) {
        if (_state.value.span == span) return
        _state.update { it.copy(span = span) }
        loadPerformance()
    }

    fun buy(asset: Asset, quantityText: String) {
        val form = TradeFormState(TradeSide.Buy, quotes[asset.symbol]?.price?.amountText, quantityText)
        val quantity = form.parsedQuantity()
        if (quantity == null) {
            _state.update { it.copy(tradeError = TradeError.InvalidQuantity.userMessage()) }
            return
        }
        scope.launch {
            try {
                portfolio = buyAsset.execute(asset, quantity, nowEpochSeconds())
                refreshQuotes()
                _state.update { it.copy(tradeError = null) }
                publish(loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TradeError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            } catch (e: QuoteError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            }
        }
    }

    fun sell(symbol: String, quantityText: String) {
        val form = TradeFormState(TradeSide.Sell, quotes[symbol]?.price?.amountText, quantityText)
        val quantity = form.parsedQuantity()
        if (quantity == null) {
            _state.update { it.copy(tradeError = TradeError.InvalidQuantity.userMessage()) }
            return
        }
        scope.launch {
            try {
                portfolio = sellAsset.execute(symbol, quantity, nowEpochSeconds())
                refreshQuotes()
                _state.update { it.copy(tradeError = null) }
                publish(loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TradeError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            } catch (e: QuoteError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            }
        }
    }

    fun reset() {
        scope.launch {
            portfolio = resetPortfolio.execute()
            quotes = emptyMap()
            _state.update { it.copy(performance = emptyList()) }
            publish(loading = false)
        }
    }

    fun exportCsv(): String = PortfolioExport.from(portfolio, quotes, "APTrade", nowEpochSeconds()).renderCsv()

    fun exportJson(): String = PortfolioExport.from(portfolio, quotes, "APTrade", nowEpochSeconds()).renderJson()

    private suspend fun refreshQuotes() {
        val symbols = portfolio.positions.map { it.asset.symbol }
        if (symbols.isEmpty()) { quotes = emptyMap(); return }
        try {
            val fetched = fetchMarketQuotes.execute(symbols)
            quotes = fetched.associateBy { it.symbol }
            _state.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuoteError) {
            _state.update { it.copy(error = e.userMessage()) }   // keep last-good quotes
        }
    }

    private fun loadPerformance() {
        val span = _state.value.span
        scope.launch {
            _state.update { it.copy(isLoadingPerformance = true) }
            val points = fetchPortfolioPerformance.execute(span.timeframe, span.sinceInception)
            _state.update { it.copy(isLoadingPerformance = false, performance = points.map { p -> p.value.amount.doubleValue(false) }) }
        }
    }

    private fun publish(loading: Boolean) {
        val valuation = portfolio.valuation(quotes)
        val holdingsUi = portfolio.positions
            .sortedByDescending { position ->
                val quote = quotes[position.asset.symbol]
                position.marketValue(quote?.price ?: position.averageCost).amount.doubleValue(false)
            }
            .map { position ->
                val quote = quotes[position.asset.symbol]
                val marketValue = position.marketValue(quote?.price ?: position.averageCost)
                val unrealized = position.unrealizedPnL(quote?.price ?: position.averageCost)
                HoldingRowUi(
                    symbol = position.asset.symbol,
                    name = position.asset.name,
                    kindLabel = kindLabel(position.asset.kind),
                    quantityText = position.quantity.toStringExpanded(),
                    averageCostText = position.averageCost.amountText,
                    marketValueText = marketValue.amountText,
                    unrealizedText = unrealized.amountText,
                    unrealizedPositive = quote?.let { unrealized.amount.doubleValue(false) >= 0.0 },
                    priceText = quote?.price?.amountText,
                )
            }

        val realized = portfolio.realizedPnL
        val transactionsUi = portfolio.transactions
            .sortedByDescending { it.epochSeconds }
            .map { txn ->
                TransactionRowUi(
                    id = txn.id,
                    symbol = txn.symbol,
                    sideLabel = sideLabel(txn.side),
                    isBuy = txn.side == TradeSide.Buy,
                    quantityText = txn.quantity.toStringExpanded(),
                    priceText = txn.price.amountText,
                    epochSeconds = txn.epochSeconds,
                )
            }

        _state.update {
            it.copy(
                isLoading = loading,
                totalValueText = valuation.totalValue.amountText,
                dayChangeText = valuation.dayChange.amountText,
                dayChangePositive = if (portfolio.positions.isEmpty()) null else valuation.dayChange.amount.doubleValue(false) >= 0.0,
                cashText = valuation.cash.amountText,
                holdingsValueText = valuation.holdingsValue.amountText,
                unrealizedText = valuation.unrealizedPnL.amountText,
                unrealizedPositive = if (portfolio.positions.isEmpty()) null else valuation.unrealizedPnL.amount.doubleValue(false) >= 0.0,
                realizedText = realized.amountText,
                realizedPositive = if (portfolio.transactions.isEmpty()) null else realized.amount.doubleValue(false) >= 0.0,
                holdings = holdingsUi,
                allocationByHolding = portfolio.allocationByHolding(quotes),
                allocationByKind = portfolio.allocationByKind(quotes),
                transactions = transactionsUi,
            )
        }
    }
}
