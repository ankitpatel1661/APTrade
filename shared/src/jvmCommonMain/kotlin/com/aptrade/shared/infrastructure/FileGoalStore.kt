package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.GoalStore
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
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

/** JSON-file goal persistence: one array under its own file, beside its sibling stores in
 *  `jvmCommonMain` so desktop AND Android share it (there is no `UserDefaults` equivalent here —
 *  everything JVM-side is file-backed via `ConfigDir`). Writes are atomic (temp file +
 *  ATOMIC_MOVE). A missing file, a corrupt payload, or an unknown [GoalKind] all load as an
 *  EMPTY list rather than throwing: a goal is an aspiration, not accounting state, so losing one
 *  must never take the app down the way a dropped transaction would corrupt cash balances. */
class FileGoalStore(private val file: Path) : GoalStore {

    @Serializable
    private data class MoneyDTO(val amount: String, val currency: String)

    @Serializable
    private data class GoalDTO(val kind: String, val target: MoneyDTO, val createdAtEpochSeconds: Long)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun load(): List<PortfolioGoal> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<GoalDTO>>(file.readText()).map { dto ->
                val kind = GoalKind.entries.firstOrNull { it.name == dto.kind }
                    ?: return@withContext emptyList()
                PortfolioGoal(
                    kind = kind,
                    target = Money(BigDecimal.parseString(dto.target.amount), dto.target.currency),
                    createdAtEpochSeconds = dto.createdAtEpochSeconds,
                )
            }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    override suspend fun save(goals: List<PortfolioGoal>) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dto = goals.map {
            GoalDTO(
                kind = it.kind.name,
                target = MoneyDTO(it.target.amount.toStringExpanded(), it.target.currencyCode),
                createdAtEpochSeconds = it.createdAtEpochSeconds,
            )
        }
        val text = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GoalDTO.serializer()), dto)
        val temp = Files.createTempFile(file.parent, "goals", ".tmp")
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it here —
        // this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
