package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "surah_states")
data class SurahStateEntity(
    @PrimaryKey
    val surahNumber: Int,
    val lastPositionMs: Long = 0L,
    val lastPlayedTimestamp: Long = 0L,
    val isFavorite: Boolean = false
)
