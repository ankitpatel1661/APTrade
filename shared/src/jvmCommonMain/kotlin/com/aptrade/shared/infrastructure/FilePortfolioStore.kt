package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.Transaction
import com.aptrade.shared.domain.TradeSide
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** JSON-file portfolio. Writes are atomic: temp file + rename, so a crash mid-save
 *  can never leave a half-written portfolio. Missing file loads null; corrupt file loads null.
 *  Unlike FileWatchlistStore, a portfolio with an unknown AssetKind or TradeSide loads as null
 *  (not row-skipped), since the entire portfolio's cash accounting depends on every transaction,
 *  and a dropped transaction would corrupt the balances.
 *
 *  Lives in the `jvmCommon` intermediate source set (java.nio), shared by the JVM (desktop)
 *  and Android targets — verified portable to Android API 26+. */
class FilePortfolioStore(private val file: Path) : PortfolioStore {

    @Serializable
    private data class MoneyDTO(val amount: String, val currency: String)

    @Serializable
    private data class AssetDTO(val symbol: String, val name: String, val kind: String)

    @Serializable
    private data class PositionDTO(
        val asset: AssetDTO,
        val quantity: String,
        val averageCost: MoneyDTO,
        val realizedPnL: MoneyDTO,
    )

    @Serializable
    private data class TransactionDTO(
        val id: String,
        val symbol: String,
        val side: String,
        val quantity: String,
        val price: MoneyDTO,
        val epochSeconds: Long,
        val pieId: String? = null,
    )

    @Serializable
    private data class PortfolioDTO(
        val cash: MoneyDTO,
        val positions: List<PositionDTO>,
        val transactions: List<TransactionDTO>,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun load(): Portfolio? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            val dto = json.decodeFromString<PortfolioDTO>(file.readText())

            val cash = Money(
                amount = BigDecimal.parseString(dto.cash.amount),
                currencyCode = dto.cash.currency,
            )

            val positions = dto.positions.map { posDto ->
                val kind = AssetKind.entries.firstOrNull { it.name == posDto.asset.kind }
                    ?: return@withContext null  // unknown kind: entire file is corrupt (non-local return — aborts the WHOLE load to null, not a per-row skip)
                val asset = Asset(posDto.asset.symbol, posDto.asset.name, kind)
                val quantity = BigDecimal.parseString(posDto.quantity)
                val averageCost = Money(
                    amount = BigDecimal.parseString(posDto.averageCost.amount),
                    currencyCode = posDto.averageCost.currency,
                )
                val realizedPnL = Money(
                    amount = BigDecimal.parseString(posDto.realizedPnL.amount),
                    currencyCode = posDto.realizedPnL.currency,
                )
                Position(asset, quantity, averageCost, realizedPnL)
            }

            val transactions = dto.transactions.map { txnDto ->
                val side = TradeSide.entries.firstOrNull { it.name == txnDto.side }
                    ?: return@withContext null  // unknown side: entire file is corrupt (would break cash accounting) (non-local return — aborts the WHOLE load to null, not a per-row skip)
                val price = Money(
                    amount = BigDecimal.parseString(txnDto.price.amount),
                    currencyCode = txnDto.price.currency,
                )
                Transaction(txnDto.id, txnDto.symbol, side, BigDecimal.parseString(txnDto.quantity), price, txnDto.epochSeconds, txnDto.pieId)
            }

            Portfolio(cash, positions, transactions)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun save(portfolio: Portfolio) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dto = PortfolioDTO(
            cash = MoneyDTO(portfolio.cash.amount.toStringExpanded(), portfolio.cash.currencyCode),
            positions = portfolio.positions.map { pos ->
                PositionDTO(
                    asset = AssetDTO(pos.asset.symbol, pos.asset.name, pos.asset.kind.name),
                    quantity = pos.quantity.toStringExpanded(),
                    averageCost = MoneyDTO(pos.averageCost.amount.toStringExpanded(), pos.averageCost.currencyCode),
                    realizedPnL = MoneyDTO(pos.realizedPnL.amount.toStringExpanded(), pos.realizedPnL.currencyCode),
                )
            },
            transactions = portfolio.transactions.map { txn ->
                TransactionDTO(
                    id = txn.id,
                    symbol = txn.symbol,
                    side = txn.side.name,
                    quantity = txn.quantity.toStringExpanded(),
                    price = MoneyDTO(txn.price.amount.toStringExpanded(), txn.price.currencyCode),
                    epochSeconds = txn.epochSeconds,
                    pieId = txn.pieId,
                )
            },
        )
        val text = json.encodeToString(PortfolioDTO.serializer(), dto)
        val temp = Files.createTempFile(file.parent, "portfolio", ".tmp")
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it
        // here — this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
