package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.DownloadItem
import com.example.model.DownloadStatus

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey
    val id: String, // "${reciterId}_${surahNumber}"
    val surahNumber: Int,
    val surahName: String,
    val reciterId: String,
    val reciterName: String,
    val localFilePath: String,
    val fileSizeBytes: Long,
    val status: String,
    val progress: Int,
    val errorMessage: String?
) {
    fun toDownloadItem(): DownloadItem {
        val s = try {
            DownloadStatus.valueOf(status)
        } catch (_: Exception) {
            DownloadStatus.IDLE
        }
        return DownloadItem(
            id = id,
            surahNumber = surahNumber,
            surahName = surahName,
            reciterId = reciterId,
            reciterName = reciterName,
            localFilePath = localFilePath,
            fileSizeBytes = fileSizeBytes,
            status = s,
            progress = progress,
            errorMessage = errorMessage
        )
    }

    companion object {
        fun fromDownloadItem(d: DownloadItem): DownloadEntity {
            return DownloadEntity(
                id = d.id,
                surahNumber = d.surahNumber,
                surahName = d.surahName,
                reciterId = d.reciterId,
                reciterName = d.reciterName,
                localFilePath = d.localFilePath,
                fileSizeBytes = d.fileSizeBytes,
                status = d.status.name,
                progress = d.progress,
                errorMessage = d.errorMessage
            )
        }
    }
}
