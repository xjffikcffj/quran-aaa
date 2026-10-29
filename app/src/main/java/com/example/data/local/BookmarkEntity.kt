package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.Bookmark

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val surahNumber: Int,
    val surahName: String,
    val reciterId: String,
    val reciterName: String,
    val positionMs: Long,
    val note: String,
    val timestamp: Long
) {
    fun toBookmark(): Bookmark {
        return Bookmark(
            id = id,
            surahNumber = surahNumber,
            surahName = surahName,
            reciterId = reciterId,
            reciterName = reciterName,
            positionMs = positionMs,
            note = note,
            timestamp = timestamp
        )
    }

    companion object {
        fun fromBookmark(b: Bookmark): BookmarkEntity {
            return BookmarkEntity(
                id = b.id,
                surahNumber = b.surahNumber,
                surahName = b.surahName,
                reciterId = b.reciterId,
                reciterName = b.reciterName,
                positionMs = b.positionMs,
                note = b.note,
                timestamp = b.timestamp
            )
        }
    }
}
