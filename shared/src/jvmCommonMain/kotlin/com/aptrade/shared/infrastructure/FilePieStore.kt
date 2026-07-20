package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.PieStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityEntry
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieError
import com.aptrade.shared.domain.PieLedgerEntry
import com.aptrade.shared.domain.PieSlice
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * JSON-file Investment Plans (Pies) — `pies.json`. Follows `FilePortfolioStore`'s and
 * `FileAlertStore`'s whole-blob philosophy: a decode failure of ANY kind (malformed JSON,
 * an unrecognized [PieCadence]/[PieActivityKind]/[AssetKind] raw value, or a stored pie
 * that fails [Pie.create]'s validation, e.g. weights that no longer sum to 100) discards
 * the ENTIRE load and returns an empty list — never a partial, per-row skip — mirroring
 * Swift's `UserDefaultsPieStore.load()`, whose `try? JSONDecoder().decode([Pie].self,
 * from: data)` fails the whole array decode the instant any single element's `Pie.init`
 * throws (`Pie`'s `Codable` `init(from:)` routes through the same validating initializer
 * as direct construction — see `Sources/APTradeDomain/Pie.swift`). Critically, a failed
 * load never writes anything: the file on disk is left byte-for-byte untouched, so a
 * corrupted `pies.json` doesn't get silently replaced by an empty array on the next
 * `save` call by an unrelated code path that merely `load()`ed first.
 *
 * Top-level JSON shape is a bare array (`[ {...}, {...} ]`), not wrapped in an object —
 * matching Swift's `JSONEncoder().encode(pies)` / `Sources/APTradeInfrastructure/
 * UserDefaultsPieStore.swift`, and per this plan's Global Constraints (spec §C).
 *
 * DTO FIELD NAMES converge with what Swift's `Codable` synthesis produces for `Pie` (see
 * `Sources/APTradeDomain/Pie.swift`): `id`/`name`/`slices`/`schedule`/`createdDay`/
 * `ledger`/`activity` at the top level; `slices[].symbol`/`assetKind`/`targetWeight.value`
 * (`Percentage` nests under a `value` key); `schedule.amount.amount`/`amount.currencyCode`
 * (`Money` nests under `amount`/`currencyCode` — NOT `currency`, unlike this codebase's
 * other `MoneyDTO`s in `FilePortfolioStore`/`FileAlertStore`, which predate this
 * convergence requirement and are out of Task 6's scope to touch)/`cadence`/`anchorDay`/
 * `nextDueDay`; `ledger[].symbol`/`quantity.amount` (`Quantity` nests under `amount`);
 * `activity[].id`/`kind`/`day`/`amount`. [PieCadence] and [PieActivityKind] raw values are
 * mapped EXPLICITLY to Swift's lowercase `RawRepresentable` strings (`weekly`/`biweekly`/
 * `monthly`; `contribution`/`rebalance`/`missedInsufficientCash`/`manualAdjustment`) since
 * Kotlin enum constants are PascalCase and would otherwise serialize as `Weekly` etc.
 *
 * VALUE ENCODING deliberately DIVERGES from Swift, following `FilePortfolioStore`'s
 * existing precedent: every decimal (`Percentage.value`, `Money.amount`, `Quantity.amount`)
 * is written as a JSON STRING (`"229.35"`), not a JSON number as Swift's `JSONEncoder`
 * produces for `Decimal`. This keeps precision exact across Kotlin's `BigDecimal` without
 * relying on JSON-number/double round-tripping. Cross-platform file exchange between the
 * Swift and Kotlin stores is explicitly NOT a requirement (this plan's Global Constraints,
 * spec §C) — field-NAME convergence here is preparation for a *future* unification, not a
 * promise that a `pies.json` written by one platform parses on the other today.
 *
 * `schedule.anchorDay` is decoded leniently: a stored schedule missing the `anchorDay` key
 * (data that predates the field) falls back to `nextDueDay`, mirroring
 * `ContributionSchedule.init(from:)` in the Swift original.
 *
 * Lives in the `jvmCommon` intermediate source set (java.nio), shared by the JVM (desktop)
 * and Android targets — same portability shape as `FilePortfolioStore`/`FileAlertStore`.
 */
class FilePieStore(private val file: Path) : PieStore {

    @Serializable
    private data class MoneyDTO(val amount: String, val currencyCode: String)

    @Serializable
    private data class PercentageDTO(val value: String)

    @Serializable
    private data class QuantityDTO(val amount: String)

    @Serializable
    private data class SliceDTO(
        val symbol: String,
        val assetKind: String,
        val targetWeight: PercentageDTO,
    )

    @Serializable
    private data class ScheduleDTO(
        val amount: MoneyDTO,
        val cadence: String,
        val anchorDay: String? = null,
        val nextDueDay: String,
    )

    @Serializable
    private data class LedgerEntryDTO(
        val symbol: String,
        val quantity: QuantityDTO,
    )

    @Serializable
    private data class ActivityEntryDTO(
        val id: String,
        val kind: String,
        val day: String,
        val amount: MoneyDTO? = null,
    )

    @Serializable
    private data class PieDTO(
        val id: String,
        val name: String,
        val slices: List<SliceDTO>,
        val schedule: ScheduleDTO? = null,
        val createdDay: String,
        val ledger: List<LedgerEntryDTO> = emptyList(),
        val activity: List<ActivityEntryDTO> = emptyList(),
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val dtoListSerializer = ListSerializer(PieDTO.serializer())

    override suspend fun load(): List<Pie> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val dtos = json.decodeFromString(dtoListSerializer, file.readText())

            dtos.map { dto ->
                val slices = dto.slices.map { sliceDto ->
                    val kind = AssetKind.entries.firstOrNull { it.name == sliceDto.assetKind }
                        ?: return@withContext emptyList() // unknown AssetKind: whole file untrusted
                    PieSlice(
                        symbol = sliceDto.symbol,
                        assetKind = kind,
                        targetWeightPP = BigDecimal.parseString(sliceDto.targetWeight.value),
                    )
                }

                val schedule = dto.schedule?.let { scheduleDto ->
                    val cadence = cadenceFromRaw(scheduleDto.cadence)
                        ?: return@withContext emptyList() // unknown PieCadence: whole file untrusted
                    ContributionSchedule(
                        amount = Money(
                            amount = BigDecimal.parseString(scheduleDto.amount.amount),
                            currencyCode = scheduleDto.amount.currencyCode,
                        ),
                        cadence = cadence,
                        anchorDay = scheduleDto.anchorDay ?: scheduleDto.nextDueDay,
                        nextDueDay = scheduleDto.nextDueDay,
                    )
                }

                val ledger = dto.ledger.map { entryDto ->
                    PieLedgerEntry(
                        symbol = entryDto.symbol,
                        quantity = BigDecimal.parseString(entryDto.quantity.amount),
                    )
                }

                val activity = dto.activity.map { entryDto ->
                    val kind = activityKindFromRaw(entryDto.kind)
                        ?: return@withContext emptyList() // unknown PieActivityKind: whole file untrusted
                    PieActivityEntry(
                        id = entryDto.id,
                        kind = kind,
                        day = entryDto.day,
                        amount = entryDto.amount?.let {
                            Money(amount = BigDecimal.parseString(it.amount), currencyCode = it.currencyCode)
                        },
                    )
                }

                // Routes through the validating factory — a stored pie whose weights no
                // longer sum to 100 (or has empty/duplicate slices) throws PieError,
                // caught below, discarding the WHOLE load (not just this pie).
                Pie.create(
                    id = dto.id,
                    name = dto.name,
                    slices = slices,
                    schedule = schedule,
                    createdDay = dto.createdDay,
                    ledger = ledger,
                    activity = activity,
                )
            }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        } catch (e: PieError) {
            emptyList()
        }
    }

    override suspend fun save(pies: List<Pie>) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dtos = pies.map { pie ->
            PieDTO(
                id = pie.id,
                name = pie.name,
                slices = pie.slices.map { slice ->
                    SliceDTO(
                        symbol = slice.symbol,
                        assetKind = slice.assetKind.name,
                        targetWeight = PercentageDTO(slice.targetWeightPP.toStringExpanded()),
                    )
                },
                schedule = pie.schedule?.let { schedule ->
                    ScheduleDTO(
                        amount = MoneyDTO(schedule.amount.amount.toStringExpanded(), schedule.amount.currencyCode),
                        cadence = schedule.cadence.toRaw(),
                        anchorDay = schedule.anchorDay,
                        nextDueDay = schedule.nextDueDay,
                    )
                },
                createdDay = pie.createdDay,
                ledger = pie.ledger.map { entry ->
                    LedgerEntryDTO(
                        symbol = entry.symbol,
                        quantity = QuantityDTO(entry.quantity.toStringExpanded()),
                    )
                },
                activity = pie.activity.map { entry ->
                    ActivityEntryDTO(
                        id = entry.id,
                        kind = entry.kind.toRaw(),
                        day = entry.day,
                        amount = entry.amount?.let { MoneyDTO(it.amount.toStringExpanded(), it.currencyCode) },
                    )
                },
            )
        }
        val text = json.encodeToString(dtoListSerializer, dtos)
        val temp = Files.createTempFile(file.parent, "pies", ".tmp")
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it
        // here — this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    private fun PieCadence.toRaw(): String = when (this) {
        PieCadence.Weekly -> "weekly"
        PieCadence.Biweekly -> "biweekly"
        PieCadence.Monthly -> "monthly"
    }

    private fun cadenceFromRaw(raw: String): PieCadence? = when (raw) {
        "weekly" -> PieCadence.Weekly
        "biweekly" -> PieCadence.Biweekly
        "monthly" -> PieCadence.Monthly
        else -> null
    }

    private fun PieActivityKind.toRaw(): String = when (this) {
        PieActivityKind.Contribution -> "contribution"
        PieActivityKind.Rebalance -> "rebalance"
        PieActivityKind.MissedInsufficientCash -> "missedInsufficientCash"
        PieActivityKind.ManualAdjustment -> "manualAdjustment"
    }

    private fun activityKindFromRaw(raw: String): PieActivityKind? = when (raw) {
        "contribution" -> PieActivityKind.Contribution
        "rebalance" -> PieActivityKind.Rebalance
        "missedInsufficientCash" -> PieActivityKind.MissedInsufficientCash
        "manualAdjustment" -> PieActivityKind.ManualAdjustment
        else -> null
    }
}
