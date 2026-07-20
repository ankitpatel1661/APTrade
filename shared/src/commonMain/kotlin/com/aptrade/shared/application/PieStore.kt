package com.aptrade.shared.application

import com.aptrade.shared.domain.Pie

/** Persistence port for the user's Investment Plans (Pies). Implementations live per
 *  platform (JSON file on desktop). Load returns an empty list when nothing was ever
 *  saved, and also when the stored data is corrupt/unparseable — mirroring
 *  `UserDefaultsPieStore`'s whole-blob fallback (`Sources/APTradeInfrastructure/
 *  UserDefaultsPieStore.swift`): a decode failure never overwrites what's on disk, it
 *  just reads back as empty until a fresh save replaces it. */
interface PieStore {
    suspend fun load(): List<Pie>
    suspend fun save(pies: List<Pie>)
}
