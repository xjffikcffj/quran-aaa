package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SurahStateDao {
    @Query("SELECT * FROM surah_states")
    fun getAllStates(): Flow<List<SurahStateEntity>>

    @Query("SELECT * FROM surah_states WHERE surahNumber = :surahNumber LIMIT 1")
    suspend fun getState(surahNumber: Int): SurahStateEntity?

    @Query("SELECT * FROM surah_states WHERE surahNumber = :surahNumber LIMIT 1")
    fun getStateFlow(surahNumber: Int): Flow<SurahStateEntity?>

    @Query("SELECT surahNumber FROM surah_states WHERE isFavorite = 1")
    fun getFavoriteSurahNumbers(): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: SurahStateEntity)

    @Query("UPDATE surah_states SET isFavorite = :isFavorite WHERE surahNumber = :surahNumber")
    suspend fun setFavorite(surahNumber: Int, isFavorite: Boolean)

    @Query("UPDATE surah_states SET lastPositionMs = :positionMs, lastPlayedTimestamp = :timestamp WHERE surahNumber = :surahNumber")
    suspend fun updateLastPosition(surahNumber: Int, positionMs: Long, timestamp: Long = System.currentTimeMillis())
}
