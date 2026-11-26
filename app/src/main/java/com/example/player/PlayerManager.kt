package com.example.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.QuranApp
import com.example.data.DefaultReciters
import com.example.data.QuranData
import com.example.download.DownloadManager
import com.example.model.PlayerState
import com.example.model.Reciter
import com.example.model.RepeatMode
import com.example.model.SleepTimerOption
import com.example.model.Surah
import com.example.service.QuranAudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PlayerManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeOnAudioFocusGain = false

    private val prefs = context.getSharedPreferences("quran_player_prefs", Context.MODE_PRIVATE)
    private val playbackRequestId = AtomicLong(0L)

    private val redirectClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _playerState = MutableStateFlow(
        PlayerState(
            currentReciter = loadSavedReciterSync(),
            repeatMode = loadSavedRepeatMode(),
            playbackSpeed = loadSavedPlaybackSpeed()
        )
    )
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var prepareTimeoutJob: Job? = null
    private var bufferingTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var isPrepared = false
    private var reconnectAttemptCount = 0
    private var lastPausedTimeMs: Long = 0L
    private var isNetworkLostDuringPlayback = false

    @get:androidx.annotation.VisibleForTesting
    internal val reconnectAttemptCountForTest: Int
        get() = reconnectAttemptCount

    @get:androidx.annotation.VisibleForTesting
    internal val activeMediaPlayerForTest: MediaPlayer?
        get() = mediaPlayer

    @androidx.annotation.VisibleForTesting
    internal var forceNetworkConnectedForTest: Boolean? = null

    @androidx.annotation.VisibleForTesting
    internal var reconnectDelayMsForTest: Long = 500L

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                resumeOnAudioFocusGain = false
                if (_playerState.value.isPlaying || _playerState.value.isBuffering) {
                    pause(abandonFocus = false)
                }
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                resumeOnAudioFocusGain = _playerState.value.isPlaying || _playerState.value.isBuffering
                if (_playerState.value.isPlaying || _playerState.value.isBuffering) {
                    pause(abandonFocus = false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    if (isPrepared) {
                        mediaPlayer?.setVolume(0.25f, 0.25f)
                    }
                } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                try {
                    if (isPrepared) {
                        mediaPlayer?.setVolume(1.0f, 1.0f)
                    }
                } catch (_: Exception) {}
                if (resumeOnAudioFocusGain) {
                    resumeOnAudioFocusGain = false
                    resume()
                }
            }
        }
    }

    init {
        val reciter = loadSavedReciterSync()
        val savedSurahId = prefs.getInt("last_surah_id", 1)
        val surah = QuranData.getSurahById(savedSurahId) ?: QuranData.allSurahs.first()
        val savedPos = getSavedPosition(reciter.id, surah.id)
        val savedDur = getSavedDuration(reciter.id, surah.id)

        _playerState.update {
            it.copy(
                currentReciter = reciter,
                currentSurah = surah,
                currentPositionMs = savedPos,
                totalDurationMs = savedDur,
                isPlaying = false,
                isBuffering = false
            )
        }

        // Refresh custom reciter from Room DB asynchronously in case metadata changed
        restoreCustomReciterFromDatabaseIfNeeded(reciter.id, surah.id)
        restoreSavedSleepTimer()
        registerNetworkCallbackSafely()
    }

    private fun loadSavedReciterSync(): Reciter {
        val reciterId = prefs.getString("saved_reciter_id", DefaultReciters.defaultReciter.id)
            ?: DefaultReciters.defaultReciter.id

        DefaultReciters.list.firstOrNull { it.id == reciterId }?.let { return it }

        val cachedJson = prefs.getString("saved_custom_reciter_json", null)
        if (!cachedJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(cachedJson)
                if (obj.optString("id") == reciterId) {
                    val csv = obj.optString("availableSurahsCsv", "")
                    val surahSet = if (csv.isBlank()) emptySet() else {
                        csv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                    }
                    return Reciter(
                        id = reciterId,
                        name = obj.optString("name", DefaultReciters.defaultReciter.name),
                        serverUrl = obj.optString("serverUrl", ""),
                        availableSurahsCount = obj.optInt("availableSurahsCount", 114),
                        availableSurahsList = surahSet,
                        isCustom = true,
                        subInfo = obj.optString("subInfo", "قارئ مخصص")
                    )
                }
            } catch (_: Exception) {}
        }
        return DefaultReciters.defaultReciter
    }

    private fun saveCustomReciterSnapshot(reciter: Reciter) {
        if (reciter.isCustom) {
            val json = JSONObject().apply {
                put("id", reciter.id)
                put("name", reciter.name)
                put("serverUrl", reciter.serverUrl)
                put("availableSurahsCount", reciter.availableSurahsCount)
                put("availableSurahsCsv", reciter.availableSurahsList.joinToString(","))
                put("subInfo", reciter.subInfo)
            }.toString()
            prefs.edit().putString("saved_custom_reciter_json", json).apply()
        } else {
            prefs.edit().remove("saved_custom_reciter_json").apply()
        }
    }

    private fun restoreCustomReciterFromDatabaseIfNeeded(savedId: String, savedSurahId: Int) {
        if (DefaultReciters.list.any { it.id == savedId }) return
        scope.launch(Dispatchers.IO) {
            try {
                val entity = QuranApp.database.reciterDao().getReciterById(savedId) ?: return@launch
                val dbReciter = entity.toReciter()
                saveCustomReciterSnapshot(dbReciter)
                val pos = getSavedPosition(dbReciter.id, savedSurahId)
                val dur = getSavedDuration(dbReciter.id, savedSurahId)
                withContext(Dispatchers.Main) {
                    // Only apply if user hasn't switched reciter/started playing in the meantime
                    if (!_playerState.value.isPlaying && !_playerState.value.isBuffering &&
                        _playerState.value.currentReciter?.id != dbReciter.id
                    ) {
                        _playerState.update {
                            it.copy(
                                currentReciter = dbReciter,
                                currentPositionMs = pos,
                                totalDurationMs = dur
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadSavedRepeatMode(): RepeatMode {
        val modeStr = prefs.getString("saved_repeat_mode", RepeatMode.ALL.name)
        return try {
            RepeatMode.valueOf(modeStr ?: RepeatMode.ALL.name)
        } catch (_: Exception) {
            RepeatMode.ALL
        }
    }

    private fun loadSavedPlaybackSpeed(): Float {
        return prefs.getFloat("saved_playback_speed", 1.0f).coerceIn(0.5f, 2.0f)
    }

    private fun registerNetworkCallbackSafely() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        if (isNetworkLostDuringPlayback) {
                            isNetworkLostDuringPlayback = false
                            _playerState.update { it.copy(errorMessage = null) }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    scope.launch {
                        val state = _playerState.value
                        if (!state.isPlayingOffline && state.isBuffering && !isNetworkConnected()) {
                            isNetworkLostDuringPlayback = true
                            playbackRequestId.incrementAndGet()
                            releaseMediaPlayer()
                            abandonAudioFocus()
                            _playerState.update {
                                it.copy(
                                    isBuffering = false,
                                    isPlaying = false,
                                    errorMessage = "انقطع الاتصال بالإنترنت. يمكنك متابعة التشغيل عند عودة الشبكة أو الاستماع للسور المنزلة."
                                )
                            }
                        }
                    }
                }
            })
        } catch (_: Exception) {}
    }

    internal fun reloadPersistedStateIfIdle() {
        val state = _playerState.value
        if (state.isPlaying || state.isBuffering) return
        val reciter = loadSavedReciterSync()
        val savedSurahId = prefs.getInt("last_surah_id", 1)
        val surah = QuranData.getSurahById(savedSurahId) ?: QuranData.allSurahs.first()
        val savedPos = getSavedPosition(reciter.id, surah.id)
        val savedDur = getSavedDuration(reciter.id, surah.id)
        _playerState.update {
            it.copy(
                currentReciter = reciter,
                currentSurah = surah,
                currentPositionMs = savedPos,
                totalDurationMs = savedDur,
                repeatMode = loadSavedRepeatMode(),
                playbackSpeed = loadSavedPlaybackSpeed(),
                isPlaying = false,
                isBuffering = false
            )
        }
        restoreCustomReciterFromDatabaseIfNeeded(reciter.id, surah.id)
    }

    private fun isNetworkConnected(): Boolean {
        forceNetworkConnectedForTest?.let { return it }
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun getSavedPosition(reciterId: String, surahNumber: Int): Long {
        return prefs.getLong("pos_${reciterId}_$surahNumber", 0L)
    }

    fun getSavedDuration(reciterId: String, surahNumber: Int): Long {
        return prefs.getLong("dur_${reciterId}_$surahNumber", 0L)
    }

    fun savePosition(
        reciterId: String,
        surahNumber: Int,
        positionMs: Long,
        durationMs: Long,
        force: Boolean = false
    ) {
        if (!force && positionMs <= 1000L) {
            val existing = getSavedPosition(reciterId, surahNumber)
            if (existing > 3000L) {
                return
            }
        }

        prefs.edit()
            .putLong("pos_${reciterId}_$surahNumber", positionMs)
            .putLong("dur_${reciterId}_$surahNumber", durationMs)
            .putString("saved_reciter_id", reciterId)
            .putInt("last_surah_id", surahNumber)
            .apply()

        scope.launch(Dispatchers.IO) {
            try {
                QuranApp.database.surahStateDao().updateLastPosition(surahNumber, positionMs)
            } catch (_: Exception) {}
        }
    }

    fun setReciter(reciter: Reciter) {
        prefs.edit().putString("saved_reciter_id", reciter.id).apply()
        saveCustomReciterSnapshot(reciter)

        val currentSurah = _playerState.value.currentSurah
        val wasPlayingOrBuffering = _playerState.value.isPlaying || _playerState.value.isBuffering

        val savedPos = if (currentSurah != null) getSavedPosition(reciter.id, currentSurah.id) else 0L
        val savedDur = if (currentSurah != null) getSavedDuration(reciter.id, currentSurah.id) else 0L

        playbackRequestId.incrementAndGet()
        reconnectJob?.cancel()

        _playerState.update {
            it.copy(
                currentReciter = reciter,
                currentPositionMs = savedPos,
                totalDurationMs = savedDur,
                isBuffering = false,
                errorMessage = null
            )
        }

        if (wasPlayingOrBuffering && currentSurah != null) {
            playSurah(currentSurah, seekToMs = savedPos, autoPlay = true)
        } else {
            releaseMediaPlayer()
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        prefs.edit().putString("saved_repeat_mode", mode.name).apply()
        _playerState.update { it.copy(repeatMode = mode) }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat("saved_playback_speed", clamped).apply()
        _playerState.update { it.copy(playbackSpeed = clamped) }

        val mp = mediaPlayer
        if (mp != null && isPrepared && _playerState.value.isPlaying) {
            applyPlaybackSpeedIfSupported(mp, clamped)
        }
    }

    private fun applyPlaybackSpeedIfSupported(mp: MediaPlayer, speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params = mp.playbackParams
                params.speed = speed
                mp.playbackParams = params
            } catch (_: Exception) {}
        }
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerJob?.cancel()
        if (option == SleepTimerOption.OFF) {
            prefs.edit()
                .remove("sleep_timer_option")
                .remove("sleep_timer_end_ms")
                .apply()
            _playerState.update {
                it.copy(
                    sleepTimerOption = SleepTimerOption.OFF,
                    sleepTimerSecondsRemaining = 0L
                )
            }
            return
        }

        if (option == SleepTimerOption.END_OF_SURAH) {
            prefs.edit()
                .putString("sleep_timer_option", option.name)
                .remove("sleep_timer_end_ms")
                .apply()
            _playerState.update {
                it.copy(
                    sleepTimerOption = SleepTimerOption.END_OF_SURAH,
                    sleepTimerSecondsRemaining = 0L
                )
            }
            return
        }

        val deadlineMs = System.currentTimeMillis() + option.totalSeconds * 1000L
        prefs.edit()
            .putString("sleep_timer_option", option.name)
            .putLong("sleep_timer_end_ms", deadlineMs)
            .apply()

        startSleepTimerCountdown(option, deadlineMs)
    }

    private fun startSleepTimerCountdown(option: SleepTimerOption, deadlineMs: Long) {
        sleepTimerJob?.cancel()
        val initialRemaining = ((deadlineMs - System.currentTimeMillis() + 500L) / 1000L).coerceAtLeast(0L)
        if (initialRemaining <= 0L) {
            clearSleepTimerPrefs()
            _playerState.update {
                it.copy(sleepTimerOption = SleepTimerOption.OFF, sleepTimerSecondsRemaining = 0L)
            }
            return
        }

        _playerState.update {
            it.copy(
                sleepTimerOption = option,
                sleepTimerSecondsRemaining = initialRemaining
            )
        }

        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = ((deadlineMs - System.currentTimeMillis() + 500L) / 1000L).coerceAtLeast(0L)
                _playerState.update { it.copy(sleepTimerSecondsRemaining = remaining) }
                if (remaining <= 0L) {
                    clearSleepTimerPrefs()
                    pause()
                    _playerState.update {
                        it.copy(
                            sleepTimerOption = SleepTimerOption.OFF,
                            sleepTimerSecondsRemaining = 0L
                        )
                    }
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun restoreSavedSleepTimer() {
        val optionName = prefs.getString("sleep_timer_option", null) ?: return
        val option = try {
            SleepTimerOption.valueOf(optionName)
        } catch (_: Exception) {
            clearSleepTimerPrefs()
            return
        }
        when {
            option == SleepTimerOption.OFF -> clearSleepTimerPrefs()
            option == SleepTimerOption.END_OF_SURAH -> {
                _playerState.update {
                    it.copy(sleepTimerOption = SleepTimerOption.END_OF_SURAH, sleepTimerSecondsRemaining = 0L)
                }
            }
            else -> {
                val deadlineMs = prefs.getLong("sleep_timer_end_ms", 0L)
                if (deadlineMs > System.currentTimeMillis()) {
                    startSleepTimerCountdown(option, deadlineMs)
                } else {
                    clearSleepTimerPrefs()
                }
            }
        }
    }

    private fun clearSleepTimerPrefs() {
        prefs.edit()
            .remove("sleep_timer_option")
            .remove("sleep_timer_end_ms")
            .apply()
    }

    fun playSurah(
        surah: Surah,
        seekToMs: Long? = null,
        startFromBeginning: Boolean = false,
        autoPlay: Boolean = true
    ) {
        playSurahInternal(
            surah = surah,
            seekToMs = seekToMs,
            startFromBeginning = startFromBeginning,
            autoPlay = autoPlay,
            isReconnectAttempt = false
        )
    }

    private fun playSurahInternal(
        surah: Surah,
        seekToMs: Long?,
        startFromBeginning: Boolean,
        autoPlay: Boolean,
        isReconnectAttempt: Boolean
    ) {
        val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter
        val requestId = playbackRequestId.incrementAndGet()
        reconnectJob?.cancel()
        prepareTimeoutJob?.cancel()
        bufferingTimeoutJob?.cancel()

        if (!isReconnectAttempt) {
            reconnectAttemptCount = 0
        }

        if (!reciter.isSurahAvailable(surah.id)) {
            releaseMediaPlayer()
            _playerState.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "سورة ${surah.name} غير متوفرة بتسجيل ${reciter.name}"
                )
            }
            return
        }

        releaseMediaPlayer()

        val savedPos = getSavedPosition(reciter.id, surah.id)
        val savedDur = getSavedDuration(reciter.id, surah.id)
        val targetSeekMs = when {
            seekToMs != null -> seekToMs
            startFromBeginning -> 0L
            savedDur > 0 && savedPos >= savedDur - 3000L -> 0L
            else -> savedPos
        }

        val downloadManager = DownloadManager.getInstance(context)
        val validLocalFile = downloadManager.getValidLocalAudioFile(reciter.id, surah.id)
        val isOffline = validLocalFile != null
        val rawUrl = reciter.getAudioUrl(surah.id)

        _playerState.update {
            it.copy(
                currentSurah = surah,
                currentPositionMs = targetSeekMs,
                totalDurationMs = if (savedDur > 0) savedDur else 0L,
                isBuffering = autoPlay,
                isPlaying = false,
                isPlayingOffline = isOffline,
                errorMessage = null
            )
        }

        prefs.edit().putInt("last_surah_id", surah.id).apply()

        if (!isOffline && autoPlay && !isNetworkConnected()) {
            _playerState.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "لا يوجد اتصال بالإنترنت لتشغيل البث المباشر. تأكد من الشبكة أو شغّل السور المنزلة."
                )
            }
            return
        }

        if (autoPlay) {
            syncServiceNotification()
        }

        if (validLocalFile != null) {
            prepareMediaPlayer(
                requestId = requestId,
                surah = surah,
                reciter = reciter,
                localPath = validLocalFile.absolutePath,
                remoteUrl = null,
                targetSeekMs = targetSeekMs,
                savedDur = savedDur,
                autoPlay = autoPlay
            )
        } else if (rawUrl.contains("archive.org/download/", ignoreCase = true) && autoPlay) {
            // Resolve Archive.org 302 redirect URL cleanly on IO thread before passing to MediaPlayer
            scope.launch {
                val resolved = withContext(Dispatchers.IO) { resolveDirectAudioUrl(rawUrl) }
                if (requestId != playbackRequestId.get()) return@launch
                prepareMediaPlayer(
                    requestId = requestId,
                    surah = surah,
                    reciter = reciter,
                    localPath = null,
                    remoteUrl = resolved,
                    targetSeekMs = targetSeekMs,
                    savedDur = savedDur,
                    autoPlay = autoPlay
                )
            }
        } else {
            prepareMediaPlayer(
                requestId = requestId,
                surah = surah,
                reciter = reciter,
                localPath = null,
                remoteUrl = rawUrl,
                targetSeekMs = targetSeekMs,
                savedDur = savedDur,
                autoPlay = autoPlay
            )
        }
    }

    private fun resolveDirectAudioUrl(url: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 QuranAudio/1.0")
                .build()
            redirectClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.request.url.toString() else url
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun prepareMediaPlayer(
        requestId: Long,
        surah: Surah,
        reciter: Reciter,
        localPath: String?,
        remoteUrl: String?,
        targetSeekMs: Long,
        savedDur: Long,
        autoPlay: Boolean
    ) {
        if (requestId != playbackRequestId.get()) return

        if (autoPlay) {
            startPrepareTimeout(requestId)
        }

        try {
            val newPlayer = MediaPlayer().apply {
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                if (localPath != null) {
                    setDataSource(localPath)
                } else {
                    val uri = Uri.parse(remoteUrl ?: reciter.getAudioUrl(surah.id))
                    val headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) QuranAudio/1.0",
                        "Accept" to "*/*"
                    )
                    setDataSource(context, uri, headers)
                }

                setOnInfoListener { mp, what, _ ->
                    if (requestId != playbackRequestId.get() || mp !== mediaPlayer) return@setOnInfoListener false
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            _playerState.update { it.copy(isBuffering = true) }
                            startBufferingWatchdog(requestId)
                            true
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            bufferingTimeoutJob?.cancel()
                            _playerState.update { it.copy(isBuffering = false) }
                            true
                        }
                        else -> false
                    }
                }

                setOnPreparedListener { mp ->
                    if (requestId != playbackRequestId.get() || mp !== mediaPlayer) {
                        try { mp.release() } catch (_: Exception) {}
                        return@setOnPreparedListener
                    }

                    prepareTimeoutJob?.cancel()
                    bufferingTimeoutJob?.cancel()
                    isPrepared = true

                    val duration = try {
                        val d = mp.duration.toLong()
                        if (d > 0L) d else 0L
                    } catch (_: Exception) {
                        0L
                    }
                    val resolvedDuration = if (duration > 0L) duration else savedDur

                    _playerState.update {
                        it.copy(
                            isBuffering = false,
                            totalDurationMs = resolvedDuration
                        )
                    }

                    if (autoPlay) {
                        val focusStatus = requestAudioFocus()
                        if (focusStatus == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                            _playerState.update {
                                it.copy(
                                    isPlaying = false,
                                    isBuffering = false,
                                    errorMessage = "تعذر تشغيل الصوت لأن النظام رفض التركيز الصوتي."
                                )
                            }
                            return@setOnPreparedListener
                        }
                        if (focusStatus == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                            resumeOnAudioFocusGain = true
                            _playerState.update { it.copy(isPlaying = false, isBuffering = false) }
                            return@setOnPreparedListener
                        }

                        try { mp.setVolume(1f, 1f) } catch (_: Exception) {}

                        val canSeek = targetSeekMs > 1000L && (resolvedDuration <= 0L || targetSeekMs < resolvedDuration - 2000L)
                        if (canSeek) {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    mp.seekTo(targetSeekMs, MediaPlayer.SEEK_CLOSEST)
                                } else {
                                    mp.seekTo(targetSeekMs.toInt())
                                }
                            } catch (_: Exception) {}
                        }

                        try {
                            mp.start()
                            val speed = _playerState.value.playbackSpeed
                            if (speed != 1.0f) {
                                applyPlaybackSpeedIfSupported(mp, speed)
                            }
                        } catch (_: Exception) {
                            releaseMediaPlayer()
                            _playerState.update {
                                it.copy(isPlaying = false, isBuffering = false, errorMessage = "تعذر بدء التشغيل الصوتي.")
                            }
                            return@setOnPreparedListener
                        }

                        _playerState.update {
                            it.copy(
                                isPlaying = true,
                                isBuffering = false,
                                currentPositionMs = if (canSeek) targetSeekMs else 0L
                            )
                        }
                        startProgressTracking()
                        syncServiceNotification()
                    } else {
                        _playerState.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                currentPositionMs = targetSeekMs
                            )
                        }
                    }
                }

                setOnCompletionListener { mp ->
                    if (requestId != playbackRequestId.get() || mp !== mediaPlayer) return@setOnCompletionListener
                    handlePlaybackCompletion()
                }

                setOnErrorListener { mp, what, extra ->
                    if (requestId != playbackRequestId.get() || mp !== mediaPlayer) return@setOnErrorListener true
                    android.util.Log.e("PlayerManager", "MediaPlayer error: what=$what, extra=$extra")
                    prepareTimeoutJob?.cancel()
                    bufferingTimeoutJob?.cancel()
                    isPrepared = false

                    val current = _playerState.value.currentSurah
                    val activeReciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter
                    val lastPos = if (current != null) getSavedPosition(activeReciter.id, current.id) else _playerState.value.currentPositionMs
                    releaseMediaPlayer()

                    val resumeSeekMs = if (lastPos > 0L) lastPos else targetSeekMs
                    if (localPath == null &&
                        reconnectAttemptCount < MAX_RECONNECT_ATTEMPTS &&
                        current != null &&
                        isNetworkConnected()
                    ) {
                        reconnectAttemptCount++
                        reconnectJob?.cancel()
                        reconnectJob = scope.launch {
                            delay(reconnectDelayMsForTest)
                            if (requestId == playbackRequestId.get()) {
                                playSurahInternal(
                                    surah = current,
                                    seekToMs = resumeSeekMs,
                                    startFromBeginning = false,
                                    autoPlay = true,
                                    isReconnectAttempt = true
                                )
                            }
                        }
                        return@setOnErrorListener true
                    }

                    abandonAudioFocus()
                    val userMessage = when {
                        !isNetworkConnected() -> "انقطع الاتصال بالإنترنت. يرجى التحقق من الشبكة أو الاستماع للسور المنزلة."
                        extra == -1004 || what == 1 -> "تعذر الاتصال بخادم التلاوة. يرجى التحقق من اتصال الإنترنت أو الاستماع دون إنترنت."
                        extra == -1010 || extra == -1007 -> "صيغة الملف الصوتي غير مدعومة."
                        what == -38 || extra == -38 -> "حدث خطأ في مشغل الصوت. يرجى إعادة المحاولة."
                        else -> "تعذر تشغيل الصوت (رمز $what, $extra)."
                    }

                    _playerState.update {
                        it.copy(
                            isBuffering = false,
                            isPlaying = false,
                            errorMessage = userMessage
                        )
                    }
                    true
                }

                mediaPlayer = this
                prepareAsync()
            }
            mediaPlayer = newPlayer
        } catch (e: IOException) {
            prepareTimeoutJob?.cancel()
            releaseMediaPlayer()
            _playerState.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "فشل تحميل ملف التلاوة: ${e.message}"
                )
            }
        } catch (e: Exception) {
            prepareTimeoutJob?.cancel()
            releaseMediaPlayer()
            _playerState.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "تعذر تهيئة المشغل الصوتي: ${e.localizedMessage ?: ""}"
                )
            }
        }
    }

    private fun startPrepareTimeout(requestId: Long) {
        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = scope.launch {
            delay(PREPARE_TIMEOUT_MS)
            if (requestId == playbackRequestId.get() && !isPrepared && _playerState.value.isBuffering) {
                playbackRequestId.incrementAndGet()
                releaseMediaPlayer()
                abandonAudioFocus()
                _playerState.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = "انتهت مهلة تحميل التلاوة. يرجى التحقق من سرعة الإنترنت أو المحاولة مجدداً."
                    )
                }
            }
        }
    }

    private fun startBufferingWatchdog(requestId: Long) {
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = scope.launch {
            delay(PREPARE_TIMEOUT_MS)
            if (requestId == playbackRequestId.get() && _playerState.value.isBuffering) {
                playbackRequestId.incrementAndGet()
                releaseMediaPlayer()
                abandonAudioFocus()
                _playerState.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = "انقطع بث التلاوة أو استغرق وقتاً طويلاً. يرجى المحاولة مجدداً."
                    )
                }
            }
        }
    }

    fun togglePlayPause() {
        if (_playerState.value.isPlaying) {
            pause()
        } else if (_playerState.value.isBuffering) {
            // Cancel stuck/slow buffering on tap instead of locking the button
            pause()
        } else {
            resume()
        }
    }

    fun resume() {
        val current = _playerState.value.currentSurah ?: QuranData.allSurahs.first()
        val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter
        val savedPos = getSavedPosition(reciter.id, current.id).let {
            if (it > 0) it else _playerState.value.currentPositionMs
        }

        val isOffline = DownloadManager.getInstance(context).isDownloaded(reciter.id, current.id)
        val timeSincePause = if (lastPausedTimeMs > 0) System.currentTimeMillis() - lastPausedTimeMs else 0L

        if (mediaPlayer != null && !isOffline && timeSincePause > 20_000L) {
            releaseMediaPlayer()
        }

        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            val focusStatus = requestAudioFocus()
            if (focusStatus == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                _playerState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = "تعذر الحصول على التركيز الصوتي للتشغيل."
                    )
                }
                return
            }
            if (focusStatus == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                resumeOnAudioFocusGain = true
                _playerState.update { it.copy(isPlaying = false, isBuffering = false) }
                return
            }

            try {
                try { mp.setVolume(1f, 1f) } catch (_: Exception) {}
                mp.start()
                val speed = _playerState.value.playbackSpeed
                if (speed != 1.0f) {
                    applyPlaybackSpeedIfSupported(mp, speed)
                }
                _playerState.update { it.copy(isPlaying = true, isBuffering = false, errorMessage = null) }
                startProgressTracking()
                syncServiceNotification()
                return
            } catch (_: Exception) {
                releaseMediaPlayer()
            }
        } else if (mp != null && !isPrepared && _playerState.value.isBuffering) {
            return
        }

        playSurah(current, seekToMs = savedPos, autoPlay = true)
    }

    fun pause(abandonFocus: Boolean = true) {
        lastPausedTimeMs = System.currentTimeMillis()
        var currentPos = _playerState.value.currentPositionMs
        var duration = _playerState.value.totalDurationMs
        val surah = _playerState.value.currentSurah
        val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter

        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            try {
                if (mp.isPlaying) {
                    mp.pause()
                }
                val mpPos = mp.currentPosition.toLong()
                val mpDur = mp.duration.toLong()
                if (mpPos > 0) currentPos = mpPos
                if (mpDur > 0) duration = mpDur
            } catch (_: Exception) {}
        } else if (!isPrepared) {
            // If paused while still buffering, cancel the pending prepare request cleanly
            playbackRequestId.incrementAndGet()
            releaseMediaPlayer()
        }

        if (abandonFocus) {
            resumeOnAudioFocusGain = false
            abandonAudioFocus()
        }

        if (surah != null && currentPos > 0) {
            savePosition(reciter.id, surah.id, currentPos, duration)
        }

        _playerState.update {
            it.copy(
                isPlaying = false,
                isBuffering = false,
                currentPositionMs = currentPos,
                totalDurationMs = duration
            )
        }
        progressJob?.cancel()
        prepareTimeoutJob?.cancel()
        bufferingTimeoutJob?.cancel()
        syncServiceNotification()
    }

    fun stop(notifyService: Boolean = true) {
        val current = _playerState.value.currentSurah
        val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter
        val currentPos = _playerState.value.currentPositionMs
        val duration = _playerState.value.totalDurationMs
        val wasActive = _playerState.value.isPlaying || _playerState.value.isBuffering || mediaPlayer != null

        playbackRequestId.incrementAndGet()
        reconnectJob?.cancel()
        resumeOnAudioFocusGain = false

        if (current != null && currentPos > 0) {
            savePosition(reciter.id, current.id, currentPos, duration)
        }

        releaseMediaPlayer()
        abandonAudioFocus()

        _playerState.update {
            it.copy(
                isPlaying = false,
                isBuffering = false
            )
        }
        if (notifyService && wasActive) {
            stopServiceNotification()
        }
    }

    fun seekTo(positionMs: Long) {
        val surah = _playerState.value.currentSurah
        val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter
        val currentDur = _playerState.value.totalDurationMs
        val safePos = if (currentDur > 0L) positionMs.coerceIn(0L, currentDur) else positionMs.coerceAtLeast(0L)

        mediaPlayer?.let { mp ->
            if (isPrepared) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mp.seekTo(safePos, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        mp.seekTo(safePos.toInt())
                    }
                    _playerState.update { it.copy(currentPositionMs = safePos) }
                    if (surah != null) {
                        savePosition(reciter.id, surah.id, safePos, currentDur, force = true)
                    }
                    return
                } catch (_: Exception) {}
            }
        }

        _playerState.update { it.copy(currentPositionMs = safePos) }
        if (surah != null) {
            savePosition(reciter.id, surah.id, safePos, _playerState.value.totalDurationMs, force = true)
        }
    }

    fun playNextSurah() {
        val current = _playerState.value.currentSurah ?: return
        val nextId = if (current.id < 114) current.id + 1 else 1
        val nextSurah = QuranData.getSurahById(nextId) ?: return
        playSurah(nextSurah, startFromBeginning = true, autoPlay = true)
    }

    fun playPreviousSurah() {
        val current = _playerState.value.currentSurah ?: return
        val prevId = if (current.id > 1) current.id - 1 else 114
        val prevSurah = QuranData.getSurahById(prevId) ?: return
        playSurah(prevSurah, startFromBeginning = true, autoPlay = true)
    }

    internal fun handlePlaybackCompletion() {
        val state = _playerState.value
        val surah = state.currentSurah
        val reciter = state.currentReciter ?: DefaultReciters.defaultReciter

        if (surah != null) {
            savePosition(reciter.id, surah.id, 0L, state.totalDurationMs, force = true)
        }

        if (state.sleepTimerOption == SleepTimerOption.END_OF_SURAH) {
            clearSleepTimerPrefs()
            _playerState.update {
                it.copy(
                    sleepTimerOption = SleepTimerOption.OFF,
                    sleepTimerSecondsRemaining = 0L,
                    currentPositionMs = 0L
                )
            }
            stop()
            return
        }

        when (state.repeatMode) {
            RepeatMode.NONE -> stop()
            RepeatMode.ONE -> surah?.let { playSurah(it, startFromBeginning = true, autoPlay = true) }
            RepeatMode.ALL -> playNextSurah()
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(1000L)
                if (!isPrepared) continue
                mediaPlayer?.let { mp ->
                    try {
                        if (mp.isPlaying) {
                            val current = mp.currentPosition.toLong()
                            val duration = try {
                                val d = mp.duration.toLong()
                                if (d > 0L) d else 0L
                            } catch (_: Exception) {
                                0L
                            }
                            if (current > 500L) {
                                _playerState.update {
                                    it.copy(
                                        currentPositionMs = current,
                                        totalDurationMs = if (duration > 0L) duration else it.totalDurationMs
                                    )
                                }
                                val surah = _playerState.value.currentSurah
                                val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter
                                if (surah != null) {
                                    savePosition(
                                        reciter.id,
                                        surah.id,
                                        current,
                                        if (duration > 0L) duration else _playerState.value.totalDurationMs
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun requestAudioFocus(): Int {
        val am = audioManager ?: return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val playbackAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(playbackAttributes)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .build()
                }
                audioFocusRequest?.let { am.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            result
        } catch (_: Exception) {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (_: Exception) {}
        hasAudioFocus = false
    }

    private fun releaseMediaPlayer() {
        isPrepared = false
        progressJob?.cancel()
        prepareTimeoutJob?.cancel()
        bufferingTimeoutJob?.cancel()
        try {
            mediaPlayer?.let { mp ->
                mp.setOnPreparedListener(null)
                mp.setOnErrorListener(null)
                mp.setOnCompletionListener(null)
                mp.setOnInfoListener(null)
                mp.reset()
                mp.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun syncServiceNotification() {
        val state = _playerState.value
        val shouldRunForeground = state.isPlaying || state.isBuffering
        val intent = Intent(context, QuranAudioService::class.java).apply {
            action = if (shouldRunForeground) QuranAudioService.ACTION_UPDATE_STATUS else QuranAudioService.ACTION_PAUSE_STATUS
            if (shouldRunForeground) {
                putExtra(QuranAudioService.EXTRA_STARTED_FOREGROUND, true)
            }
        }
        try {
            if (shouldRunForeground) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun stopServiceNotification() {
        val intent = Intent(context, QuranAudioService::class.java).apply {
            action = QuranAudioService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}
    }

    companion object {
        private const val PREPARE_TIMEOUT_MS = 20_000L
        private const val MAX_RECONNECT_ATTEMPTS = 1

        @Volatile
        private var instance: PlayerManager? = null

        fun getInstance(context: Context): PlayerManager {
            return instance ?: synchronized(this) {
                instance ?: PlayerManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
