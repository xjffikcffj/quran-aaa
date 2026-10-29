package com.example.download

import android.content.Context
import com.example.QuranApp
import com.example.data.QuranData
import com.example.data.local.DownloadEntity
import com.example.model.DownloadItem
import com.example.model.DownloadStatus
import com.example.model.Reciter
import com.example.model.Surah
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadsDir: File
        get() {
            val dir = File(context.filesDir, "quran_downloads")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun getLocalAudioFile(reciterId: String, surahNumber: Int): File {
        return File(downloadsDir, "quran_${reciterId}_${String.format("%03d", surahNumber)}.mp3")
    }

    fun isDownloaded(reciterId: String, surahNumber: Int): Boolean {
        val file = getLocalAudioFile(reciterId, surahNumber)
        return file.exists() && file.length() > 50000L
    }

    fun getAllDownloads(): Flow<List<DownloadItem>> {
        return QuranApp.database.downloadDao().getAllDownloads().map { list ->
            list.map { it.toDownloadItem() }
        }
    }

    fun startDownload(reciter: Reciter, surah: Surah) {
        val downloadId = "${reciter.id}_${surah.id}"

        // Cancel existing job if running
        activeJobs[downloadId]?.cancel()

        val job = scope.launch {
            val audioUrl = reciter.getAudioUrl(surah.id)
            val finalFile = getLocalAudioFile(reciter.id, surah.id)
            val tempFile = File(downloadsDir, "temp_${reciter.id}_${surah.id}.tmp")

            // Insert initial DOWNLOADING state
            val initialEntity = DownloadEntity(
                id = downloadId,
                surahNumber = surah.id,
                surahName = surah.name,
                reciterId = reciter.id,
                reciterName = reciter.name,
                localFilePath = finalFile.absolutePath,
                fileSizeBytes = 0L,
                status = DownloadStatus.DOWNLOADING.name,
                progress = 0,
                errorMessage = null
            )
            QuranApp.database.downloadDao().insertOrUpdate(initialEntity)

            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                val request = Request.Builder()
                    .url(audioUrl)
                    .header("User-Agent", "QuranAudioApp/1.0")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                }

                val body = response.body ?: throw IllegalStateException("استجابة الخادم فارغة")
                val totalBytes = body.contentLength()
                inputStream = body.byteStream()
                outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                var downloadedBytes = 0L
                var lastReportedPercent = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    if (totalBytes > 0) {
                        val currentPercent = ((downloadedBytes * 100) / totalBytes).toInt()
                        if (currentPercent >= lastReportedPercent + 5 || currentPercent == 100) {
                            lastReportedPercent = currentPercent
                            QuranApp.database.downloadDao().insertOrUpdate(
                                initialEntity.copy(
                                    progress = currentPercent,
                                    fileSizeBytes = downloadedBytes
                                )
                            )
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                // File integrity verification
                if (tempFile.length() < 30000L) {
                    tempFile.delete()
                    throw IllegalStateException("الملف غير مكتمل أو تالف")
                }

                if (finalFile.exists()) finalFile.delete()
                val renamed = tempFile.renameTo(finalFile)
                if (!renamed) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                // Mark COMPLETED
                QuranApp.database.downloadDao().insertOrUpdate(
                    initialEntity.copy(
                        status = DownloadStatus.COMPLETED.name,
                        progress = 100,
                        fileSizeBytes = finalFile.length(),
                        errorMessage = null
                    )
                )

            } catch (e: CancellationException) {
                if (tempFile.exists()) tempFile.delete()
                QuranApp.database.downloadDao().deleteById(downloadId)
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                QuranApp.database.downloadDao().insertOrUpdate(
                    initialEntity.copy(
                        status = DownloadStatus.FAILED.name,
                        errorMessage = e.localizedMessage ?: "فشل التنزيل"
                    )
                )
            } finally {
                activeJobs.remove(downloadId)
            }
        }

        activeJobs[downloadId] = job
    }

    fun cancelDownload(downloadId: String) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        scope.launch {
            QuranApp.database.downloadDao().deleteById(downloadId)
        }
    }

    fun deleteDownload(downloadId: String) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        scope.launch {
            val entity = QuranApp.database.downloadDao().getDownloadById(downloadId)
            if (entity != null) {
                val file = File(entity.localFilePath)
                if (file.exists()) file.delete()
                QuranApp.database.downloadDao().deleteById(downloadId)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return instance ?: synchronized(this) {
                instance ?: DownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
