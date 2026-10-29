package com.example.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.QuranApp
import com.example.R
import java.util.Calendar

class FridayReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(PREF_KEY_FRIDAY_REMINDER, true)

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Device rebooted or app replaced: restore schedule without showing notification
            if (isEnabled) {
                scheduleFridayReminder(context, true)
            }
            return
        }

        // Alarm triggered: show notification
        showFridayNotification(context)

        // Reschedule for the next Friday to ensure continuous weekly cycle
        if (isEnabled) {
            scheduleFridayReminder(context, true)
        }
    }

    companion object {
        const val REMINDER_NOTIFICATION_ID = 2002
        const val ALARM_REQUEST_CODE = 100
        const val ACTION_FRIDAY_ALARM = "com.example.receiver.ACTION_FRIDAY_ALARM"
        const val PREFS_NAME = "quran_prefs"
        const val PREF_KEY_FRIDAY_REMINDER = "friday_reminder"

        fun showFridayNotification(context: Context) {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("NAVIGATE_TO_SURAH", 18) // Al-Kahf
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                18,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, QuranApp.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.friday_notification_title))
                .setContentText(context.getString(R.string.friday_notification_body))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.friday_notification_body))
                )
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {}
        }

        /**
         * Calculates the epoch millisecond timestamp for the upcoming Friday at 9:00 AM.
         * If today is Friday and 9:00 AM has already passed (or is within 60s),
         * advances exactly 7 days to next Friday.
         */
        fun calculateNextFridayTime(nowMs: Long = System.currentTimeMillis()): Long {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)

                val dayOfWeek = get(Calendar.DAY_OF_WEEK)
                val daysUntilFriday = (Calendar.FRIDAY - dayOfWeek + 7) % 7
                if (daysUntilFriday > 0) {
                    add(Calendar.DAY_OF_YEAR, daysUntilFriday)
                } else if (timeInMillis <= nowMs + 60_000L) {
                    add(Calendar.DAY_OF_YEAR, 7)
                }
            }
            return calendar.timeInMillis
        }

        fun scheduleFridayReminder(context: Context, enable: Boolean) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, FridayReminderReceiver::class.java).apply {
                action = ACTION_FRIDAY_ALARM
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (!enable) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                return
            }

            val triggerAtMs = calculateNextFridayTime()

            try {
                val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }

                if (canScheduleExact) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    } else {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    }
                } else {
                    // Safe fallback: Inexact alarm compatible with idle/Doze mode without requiring SCHEDULE_EXACT_ALARM
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    } else {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    }
                }
            } catch (_: SecurityException) {
                // Secondary fallback in case exact alarm was rejected by device security policy
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    } else {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }
}
