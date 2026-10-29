package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.Reciter

@Entity(tableName = "reciters")
data class ReciterEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val serverUrl: String,
    val availableSurahsCount: Int,
    val availableSurahsCsv: String,
    val isCustom: Boolean,
    val subInfo: String
) {
    fun toReciter(): Reciter {
        val surahsSet = if (availableSurahsCsv.isBlank()) {
            emptySet()
        } else {
            availableSurahsCsv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        }
        return Reciter(
            id = id,
            name = name,
            serverUrl = serverUrl,
            availableSurahsCount = availableSurahsCount,
            availableSurahsList = surahsSet,
            isCustom = isCustom,
            subInfo = subInfo
        )
    }

    companion object {
        fun fromReciter(reciter: Reciter): ReciterEntity {
            return ReciterEntity(
                id = reciter.id,
                name = reciter.name,
                serverUrl = reciter.serverUrl,
                availableSurahsCount = reciter.availableSurahsCount,
                availableSurahsCsv = reciter.availableSurahsList.joinToString(","),
                isCustom = reciter.isCustom,
                subInfo = reciter.subInfo
            )
        }
    }
}
