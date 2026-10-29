package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class QuranViewModel(application: Application) : AndroidViewModel(application) {

    private val playerManager = PlayerManager.getInstance(application)
    private val downloadManager = DownloadManager.getInstance(application)
    private val prefs = application.getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)

    val playerState: StateFlow<PlayerState> = playerManager.playerState

    // Search Query State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Filter Favorites Only toggle
    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    // Favorite Surah IDs from Room
    val favoriteSurahNumbers: StateFlow<List<Int>> = QuranApp.database.surahStateDao()
        .getFavoriteSurahNumbers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All Reciters from Room (defaults + custom)
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

    // Filtered Surahs based on search query and favorites filter
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

    // Bookmarks from Room
    val bookmarks: StateFlow<List<Bookmark>> = QuranApp.database.bookmarkDao()
        .getAllBookmarks()
        .combine(MutableStateFlow(emptyList<Bookmark>())) { entities, _ ->
            entities.map { it.toBookmark() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Downloads from DownloadManager
    val downloads: StateFlow<List<DownloadItem>> = downloadManager.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Friday Reminder setting
    private val _fridayReminderEnabled = MutableStateFlow(prefs.getBoolean("friday_reminder", true))
    val fridayReminderEnabled: StateFlow<Boolean> = _fridayReminderEnabled.asStateFlow()

    init {
        // Schedule if enabled
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
        // Do not lose favorite/bookmark data when switching reciter; play with user's current reciter or bookmark's reciter
        val reciter = reciters.value.firstOrNull { it.id == bookmark.reciterId }
            ?: (playerState.value.currentReciter ?: DefaultReciters.defaultReciter)
        playerManager.setReciter(reciter)
        playerManager.playSurah(surah, seekToMs = bookmark.positionMs)
    }

    fun startDownload(surah: Surah) {
        val reciter = playerState.value.currentReciter ?: DefaultReciters.defaultReciter
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
            val cleanUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
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
        }
    }

    suspend fun exportBackupJson(): String {
        val root = JSONObject()
        val bookmarksArray = JSONArray()
        bookmarks.value.forEach { b ->
            val obj = JSONObject().apply {
                put("surahNumber", b.surahNumber)
                put("surahName", b.surahName)
                put("reciterId", b.reciterId)
                put("reciterName", b.reciterName)
                put("positionMs", b.positionMs)
                put("note", b.note)
                put("timestamp", b.timestamp)
            }
            bookmarksArray.put(obj)
        }
        root.put("bookmarks", bookmarksArray)

        val customRecitersArray = JSONArray()
        reciters.value.filter { it.isCustom }.forEach { r ->
            val obj = JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("serverUrl", r.serverUrl)
                put("availableSurahsCount", r.availableSurahsCount)
            }
            customRecitersArray.put(obj)
        }
        root.put("customReciters", customRecitersArray)

        return root.toString(2)
    }

    suspend fun importBackupJson(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)
            if (root.has("bookmarks")) {
                val array = root.getJSONArray("bookmarks")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val bookmark = BookmarkEntity(
                        surahNumber = obj.getInt("surahNumber"),
                        surahName = obj.getString("surahName"),
                        reciterId = obj.getString("reciterId"),
                        reciterName = obj.getString("reciterName"),
                        positionMs = obj.getLong("positionMs"),
                        note = obj.optString("note", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    QuranApp.database.bookmarkDao().insertBookmark(bookmark)
                }
            }

            if (root.has("customReciters")) {
                val array = root.getJSONArray("customReciters")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val entity = ReciterEntity(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        serverUrl = obj.getString("serverUrl"),
                        availableSurahsCount = obj.getInt("availableSurahsCount"),
                        availableSurahsCsv = "",
                        isCustom = true,
                        subInfo = "قارئ مخصص مستعاد من النسخة الاحتياطية"
                    )
                    QuranApp.database.reciterDao().insertReciter(entity)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
