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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DownloadManager(private val context: Context) {

    private class ActiveTask(
        val taskId: String,
        val generation: Long,
        @Volatile var job: Job? = null,
        @Volatile var call: Call? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTasks = ConcurrentHashMap<String, ActiveTask>()
    private val downloadGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    private fun nextGeneration(downloadId: String): Long {
        return downloadGenerations.computeIfAbsent(downloadId) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun currentGeneration(downloadId: String): Long {
        return downloadGenerations[downloadId]?.get() ?: 0L
    }

    private fun mutexFor(downloadId: String): Mutex {
        return downloadMutexes.computeIfAbsent(downloadId) { Mutex() }
    }

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

    @androidx.annotation.VisibleForTesting
    internal var httpClientOverride: OkHttpClient? = null

    @androidx.annotation.VisibleForTesting
    internal var retryBaseDelayMs: Long = 750L

    private fun activeClient(): OkHttpClient = httpClientOverride ?: okHttpClient

    fun getLocalAudioFile(reciterId: String, surahNumber: Int): File {
        val padded = String.format(Locale.US, "%03d", surahNumber)
        return File(downloadsDir, "quran_${reciterId}_${padded}.mp3")
    }

    fun getValidLocalAudioFile(reciterId: String, surahNumber: Int): File? {
        val file = getLocalAudioFile(reciterId, surahNumber)
        return if (isValidMp3File(file)) file else null
    }

    fun isDownloaded(reciterId: String, surahNumber: Int): Boolean {
        return getValidLocalAudioFile(reciterId, surahNumber) != null
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
        val taskGen = nextGeneration(downloadId)

        // Cancel any previous task and its live HTTP call immediately
        activeTasks.remove(downloadId)?.let { previous ->
            previous.call?.cancel()
            previous.job?.cancel()
        }

        val taskHolder = ActiveTask(taskId = taskId, generation = taskGen)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val audioUrl = reciter.getAudioUrl(surah.id)
            val finalFile = getLocalAudioFile(reciter.id, surah.id)
            val tempFile = File(tempDir, "${downloadId}_${taskId}.part")
            val isStillCurrent: () -> Boolean = {
                activeTasks[downloadId]?.taskId == taskId && currentGeneration(downloadId) == taskGen
            }

            var hadValidExistingFile = false
            var existingValidEntity: DownloadEntity? = null

            val preparedToProceed = mutexFor(downloadId).withLock {
                if (!isStillCurrent()) return@withLock false
                hadValidExistingFile = finalFile.exists() && isValidMp3File(finalFile)
                val baseEntity = DownloadEntity(
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
                if (hadValidExistingFile) {
                    existingValidEntity = baseEntity
                    QuranApp.database.downloadDao().insertOrUpdate(baseEntity)
                    activeTasks.remove(downloadId, taskHolder)
                    return@withLock false
                }
                existingValidEntity = baseEntity
                QuranApp.database.downloadDao().insertOrUpdate(
                    baseEntity.copy(
                        status = DownloadStatus.DOWNLOADING.name,
                        progress = 0,
                        fileSizeBytes = 0L,
                        errorMessage = null
                    )
                )
                true
            }
            if (!preparedToProceed) return@launch

            val downloadingEntity = existingValidEntity!!.copy(
                status = DownloadStatus.DOWNLOADING.name,
                progress = 0,
                fileSizeBytes = 0L,
                errorMessage = null
            )

            try {
                downloadWithRetryAndFallback(
                    primaryUrl = audioUrl,
                    tempFile = tempFile,
                    taskHolder = taskHolder,
                    isCurrentTask = isStillCurrent,
                    onProgress = { progress, bytes ->
                        mutexFor(downloadId).withLock {
                            if (isStillCurrent()) {
                                QuranApp.database.downloadDao().insertOrUpdate(
                                    downloadingEntity.copy(
                                        progress = progress,
                                        fileSizeBytes = bytes
                                    )
                                )
                            }
                        }
                    }
                )

                // Guard against race under per-ID lock before touching final file or Room
                mutexFor(downloadId).withLock {
                    if (!isStillCurrent()) {
                        tempFile.delete()
                        return@withLock
                    }

                    // Atomic move into final destination ONLY after verified MP3 integrity
                    val backupFile = if (finalFile.exists() && isValidMp3File(finalFile)) {
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

                    if (isStillCurrent()) {
                        QuranApp.database.downloadDao().insertOrUpdate(
                            downloadingEntity.copy(
                                status = DownloadStatus.COMPLETED.name,
                                progress = 100,
                                fileSizeBytes = finalFile.length(),
                                errorMessage = null
                            )
                        )
                    }
                }
            } catch (_: CancellationException) {
                tempFile.delete()
                mutexFor(downloadId).withLock {
                    if (isStillCurrent()) {
                        if (hadValidExistingFile && isValidMp3File(finalFile)) {
                            QuranApp.database.downloadDao().insertOrUpdate(existingValidEntity!!)
                        } else {
                            QuranApp.database.downloadDao().deleteById(downloadId)
                        }
                    }
                }
            } catch (e: Exception) {
                tempFile.delete()
                mutexFor(downloadId).withLock {
                    if (isStillCurrent()) {
                        val userError = mapDownloadErrorMessage(e)
                        if (hadValidExistingFile && isValidMp3File(finalFile)) {
                            QuranApp.database.downloadDao().insertOrUpdate(existingValidEntity!!)
                        } else {
                            QuranApp.database.downloadDao().insertOrUpdate(
                                downloadingEntity.copy(
                                    status = DownloadStatus.FAILED.name,
                                    errorMessage = userError
                                )
                            )
                        }
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

                val call = activeClient().newCall(requestBuilder.build())
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
                    val contentRangeHeader = response.header("Content-Range")
                    val rangeSpec = if (isPartial) parseContentRange(contentRangeHeader) else null

                    val appendMode: Boolean
                    val baseOffset: Long
                    if (isPartial && existingBytes > 0L) {
                        when {
                            rangeSpec != null && rangeSpec.start == existingBytes -> {
                                appendMode = true
                                baseOffset = existingBytes
                            }
                            rangeSpec != null && rangeSpec.start == 0L -> {
                                // Server returned partial bytes starting from 0 instead of requested offset
                                if (tempFile.exists()) tempFile.delete()
                                appendMode = false
                                baseOffset = 0L
                            }
                            else -> {
                                // Mismatched or missing Content-Range: discard partial temp file and restart from byte 0
                                if (tempFile.exists()) tempFile.delete()
                                throw IOException(
                                    "تعارض في نطاق الاستئناف (متوقع $existingBytes، المستلم ${rangeSpec?.start})"
                                )
                            }
                        }
                    } else {
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                        appendMode = false
                        baseOffset = 0L
                    }

                    val bodyLength = body.contentLength()
                    val totalBytes = when {
                        isPartial -> rangeSpec?.total
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
                    delay(retryBaseDelayMs * attempt)
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
            val call = activeClient().newCall(metadataReq)
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
        val opGen = nextGeneration(downloadId)
        val task = activeTasks.remove(downloadId)
        task?.call?.cancel()
        task?.job?.cancel()
        scope.launch {
            mutexFor(downloadId).withLock {
                if (currentGeneration(downloadId) != opGen || activeTasks.containsKey(downloadId)) {
                    return@withLock
                }
                val file = parseDownloadIdToFile(downloadId)
                val hadValidFile = file != null && isValidMp3File(file)
                cleanupTempFilesForDownload(downloadId)
                if (!hadValidFile) {
                    QuranApp.database.downloadDao().deleteById(downloadId)
                }
            }
        }
    }

    fun deleteDownload(downloadId: String) {
        val opGen = nextGeneration(downloadId)
        val task = activeTasks.remove(downloadId)
        task?.call?.cancel()
        task?.job?.cancel()
        scope.launch {
            mutexFor(downloadId).withLock {
                // Never delete file or Room record if a newer task for this downloadId superseded this delete
                if (currentGeneration(downloadId) != opGen || activeTasks.containsKey(downloadId)) {
                    return@withLock
                }
                val entity = QuranApp.database.downloadDao().getDownloadById(downloadId)
                val fallbackFile = parseDownloadIdToFile(downloadId)
                cleanupTempFilesForDownload(downloadId)
                if (entity != null) {
                    val file = File(entity.localFilePath)
                    if (file.exists()) file.delete()
                }
                if (fallbackFile != null && fallbackFile.exists()) {
                    fallbackFile.delete()
                }
                QuranApp.database.downloadDao().deleteById(downloadId)
            }
        }
    }

    private fun cleanupTempFilesForDownload(downloadId: String) {
        try {
            tempDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("${downloadId}_")) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
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
        internal const val MAX_MPEG_SYNC_SEARCH_BYTES = 16_384
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) QuranAudio/1.0"

        @Volatile
        private var instance: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return instance ?: synchronized(this) {
                instance ?: DownloadManager(context.applicationContext).also { instance = it }
            }
        }

        internal data class ContentRangeSpec(
            val start: Long,
            val end: Long?,
            val total: Long?
        )

        private data class Id3v2HeaderInfo(
            val audioOffset: Long
        )

        private val CONTENT_RANGE_REGEX =
            Regex("^bytes\\s+(\\d+)-(\\d+|\\*)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

        internal fun parseContentRange(contentRange: String?): ContentRangeSpec? {
            if (contentRange.isNullOrBlank()) return null
            val match = CONTENT_RANGE_REGEX.matchEntire(contentRange.trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull()
            val total = match.groupValues[3].toLongOrNull()
            return ContentRangeSpec(start = start, end = end, total = total)
        }

        private fun isNonAudioTextPrefix(bytes: ByteArray, length: Int): Boolean {
            val prefix = String(bytes, 0, minOf(length, 32), Charsets.UTF_8).trimStart().lowercase(Locale.US)
            return prefix.startsWith("<html") ||
                prefix.startsWith("<!doc") ||
                prefix.startsWith("<?xml") ||
                prefix.startsWith("{")
        }

        private fun parseId3v2Header(bytes: ByteArray, length: Int): Id3v2HeaderInfo? {
            if (length < 10) return null
            if (bytes[0] != 0x49.toByte() || bytes[1] != 0x44.toByte() || bytes[2] != 0x33.toByte()) {
                return null
            }
            val majorVersion = bytes[3].toInt() and 0xFF
            val minorVersion = bytes[4].toInt() and 0xFF
            val flags = bytes[5].toInt() and 0xFF
            if (majorVersion !in 2..4 || minorVersion == 0xFF) return null

            val b6 = bytes[6].toInt() and 0xFF
            val b7 = bytes[7].toInt() and 0xFF
            val b8 = bytes[8].toInt() and 0xFF
            val b9 = bytes[9].toInt() and 0xFF
            val validSynchsafe = (b6 and 0x80) == 0 && (b7 and 0x80) == 0 &&
                (b8 and 0x80) == 0 && (b9 and 0x80) == 0
            if (!validSynchsafe) return null

            val tagSize = ((b6 and 0x7F) shl 21) or
                ((b7 and 0x7F) shl 14) or
                ((b8 and 0x7F) shl 7) or
                (b9 and 0x7F)
            val footerSize = if (majorVersion >= 4 && (flags and 0x10) != 0) 10L else 0L
            return Id3v2HeaderInfo(audioOffset = 10L + tagSize + footerSize)
        }

        private fun isValidMpegLayer3FrameHeader(b0: Int, b1: Int, b2: Int): Boolean {
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return false
            val mpegVersionBits = (b1 ushr 3) and 0x03
            val layerBits = (b1 ushr 1) and 0x03
            val bitrateIndex = (b2 ushr 4) and 0x0F
            val sampleRateIndex = (b2 ushr 2) and 0x03
            return mpegVersionBits != 0x01 &&
                layerBits == 0x01 && // MPEG Layer III (MP3)
                bitrateIndex in 1..14 && // Reject free (0) & invalid (15)
                sampleRateIndex in 0..2 // Reject reserved (3)
        }

        private fun findValidMpegFrameOffset(bytes: ByteArray, startIndex: Int, length: Int): Int {
            if (startIndex < 0 || length - startIndex < 4) return -1
            val searchLimit = minOf(length - 4, startIndex + MAX_MPEG_SYNC_SEARCH_BYTES)
            for (i in startIndex..searchLimit) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                if (isValidMpegLayer3FrameHeader(b0, b1, b2)) {
                    return i
                }
            }
            return -1
        }

        /**
         * Verifies that a downloaded file is a non-empty valid MP3 audio file:
         * - When ID3v2 is present, parses tag size, seeks to audio data offset, and verifies a valid MPEG Layer III frame header.
         * - When ID3v2 is absent, scans initial bytes for a valid MPEG Layer III frame header.
         * - Rejects HTML/JSON/XML error payloads and ID3-only fakes lacking audio frames.
         */
        internal fun isValidMp3File(file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() < MIN_VALID_AUDIO_BYTES) {
                return false
            }
            return try {
                val fileLength = file.length()
                val initialSampleSize = minOf(fileLength, MAX_MPEG_SYNC_SEARCH_BYTES.toLong()).toInt()
                val initialBuffer = ByteArray(initialSampleSize)
                var initialRead = 0
                FileInputStream(file).use { fis ->
                    while (initialRead < initialSampleSize) {
                        val read = fis.read(initialBuffer, initialRead, initialSampleSize - initialRead)
                        if (read == -1) break
                        initialRead += read
                    }
                }
                if (initialRead < 4 || isNonAudioTextPrefix(initialBuffer, initialRead)) {
                    return false
                }

                if (initialBuffer[0] == 0x49.toByte() &&
                    initialBuffer[1] == 0x44.toByte() &&
                    initialBuffer[2] == 0x33.toByte()
                ) {
                    val id3 = parseId3v2Header(initialBuffer, initialRead) ?: return false
                    if (fileLength < id3.audioOffset + 4L) return false
                    if (id3.audioOffset + MAX_MPEG_SYNC_SEARCH_BYTES <= initialRead) {
                        return findValidMpegFrameOffset(initialBuffer, id3.audioOffset.toInt(), initialRead) >= 0
                    }
                    // Large ID3v2 tag (e.g. embedded cover art): seek directly to audioOffset
                    val remaining = (fileLength - id3.audioOffset).coerceAtMost(MAX_MPEG_SYNC_SEARCH_BYTES.toLong()).toInt()
                    val postId3Buffer = ByteArray(remaining)
                    var postRead = 0
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(id3.audioOffset)
                        while (postRead < remaining) {
                            val read = raf.read(postId3Buffer, postRead, remaining - postRead)
                            if (read == -1) break
                            postRead += read
                        }
                    }
                    return findValidMpegFrameOffset(postId3Buffer, 0, postRead) >= 0
                }

                findValidMpegFrameOffset(initialBuffer, 0, initialRead) >= 0
            } catch (_: Exception) {
                false
            }
        }

        internal fun hasValidMp3HeaderBytes(
            bytes: ByteArray,
            length: Int = bytes.size,
            totalFileSize: Long = length.toLong()
        ): Boolean {
            if (length < 4 || isNonAudioTextPrefix(bytes, length)) return false

            if (bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) {
                val id3 = parseId3v2Header(bytes, length) ?: return false
                if (totalFileSize < id3.audioOffset + 4L) return false
                if (id3.audioOffset >= length.toLong()) return false
                return findValidMpegFrameOffset(bytes, id3.audioOffset.toInt(), length) >= 0
            }

            return findValidMpegFrameOffset(bytes, 0, length) >= 0
        }
    }
}
