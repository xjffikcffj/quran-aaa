package com.example

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("القرآن الصوتي", appName)
  }

  @Test
  fun `test PlayerManager play and resume`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val url = "https://cdn.mp3quran.net/audio/mishary-alafasy/r1/001.mp3"
    val ds = org.robolectric.shadows.util.DataSource.toDataSource(url)
    org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(ds, org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(180000, 0))

    val playerManager = com.example.player.PlayerManager.getInstance(context)
    val surah = com.example.data.QuranData.getSurahById(1)!!
    playerManager.playSurah(surah, autoPlay = true)
    
    // Check state while buffering
    org.junit.Assert.assertEquals(1, playerManager.playerState.value.currentSurah?.id)
  }

  @Test
  fun `verify AdMob configuration unit IDs`() {
    assertEquals("ca-app-pub-8150114459606774~9089399753", com.example.ads.AdConfig.APP_ID)
    assertEquals("ca-app-pub-8150114459606774/7655852403", com.example.ads.AdConfig.INTERSTITIAL_AD_UNIT_ID)
    assertEquals("ca-app-pub-8150114459606774/6463236417", com.example.ads.AdConfig.BANNER_AD_UNIT_ID)
  }

  @Test
  fun `verify AdBanner diagnostic function reports dimensions and loading correctly`() {
    val report = com.example.ui.components.logAdBannerDiagnostics(
      isAdLoaded = true,
      isAdsReady = true,
      canRequestAds = true,
      isNetworkAvailable = true,
      adUnitId = com.example.ads.AdConfig.BANNER_AD_UNIT_ID,
      targetHeightDp = 50.dp,
      measuredWidthPx = 1080,
      measuredHeightPx = 150,
      density = 3.0f
    )
    org.junit.Assert.assertTrue(report.contains("AD BANNER DIAGNOSTIC REPORT"))
    org.junit.Assert.assertTrue(report.contains("DIMENSION OK"))
    org.junit.Assert.assertTrue(report.contains("AD LOADED"))

    // Test zero height error detection
    val errorReport = com.example.ui.components.logAdBannerDiagnostics(
      isAdLoaded = false,
      isAdsReady = false,
      canRequestAds = false,
      isNetworkAvailable = false,
      adUnitId = com.example.ads.AdConfig.BANNER_AD_UNIT_ID,
      targetHeightDp = 50.dp,
      measuredWidthPx = 0,
      measuredHeightPx = 0,
      density = 3.0f
    )
    org.junit.Assert.assertTrue(errorReport.contains("CRITICAL DIMENSION ERROR"))
  }

  @Test
  fun `verify AdBannerDiagnosticListener marks request and handles callbacks`() {
    var loaded = false
    var opened = false
    var closed = false
    val listener = com.example.ads.AdBannerDiagnosticListener(
      adUnitId = com.example.ads.AdConfig.BANNER_AD_UNIT_ID,
      onAdLoadedCallback = { loaded = true },
      onAdOpenedCallback = { opened = true },
      onAdClosedCallback = { closed = true }
    )

    listener.markRequestStarted()
    listener.onAdLoaded()
    org.junit.Assert.assertTrue(loaded)

    listener.onAdOpened()
    org.junit.Assert.assertTrue(opened)

    listener.onAdClosed()
    org.junit.Assert.assertTrue(closed)
  }

  @Test
  fun `verify banner layout constraint checker confirms non-zero height`() {
    val isNonZero = com.example.ui.components.verifyBannerLayoutConstraints(
      minHeightPx = 150,
      maxHeightPx = 240,
      measuredWidthPx = 1080,
      measuredHeightPx = 168,
      density = 3.0f
    )
    org.junit.Assert.assertTrue(isNonZero)

    val isZero = com.example.ui.components.verifyBannerLayoutConstraints(
      minHeightPx = 0,
      maxHeightPx = 0,
      measuredWidthPx = 1080,
      measuredHeightPx = 0,
      density = 3.0f
    )
    org.junit.Assert.assertFalse(isZero)
  }

  @Test
  fun `verify logAdBannerLifecycleEvent outputs formatted event strings`() {
    val loadedMsg = com.example.ui.components.logAdBannerLifecycleEvent("onAdLoaded", "Test loaded")
    org.junit.Assert.assertTrue(loadedMsg.contains("onAdLoaded"))
    org.junit.Assert.assertTrue(loadedMsg.contains("Test loaded"))

    val openedMsg = com.example.ui.components.logAdBannerLifecycleEvent("onAdOpened", "Overlay opened")
    org.junit.Assert.assertTrue(openedMsg.contains("onAdOpened"))
  }

  @Test
  fun `verify QuranAudioService actions and notification setup`() {
    assertEquals("com.example.service.ACTION_PLAY", com.example.service.QuranAudioService.ACTION_PLAY)
    assertEquals("com.example.service.ACTION_PAUSE", com.example.service.QuranAudioService.ACTION_PAUSE)
    assertEquals("com.example.service.ACTION_NEXT", com.example.service.QuranAudioService.ACTION_NEXT)
    assertEquals("com.example.service.ACTION_PREV", com.example.service.QuranAudioService.ACTION_PREV)
    assertEquals("com.example.service.ACTION_TOGGLE", com.example.service.QuranAudioService.ACTION_TOGGLE)
    assertEquals("com.example.service.ACTION_STOP", com.example.service.QuranAudioService.ACTION_STOP)
  }

  @Test
  fun `verify canRequestAds returns false safely on uninitialized environment`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val canRequest = com.example.ads.ConsentManager.canRequestAds(context)
    org.junit.Assert.assertFalse(canRequest)
  }

  @Test
  fun `verify POST_NOTIFICATIONS permission declared in package manager`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val packageInfo = context.packageManager.getPackageInfo(
      context.packageName,
      android.content.pm.PackageManager.GET_PERMISSIONS
    )
    val permissions = packageInfo.requestedPermissions ?: emptyArray()
    org.junit.Assert.assertTrue(permissions.contains("android.permission.POST_NOTIFICATIONS"))
  }

  @Test
  fun `verify RECEIVE_BOOT_COMPLETED permission declared in package manager`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val packageInfo = context.packageManager.getPackageInfo(
      context.packageName,
      android.content.pm.PackageManager.GET_PERMISSIONS
    )
    val permissions = packageInfo.requestedPermissions ?: emptyArray()
    org.junit.Assert.assertTrue(permissions.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
  }

  @Test
  fun `verify Friday reminder calculates next Friday timestamp correctly`() {
    // Construct a known timestamp: Wednesday, Oct 28, 2026 at 12:00:00 PM
    val calWed = java.util.Calendar.getInstance().apply {
      set(2026, java.util.Calendar.OCTOBER, 28, 12, 0, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }
    val nextFridayMs = com.example.receiver.FridayReminderReceiver.calculateNextFridayTime(calWed.timeInMillis)
    val resultCal = java.util.Calendar.getInstance().apply { timeInMillis = nextFridayMs }

    assertEquals(java.util.Calendar.FRIDAY, resultCal.get(java.util.Calendar.DAY_OF_WEEK))
    assertEquals(9, resultCal.get(java.util.Calendar.HOUR_OF_DAY))
    assertEquals(0, resultCal.get(java.util.Calendar.MINUTE))
    assertEquals(30, resultCal.get(java.util.Calendar.DAY_OF_MONTH)) // Oct 30, 2026 is Friday

    // If today is Friday at 10:00 AM (already passed 9:00 AM), must schedule next Friday (+7 days)
    val calFridayPassed = java.util.Calendar.getInstance().apply {
      set(2026, java.util.Calendar.OCTOBER, 30, 10, 0, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }
    val nextFridayFromFridayMs = com.example.receiver.FridayReminderReceiver.calculateNextFridayTime(calFridayPassed.timeInMillis)
    val nextFridayCal = java.util.Calendar.getInstance().apply { timeInMillis = nextFridayFromFridayMs }

    assertEquals(java.util.Calendar.FRIDAY, nextFridayCal.get(java.util.Calendar.DAY_OF_WEEK))
    assertEquals(9, nextFridayCal.get(java.util.Calendar.HOUR_OF_DAY))
    assertEquals(6, nextFridayCal.get(java.util.Calendar.DAY_OF_MONTH)) // Nov 6, 2026
    assertEquals(java.util.Calendar.NOVEMBER, nextFridayCal.get(java.util.Calendar.MONTH))
  }

  @Test
  fun `verify FridayReminderReceiver handles BOOT_COMPLETED intent gracefully`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val receiver = com.example.receiver.FridayReminderReceiver()
    val bootIntent = android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED)

    // Should process without exception
    receiver.onReceive(context, bootIntent)

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val shadowAlarm = org.robolectric.Shadows.shadowOf(alarmManager)
    // Verify an alarm was scheduled in shadow alarm manager
    val scheduledAlarms = shadowAlarm.scheduledAlarms
    org.junit.Assert.assertTrue(scheduledAlarms.isNotEmpty())
  }

  @Test
  fun `verify FridayReminderReceiver onReceive shows notification and reschedules next alarm`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val receiver = com.example.receiver.FridayReminderReceiver()
    val alarmIntent = android.content.Intent(com.example.receiver.FridayReminderReceiver.ACTION_FRIDAY_ALARM)

    receiver.onReceive(context, alarmIntent)

    // Verify notification was posted
    val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)
    val shadowNotificationManager = org.robolectric.Shadows.shadowOf(
      context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    )
    val notifications = shadowNotificationManager.allNotifications
    org.junit.Assert.assertTrue(notifications.any { it.extras.getString(androidx.core.app.NotificationCompat.EXTRA_TITLE)?.contains("سورة الكهف") == true || it.extras.getString(androidx.core.app.NotificationCompat.EXTRA_TITLE)?.contains("الجمعة") == true })

    // Verify next alarm was scheduled
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val shadowAlarm = org.robolectric.Shadows.shadowOf(alarmManager)
    org.junit.Assert.assertTrue(shadowAlarm.scheduledAlarms.isNotEmpty())
  }

  @Test
  fun `verify FridayReminderReceiver cancels alarm when disabled`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // First schedule
    com.example.receiver.FridayReminderReceiver.scheduleFridayReminder(context, true)
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val shadowAlarm = org.robolectric.Shadows.shadowOf(alarmManager)
    org.junit.Assert.assertTrue(shadowAlarm.scheduledAlarms.isNotEmpty())

    // Now disable
    com.example.receiver.FridayReminderReceiver.scheduleFridayReminder(context, false)
    org.junit.Assert.assertTrue(shadowAlarm.scheduledAlarms.isEmpty())
  }

  @Test
  fun `verify SleepTimerOption END_OF_SURAH stops playback and resets timer without advancing surah`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val playerManager = com.example.player.PlayerManager.getInstance(context)

    // Register data source in ShadowMediaPlayer
    val reciter = com.example.data.DefaultReciters.defaultReciter
    val url = reciter.getAudioUrl(1)
    val headers = mapOf(
      "User-Agent" to "Mozilla/5.0 (Linux; Android 14) QuranAudio/1.0",
      "Accept" to "*/*"
    )
    val ds = org.robolectric.shadows.util.DataSource.toDataSource(context, android.net.Uri.parse(url), headers)
    org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(ds, org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(180000, 0))

    // Set repeat mode to ALL so we test that END_OF_SURAH intercepts and prevents advancing
    playerManager.setRepeatMode(com.example.model.RepeatMode.ALL)

    // Start with surah 1
    val surah1 = com.example.data.QuranData.getSurahById(1)!!
    playerManager.playSurah(surah1, startFromBeginning = true, autoPlay = false)
    assertEquals(1, playerManager.playerState.value.currentSurah?.id)

    // Enable END_OF_SURAH sleep timer
    playerManager.setSleepTimer(com.example.model.SleepTimerOption.END_OF_SURAH)
    assertEquals(com.example.model.SleepTimerOption.END_OF_SURAH, playerManager.playerState.value.sleepTimerOption)
    assertEquals(0L, playerManager.playerState.value.sleepTimerSecondsRemaining)

    // Trigger playback completion
    playerManager.handlePlaybackCompletion()

    // Playback must be stopped
    org.junit.Assert.assertFalse(playerManager.playerState.value.isPlaying)
    // Sleep timer must be reset to OFF
    assertEquals(com.example.model.SleepTimerOption.OFF, playerManager.playerState.value.sleepTimerOption)
    // Must NOT advance to surah 2, must stay on surah 1
    assertEquals(1, playerManager.playerState.value.currentSurah?.id)
  }

  @Test
  fun `verify time based sleep timer sets remaining seconds and can be cancelled`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val playerManager = com.example.player.PlayerManager.getInstance(context)

    // Set 15 minutes sleep timer
    playerManager.setSleepTimer(com.example.model.SleepTimerOption.MINUTES_15)
    assertEquals(com.example.model.SleepTimerOption.MINUTES_15, playerManager.playerState.value.sleepTimerOption)
    assertEquals(900L, playerManager.playerState.value.sleepTimerSecondsRemaining)

    // Cancel timer
    playerManager.setSleepTimer(com.example.model.SleepTimerOption.OFF)
    assertEquals(com.example.model.SleepTimerOption.OFF, playerManager.playerState.value.sleepTimerOption)
    assertEquals(0L, playerManager.playerState.value.sleepTimerSecondsRemaining)
  }
}
