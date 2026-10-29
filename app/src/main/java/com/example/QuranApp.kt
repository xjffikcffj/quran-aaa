package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.data.local.QuranDatabase

class QuranApp : Application() {

    companion object {
        const val CHANNEL_AUDIO_PLAYBACK = "quran_audio_playback_channel"
        const val CHANNEL_REMINDERS = "quran_reminders_channel"
        lateinit var database: QuranDatabase
            private set
    }

    override fun onCreate() {
        super.onCreate()
        database = QuranDatabase.getDatabase(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Playback Channel
            val playbackChannel = NotificationChannel(
                CHANNEL_AUDIO_PLAYBACK,
                "مشغل تلاوة القرآن الكريم",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعارات التحكم في مشغل التلاوة الصوتية"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }

            // Reminders Channel
            val remindersChannel = NotificationChannel(
                CHANNEL_REMINDERS,
                "تذكيرات القرآن والجمعة",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "تنبيهات تذكير سورة الكهف يوم الجمعة المباركة"
            }

            notificationManager.createNotificationChannel(playbackChannel)
            notificationManager.createNotificationChannel(remindersChannel)
        }
    }
}
