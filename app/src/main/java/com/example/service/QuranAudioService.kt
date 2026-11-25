package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.example.MainActivity
import com.example.QuranApp
import com.example.R
import com.example.model.PlayerState
import com.example.model.SleepTimerOption
import com.example.player.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class QuranAudioService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.example.service.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.service.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.example.service.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.example.service.ACTION_NEXT"
        const val ACTION_PREV = "com.example.service.ACTION_PREV"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_REWIND = "com.example.service.ACTION_REWIND"
        const val ACTION_FAST_FORWARD = "com.example.service.ACTION_FAST_FORWARD"
        const val ACTION_UPDATE_STATUS = "com.example.service.ACTION_UPDATE_STATUS"
        const val ACTION_PAUSE_STATUS = "com.example.service.ACTION_PAUSE_STATUS"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isForegroundActive = false
    private var mediaSession: MediaSessionCompat? = null
    private var cachedAlbumArt: Bitmap? = null

    // Caching keys to prevent redundant PlaybackStateCompat/notification resets every 1s
    private var lastMetadataKey: String? = null
    private var lastPlaybackKey: String? = null
    private var lastSyncedPosMs: Long = -1L
    private var lastSyncedElapsedMs: Long = -1L
    private var lastSyncedSpeed: Float = 0f
    private var lastNotificationKey: String? = null

    private var isNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlayerManager.getInstance(this@QuranAudioService).pause()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val playerManager = PlayerManager.getInstance(this)

        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON, null, this, MediaButtonReceiver::class.java)
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "QuranAudioMediaSession").apply {
            setMediaButtonReceiver(mediaButtonPendingIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    playerManager.resume()
                }

                override fun onPause() {
                    playerManager.pause()
                }

                override fun onSkipToNext() {
                    playerManager.playNextSurah()
                }

                override fun onSkipToPrevious() {
                    playerManager.playPreviousSurah()
                }

                override fun onFastForward() {
                    val currentPos = playerManager.playerState.value.currentPositionMs
                    val totalDur = playerManager.playerState.value.totalDurationMs
                    val target = (currentPos + 10000L).coerceAtMost(if (totalDur > 0L) totalDur else Long.MAX_VALUE)
                    playerManager.seekTo(target)
                }

                override fun onRewind() {
                    val currentPos = playerManager.playerState.value.currentPositionMs
                    val target = (currentPos - 10000L).coerceAtLeast(0L)
                    playerManager.seekTo(target)
                }

                override fun onSeekTo(pos: Long) {
                    playerManager.seekTo(pos)
                }

                override fun onStop() {
                    playerManager.stop(notifyService = false)
                    stopPlaybackService()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }

        serviceScope.launch {
            playerManager.playerState.collectLatest { state ->
                syncMediaSessionState(state)
                syncNotificationWithState(state)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterBecomingNoisyReceiver()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {}
        mediaSession = null

        cachedAlbumArt?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        cachedAlbumArt = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        val playerManager = PlayerManager.getInstance(this)

        if (intent == null) {
            if (!playerManager.playerState.value.isPlaying) {
                stopPlaybackService()
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_TOGGLE -> playerManager.togglePlayPause()
            ACTION_PLAY -> playerManager.resume()
            ACTION_PAUSE -> playerManager.pause()
            ACTION_NEXT -> playerManager.playNextSurah()
            ACTION_PREV -> playerManager.playPreviousSurah()
            ACTION_FAST_FORWARD -> {
                val currentPos = playerManager.playerState.value.currentPositionMs
                val totalDur = playerManager.playerState.value.totalDurationMs
                val target = (currentPos + 10000L).coerceAtMost(if (totalDur > 0L) totalDur else Long.MAX_VALUE)
                playerManager.seekTo(target)
            }
            ACTION_REWIND -> {
                val currentPos = playerManager.playerState.value.currentPositionMs
                val target = (currentPos - 10000L).coerceAtLeast(0L)
                playerManager.seekTo(target)
            }
            ACTION_STOP -> {
                playerManager.stop(notifyService = false)
                stopPlaybackService()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STATUS, ACTION_PAUSE_STATUS -> {
                syncMediaSessionState(playerManager.playerState.value, forceUpdate = true)
                syncNotificationWithState(playerManager.playerState.value, forceUpdate = true)
            }
        }

        return START_NOT_STICKY
    }

    private fun registerBecomingNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            try {
                val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                ContextCompat.registerReceiver(
                    this,
                    becomingNoisyReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isNoisyReceiverRegistered = true
            } catch (_: Exception) {}
        }
    }

    private fun unregisterBecomingNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (_: Exception) {}
            isNoisyReceiverRegistered = false
        }
    }

    /**
     * Updates MediaSession metadata & PlaybackStateCompat only on state transitions or genuine position seeks
     * so SystemUI does not reset/jump the notification progress seekbar every second.
     */
    private fun syncMediaSessionState(state: PlayerState, forceUpdate: Boolean = false) {
        val session = mediaSession ?: return
        val surah = state.currentSurah
        val reciter = state.currentReciter

        val playbackCode = when {
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            state.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val speed = if (state.isPlaying) state.playbackSpeed else 0.0f
        val nowElapsed = SystemClock.elapsedRealtime()
        val playbackKey = "${surah?.id}_${reciter?.id}_${playbackCode}_${speed}"

        // Calculate extrapolated position from last sync to distinguish smooth playback ticks from explicit seeks
        val expectedPosMs = if (lastSyncedPosMs >= 0L && lastSyncedElapsedMs >= 0L && lastSyncedSpeed > 0f) {
            lastSyncedPosMs + ((nowElapsed - lastSyncedElapsedMs) * lastSyncedSpeed).toLong()
        } else {
            lastSyncedPosMs
        }
        val positionJumped = lastSyncedPosMs < 0L || abs(state.currentPositionMs - expectedPosMs) > 2000L

        if (forceUpdate || playbackKey != lastPlaybackKey || positionJumped) {
            val playbackState = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
                )
                .setState(playbackCode, state.currentPositionMs, speed, nowElapsed)
                .build()
            session.setPlaybackState(playbackState)

            lastPlaybackKey = playbackKey
            lastSyncedPosMs = state.currentPositionMs
            lastSyncedElapsedMs = nowElapsed
            lastSyncedSpeed = speed
        }

        if (surah != null) {
            val metadataKey = "${surah.id}_${reciter?.id}_${state.totalDurationMs}"
            if (forceUpdate || metadataKey != lastMetadataKey) {
                lastMetadataKey = metadataKey
                val albumArt = getAlbumArtBitmap()
                val metadataBuilder = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "سورة ${surah.name}")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, reciter?.name ?: "القارئ")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "القرآن الكريم")
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "سورة ${surah.name}")
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, reciter?.name ?: "")
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "تلاوة القرآن الكريم بصوت ${reciter?.name ?: ""}")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.totalDurationMs.coerceAtLeast(0L))

                if (albumArt != null) {
                    metadataBuilder
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, albumArt)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, albumArt)
                }

                session.setMetadata(metadataBuilder.build())
            }
        }
    }

    private fun formatSleepTimerLabel(state: PlayerState): String? {
        return when {
            state.sleepTimerOption == SleepTimerOption.END_OF_SURAH -> "مؤقت النوم: نهاية السورة"
            state.sleepTimerSecondsRemaining > 0L -> {
                val mins = state.sleepTimerSecondsRemaining / 60L
                val secs = state.sleepTimerSecondsRemaining % 60L
                "مؤقت النوم: ${String.format(Locale.US, "%02d:%02d", mins, secs)}"
            }
            else -> null
        }
    }

    private fun syncNotificationWithState(state: PlayerState, forceUpdate: Boolean = false) {
        val surah = state.currentSurah
        if (surah == null) {
            stopPlaybackService()
            return
        }

        if (state.isPlaying) {
            registerBecomingNoisyReceiver()
        } else {
            unregisterBecomingNoisyReceiver()
        }

        val sleepLabel = formatSleepTimerLabel(state)
        val notificationKey = "${surah.id}_${state.currentReciter?.id}_${state.isPlaying}_${state.isBuffering}_${state.isPlayingOffline}_${sleepLabel}"
        if (!forceUpdate && notificationKey == lastNotificationKey && (state.isPlaying == isForegroundActive)) {
            return
        }
        lastNotificationKey = notificationKey

        val notification = buildNotification(state, sleepLabel)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (state.isPlaying) {
            if (!isForegroundActive) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    isForegroundActive = true
                } catch (_: Exception) {}
            } else {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            if (isForegroundActive) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                isForegroundActive = false
            }
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopPlaybackService() {
        unregisterBecomingNoisyReceiver()
        isForegroundActive = false
        lastPlaybackKey = null
        lastNotificationKey = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun buildNotification(state: PlayerState, sleepLabel: String?): Notification {
        val surah = state.currentSurah
        val reciter = state.currentReciter

        val title = if (surah != null) "سورة ${surah.name}" else "القرآن الكريم"
        val subtitle = reciter?.name ?: "تلاوة القرآن الكريم"
        val modeText = if (state.isPlayingOffline) "تشغيل بدون إنترنت" else "بث صوتي مباشر"
        val contentText = if (sleepLabel != null) "$subtitle • $sleepLabel" else "$subtitle • $modeText"
        val subText = sleepLabel ?: "القرآن الكريم"

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(this, QuranAudioService::class.java).apply { action = ACTION_PREV }
        val prevPending = PendingIntent.getService(this, 1, prevIntent, PendingIntent.FLAG_IMMUTABLE)

        val toggleIntent = Intent(this, QuranAudioService::class.java).apply { action = ACTION_TOGGLE }
        val togglePending = PendingIntent.getService(this, 2, toggleIntent, PendingIntent.FLAG_IMMUTABLE)

        val nextIntent = Intent(this, QuranAudioService::class.java).apply { action = ACTION_NEXT }
        val nextPending = PendingIntent.getService(this, 3, nextIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, QuranAudioService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 4, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val playPauseIcon = if (state.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = if (state.isPlaying) "إيقاف مؤقت" else "تشغيل"

        val builder = NotificationCompat.Builder(this, QuranApp.CHANNEL_AUDIO_PLAYBACK)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(subText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "السابقة", prevPending)
            .addAction(playPauseIcon, playPauseLabel, togglePending)
            .addAction(android.R.drawable.ic_media_next, "التالية", nextPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPending)

        val albumArt = getAlbumArtBitmap()
        if (albumArt != null) {
            builder.setLargeIcon(albumArt)
        }

        mediaSession?.let { session ->
            builder.setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopPending)
            )
        }

        return builder.build()
    }

    private fun getAlbumArtBitmap(): Bitmap? {
        if (cachedAlbumArt != null && !cachedAlbumArt!!.isRecycled) {
            return cachedAlbumArt
        }
        return try {
            val size = 256
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#0C2A1E")
            }
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#D4AF37")
                style = Paint.Style.STROKE
                strokeWidth = 8f
            }
            canvas.drawRoundRect(16f, 16f, (size - 16).toFloat(), (size - 16).toFloat(), 28f, 28f, borderPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#D4AF37")
                textSize = 46f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("القرآن الكريم", size / 2f, size / 2f + 16f, textPaint)

            cachedAlbumArt = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
