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

    fun addCustomReciter(name: String, serverUrl: String, availableCount: Int) {
        viewModelScope.launch {
            val trimmedUrl = serverUrl.trim()
            val cleanUrl = if (trimmedUrl.endsWith("/")) trimmedUrl else "$trimmedUrl/"
            val id = "custom_${System.currentTimeMillis()}"
            val newReciter = Reciter(
                id = id,
                name = name.trim(),
                serverUrl = cleanUrl,
                availableSurahsCount = availableCount.coerceIn(1, 114),
                isCustom = true,
                subInfo = "قارئ مخصص مضاف من لوحة المطور"
            )
            QuranApp.database.reciterDao().insertReciter(ReciterEntity.fromReciter(newReciter))
        }
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

    suspend fun importBackupJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        if (jsonString.isBlank()) return@withContext false

        try {
            val root = JSONObject(jsonString.trim())
            val hasRecognizedSection = root.has("bookmarks") ||
                root.has("customReciters") ||
                root.has("favorites") ||
                root.has("surahStates") ||
                root.has("reciterPositions")
            if (!hasRecognizedSection) return@withContext false

            // Pre-validate all sections before mutating DB or prefs
            val parsedBookmarks = mutableListOf<BookmarkEntity>()
            if (root.has("bookmarks")) {
                val array = root.getJSONArray("bookmarks")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val surahNumber = obj.getInt("surahNumber")
                    if (surahNumber !in 1..114) return@withContext false
                    val positionMs = obj.getLong("positionMs").coerceAtLeast(0L)
                    val reciterId = obj.getString("reciterId").trim()
                    val reciterName = obj.getString("reciterName").trim()
                    if (reciterId.isEmpty() || reciterName.isEmpty()) return@withContext false
                    val surahName = obj.optString("surahName").ifBlank {
                        QuranData.getSurahById(surahNumber)?.name ?: return@withContext false
                    }
                    parsedBookmarks.add(
                        BookmarkEntity(
                            surahNumber = surahNumber,
                            surahName = surahName,
                            reciterId = reciterId,
                            reciterName = reciterName,
                            positionMs = positionMs,
                            note = obj.optString("note", "").trim(),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }

            val parsedReciters = mutableListOf<ReciterEntity>()
            if (root.has("customReciters")) {
                val array = root.getJSONArray("customReciters")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val rawId = obj.getString("id").trim()
                    val name = obj.getString("name").trim()
                    val serverUrl = obj.getString("serverUrl").trim()
                    val count = obj.getInt("availableSurahsCount").coerceIn(1, 114)
                    if (rawId.isEmpty() || name.isEmpty()) return@withContext false
                    if (!serverUrl.startsWith("https://", ignoreCase = true) &&
                        !serverUrl.startsWith("http://", ignoreCase = true)
                    ) {
                        return@withContext false
                    }
                    val safeId = if (DefaultReciters.list.any { it.id == rawId }) "custom_$rawId" else rawId
                    val normalizedUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                    parsedReciters.add(
                        ReciterEntity(
                            id = safeId,
                            name = name,
                            serverUrl = normalizedUrl,
                            availableSurahsCount = count,
                            availableSurahsCsv = obj.optString("availableSurahsCsv", ""),
                            isCustom = true,
                            subInfo = obj.optString("subInfo", "قارئ مخصص مستعاد من النسخة الاحتياطية")
                                .ifBlank { "قارئ مخصص مستعاد من النسخة الاحتياطية" }
                        )
                    )
                }
            }

            val parsedFavorites = mutableSetOf<Int>()
            if (root.has("favorites")) {
                val favArray = root.getJSONArray("favorites")
                for (i in 0 until favArray.length()) {
                    val surahNum = favArray.getInt(i)
                    if (surahNum !in 1..114) return@withContext false
                    parsedFavorites.add(surahNum)
                }
            }

            val parsedStates = mutableListOf<SurahStateEntity>()
            if (root.has("surahStates")) {
                val stateArray = root.getJSONArray("surahStates")
                for (i in 0 until stateArray.length()) {
                    val obj = stateArray.getJSONObject(i)
                    val surahNum = obj.getInt("surahNumber")
                    if (surahNum !in 1..114) return@withContext false
                    parsedStates.add(
                        SurahStateEntity(
                            surahNumber = surahNum,
                            lastPositionMs = obj.optLong("lastPositionMs", 0L).coerceAtLeast(0L),
                            lastPlayedTimestamp = obj.optLong("lastPlayedTimestamp", 0L).coerceAtLeast(0L),
                            isFavorite = obj.optBoolean("isFavorite", false)
                        )
                    )
                }
            }

            val parsedPositions = mutableMapOf<String, Long>()
            if (root.has("reciterPositions")) {
                val posObj = root.getJSONObject("reciterPositions")
                val keys = posObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.startsWith("pos_") || key.startsWith("dur_")) {
                        val value = posObj.getLong(key).coerceAtLeast(0L)
                        parsedPositions[key] = value
                    }
                }
            }

            // Apply atomically inside a Room transaction (no partial updates on DB failure)
            val db = QuranApp.database
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

                parsedReciters.forEach { reciter ->
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

            // Apply validated SharedPreferences positions & settings after DB commit
            if (parsedPositions.isNotEmpty() || root.has("playerSettings")) {
                val editor = playerPrefs.edit()
                parsedPositions.forEach { (key, value) ->
                    val currentVal = playerPrefs.getLong(key, 0L)
                    if (value > 0L || currentVal == 0L) {
                        editor.putLong(key, value)
                    }
                }
                if (root.has("playerSettings")) {
                    val settingsObj = root.getJSONObject("playerSettings")
                    val repeatName = settingsObj.optString("savedRepeatMode", "")
                    if (RepeatMode.entries.any { it.name == repeatName }) {
                        editor.putString("saved_repeat_mode", repeatName)
                    }
                    if (settingsObj.has("savedPlaybackSpeed")) {
                        val speed = settingsObj.getDouble("savedPlaybackSpeed").toFloat().coerceIn(0.5f, 2.0f)
                        editor.putFloat("saved_playback_speed", speed)
                    }
                }
                editor.apply()
            }

            true
        } catch (_: Exception) {
            false
        }
    }
}
