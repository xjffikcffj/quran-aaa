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
@Config(sdk = [35])
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
    org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
      ds,
      org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(180000, 0)
    )

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
    val shadowNotificationManager = org.robolectric.Shadows.shadowOf(
      context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    )
    val notifications = shadowNotificationManager.allNotifications
    org.junit.Assert.assertTrue(
      notifications.any {
        it.extras.getString(androidx.core.app.NotificationCompat.EXTRA_TITLE)
          ?.contains("سورة الكهف") == true ||
          it.extras.getString(androidx.core.app.NotificationCompat.EXTRA_TITLE)
            ?.contains("الجمعة") == true
      }
    )

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
    val ds = org.robolectric.shadows.util.DataSource.toDataSource(
      context,
      android.net.Uri.parse(url),
      headers
    )
    org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
      ds,
      org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(180000, 0)
    )

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

  @Test
  fun `verify playback speed persists and updates playerState without auto-playing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val playerManager = com.example.player.PlayerManager.getInstance(context)

    playerManager.stop()
    playerManager.setPlaybackSpeed(1.5f)
    assertEquals(1.5f, playerManager.playerState.value.playbackSpeed, 0.001f)
    org.junit.Assert.assertFalse(playerManager.playerState.value.isPlaying)

    // Out-of-bounds speeds should clamp cleanly
    playerManager.setPlaybackSpeed(4.0f)
    assertEquals(2.0f, playerManager.playerState.value.playbackSpeed, 0.001f)
    playerManager.setPlaybackSpeed(1.0f)
  }

  @Test
  fun `verify FridayReminderReceiver ignores unrecognized external actions`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val receiver = com.example.receiver.FridayReminderReceiver()
    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
    val initialCount = shadowNotificationManager.allNotifications.size

    val maliciousIntent = android.content.Intent("com.attacker.RANDOM_ACTION")
    receiver.onReceive(context, maliciousIntent)

    assertEquals(initialCount, shadowNotificationManager.allNotifications.size)
  }

  @Test
  fun `verify custom reciter persists via PlayerManager and backup export-import includes all data`() {
    kotlinx.coroutines.runBlocking {
      val app = ApplicationProvider.getApplicationContext<android.app.Application>()
      val viewModel = com.example.ui.viewmodel.QuranViewModel(app)

      // Seed custom reciter, favorite, bookmark, and saved position
      val customReciter = com.example.model.Reciter(
        id = "custom_test_1",
        name = "قارئ اختباري",
        serverUrl = "https://example.com/quran/",
        availableSurahsCount = 50,
        isCustom = true
      )
      com.example.QuranApp.database.reciterDao()
        .insertReciter(com.example.data.local.ReciterEntity.fromReciter(customReciter))
      com.example.QuranApp.database.surahStateDao().insertOrUpdate(
        com.example.data.local.SurahStateEntity(surahNumber = 18, lastPositionMs = 45_000L, isFavorite = true)
      )
      com.example.QuranApp.database.bookmarkDao().insertBookmark(
        com.example.data.local.BookmarkEntity(
          surahNumber = 18,
          surahName = "الكهف",
          reciterId = "custom_test_1",
          reciterName = "قارئ اختباري",
          positionMs = 45_000L,
          note = "تذكير اختبار",
          timestamp = 123456L
        )
      )
      com.example.player.PlayerManager.getInstance(app).savePosition("custom_test_1", 18, 45_000L, 600_000L, force = true)

      val exportedJson = viewModel.exportBackupJson()
      org.junit.Assert.assertTrue(exportedJson.contains("custom_test_1"))
      org.junit.Assert.assertTrue(exportedJson.contains("\"favorites\""))
      org.junit.Assert.assertTrue(exportedJson.contains("\"reciterPositions\""))
      org.junit.Assert.assertTrue(exportedJson.contains("تذكير اختبار"))

      // Invalid JSON must be rejected cleanly
      org.junit.Assert.assertFalse(viewModel.importBackupJson("{not valid json"))
      org.junit.Assert.assertFalse(viewModel.importBackupJson("{\"unrelated\": 123}"))

      // Valid JSON re-import succeeds and keeps deduplicated bookmarks
      org.junit.Assert.assertTrue(viewModel.importBackupJson(exportedJson))
      val allBookmarks = com.example.QuranApp.database.bookmarkDao().getAllBookmarksOnce()
      assertEquals(1, allBookmarks.count { it.note == "تذكير اختبار" })
    }
  }

  @Test
  fun `verify stuck DOWNLOADING rows recover to FAILED without deleting completed files`() {
    kotlinx.coroutines.runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dm = com.example.download.DownloadManager.getInstance(context)
      val dao = com.example.QuranApp.database.downloadDao()

      dao.insertOrUpdate(
        com.example.data.local.DownloadEntity(
          id = "afs_2",
          surahNumber = 2,
          surahName = "البقرة",
          reciterId = "afs",
          reciterName = "مشاري العفاسي",
          localFilePath = dm.getLocalAudioFile("afs", 2).absolutePath,
          fileSizeBytes = 1000L,
          status = com.example.model.DownloadStatus.DOWNLOADING.name,
          progress = 40,
          errorMessage = null
        )
      )

      dao.markStuckDownloadsFailed("توقف التنزيل قبل اكتماله، اضغط لإعادة المحاولة")
      val recovered = dao.getDownloadById("afs_2")
      assertEquals(com.example.model.DownloadStatus.FAILED.name, recovered?.status)
    }
  }

  @Test
  fun `verify PlayerManager performs at most one reconnect retry per user play request`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val reciter = com.example.data.DefaultReciters.getReciterById("afs")
    val headers = mapOf(
      "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 QuranAudio/1.0",
      "Accept" to "*/*"
    )
    listOf(1, 2).forEach { surahId ->
      val url = reciter.getAudioUrl(surahId)
      val mediaInfo = org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(180_000, 500)
      org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
        org.robolectric.shadows.util.DataSource.toDataSource(url),
        mediaInfo
      )
      org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
        org.robolectric.shadows.util.DataSource.toDataSource(context, android.net.Uri.parse(url), headers),
        mediaInfo
      )
    }

    val playerManager = com.example.player.PlayerManager.getInstance(context)
    playerManager.stop()
    playerManager.forceNetworkConnectedForTest = true
    playerManager.reconnectDelayMsForTest = 10L

    try {
      playerManager.setReciter(reciter)
      val surah1 = com.example.data.QuranData.getSurahById(1)!!
      val surah2 = com.example.data.QuranData.getSurahById(2)!!

      playerManager.playSurah(surah1, startFromBeginning = true, autoPlay = true)
      val firstMp = playerManager.activeMediaPlayerForTest
      org.junit.Assert.assertNotNull(firstMp)
      assertEquals(0, playerManager.reconnectAttemptCountForTest)

      // 1st failure should schedule exactly 1 reconnect attempt
      org.robolectric.Shadows.shadowOf(firstMp).invokeErrorListener(1, -1004)
      assertEquals(1, playerManager.reconnectAttemptCountForTest)

      // Allow internal reconnect coroutine to execute
      org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
      val secondMp = playerManager.activeMediaPlayerForTest
      org.junit.Assert.assertNotNull(secondMp)
      // Counter must NOT have been reset by the internal retry
      assertEquals(1, playerManager.reconnectAttemptCountForTest)

      // 2nd failure must NOT schedule a 3rd attempt
      org.robolectric.Shadows.shadowOf(secondMp).invokeErrorListener(1, -1004)
      org.robolectric.shadows.ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)

      assertEquals(1, playerManager.reconnectAttemptCountForTest)
      org.junit.Assert.assertNull(playerManager.activeMediaPlayerForTest)
      org.junit.Assert.assertFalse(playerManager.playerState.value.isPlaying)
      org.junit.Assert.assertFalse(playerManager.playerState.value.isBuffering)
      org.junit.Assert.assertNotNull(playerManager.playerState.value.errorMessage)

      // Independent new play request from user MUST reset reconnect attempt counter
      playerManager.playSurah(surah2, startFromBeginning = true, autoPlay = true)
      assertEquals(0, playerManager.reconnectAttemptCountForTest)
      playerManager.stop()
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
    } finally {
      playerManager.forceNetworkConnectedForTest = null
      playerManager.reconnectDelayMsForTest = 500L
    }
  }

  @Test
  fun `verify atomic backup import rejects invalid playerSettings and HTTP custom reciters without partial writes`() {
    kotlinx.coroutines.runBlocking {
      val app = ApplicationProvider.getApplicationContext<android.app.Application>()
      val viewModel = com.example.ui.viewmodel.QuranViewModel(app)
      val db = com.example.QuranApp.database
      val playerPrefs = app.getSharedPreferences("quran_player_prefs", Context.MODE_PRIVATE)

      // Seed baseline user data
      val existingReciter = com.example.model.Reciter(
        id = "custom_existing_keep",
        name = "قارئ محفوظ مسبقاً",
        serverUrl = "https://existing.example.com/audio/",
        availableSurahsCount = 114,
        isCustom = true
      )
      db.reciterDao().insertReciter(com.example.data.local.ReciterEntity.fromReciter(existingReciter))
      db.surahStateDao().insertOrUpdate(com.example.data.local.SurahStateEntity(surahNumber = 36, isFavorite = true))
      playerPrefs.edit()
        .putString("saved_reciter_id", "afs")
        .putInt("last_surah_id", 1)
        .putString("saved_repeat_mode", com.example.model.RepeatMode.ALL.name)
        .putFloat("saved_playback_speed", 1.0f)
        .commit()

      val baselineBookmarkCount = db.bookmarkDao().getAllBookmarksOnce().size

      // Case 1: Invalid playerSettings alongside valid bookmarks, favorites, and customReciters -> MUST NOT mutate DB/prefs!
      val badSettingsBackup = """
        {
          "version": 2,
          "bookmarks": [
            {
              "surahNumber": 19,
              "surahName": "مريم",
              "reciterId": "afs",
              "reciterName": "مشاري العفاسي",
              "positionMs": 12000,
              "note": "يجب ألا يُحفظ جزئياً",
              "timestamp": 999999
            }
          ],
          "favorites": [55],
          "customReciters": [
            {
              "id": "custom_should_not_persist",
              "name": "قارئ يجب ألا يحفظ",
              "serverUrl": "https://valid.example.com/audio/",
              "availableSurahsCount": 114
            }
          ],
          "playerSettings": {
            "savedReciterId": "afs",
            "lastSurahId": 55,
            "savedRepeatMode": "ALL",
            "savedPlaybackSpeed": "not_a_number"
          }
        }
      """.trimIndent()

      org.junit.Assert.assertFalse(viewModel.importBackupJson(badSettingsBackup))
      assertEquals(baselineBookmarkCount, db.bookmarkDao().getAllBookmarksOnce().size)
      org.junit.Assert.assertNull(db.reciterDao().getReciterById("custom_should_not_persist"))
      org.junit.Assert.assertFalse(db.surahStateDao().getState(55)?.isFavorite == true)
      assertEquals(1, playerPrefs.getInt("last_surah_id", -1))

      // Case 2: Non-existent savedReciterId in playerSettings -> MUST fail atomically
      val unknownReciterSettings = """
        {
          "version": 2,
          "favorites": [56],
          "playerSettings": {
            "savedReciterId": "missing_reciter_id",
            "lastSurahId": 56
          }
        }
      """.trimIndent()
      org.junit.Assert.assertFalse(viewModel.importBackupJson(unknownReciterSettings))
      org.junit.Assert.assertFalse(db.surahStateDao().getState(56)?.isFavorite == true)

      // Case 3: HTTP custom reciter URL in backup -> MUST reject with HTTPS error and keep existing custom reciters
      val httpBackup = """
        {
          "version": 2,
          "favorites": [67],
          "customReciters": [
            {
              "id": "custom_http_bad",
              "name": "قارئ غير آمن",
              "serverUrl": "http://insecure.example.com/audio/",
              "availableSurahsCount": 114
            }
          ]
        }
      """.trimIndent()
      val httpResult = viewModel.importBackupJsonWithResult(httpBackup)
      org.junit.Assert.assertFalse(httpResult.success)
      org.junit.Assert.assertTrue(httpResult.message.contains("HTTPS"))
      org.junit.Assert.assertNull(db.reciterDao().getReciterById("custom_http_bad"))
      org.junit.Assert.assertFalse(db.surahStateDao().getState(67)?.isFavorite == true)
      org.junit.Assert.assertNotNull(db.reciterDao().getReciterById("custom_existing_keep"))

      // ViewModel addCustomReciter also enforces HTTPS
      org.junit.Assert.assertFalse(viewModel.addCustomReciter("غير آمن", "http://insecure.example.com/audio/", 114))

      // Case 4: Valid backup imports custom reciter, bookmarks, favorites, savedReciterId, lastSurahId while keeping existing favorites
      val validBackup = """
        {
          "version": 2,
          "bookmarks": [
            {
              "surahNumber": 19,
              "surahName": "مريم",
              "reciterId": "custom_imported_ok",
              "reciterName": "قارئ مستورد آمن",
              "positionMs": 22000,
              "note": "حفظ كامل",
              "timestamp": 1111111
            }
          ],
          "favorites": [78],
          "customReciters": [
            {
              "id": "custom_imported_ok",
              "name": "قارئ مستورد آمن",
              "serverUrl": "https://secure.example.com/audio/",
              "availableSurahsCount": 114
            }
          ],
          "playerSettings": {
            "savedReciterId": "custom_imported_ok",
            "lastSurahId": 78,
            "savedRepeatMode": "ONE",
            "savedPlaybackSpeed": 1.25
          }
        }
      """.trimIndent()

      val validResult = viewModel.importBackupJsonWithResult(validBackup)
      org.junit.Assert.assertTrue(validResult.success)
      org.junit.Assert.assertNotNull(db.reciterDao().getReciterById("custom_existing_keep"))
      org.junit.Assert.assertNotNull(db.reciterDao().getReciterById("custom_imported_ok"))
      org.junit.Assert.assertTrue(db.surahStateDao().getState(36)?.isFavorite == true)
      org.junit.Assert.assertTrue(db.surahStateDao().getState(78)?.isFavorite == true)
      assertEquals("custom_imported_ok", playerPrefs.getString("saved_reciter_id", ""))
      assertEquals(78, playerPrefs.getInt("last_surah_id", 0))
      assertEquals("ONE", playerPrefs.getString("saved_repeat_mode", ""))
      assertEquals(1.25f, playerPrefs.getFloat("saved_playback_speed", 0f), 0.001f)
    }
  }

  @Test
  fun `verify DownloadManager validates MP3 integrity, rejects mismatched Range, and protects valid files`() {
    kotlinx.coroutines.runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dm = com.example.download.DownloadManager.getInstance(context)
      val dao = com.example.QuranApp.database.downloadDao()
      val reciter = com.example.data.DefaultReciters.getReciterById("afs")

      // 1. Corrupt local file >= 16KB MUST NOT pass isDownloaded() or play offline
      val corruptFile = dm.getLocalAudioFile(reciter.id, 3)
      corruptFile.parentFile?.mkdirs()
      corruptFile.writeBytes(("<html><body>Not an mp3</body></html>" + " ".repeat(20_000)).toByteArray())
      org.junit.Assert.assertFalse(dm.isDownloaded(reciter.id, 3))
      org.junit.Assert.assertNull(dm.getValidLocalAudioFile(reciter.id, 3))

      val pm = com.example.player.PlayerManager.getInstance(context)
      pm.setReciter(reciter)
      pm.playSurah(com.example.data.QuranData.getSurahById(3)!!, autoPlay = false)
      org.junit.Assert.assertFalse(pm.playerState.value.isPlayingOffline)
      corruptFile.delete()

      // 2. Prepare valid 24KB MP3 payload (10-byte ID3v2 header with tagSize=0 + MPEG sync frame)
      val validMp3Bytes = ByteArray(24_576) { idx ->
        when (idx) {
          0 -> 0x49
          1 -> 0x44
          2 -> 0x33
          3 -> 0x03
          in 4..9 -> 0x00
          10 -> 0xFF.toByte()
          11 -> 0xFB.toByte()
          12 -> 0x90.toByte()
          else -> 0x55
        }
      }

      // Simulate: Attempt 1 returns truncated 2048 bytes (200 OK claiming 24576 -> incomplete),
      // Attempt 2 receives Range: bytes=2048- but server returns mismatched Content-Range bytes 100-24575/24576,
      // Attempt 3 (restarted clean from byte 0 without Range!) returns full 24576 valid bytes!
      var callCount = 0
      var thirdCallHadRangeHeader = true
      val interceptorClient = okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
          val req = chain.request()
          callCount++
          when (callCount) {
            1 -> okhttp3.Response.Builder()
              .request(req)
              .protocol(okhttp3.Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .header("Content-Type", "audio/mpeg")
              .header("Content-Length", validMp3Bytes.size.toString())
              .body(object : okhttp3.ResponseBody() {
                private val partial = validMp3Bytes.copyOfRange(0, 2048)
                override fun contentType() = null
                override fun contentLength() = validMp3Bytes.size.toLong()
                override fun source(): okio.BufferedSource = okio.Buffer().write(partial)
              })
              .build()
            2 -> okhttp3.Response.Builder()
              .request(req)
              .protocol(okhttp3.Protocol.HTTP_1_1)
              .code(206)
              .message("Partial Content")
              .header("Content-Type", "audio/mpeg")
              .header("Content-Range", "bytes 100-${validMp3Bytes.size - 1}/${validMp3Bytes.size}")
              .body(okhttp3.ResponseBody.create(null, validMp3Bytes.copyOfRange(100, validMp3Bytes.size)))
              .build()
            else -> {
              thirdCallHadRangeHeader = req.header("Range") != null
              okhttp3.Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "audio/mpeg")
                .header("Content-Length", validMp3Bytes.size.toString())
                .body(okhttp3.ResponseBody.create(null, validMp3Bytes))
                .build()
            }
          }
        }
        .build()

      dm.httpClientOverride = interceptorClient
      dm.retryBaseDelayMs = 10L
      try {
        val surah4 = com.example.data.QuranData.getSurahById(4)!!
        dm.getLocalAudioFile(reciter.id, 4).delete()
        dm.startDownload(reciter, surah4)

        // Wait up to 3 seconds for async download coroutine to finish
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
          val row = dao.getDownloadById("${reciter.id}_4")
          if (row?.status == com.example.model.DownloadStatus.COMPLETED.name) break
          kotlinx.coroutines.delay(30L)
        }

        val completedRow = dao.getDownloadById("${reciter.id}_4")
        assertEquals(com.example.model.DownloadStatus.COMPLETED.name, completedRow?.status)
        org.junit.Assert.assertFalse(thirdCallHadRangeHeader)
        org.junit.Assert.assertTrue(dm.isDownloaded(reciter.id, 4))
        assertEquals(validMp3Bytes.size.toLong(), dm.getLocalAudioFile(reciter.id, 4).length())

        // 3. Starting download again when valid completed file exists MUST preserve it
        dm.startDownload(reciter, surah4)
        kotlinx.coroutines.delay(100L)
        org.junit.Assert.assertTrue(dm.isDownloaded(reciter.id, 4))
        assertEquals(validMp3Bytes.size.toLong(), dm.getLocalAudioFile(reciter.id, 4).length())

        // 4. Clean up
        dm.deleteDownload("${reciter.id}_4")
        kotlinx.coroutines.delay(100L)
        org.junit.Assert.assertFalse(dm.isDownloaded(reciter.id, 4))
      } finally {
        dm.httpClientOverride = null
        dm.retryBaseDelayMs = 750L
      }
    }
  }

  @Test
  fun `verify DownloadManager handles cancellation, competing tasks on same surah, and duplicate reciter conflict in backup`() {
    kotlinx.coroutines.runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dm = com.example.download.DownloadManager.getInstance(context)
      val dao = com.example.QuranApp.database.downloadDao()
      val reciter = com.example.data.DefaultReciters.getReciterById("afs")
      val surah5 = com.example.data.QuranData.getSurahById(5)!!
      val surah6 = com.example.data.QuranData.getSurahById(6)!!

      val validMp3Bytes = ByteArray(24_576) { idx ->
        when (idx) {
          0 -> 0x49
          1 -> 0x44
          2 -> 0x33
          3 -> 0x03
          in 4..9 -> 0x00
          10 -> 0xFF.toByte()
          11 -> 0xFB.toByte()
          12 -> 0x90.toByte()
          else -> 0x55
        }
      }

      // 1. Cancellation mid-download removes in-progress row and does NOT mark file downloaded
      val slowGate = java.util.concurrent.CountDownLatch(1)
      val slowClient = okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
          slowGate.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
          okhttp3.Response.Builder()
            .request(chain.request())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(500)
            .message("Cancelled")
            .body(okhttp3.ResponseBody.create(null, ByteArray(0)))
            .build()
        }
        .build()

      dm.httpClientOverride = slowClient
      dm.retryBaseDelayMs = 10L
      try {
        dm.getLocalAudioFile(reciter.id, 5).delete()
        dm.startDownload(reciter, surah5)
        kotlinx.coroutines.delay(50L)
        dm.cancelDownload("${reciter.id}_5")
        slowGate.countDown()
        kotlinx.coroutines.delay(150L)
        org.junit.Assert.assertFalse(dm.isDownloaded(reciter.id, 5))
      } finally {
        dm.httpClientOverride = null
        dm.retryBaseDelayMs = 750L
      }

      // 2. Two competing startDownload calls for same surah: second task supersedes first and finishes cleanly
      val task1Gate = java.util.concurrent.CountDownLatch(1)
      var reqNum = 0
      val raceClient = okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
          val currentReq = ++reqNum
          if (currentReq == 1) {
            task1Gate.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            okhttp3.Response.Builder()
              .request(chain.request())
              .protocol(okhttp3.Protocol.HTTP_1_1)
              .code(500)
              .message("Superseded")
              .body(okhttp3.ResponseBody.create(null, ByteArray(0)))
              .build()
          } else {
            okhttp3.Response.Builder()
              .request(chain.request())
              .protocol(okhttp3.Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .header("Content-Type", "audio/mpeg")
              .header("Content-Length", validMp3Bytes.size.toString())
              .body(okhttp3.ResponseBody.create(null, validMp3Bytes))
              .build()
          }
        }
        .build()

      dm.httpClientOverride = raceClient
      dm.retryBaseDelayMs = 10L
      try {
        dm.getLocalAudioFile(reciter.id, 6).delete()
        dm.startDownload(reciter, surah6)
        kotlinx.coroutines.delay(30L)
        // Supersede immediately with 2nd task on same surah
        dm.startDownload(reciter, surah6)
        task1Gate.countDown()

        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
          val row = dao.getDownloadById("${reciter.id}_6")
          if (row?.status == com.example.model.DownloadStatus.COMPLETED.name) break
          kotlinx.coroutines.delay(30L)
        }
        assertEquals(com.example.model.DownloadStatus.COMPLETED.name, dao.getDownloadById("${reciter.id}_6")?.status)
        org.junit.Assert.assertTrue(dm.isDownloaded(reciter.id, 6))
        dm.deleteDownload("${reciter.id}_6")
        kotlinx.coroutines.delay(100L)
      } finally {
        dm.httpClientOverride = null
        dm.retryBaseDelayMs = 750L
      }

      // 3. Conflicting custom reciter IDs inside same backup JSON must be rejected cleanly
      val app = ApplicationProvider.getApplicationContext<android.app.Application>()
      val viewModel = com.example.ui.viewmodel.QuranViewModel(app)
      val conflictingRecitersBackup = """
        {
          "version": 2,
          "favorites": [101],
          "customReciters": [
            {
              "id": "dup_id",
              "name": "قارئ أول",
              "serverUrl": "https://one.example.com/audio/",
              "availableSurahsCount": 114
            },
            {
              "id": "dup_id",
              "name": "قارئ مختلف بنفس المعرف",
              "serverUrl": "https://two.example.com/audio/",
              "availableSurahsCount": 114
            }
          ]
        }
      """.trimIndent()
      org.junit.Assert.assertFalse(viewModel.importBackupJson(conflictingRecitersBackup))
      org.junit.Assert.assertNull(com.example.QuranApp.database.reciterDao().getReciterById("dup_id"))
      org.junit.Assert.assertFalse(com.example.QuranApp.database.surahStateDao().getState(101)?.isFavorite == true)
    }
  }
}

