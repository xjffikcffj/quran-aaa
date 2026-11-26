package com.example.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.player.PlayerManager
import com.example.service.QuranAudioService

/**
 * Receives notification playback actions via PendingIntent.getBroadcast so controls
 * (Play/Pause, Next, Previous, Stop) remain responsive after long pauses, background
 * service detachment (STOP_FOREGROUND_DETACH), or process termination on Android 8-15+.
 */
class QuranNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val playerManager = PlayerManager.getInstance(appContext)

        when (action) {
            QuranAudioService.ACTION_STOP -> {
                playerManager.stop(notifyService = false)
                val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(QuranAudioService.NOTIFICATION_ID)
                try {
                    appContext.stopService(Intent(appContext, QuranAudioService::class.java))
                } catch (_: Exception) {}
            }

            QuranAudioService.ACTION_PAUSE -> {
                playerManager.pause()
            }

            QuranAudioService.ACTION_TOGGLE -> {
                val state = playerManager.playerState.value
                if (state.isPlaying || state.isBuffering) {
                    playerManager.pause()
                } else {
                    startPlaybackForegroundService(appContext, QuranAudioService.ACTION_PLAY)
                }
            }

            QuranAudioService.ACTION_PLAY,
            QuranAudioService.ACTION_NEXT,
            QuranAudioService.ACTION_PREV,
            QuranAudioService.ACTION_FAST_FORWARD,
            QuranAudioService.ACTION_REWIND -> {
                startPlaybackForegroundService(appContext, action)
            }
        }
    }

    private fun startPlaybackForegroundService(context: Context, action: String) {
        val serviceIntent = Intent(context, QuranAudioService::class.java).apply {
            this.action = action
            putExtra(QuranAudioService.EXTRA_STARTED_FOREGROUND, true)
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {}
    }
}
