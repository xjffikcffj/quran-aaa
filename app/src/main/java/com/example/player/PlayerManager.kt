package com.example.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import java.io.IOException

class PlayerManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val prefs = context.getSharedPreferences("quran_player_prefs", Context.MODE_PRIVATE)

    private val _playerState = MutableStateFlow(
        PlayerState(
            currentReciter = loadSavedReciter(),
            repeatMode = loadSavedRepeatMode()
        )
    )
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var isPrepared = false
    private var hasRetriedReconnect = false
    private var lastPausedTimeMs: Long = 0L

    init {
        // Restore last played surah and position without auto-playing!
        val reciter = loadSavedReciter()
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
    }

    private fun loadSavedReciter(): Reciter {
        val reciterId = prefs.getString("saved_reciter_id", DefaultReciters.defaultReciter.id)
            ?: DefaultReciters.defaultReciter.id
        return DefaultReciters.getReciterById(reciterId)
    }

    private fun loadSavedRepeatMode(): RepeatMode {
        val modeStr = prefs.getString("saved_repeat_mode", RepeatMode.ALL.name)
        return try {
            RepeatMode.valueOf(modeStr ?: RepeatMode.ALL.name)
        } catch (_: Exception) {
            RepeatMode.ALL
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
        // Protection against zero-overwrite:
        // Do not overwrite an existing valid position (> 3000ms) with 0 during buffering or unready states!
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

        // Also persist in local Room database asynchronously
        scope.launch(Dispatchers.IO) {
            try {
                QuranApp.database.surahStateDao().updateLastPosition(surahNumber, positionMs)
            } catch (_: Exception) {}
        }
    }

    fun setReciter(reciter: Reciter) {
        prefs.edit().putString("saved_reciter_id", reciter.id).apply()
        val currentSurah = _playerState.value.currentSurah
        val wasPlaying = _playerState.value.isPlaying

        // Look up saved position for this new reciter with the current surah
        val savedPos = if (currentSurah != null) getSavedPosition(reciter.id, currentSurah.id) else 0L
        val savedDur = if (currentSurah != null) getSavedDuration(reciter.id, currentSurah.id) else 0L

        _playerState.update {
            it.copy(
                currentReciter = reciter,
                currentPositionMs = savedPos,
                totalDurationMs = savedDur
            )
        }

        // If playing, switch audio stream cleanly
        if (wasPlaying && currentSurah != null) {
            playSurah(currentSurah, seekToMs = savedPos, autoPlay = true)
        } else {
            releaseMediaPlayer()
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        prefs.edit().putString("saved_repeat_mode", mode.name).apply()
        _playerState.update { it.copy(repeatMode = mode) }
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerJob?.cancel()
        _playerState.update {
            it.copy(
                sleepTimerOption = option,
                sleepTimerSecondsRemaining = if (option.totalSeconds > 0) option.totalSeconds else 0L
            )
        }

        if (option.totalSeconds > 0) {
            sleepTimerJob = scope.launch {
                var remaining = option.totalSeconds
                while (isActive && remaining > 0) {
                    delay(1000L)
                    remaining--
                    _playerState.update { it.copy(sleepTimerSecondsRemaining = remaining) }
                    if (remaining <= 0) {
                        pause()
                        _playerState.update {
                            it.copy(
                                sleepTimerOption = SleepTimerOption.OFF,
                                sleepTimerSecondsRemaining = 0L
                            )
                        }
                        break
                    }
                }
            }
        }
    }

    fun playSurah(
        surah: Surah,
        seekToMs: Long? = null,
        startFromBeginning: Boolean = false,
        autoPlay: Boolean = true
    ) {
        val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter

        if (!reciter.isSurahAvailable(surah.id)) {
            _playerState.update {
                it.copy(
                    errorMessage = "سورة ${surah.name} غير متوفرة بتسجيل ${reciter.name}"
                )
            }
            return
        }

        releaseMediaPlayer()

        // Determine seek position:
        // 1. Explicit seekToMs if provided
        // 2. 0L if startFromBeginning is requested
        // 3. Saved position for this reciter + surah
        val savedPos = getSavedPosition(reciter.id, surah.id)
        val savedDur = getSavedDuration(reciter.id, surah.id)
        val targetSeekMs = when {
            seekToMs != null -> seekToMs
            startFromBeginning -> 0L
            savedDur > 0 && savedPos >= savedDur - 3000L -> 0L // If was at very end, start fresh
            else -> savedPos
        }

        val downloadManager = DownloadManager.getInstance(context)
        val isOffline = downloadManager.isDownloaded(reciter.id, surah.id)
        val dataSource = if (isOffline) {
            downloadManager.getLocalAudioFile(reciter.id, surah.id).absolutePath
        } else {
            reciter.getAudioUrl(surah.id)
        }

        _playerState.update {
            it.copy(
                currentSurah = surah,
                currentPositionMs = targetSeekMs,
                totalDurationMs = if (savedDur > 0) savedDur else it.totalDurationMs,
                isBuffering = autoPlay,
                isPlaying = false,
                isPlayingOffline = isOffline,
                errorMessage = null
            )
        }

        // Save last active surah
        prefs.edit().putInt("last_surah_id", surah.id).apply()

        try {
            mediaPlayer = MediaPlayer().apply {
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                if (isOffline) {
                    val localFile = downloadManager.getLocalAudioFile(reciter.id, surah.id)
                    if (localFile.exists() && localFile.length() > 0L) {
                        setDataSource(localFile.absolutePath)
                    } else {
                        val uri = Uri.parse(reciter.getAudioUrl(surah.id))
                        val headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) QuranAudio/1.0",
                            "Accept" to "*/*"
                        )
                        setDataSource(context, uri, headers)
                    }
                } else {
                    val uri = Uri.parse(dataSource)
                    val headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) QuranAudio/1.0",
                        "Accept" to "*/*"
                    )
                    setDataSource(context, uri, headers)
                }

                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            _playerState.update { it.copy(isBuffering = true) }
                            true
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            _playerState.update { it.copy(isBuffering = false) }
                            true
                        }
                        else -> false
                    }
                }

                setOnPreparedListener { mp ->
                    isPrepared = true
                    hasRetriedReconnect = false
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
                        requestAudioFocus()
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
                        } catch (_: Exception) {
                            releaseMediaPlayer()
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
                        // User opened without auto-play: do not seek unstarted stream
                        _playerState.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                currentPositionMs = targetSeekMs
                            )
                        }
                    }
                }

                setOnCompletionListener {
                    handlePlaybackCompletion()
                }

                setOnErrorListener { _, what, extra ->
                    android.util.Log.e("PlayerManager", "MediaPlayer error: what=$what, extra=$extra")
                    isPrepared = false
                    val current = _playerState.value.currentSurah
                    val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter
                    val lastPos = if (current != null) getSavedPosition(reciter.id, current.id) else _playerState.value.currentPositionMs
                    releaseMediaPlayer()

                    // If network socket dropped during pause or transient error, retry once cleanly from saved position
                    if (!hasRetriedReconnect && current != null) {
                        hasRetriedReconnect = true
                        scope.launch {
                            delay(300L)
                            playSurah(current, seekToMs = lastPos, autoPlay = true)
                        }
                        return@setOnErrorListener true
                    }

                    val userMessage = when {
                        extra == -1004 || what == 1 -> "تعذر الاتصال بخادم التلاوة. يرجى التحقق من اتصال الإنترنت أو الاستماع دون إنترنت."
                        extra == -1010 || extra == -1007 -> "صيغة الملف الصوتي غير مدعومة."
                        what == -38 || extra == -38 -> "حدث خطأ في مشغل الصوت (-38). يرجى إعادة المحاولة."
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

                prepareAsync()
            }
        } catch (e: IOException) {
            _playerState.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "فشل تحميل ملف التلاوة: ${e.message}"
                )
            }
        }
    }

    fun togglePlayPause() {
        if (_playerState.value.isBuffering) {
            return
        }
        if (_playerState.value.isPlaying) {
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

        // If streaming over HTTP and paused for > 20 seconds, reconnect cleanly
        if (mediaPlayer != null && !isOffline && timeSincePause > 20_000L) {
            releaseMediaPlayer()
        }

        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            try {
                requestAudioFocus()
                try { mp.setVolume(1f, 1f) } catch (_: Exception) {}
                mp.start()
                _playerState.update { it.copy(isPlaying = true, isBuffering = false) }
                startProgressTracking()
                syncServiceNotification()
                return
            } catch (_: Exception) {
                // If mp was in invalid state, release and fall through to playSurah
                releaseMediaPlayer()
            }
        } else if (mp != null && !isPrepared) {
            // Player is already preparing! Do not start or release, keep buffering state
            _playerState.update { it.copy(isBuffering = true, isPlaying = false) }
            return
        }

        // MediaPlayer not loaded, invalidated, or stale; start fresh from saved position
        playSurah(current, seekToMs = savedPos, autoPlay = true)
    }

    fun pause() {
        lastPausedTimeMs = System.currentTimeMillis()
        var currentPos = _playerState.value.currentPositionMs
        var duration = _playerState.value.totalDurationMs
        val surah = _playerState.value.currentSurah
        val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter

        mediaPlayer?.let { mp ->
            if (isPrepared) {
                try {
                    if (mp.isPlaying) {
                        mp.pause()
                    }
                    val mpPos = mp.currentPosition.toLong()
                    val mpDur = mp.duration.toLong()
                    if (mpPos > 0) {
                        currentPos = mpPos
                    }
                    if (mpDur > 0) {
                        duration = mpDur
                    }
                } catch (_: Exception) {}
            }
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
        syncServiceNotification()
    }

    fun stop() {
        val current = _playerState.value.currentSurah
        val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter
        val currentPos = _playerState.value.currentPositionMs
        val duration = _playerState.value.totalDurationMs

        if (current != null && currentPos > 0) {
            savePosition(reciter.id, current.id, currentPos, duration)
        }

        releaseMediaPlayer()
        _playerState.update {
            it.copy(
                isPlaying = false,
                isBuffering = false
            )
        }
        stopServiceNotification()
    }

    fun seekTo(positionMs: Long) {
        val surah = _playerState.value.currentSurah
        val reciter = _playerState.value.currentReciter ?: DefaultReciters.defaultReciter

        mediaPlayer?.let { mp ->
            if (isPrepared) {
                try {
                    val currentDur = _playerState.value.totalDurationMs
                    val safePos = if (currentDur > 0L) positionMs.coerceIn(0L, currentDur) else positionMs.coerceAtLeast(0L)
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

        // If mediaPlayer is not active, record the requested seek position directly
        _playerState.update { it.copy(currentPositionMs = positionMs) }
        if (surah != null) {
            savePosition(reciter.id, surah.id, positionMs, _playerState.value.totalDurationMs, force = true)
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

        // Reset saved position for this surah as it has completed
        if (surah != null) {
            savePosition(reciter.id, surah.id, 0L, state.totalDurationMs, force = true)
        }

        if (state.sleepTimerOption == SleepTimerOption.END_OF_SURAH) {
            // Sleep timer "At the end of surah" triggered: stop playback and reset timer to OFF
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
            RepeatMode.NONE -> {
                stop()
            }
            RepeatMode.ONE -> {
                surah?.let { playSurah(it, startFromBeginning = true, autoPlay = true) }
            }
            RepeatMode.ALL -> {
                playNextSurah()
            }
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

    private fun requestAudioFocus(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val playbackAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(playbackAttributes)
                        .setAcceptsDelayedFocusGain(true)
                        .setOnAudioFocusChangeListener { focusChange ->
                            // Only pause on loss if the player is actually prepared and currently playing
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                                if (isPrepared && _playerState.value.isPlaying) {
                                    pause()
                                }
                            }
                        }
                        .build()
                }
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            if (isPrepared && _playerState.value.isPlaying) {
                                pause()
                            }
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (_: Exception) {}
        // Always return true so that playback is never blocked by dummy audio drivers in emulators
        return true
    }

    private fun releaseMediaPlayer() {
        isPrepared = false
        progressJob?.cancel()
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
        val intent = Intent(context, QuranAudioService::class.java).apply {
            action = if (state.isPlaying) QuranAudioService.ACTION_UPDATE_STATUS else QuranAudioService.ACTION_PAUSE_STATUS
        }
        try {
            if (state.isPlaying) {
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
        @Volatile
        private var instance: PlayerManager? = null

        fun getInstance(context: Context): PlayerManager {
            return instance ?: synchronized(this) {
                instance ?: PlayerManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
