package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReciterDao {
    @Query("SELECT * FROM reciters ORDER BY isCustom ASC, id ASC")
    fun getAllReciters(): Flow<List<ReciterEntity>>

    @Query("SELECT * FROM reciters WHERE id = :id LIMIT 1")
    suspend fun getReciterById(id: String): ReciterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReciter(reciter: ReciterEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(reciters: List<ReciterEntity>)

    @Update
    suspend fun updateReciter(reciter: ReciterEntity)

    @Delete
    suspend fun deleteReciter(reciter: ReciterEntity)

    @Query("DELETE FROM reciters WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustomReciter(id: String)

    @Query("DELETE FROM reciters WHERE id = :id")
    suspend fun deleteById(id: String)
}
