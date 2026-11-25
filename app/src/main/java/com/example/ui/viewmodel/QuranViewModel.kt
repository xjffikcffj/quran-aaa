package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.QuranApp
import com.example.data.ArabicSearchUtils
import com.example.data.DefaultReciters
import com.example.data.QuranData
import com.example.data.local.BookmarkEntity
import com.example.data.local.ReciterEntity
import com.example.data.local.SurahStateEntity
import com.example.download.DownloadManager
import com.example.model.Bookmark
import com.example.model.DownloadItem
import com.example.model.PlayerState
import com.example.model.Reciter
import com.example.model.RepeatMode
import com.example.model.SleepTimerOption
import com.example.model.Surah
import com.example.player.PlayerManager
import com.example.receiver.FridayReminderReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class QuranViewModel(application: Application) : AndroidViewModel(application) {

    private val playerManager = PlayerManager.getInstance(application)
    private val downloadManager = DownloadManager.getInstance(application)
    private val prefs = application.getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)
    private val playerPrefs = application.getSharedPreferences("quran_player_prefs", Context.MODE_PRIVATE)

    val playerState: StateFlow<PlayerState> = playerManager.playerState

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    val favoriteSurahNumbers: StateFlow<List<Int>> = QuranApp.database.surahStateDao()
        .getFavoriteSurahNumbers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reciters: StateFlow<List<Reciter>> = QuranApp.database.reciterDao()
        .getAllReciters()
        .combine(MutableStateFlow(DefaultReciters.list)) { dbEntities, defaultList ->
            if (dbEntities.isEmpty()) {
                defaultList
            } else {
                dbEntities.map { it.toReciter() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DefaultReciters.list)

    val displayedSurahs: StateFlow<List<Surah>> = combine(
        _searchQuery,
        _showFavoritesOnly,
        favoriteSurahNumbers
    ) { query, favOnly, favList ->
        QuranData.allSurahs.filter { surah ->
            val matchesFavorite = !favOnly || favList.contains(surah.id)
            val matchesSearch = ArabicSearchUtils.matchesQuery(surah.id, surah.name, surah.englishName, query)
            matchesFavorite && matchesSearch
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QuranData.allSurahs)

    val bookmarks: StateFlow<List<Bookmark>> = QuranApp.database.bookmarkDao()
        .getAllBookmarks()
        .combine(MutableStateFlow(emptyList<Bookmark>())) { entities, _ ->
            entities.map { it.toBookmark() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloads: StateFlow<List<DownloadItem>> = downloadManager.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _fridayReminderEnabled = MutableStateFlow(prefs.getBoolean("friday_reminder", true))
    val fridayReminderEnabled: StateFlow<Boolean> = _fridayReminderEnabled.asStateFlow()

    init {
        if (_fridayReminderEnabled.value) {
            FridayReminderReceiver.scheduleFridayReminder(application, true)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavoritesOnly() {
        _showFavoritesOnly.value = !_showFavoritesOnly.value
    }

    fun toggleFavorite(surahId: Int) {
        viewModelScope.launch {
            val isCurrentFav = favoriteSurahNumbers.value.contains(surahId)
            val dao = QuranApp.database.surahStateDao()
            val existing = dao.getState(surahId)
            if (existing != null) {
                dao.setFavorite(surahId, !isCurrentFav)
            } else {
                dao.insertOrUpdate(
                    SurahStateEntity(
                        surahNumber = surahId,
                        isFavorite = !isCurrentFav
                    )
                )
            }
        }
    }

    fun playSurah(
        surah: Surah,
        seekToMs: Long? = null,
        startFromBeginning: Boolean = false,
        autoPlay: Boolean = true
    ) {
        playerManager.playSurah(surah, seekToMs, startFromBeginning, autoPlay)
    }

    fun getSavedPosition(reciterId: String, surahNumber: Int): Long {
        return playerManager.getSavedPosition(reciterId, surahNumber)
    }

    fun togglePlayPause() {
        playerManager.togglePlayPause()
    }

    fun resume() {
        playerManager.resume()
    }

    fun pause() {
        playerManager.pause()
    }

    fun playNext() {
        playerManager.playNextSurah()
    }

    fun playPrevious() {
        playerManager.playPreviousSurah()
    }

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs)
    }

    fun selectReciter(reciter: Reciter) {
        playerManager.setReciter(reciter)
    }

    fun setRepeatMode(mode: RepeatMode) {
        playerManager.setRepeatMode(mode)
    }

    fun setPlaybackSpeed(speed: Float) {
        playerManager.setPlaybackSpeed(speed)
    }

    fun setSleepTimer(option: SleepTimerOption) {
        playerManager.setSleepTimer(option)
    }

    fun addBookmark(surah: Surah, note: String) {
        viewModelScope.launch {
            val state = playerState.value
            val reciter = state.currentReciter ?: DefaultReciters.defaultReciter
            val pos = if (state.currentSurah?.id == surah.id) state.currentPositionMs else 0L

            val bookmark = Bookmark(
                surahNumber = surah.id,
                surahName = surah.name,
                reciterId = reciter.id,
                reciterName = reciter.name,
                positionMs = pos,
                note = note.trim(),
                timestamp = System.currentTimeMillis()
            )
            QuranApp.database.bookmarkDao().insertBookmark(BookmarkEntity.fromBookmark(bookmark))
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            QuranApp.database.bookmarkDao().deleteById(bookmark.id)
        }
    }

    fun playFromBookmark(bookmark: Bookmark) {
        val surah = QuranData.getSurahById(bookmark.surahNumber) ?: return
        val reciter = reciters.value.firstOrNull { it.id == bookmark.reciterId }
            ?: (playerState.value.currentReciter ?: DefaultReciters.defaultReciter)
        playerManager.setReciter(reciter)
        playerManager.playSurah(surah, seekToMs = bookmark.positionMs)
    }

    fun startDownload(surah: Surah) {
        val reciter = playerState.value.currentReciter ?: DefaultReciters.defaultReciter
        downloadManager.startDownload(reciter, surah)
    }

    fun startDownload(reciter: Reciter, surah: Surah) {
        downloadManager.startDownload(reciter, surah)
    }

    fun cancelDownload(downloadId: String) {
        downloadManager.cancelDownload(downloadId)
    }

    fun deleteDownload(downloadId: String) {
        downloadManager.deleteDownload(downloadId)
    }

    fun isSurahDownloaded(surahId: Int): Boolean {
        val reciter = playerState.value.currentReciter ?: DefaultReciters.defaultReciter
        return downloadManager.isDownloaded(reciter.id, surahId)
    }

    fun toggleFridayReminder(enabled: Boolean) {
        _fridayReminderEnabled.value = enabled
        prefs.edit().putBoolean("friday_reminder", enabled).apply()
        FridayReminderReceiver.scheduleFridayReminder(getApplication(), enabled)
    }

    fun triggerFridayReminderTest() {
        FridayReminderReceiver.showFridayNotification(getApplication())
    }

    fun addCustomReciter(name: String, serverUrl: String, availableCount: Int): Boolean {
        val cleanName = name.trim()
        val trimmedUrl = serverUrl.trim()
        if (cleanName.isEmpty() || !Reciter.isValidHttpsServerUrl(trimmedUrl)) {
            return false
        }
        val cleanUrl = Reciter.normalizeServerUrl(trimmedUrl)
        val id = "custom_${System.currentTimeMillis()}"
        val newReciter = Reciter(
            id = id,
            name = cleanName,
            serverUrl = cleanUrl,
            availableSurahsCount = availableCount.coerceIn(1, 114),
            isCustom = true,
            subInfo = "قارئ مخصص مضاف من لوحة المطور"
        )
        viewModelScope.launch {
            QuranApp.database.reciterDao().insertReciter(ReciterEntity.fromReciter(newReciter))
        }
        return true
    }

    fun deleteCustomReciter(reciterId: String) {
        viewModelScope.launch {
            QuranApp.database.reciterDao().deleteCustomReciter(reciterId)
            if (playerState.value.currentReciter?.id == reciterId) {
                playerManager.setReciter(DefaultReciters.defaultReciter)
            }
        }
    }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val db = QuranApp.database
        val root = JSONObject()
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())

        // 1. Bookmarks
        val bookmarksArray = JSONArray()
        db.bookmarkDao().getAllBookmarksOnce().forEach { b ->
            bookmarksArray.put(
                JSONObject().apply {
                    put("surahNumber", b.surahNumber)
                    put("surahName", b.surahName)
                    put("reciterId", b.reciterId)
                    put("reciterName", b.reciterName)
                    put("positionMs", b.positionMs)
                    put("note", b.note)
                    put("timestamp", b.timestamp)
                }
            )
        }
        root.put("bookmarks", bookmarksArray)

        // 2. Custom Reciters
        val customRecitersArray = JSONArray()
        db.reciterDao().getAllRecitersOnce().filter { it.isCustom }.forEach { r ->
            customRecitersArray.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("serverUrl", r.serverUrl)
                    put("availableSurahsCount", r.availableSurahsCount)
                    put("availableSurahsCsv", r.availableSurahsCsv)
                    put("subInfo", r.subInfo)
                }
            )
        }
        root.put("customReciters", customRecitersArray)

        // 3. Favorites & Surah States
        val allStates = db.surahStateDao().getAllStatesOnce()
        val favoritesArray = JSONArray()
        val surahStatesArray = JSONArray()
        allStates.forEach { state ->
            if (state.isFavorite) {
                favoritesArray.put(state.surahNumber)
            }
            surahStatesArray.put(
                JSONObject().apply {
                    put("surahNumber", state.surahNumber)
                    put("isFavorite", state.isFavorite)
                    put("lastPositionMs", state.lastPositionMs)
                    put("lastPlayedTimestamp", state.lastPlayedTimestamp)
                }
            )
        }
        root.put("favorites", favoritesArray)
        root.put("surahStates", surahStatesArray)

        // 4. Per-reciter playback positions & settings
        val positionsObj = JSONObject()
        playerPrefs.all.forEach { (key, value) ->
            if ((key.startsWith("pos_") || key.startsWith("dur_")) && value is Number) {
                positionsObj.put(key, value.toLong())
            }
        }
        root.put("reciterPositions", positionsObj)

        val settingsObj = JSONObject().apply {
            put("savedReciterId", playerPrefs.getString("saved_reciter_id", DefaultReciters.defaultReciter.id))
            put("lastSurahId", playerPrefs.getInt("last_surah_id", 1))
            put("savedRepeatMode", playerPrefs.getString("saved_repeat_mode", RepeatMode.ALL.name))
            put("savedPlaybackSpeed", playerPrefs.getFloat("saved_playback_speed", 1.0f).toDouble())
        }
        root.put("playerSettings", settingsObj)

        root.toString(2)
    }

    data class BackupImportResult(
        val success: Boolean,
        val message: String
    )

    suspend fun importBackupJson(jsonString: String): Boolean {
        return importBackupJsonWithResult(jsonString).success
    }

    suspend fun importBackupJsonWithResult(jsonString: String): BackupImportResult = withContext(Dispatchers.IO) {
        if (jsonString.isBlank()) {
            return@withContext BackupImportResult(false, "محتوى النسخة الاحتياطية فارغ.")
        }

        try {
            val root = JSONObject(jsonString.trim())
            if (root.has("version")) {
                val rawVersion = root.opt("version")
                val versionNum = (rawVersion as? Number)?.toInt()
                val isIntegral = rawVersion is Int || rawVersion is Long
                if (!isIntegral || versionNum == null || versionNum !in 1..2) {
                    return@withContext BackupImportResult(false, "إصدار النسخة الاحتياطية غير مدعوم.")
                }
            }

            val hasRecognizedSection = root.has("bookmarks") ||
                root.has("customReciters") ||
                root.has("favorites") ||
                root.has("surahStates") ||
                root.has("reciterPositions") ||
                root.has("playerSettings")
            if (!hasRecognizedSection) {
                return@withContext BackupImportResult(false, "ملف JSON لا يحتوي على بيانات صالحة للتطبيق.")
            }

            val db = QuranApp.database
            val existingReciters = db.reciterDao().getAllRecitersOnce()
            val existingCustomById = existingReciters.filter { it.isCustom }.associateBy { it.id }
            val usedReciterIds = (existingReciters.map { it.id } + DefaultReciters.list.map { it.id }).toMutableSet()

            // 1. Validate & resolve customReciters first (enforcing HTTPS only, duplicate/ID conflict checks)
            val parsedRecitersToInsert = mutableListOf<ReciterEntity>()
            val resolvedRecitersById = mutableMapOf<String, ReciterEntity>()
            val idMapping = mutableMapOf<String, String>()
            val batchSeenIds = mutableMapOf<String, Pair<String, String>>() // rawId -> (name, normalizedUrl)

            if (root.has("customReciters")) {
                val array = root.getJSONArray("customReciters")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val rawId = (obj.opt("id") as? String)?.trim().orEmpty()
                    val name = (obj.opt("name") as? String)?.trim().orEmpty()
                    val rawServerUrl = (obj.opt("serverUrl") as? String)?.trim().orEmpty()
                    if (rawId.isEmpty() || name.isEmpty() || rawServerUrl.isEmpty()) {
                        return@withContext BackupImportResult(false, "بيانات أحد القراء المخصصين ناقصة في النسخة الاحتياطية.")
                    }
                    if (!Reciter.isValidHttpsServerUrl(rawServerUrl)) {
                        return@withContext BackupImportResult(
                            false,
                            "تم رفض الاستيراد: روابط القراء المخصصين يجب أن تستخدم بروتوكول HTTPS فقط."
                        )
                    }

                    val rawCount = if (obj.has("availableSurahsCount")) obj.opt("availableSurahsCount") else 114
                    val count = (rawCount as? Number)?.toInt()
                    val isIntegralCount = rawCount is Int || rawCount is Long
                    if (!isIntegralCount || count == null || count !in 1..114) {
                        return@withContext BackupImportResult(false, "عدد السور لأحد القراء المخصصين غير صالح.")
                    }

                    val csv = (obj.opt("availableSurahsCsv") as? String)?.trim().orEmpty()
                    if (csv.isNotEmpty()) {
                        val tokens = csv.split(",")
                        for (token in tokens) {
                            val num = token.trim().toIntOrNull()
                            if (num == null || num !in 1..114) {
                                return@withContext BackupImportResult(false, "قائمة السور لأحد القراء المخصصين غير صالحة.")
                            }
                        }
                    }

                    val normalizedUrl = Reciter.normalizeServerUrl(rawServerUrl)
                    val prevSameIdInBatch = batchSeenIds[rawId]
                    if (prevSameIdInBatch != null) {
                        if (prevSameIdInBatch.first == name && prevSameIdInBatch.second.equals(normalizedUrl, ignoreCase = true)) {
                            // Identical duplicate entry in same JSON -> skip without failing
                            continue
                        } else {
                            return@withContext BackupImportResult(
                                false,
                                "تم رفض الاستيراد: تعارض في معرّف القارئ المخصص ($rawId) داخل الملف."
                            )
                        }
                    }
                    batchSeenIds[rawId] = name to normalizedUrl

                    // Check if identical custom reciter already exists in DB or in this batch
                    val matchingExisting = existingCustomById.values.firstOrNull {
                        it.name == name && it.serverUrl.equals(normalizedUrl, ignoreCase = true)
                    }
                    val matchingBatch = parsedRecitersToInsert.firstOrNull {
                        it.name == name && it.serverUrl.equals(normalizedUrl, ignoreCase = true)
                    }

                    when {
                        matchingExisting != null -> {
                            idMapping[rawId] = matchingExisting.id
                            resolvedRecitersById[matchingExisting.id] = matchingExisting
                        }
                        matchingBatch != null -> {
                            idMapping[rawId] = matchingBatch.id
                            resolvedRecitersById[matchingBatch.id] = matchingBatch
                        }
                        else -> {
                            val resolvedId = if (!usedReciterIds.contains(rawId)) {
                                rawId
                            } else {
                                var suffix = 1
                                var candidate = "custom_${rawId}_$suffix"
                                while (usedReciterIds.contains(candidate)) {
                                    suffix++
                                    candidate = "custom_${rawId}_$suffix"
                                }
                                candidate
                            }
                            usedReciterIds.add(resolvedId)
                            idMapping[rawId] = resolvedId

                            val entity = ReciterEntity(
                                id = resolvedId,
                                name = name,
                                serverUrl = normalizedUrl,
                                availableSurahsCount = count,
                                availableSurahsCsv = csv,
                                isCustom = true,
                                subInfo = obj.optString("subInfo", "قارئ مخصص مستعاد من النسخة الاحتياطية")
                                    .ifBlank { "قارئ مخصص مستعاد من النسخة الاحتياطية" }
                            )
                            parsedRecitersToInsert.add(entity)
                            resolvedRecitersById[resolvedId] = entity
                        }
                    }
                }
            }

            // 2. Validate bookmarks
            val parsedBookmarks = mutableListOf<BookmarkEntity>()
            if (root.has("bookmarks")) {
                val array = root.getJSONArray("bookmarks")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val rawSurah = obj.opt("surahNumber")
                    val surahNumber = (rawSurah as? Number)?.toInt()
                    if (!(rawSurah is Int || rawSurah is Long) || surahNumber == null || surahNumber !in 1..114) {
                        return@withContext BackupImportResult(false, "رقم السورة في أحد الإشارات المرجعية غير صالح.")
                    }
                    val rawPos = obj.opt("positionMs")
                    val positionMs = (rawPos as? Number)?.toLong()
                    if (rawPos !is Number || rawPos is Double || rawPos is Float || positionMs == null || positionMs < 0L) {
                        return@withContext BackupImportResult(false, "موضع الإشارة المرجعية غير صالح.")
                    }
                    val rawReciterId = (obj.opt("reciterId") as? String)?.trim().orEmpty()
                    val reciterName = (obj.opt("reciterName") as? String)?.trim().orEmpty()
                    if (rawReciterId.isEmpty() || reciterName.isEmpty()) {
                        return@withContext BackupImportResult(false, "بيانات القارئ في الإشارة المرجعية غير صالحة.")
                    }
                    val rawNote = if (obj.has("note")) obj.opt("note") else ""
                    if (rawNote !is String) {
                        return@withContext BackupImportResult(false, "ملاحظة الإشارة المرجعية غير صالحة.")
                    }
                    val rawTs = if (obj.has("timestamp")) obj.opt("timestamp") else System.currentTimeMillis()
                    val timestamp = (rawTs as? Number)?.toLong()
                    if (rawTs !is Number || timestamp == null || timestamp < 0L) {
                        return@withContext BackupImportResult(false, "تاريخ الإشارة المرجعية غير صالح.")
                    }
                    val surahName = obj.optString("surahName").ifBlank {
                        QuranData.getSurahById(surahNumber)?.name
                            ?: return@withContext BackupImportResult(false, "اسم السورة غير صالح.")
                    }
                    val mappedReciterId = idMapping[rawReciterId] ?: rawReciterId
                    parsedBookmarks.add(
                        BookmarkEntity(
                            surahNumber = surahNumber,
                            surahName = surahName,
                            reciterId = mappedReciterId,
                            reciterName = reciterName,
                            positionMs = positionMs,
                            note = rawNote.trim(),
                            timestamp = timestamp
                        )
                    )
                }
            }

            // 3. Validate favorites
            val parsedFavorites = mutableSetOf<Int>()
            if (root.has("favorites")) {
                val favArray = root.getJSONArray("favorites")
                for (i in 0 until favArray.length()) {
                    val rawNum = favArray.opt(i)
                    val surahNum = (rawNum as? Number)?.toInt()
                    if (!(rawNum is Int || rawNum is Long) || surahNum == null || surahNum !in 1..114) {
                        return@withContext BackupImportResult(false, "قائمة المفضلة تحتوي على رقم سورة غير صالح.")
                    }
                    parsedFavorites.add(surahNum)
                }
            }

            // 4. Validate surahStates
            val parsedStates = mutableListOf<SurahStateEntity>()
            if (root.has("surahStates")) {
                val stateArray = root.getJSONArray("surahStates")
                for (i in 0 until stateArray.length()) {
                    val obj = stateArray.getJSONObject(i)
                    val rawNum = obj.opt("surahNumber")
                    val surahNum = (rawNum as? Number)?.toInt()
                    if (!(rawNum is Int || rawNum is Long) || surahNum == null || surahNum !in 1..114) {
                        return@withContext BackupImportResult(false, "حالة إحدى السور تحتوي على رقم سورة غير صالح.")
                    }
                    val rawPos = if (obj.has("lastPositionMs")) obj.opt("lastPositionMs") else 0L
                    val lastPos = (rawPos as? Number)?.toLong()
                    if (rawPos !is Number || lastPos == null || lastPos < 0L) {
                        return@withContext BackupImportResult(false, "موضع السورة المحفوظ غير صالح.")
                    }
                    val rawTs = if (obj.has("lastPlayedTimestamp")) obj.opt("lastPlayedTimestamp") else 0L
                    val lastTs = (rawTs as? Number)?.toLong()
                    if (rawTs !is Number || lastTs == null || lastTs < 0L) {
                        return@withContext BackupImportResult(false, "تاريخ تشغيل السورة غير صالح.")
                    }
                    val rawFav = if (obj.has("isFavorite")) obj.opt("isFavorite") else false
                    if (rawFav !is Boolean) {
                        return@withContext BackupImportResult(false, "قيمة المفضلة لإحدى السور غير صالحة.")
                    }
                    parsedStates.add(
                        SurahStateEntity(
                            surahNumber = surahNum,
                            lastPositionMs = lastPos,
                            lastPlayedTimestamp = lastTs,
                            isFavorite = rawFav
                        )
                    )
                }
            }

            // 5. Validate reciterPositions
            val parsedPositions = mutableMapOf<String, Long>()
            if (root.has("reciterPositions")) {
                val posObj = root.getJSONObject("reciterPositions")
                val keys = posObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.startsWith("pos_") || key.startsWith("dur_")) {
                        val rawVal = posObj.opt(key)
                        val value = (rawVal as? Number)?.toLong()
                        if (rawVal !is Number || value == null || value < 0L) {
                            return@withContext BackupImportResult(false, "قيم مواضع الاستماع في النسخة غير صالحة.")
                        }
                        val mappedKey = remapPositionKeyIfNeeded(key, idMapping)
                        parsedPositions[mappedKey] = value
                    } else {
                        return@withContext BackupImportResult(false, "مفتاح غير معروف في قسم مواضع الاستماع.")
                    }
                }
            }

            // 6. Validate playerSettings COMPLETELY before any DB or SharedPreferences mutation
            var validatedReciterId: String? = null
            var validatedLastSurahId: Int? = null
            var validatedRepeatMode: String? = null
            var validatedPlaybackSpeed: Float? = null

            if (root.has("playerSettings")) {
                val settingsObj = root.getJSONObject("playerSettings")

                if (settingsObj.has("savedRepeatMode")) {
                    val rawRepeat = settingsObj.opt("savedRepeatMode")
                    if (rawRepeat !is String || RepeatMode.entries.none { it.name == rawRepeat }) {
                        return@withContext BackupImportResult(false, "نمط التكرار في إعدادات المشغل غير صالح.")
                    }
                    validatedRepeatMode = rawRepeat
                }

                if (settingsObj.has("savedPlaybackSpeed")) {
                    val rawSpeed = settingsObj.opt("savedPlaybackSpeed")
                    val speedDouble = (rawSpeed as? Number)?.toDouble()
                    if (rawSpeed !is Number || speedDouble == null ||
                        speedDouble.isNaN() || speedDouble.isInfinite() ||
                        speedDouble < 0.5 || speedDouble > 2.0
                    ) {
                        return@withContext BackupImportResult(false, "سرعة القراءة في إعدادات المشغل غير صالحة.")
                    }
                    validatedPlaybackSpeed = speedDouble.toFloat()
                }

                if (settingsObj.has("lastSurahId")) {
                    val rawLastSurah = settingsObj.opt("lastSurahId")
                    val surahId = (rawLastSurah as? Number)?.toInt()
                    if (!(rawLastSurah is Int || rawLastSurah is Long) || surahId == null || surahId !in 1..114) {
                        return@withContext BackupImportResult(false, "رقم السورة الأخيرة في إعدادات المشغل غير صالح.")
                    }
                    validatedLastSurahId = surahId
                }

                if (settingsObj.has("savedReciterId")) {
                    val rawReciterId = settingsObj.opt("savedReciterId")
                    if (rawReciterId !is String || rawReciterId.trim().isEmpty()) {
                        return@withContext BackupImportResult(false, "معرّف القارئ المحفوظ في إعدادات المشغل غير صالح.")
                    }
                    val candidateReciterId = idMapping[rawReciterId.trim()] ?: rawReciterId.trim()
                    val reciterExists = DefaultReciters.list.any { it.id == candidateReciterId } ||
                        existingCustomById.containsKey(candidateReciterId) ||
                        resolvedRecitersById.containsKey(candidateReciterId)
                    if (!reciterExists) {
                        return@withContext BackupImportResult(
                            false,
                            "تم رفض الاستيراد: القارئ المحفوظ ($candidateReciterId) غير موجود."
                        )
                    }
                    validatedReciterId = candidateReciterId
                }
            }

            // 7. All validation passed! Apply DB changes atomically in a single Room transaction
            db.withTransaction {
                val existingBookmarks = db.bookmarkDao().getAllBookmarksOnce()
                parsedBookmarks.forEach { incoming ->
                    val alreadyExists = existingBookmarks.any {
                        it.surahNumber == incoming.surahNumber &&
                            it.reciterId == incoming.reciterId &&
                            it.positionMs == incoming.positionMs &&
                            it.note == incoming.note
                    }
                    if (!alreadyExists) {
                        db.bookmarkDao().insertBookmark(incoming)
                    }
                }

                parsedRecitersToInsert.forEach { reciter ->
                    db.reciterDao().insertReciter(reciter)
                }

                // Merge SurahStates & Favorites without deleting existing user favorites/progress
                val mergedSurahNumbers = (parsedStates.map { it.surahNumber } + parsedFavorites).toSet()
                val stateMap = parsedStates.associateBy { it.surahNumber }
                for (surahNum in mergedSurahNumbers) {
                    val existing = db.surahStateDao().getState(surahNum)
                    val incoming = stateMap[surahNum]
                    val mergedFav = (existing?.isFavorite == true) ||
                        (incoming?.isFavorite == true) ||
                        parsedFavorites.contains(surahNum)
                    val useIncomingPos = incoming != null &&
                        incoming.lastPositionMs > 0L &&
                        (existing == null || incoming.lastPlayedTimestamp >= existing.lastPlayedTimestamp)
                    val mergedPos = if (useIncomingPos) incoming!!.lastPositionMs else (existing?.lastPositionMs ?: 0L)
                    val mergedTs = maxOf(existing?.lastPlayedTimestamp ?: 0L, incoming?.lastPlayedTimestamp ?: 0L)
                    db.surahStateDao().insertOrUpdate(
                        SurahStateEntity(
                            surahNumber = surahNum,
                            lastPositionMs = mergedPos,
                            lastPlayedTimestamp = mergedTs,
                            isFavorite = mergedFav
                        )
                    )
                }
            }

            // 8. Apply SharedPreferences updates ONLY after DB transaction succeeded
            val hasPrefUpdates = parsedPositions.isNotEmpty() ||
                validatedRepeatMode != null ||
                validatedPlaybackSpeed != null ||
                validatedLastSurahId != null ||
                validatedReciterId != null

            if (hasPrefUpdates) {
                val editor = playerPrefs.edit()
                parsedPositions.forEach { (key, value) ->
                    val currentVal = playerPrefs.getLong(key, 0L)
                    if (value > 0L || currentVal == 0L) {
                        editor.putLong(key, value)
                    }
                }
                validatedRepeatMode?.let { editor.putString("saved_repeat_mode", it) }
                validatedPlaybackSpeed?.let { editor.putFloat("saved_playback_speed", it) }
                validatedLastSurahId?.let { editor.putInt("last_surah_id", it) }
                validatedReciterId?.let { reciterId ->
                    editor.putString("saved_reciter_id", reciterId)
                    val customEntity = resolvedRecitersById[reciterId] ?: existingCustomById[reciterId]
                    if (customEntity != null) {
                        val snapshot = JSONObject().apply {
                            put("id", customEntity.id)
                            put("name", customEntity.name)
                            put("serverUrl", customEntity.serverUrl)
                            put("availableSurahsCount", customEntity.availableSurahsCount)
                            put("availableSurahsCsv", customEntity.availableSurahsCsv)
                            put("subInfo", customEntity.subInfo)
                        }.toString()
                        editor.putString("saved_custom_reciter_json", snapshot)
                    } else {
                        editor.remove("saved_custom_reciter_json")
                    }
                }
                editor.commit()
            }

            playerManager.reloadPersistedStateIfIdle()

            BackupImportResult(true, "تمت استعادة البيانات والإعدادات بنجاح.")
        } catch (_: Exception) {
            BackupImportResult(false, "فشل استيراد النسخة: تنسيق JSON أو الحقول غير صالحة.")
        }
    }

    private fun remapPositionKeyIfNeeded(key: String, idMapping: Map<String, String>): String {
        if (idMapping.isEmpty()) return key
        val prefix = when {
            key.startsWith("pos_") -> "pos_"
            key.startsWith("dur_") -> "dur_"
            else -> return key
        }
        val remainder = key.removePrefix(prefix)
        val lastUnderscore = remainder.lastIndexOf('_')
        if (lastUnderscore <= 0) return key
        val rawReciterId = remainder.substring(0, lastUnderscore)
        val surahPart = remainder.substring(lastUnderscore + 1)
        val mapped = idMapping[rawReciterId] ?: return key
        return "$prefix${mapped}_$surahPart"
    }
}
