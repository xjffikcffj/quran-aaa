package com.example.model

enum class DownloadStatus {
    IDLE,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

data class DownloadItem(
    val id: String, // "${reciterId}_${surahNumber}"
    val surahNumber: Int,
    val surahName: String,
    val reciterId: String,
    val reciterName: String,
    val localFilePath: String,
    val fileSizeBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Int = 0,
    val errorMessage: String? = null
)
