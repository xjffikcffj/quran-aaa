package com.example.download

import android.content.Context
import com.example.QuranApp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManager(private val context: Context) {

    private class ActiveTask(
        val taskId: String,
        @Volatile var job: Job? = null,
        @Volatile var call: Call? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTasks = ConcurrentHashMap<String, ActiveTask>()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val downloadsDir: File
        get() {
            val dir = File(context.filesDir, "quran_downloads")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val tempDir: File
        get() {
            val dir = File(downloadsDir, "tmp")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    init {
        recoverStuckDownloads()
    }

    fun recoverStuckDownloads() {
        scope.launch {
            try {
                QuranApp.database.downloadDao().markStuckDownloadsFailed(
                    "توقف التنزيل قبل اكتماله، اضغط لإعادة المحاولة"
                )
            } catch (_: Exception) {}

            // Clean only orphaned temp files not referenced by any active task
            try {
                val activeTaskIds = activeTasks.values.map { it.taskId }.toSet()
                tempDir.listFiles()?.forEach { file ->
                    val matchesActive = activeTaskIds.any { id -> file.name.contains(id) }
                    if (!matchesActive) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun getLocalAudioFile(reciterId: String, surahNumber: Int): File {
        val padded = String.format(Locale.US, "%03d", surahNumber)
        return File(downloadsDir, "quran_${reciterId}_${padded}.mp3")
    }

    fun isDownloaded(reciterId: String, surahNumber: Int): Boolean {
        val file = getLocalAudioFile(reciterId, surahNumber)
        return file.exists() && file.length() >= MIN_VALID_AUDIO_BYTES
    }

    fun getAllDownloads(): Flow<List<DownloadItem>> {
        return QuranApp.database.downloadDao().getAllDownloads().map { list ->
            list.map { it.toDownloadItem() }
        }
    }

    fun startDownload(reciter: Reciter, surah: Surah) {
        if (!reciter.isSurahAvailable(surah.id)) {
            scope.launch {
                val downloadId = "${reciter.id}_${surah.id}"
                val finalFile = getLocalAudioFile(reciter.id, surah.id)
                QuranApp.database.downloadDao().insertOrUpdate(
                    DownloadEntity(
                        id = downloadId,
                        surahNumber = surah.id,
                        surahName = surah.name,
                        reciterId = reciter.id,
                        reciterName = reciter.name,
                        localFilePath = finalFile.absolutePath,
                        fileSizeBytes = 0L,
                        status = DownloadStatus.FAILED.name,
                        progress = 0,
                        errorMessage = "سورة ${surah.name} غير متوفرة لتسجيل ${reciter.name}"
                    )
                )
            }
            return
        }

        val downloadId = "${reciter.id}_${surah.id}"
        val taskId = UUID.randomUUID().toString()

        // Cancel any previous task and its live HTTP call immediately
        activeTasks.remove(downloadId)?.let { previous ->
            previous.call?.cancel()
            previous.job?.cancel()
        }

        val taskHolder = ActiveTask(taskId = taskId)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val audioUrl = reciter.getAudioUrl(surah.id)
            val finalFile = getLocalAudioFile(reciter.id, surah.id)
            val tempFile = File(tempDir, "${downloadId}_${taskId}.part")
            val hadValidExistingFile = finalFile.exists() && isValidMp3File(finalFile)

            val initialEntity = DownloadEntity(
                id = downloadId,
                surahNumber = surah.id,
                surahName = surah.name,
                reciterId = reciter.id,
                reciterName = reciter.name,
                localFilePath = finalFile.absolutePath,
                fileSizeBytes = if (hadValidExistingFile) finalFile.length() else 0L,
                status = if (hadValidExistingFile) DownloadStatus.COMPLETED.name else DownloadStatus.DOWNLOADING.name,
                progress = if (hadValidExistingFile) 100 else 0,
                errorMessage = null
            )

            // If completed valid file is already present, verify/preserve it without re-downloading
            if (hadValidExistingFile) {
                QuranApp.database.downloadDao().insertOrUpdate(initialEntity)
                activeTasks.remove(downloadId, taskHolder)
                return@launch
            }

            val downloadingEntity = initialEntity.copy(
                status = DownloadStatus.DOWNLOADING.name,
                progress = 0,
                fileSizeBytes = 0L,
                errorMessage = null
            )
            QuranApp.database.downloadDao().insertOrUpdate(downloadingEntity)

            try {
                downloadWithRetryAndFallback(
                    primaryUrl = audioUrl,
                    tempFile = tempFile,
                    taskHolder = taskHolder,
                    isCurrentTask = { activeTasks[downloadId]?.taskId == taskId },
                    onProgress = { progress, bytes ->
                        if (activeTasks[downloadId]?.taskId == taskId) {
                            QuranApp.database.downloadDao().insertOrUpdate(
                                downloadingEntity.copy(
                                    progress = progress,
                                    fileSizeBytes = bytes
                                )
                            )
                        }
                    }
                )

                // Guard against race: ensure this task wasn't superseded while writing
                if (activeTasks[downloadId]?.taskId != taskId) {
                    tempFile.delete()
                    return@launch
                }

                // Atomic move into final destination ONLY after verified MP3 integrity
                val backupFile = if (finalFile.exists()) {
                    File(tempDir, "${downloadId}_${taskId}.bak").also { bak ->
                        finalFile.copyTo(bak, overwrite = true)
                    }
                } else null

                if (finalFile.exists()) {
                    finalFile.delete()
                }

                val moved = tempFile.renameTo(finalFile)
                if (!moved) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                if (!isValidMp3File(finalFile)) {
                    finalFile.delete()
                    backupFile?.copyTo(finalFile, overwrite = true)
                    backupFile?.delete()
                    throw IllegalStateException("الملف الذي تم تنزيله غير صالح أو تالف")
                }
                backupFile?.delete()

                if (activeTasks[downloadId]?.taskId == taskId) {
                    QuranApp.database.downloadDao().insertOrUpdate(
                        downloadingEntity.copy(
                            status = DownloadStatus.COMPLETED.name,
                            progress = 100,
                            fileSizeBytes = finalFile.length(),
                            errorMessage = null
                        )
                    )
                }
            } catch (_: CancellationException) {
                tempFile.delete()
                if (activeTasks[downloadId]?.taskId == taskId) {
                    if (hadValidExistingFile && finalFile.exists()) {
                        QuranApp.database.downloadDao().insertOrUpdate(initialEntity)
                    } else {
                        QuranApp.database.downloadDao().deleteById(downloadId)
                    }
                }
            } catch (e: Exception) {
                tempFile.delete()
                if (activeTasks[downloadId]?.taskId == taskId) {
                    val userError = mapDownloadErrorMessage(e)
                    if (hadValidExistingFile && finalFile.exists()) {
                        QuranApp.database.downloadDao().insertOrUpdate(initialEntity)
                    } else {
                        QuranApp.database.downloadDao().insertOrUpdate(
                            downloadingEntity.copy(
                                status = DownloadStatus.FAILED.name,
                                errorMessage = userError
                            )
                        )
                    }
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                activeTasks.remove(downloadId, taskHolder)
            }
        }

        taskHolder.job = job
        activeTasks[downloadId] = taskHolder
        job.start()
    }

    private suspend fun downloadWithRetryAndFallback(
        primaryUrl: String,
        tempFile: File,
        taskHolder: ActiveTask,
        isCurrentTask: () -> Boolean,
        onProgress: suspend (progress: Int, bytes: Long) -> Unit
    ) {
        val candidateUrls = mutableListOf(primaryUrl)
        var lastException: Exception? = null
        var resolvedMirrors = false

        var attempt = 0
        val maxAttempts = 4

        while (attempt < maxAttempts) {
            if (!isCurrentTask()) throw CancellationException("Superseded")

            val targetUrl = candidateUrls.getOrElse(attempt) { candidateUrls.first() }
            try {
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                val isArchive = targetUrl.contains("archive.org", ignoreCase = true)

                val requestBuilder = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("Accept", "audio/mpeg, audio/*;q=0.9, */*;q=0.8")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")

                if (existingBytes > 0L) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                val call = okHttpClient.newCall(requestBuilder.build())
                taskHolder.call = call

                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val code = response.code
                        // Handle 416 Range Not Satisfiable by restarting clean
                        if (code == 416 && tempFile.exists()) {
                            tempFile.delete()
                        }
                        throw IOException("HTTP $code: ${response.message}")
                    }

                    val contentType = response.header("Content-Type")?.lowercase(Locale.US).orEmpty()
                    if (contentType.startsWith("text/html") ||
                        contentType.startsWith("application/json") ||
                        contentType.startsWith("text/plain")
                    ) {
                        throw IOException("استجابة غير صوتية من الخادم ($contentType)")
                    }

                    val body = response.body ?: throw IOException("استجابة الخادم فارغة")
                    val isPartial = response.code == 206
                    val appendMode = isPartial && existingBytes > 0L
                    if (!isPartial && tempFile.exists()) {
                        tempFile.delete()
                    }

                    val baseOffset = if (appendMode) existingBytes else 0L
                    val bodyLength = body.contentLength()
                    val totalBytes = when {
                        isPartial -> parseTotalFromContentRange(response.header("Content-Range"))
                            ?: if (bodyLength > 0L) baseOffset + bodyLength else -1L
                        else -> bodyLength
                    }

                    var downloadedBytes = baseOffset
                    var lastReportedPercent = if (totalBytes > 0L) ((downloadedBytes * 100) / totalBytes).toInt() else -1
                    var lastReportedBytesChunk = downloadedBytes

                    var inputStream: InputStream? = null
                    var outputStream: FileOutputStream? = null
                    try {
                        inputStream = body.byteStream()
                        outputStream = FileOutputStream(tempFile, appendMode)

                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (!isCurrentTask()) throw CancellationException("Cancelled")
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0L) {
                                val currentPercent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (currentPercent >= lastReportedPercent + 5 || currentPercent == 100) {
                                    lastReportedPercent = currentPercent
                                    onProgress(currentPercent, downloadedBytes)
                                }
                            } else if (downloadedBytes - lastReportedBytesChunk >= 131_072L) {
                                lastReportedBytesChunk = downloadedBytes
                                onProgress(0, downloadedBytes)
                            }
                        }
                        outputStream.flush()
                    } finally {
                        try { outputStream?.close() } catch (_: Exception) {}
                        try { inputStream?.close() } catch (_: Exception) {}
                    }

                    // Verify completeness and MP3 structure
                    if (totalBytes > 0L && tempFile.length() < totalBytes) {
                        throw IOException("انقطع التنزيل قبل اكتمال الملف (${tempFile.length()} / $totalBytes)")
                    }
                    if (!isValidMp3File(tempFile)) {
                        tempFile.delete()
                        throw IllegalStateException("الملف الذي تم تنزيله تالف أو ليس بصيغة MP3 صالحة")
                    }

                    // Success!
                    return
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastException = e
                // If Archive.org failed on redirect or mirror, resolve direct metadata mirrors once
                if (!resolvedMirrors && primaryUrl.contains("archive.org/download/", ignoreCase = true)) {
                    resolvedMirrors = true
                    val directMirrors = resolveArchiveOrgDirectMirrors(primaryUrl, taskHolder)
                    if (directMirrors.isNotEmpty()) {
                        candidateUrls.addAll(directMirrors)
                    }
                }
                attempt++
                if (attempt < maxAttempts && isCurrentTask()) {
                    delay(750L * attempt)
                }
            } finally {
                taskHolder.call = null
            }
        }

        throw lastException ?: IOException("تعذر إتمام التنزيل")
    }

    private fun resolveArchiveOrgDirectMirrors(downloadUrl: String, taskHolder: ActiveTask): List<String> {
        return try {
            // URL format: https://archive.org/download/<identifier>/<fileName>
            val afterDownload = downloadUrl.substringAfter("archive.org/download/", "")
            val parts = afterDownload.split("/", limit = 2)
            if (parts.size < 2) return emptyList()
            val identifier = parts[0]
            val filePath = parts[1]
            if (identifier.isBlank() || filePath.isBlank()) return emptyList()

            val metadataReq = Request.Builder()
                .url("https://archive.org/metadata/$identifier")
                .header("User-Agent", DEFAULT_USER_AGENT)
                .build()
            val call = okHttpClient.newCall(metadataReq)
            taskHolder.call = call
            call.execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val jsonText = resp.body?.string() ?: return emptyList()
                val json = JSONObject(jsonText)
                val dir = json.optString("dir", "")
                if (dir.isBlank()) return emptyList()
                val mirrors = linkedSetOf<String>()
                val d1 = json.optString("d1", "")
                val d2 = json.optString("d2", "")
                if (d1.isNotBlank()) mirrors.add("https://$d1$dir/$filePath")
                if (d2.isNotBlank()) mirrors.add("https://$d2$dir/$filePath")
                val workable = json.optJSONArray("workable_servers")
                if (workable != null) {
                    for (i in 0 until workable.length()) {
                        val host = workable.optString(i, "")
                        if (host.isNotBlank()) {
                            mirrors.add("https://$host$dir/$filePath")
                        }
                    }
                }
                mirrors.toList()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            taskHolder.call = null
        }
    }

    private fun parseTotalFromContentRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex == -1 || slashIndex == contentRange.lastIndex) return null
        return contentRange.substring(slashIndex + 1).trim().toLongOrNull()
    }

    private fun mapDownloadErrorMessage(e: Exception): String {
        val msg = e.localizedMessage ?: ""
        return when {
            msg.contains("HTTP 404") -> "الملف الصوتي غير موجود على الخادم (404)"
            msg.contains("HTTP 503") || msg.contains("HTTP 429") -> "الخادم مزدحم مؤقتاً، يرجى إعادة المحاولة"
            msg.contains("Unable to resolve host") || msg.contains("UnknownHost") -> "تعذر الاتصال بالإنترنت، تحقق من الشبكة"
            msg.contains("timeout", ignoreCase = true) -> "انتهت مهلة الاتصال بالخادم أثناء التنزيل"
            msg.isNotBlank() -> msg
            else -> "فشل التنزيل"
        }
    }

    fun cancelDownload(downloadId: String) {
        val task = activeTasks.remove(downloadId)
        task?.call?.cancel()
        task?.job?.cancel()
        scope.launch {
            val file = parseDownloadIdToFile(downloadId)
            val hadValidFile = file != null && file.exists() && isValidMp3File(file)
            if (!hadValidFile) {
                QuranApp.database.downloadDao().deleteById(downloadId)
            }
        }
    }

    fun deleteDownload(downloadId: String) {
        val task = activeTasks.remove(downloadId)
        task?.call?.cancel()
        task?.job?.cancel()
        scope.launch {
            val entity = QuranApp.database.downloadDao().getDownloadById(downloadId)
            if (entity != null) {
                val file = File(entity.localFilePath)
                if (file.exists()) file.delete()
                QuranApp.database.downloadDao().deleteById(downloadId)
            }
        }
    }

    private fun parseDownloadIdToFile(downloadId: String): File? {
        val sep = downloadId.lastIndexOf('_')
        if (sep <= 0 || sep >= downloadId.lastIndex) return null
        val reciterId = downloadId.substring(0, sep)
        val surahNum = downloadId.substring(sep + 1).toIntOrNull() ?: return null
        return getLocalAudioFile(reciterId, surahNum)
    }

    companion object {
        internal const val MIN_VALID_AUDIO_BYTES = 16_384L
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) QuranAudio/1.0"

        @Volatile
        private var instance: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return instance ?: synchronized(this) {
                instance ?: DownloadManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Verifies that a downloaded file is a non-empty valid MP3 audio file (ID3v2 header or MPEG audio frame sync)
         * and not an HTML/JSON error payload.
         */
        internal fun isValidMp3File(file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() < MIN_VALID_AUDIO_BYTES) {
                return false
            }
            return try {
                val sampleSize = minOf(file.length(), 16_384L).toInt()
                val header = ByteArray(sampleSize)
                FileInputStream(file).use { fis ->
                    var offset = 0
                    while (offset < sampleSize) {
                        val read = fis.read(header, offset, sampleSize - offset)
                        if (read == -1) break
                        offset += read
                    }
                    hasValidMp3HeaderBytes(header, offset)
                }
            } catch (_: Exception) {
                false
            }
        }

        internal fun hasValidMp3HeaderBytes(bytes: ByteArray, length: Int = bytes.size): Boolean {
            if (length < 4) return false

            // Check ASCII text error responses (<html, <!doc, {...)
            val prefix = String(bytes, 0, minOf(length, 32), Charsets.UTF_8).trimStart().lowercase(Locale.US)
            if (prefix.startsWith("<html") || prefix.startsWith("<!doc") || prefix.startsWith("<?xml") || prefix.startsWith("{")) {
                return false
            }

            // Standard ID3v2 header: "ID3"
            if (bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) {
                return true
            }

            // Scan initial bytes for valid MPEG-1/2 Layer III frame sync (11 set bits: 0xFF 0xE0+)
            val limit = length - 3
            for (i in 0..limit) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                    val mpegVersionBits = (b1 ushr 3) and 0x03
                    val layerBits = (b1 ushr 1) and 0x03
                    val bitrateIndex = (b2 ushr 4) and 0x0F
                    val sampleRateIndex = (b2 ushr 2) and 0x03
                    if (mpegVersionBits != 0x01 && layerBits != 0x00 && bitrateIndex != 0x0F && sampleRateIndex != 0x03) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
