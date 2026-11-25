package com.example.model

data class Bookmark(
    val id: Long = 0,
    val surahNumber: Int,
    val surahName: String,
    val reciterId: String,
    val reciterName: String,
    val positionMs: Long,
    val note: String,
    val timestamp: Long = System.currentTimeMillis()
)
