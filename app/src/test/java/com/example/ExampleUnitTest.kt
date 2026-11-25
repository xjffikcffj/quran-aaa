package com.example

import com.example.data.ArabicSearchUtils
import com.example.data.DefaultReciters
import com.example.data.QuranData
import com.example.model.SleepTimerOption
import com.example.ui.components.SleepTimerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun verifySurahsCountAndNames() {
        assertEquals(114, QuranData.allSurahs.size)
        assertEquals("الفاتحة", QuranData.allSurahs.first().name)
        assertEquals(7, QuranData.allSurahs.first().versesCount)
        assertEquals("الناس", QuranData.allSurahs.last().name)
        assertEquals(6, QuranData.allSurahs.last().versesCount)
        assertEquals("الكهف", QuranData.getSurahById(18)?.name)
        assertEquals(110, QuranData.getSurahById(18)?.versesCount)
    }

    @Test
    fun verifyEightVerifiedCompleteReciters() {
        // Waleed Al-Dulaimi removed because he only had 58 surahs.
        // Exactly 8 verified reciters with complete 114 surahs remain.
        assertEquals(8, DefaultReciters.list.size)
        val names = DefaultReciters.list.map { it.name }
        assertTrue(names.contains("مشاري العفاسي"))
        assertTrue(names.contains("عبد الرحمن السديس"))
        assertTrue(names.contains("محمود خليل الحصري"))
        assertTrue(names.contains("عبد الباسط عبد الصمد"))
        assertTrue(names.contains("سعد الغامدي"))
        assertTrue(names.contains("سهل ياسين"))
        assertTrue(names.contains("رعد الكردي"))
        assertTrue(names.contains("عبد الرزاق الدليمي"))
        assertFalse(names.contains("وليد الدليمي"))

        // All 8 reciters have full 114 surahs available
        for (reciter in DefaultReciters.list) {
            assertEquals(114, reciter.availableSurahsCount)
            assertTrue(reciter.isSurahAvailable(1))
            assertTrue(reciter.isSurahAvailable(18))
            assertTrue(reciter.isSurahAvailable(114))
        }
    }

    @Test
    fun verifySleepTimerOptionsInExactOrder() {
        // Verify all sleep timer enum entries and their exact durations
        assertEquals(0L, SleepTimerOption.OFF.totalSeconds)
        assertEquals("إيقاف مؤقت النوم", SleepTimerOption.OFF.label)

        assertEquals(-1L, SleepTimerOption.END_OF_SURAH.totalSeconds)
        assertEquals("عند نهاية السورة", SleepTimerOption.END_OF_SURAH.label)

        assertEquals(300L, SleepTimerOption.MINUTES_5.totalSeconds)
        assertEquals("5 دقائق", SleepTimerOption.MINUTES_5.label)

        assertEquals(600L, SleepTimerOption.MINUTES_10.totalSeconds)
        assertEquals("10 دقائق", SleepTimerOption.MINUTES_10.label)

        assertEquals(900L, SleepTimerOption.MINUTES_15.totalSeconds)
        assertEquals("15 دقيقة", SleepTimerOption.MINUTES_15.label)

        assertEquals(1200L, SleepTimerOption.MINUTES_20.totalSeconds)
        assertEquals("20 دقيقة", SleepTimerOption.MINUTES_20.label)

        assertEquals(1800L, SleepTimerOption.MINUTES_30.totalSeconds)
        assertEquals("30 دقيقة", SleepTimerOption.MINUTES_30.label)

        assertEquals(2700L, SleepTimerOption.MINUTES_45.totalSeconds)
        assertEquals("45 دقيقة", SleepTimerOption.MINUTES_45.label)

        assertEquals(3600L, SleepTimerOption.HOUR_1.totalSeconds)
        assertEquals("ساعة واحدة", SleepTimerOption.HOUR_1.label)

        assertEquals(20L, SleepTimerOption.SECONDS_20_TEST.totalSeconds)
        assertEquals("20 ثانية (اختبار)", SleepTimerOption.SECONDS_20_TEST.label)
    }

    @Test
    fun verifySleepTimerDialogConfigVisibility() {
        // In Release mode (showTestOption = false):
        val releaseOptions = SleepTimerConfig.getDialogOptions(showTestOption = false)
        // 1. SECONDS_20_TEST MUST NOT be visible to end users in release
        assertFalse(releaseOptions.any { it.first == SleepTimerOption.SECONDS_20_TEST })
        // 2. END_OF_SURAH MUST be present
        assertTrue(releaseOptions.any { it.first == SleepTimerOption.END_OF_SURAH })
        assertEquals("عند نهاية السورة", releaseOptions.first().second)
        // 3. Count is exactly 9 (END_OF_SURAH + 7 minute/hour options + OFF)
        assertEquals(9, releaseOptions.size)

        // In Debug mode (showTestOption = true):
        val debugOptions = SleepTimerConfig.getDialogOptions(showTestOption = true)
        // SECONDS_20_TEST is present for internal verification
        assertTrue(debugOptions.any { it.first == SleepTimerOption.SECONDS_20_TEST })
        assertEquals(10, debugOptions.size)
    }

    @Test
    fun testArabicSearchUtils() {
        // Indic digits normalization
        assertEquals("18", ArabicSearchUtils.normalizeDigits("١٨"))
        assertEquals("114", ArabicSearchUtils.normalizeDigits("١١٤"))

        // Ignoring word "سورة"
        val normalized = ArabicSearchUtils.normalizeArabicText("سورة البقرة")
        assertTrue(normalized.contains("البقره") || normalized.contains("البقرة"))
        assertFalse(normalized.contains("سورة"))

        // Diacritics stripping
        val withTashkeel = "سُورَةُ الْكَهْفِ"
        assertTrue(ArabicSearchUtils.matchesQuery(18, "الكهف", "Al-Kahf", withTashkeel))

        // Match by Indic number
        assertTrue(ArabicSearchUtils.matchesQuery(18, "الكهف", "Al-Kahf", "١٨"))
        assertTrue(ArabicSearchUtils.matchesQuery(1, "الفاتحة", "Al-Fatihah", "١"))

        // Match by name
        assertTrue(ArabicSearchUtils.matchesQuery(112, "الإخلاص", "Al-Ikhlas", "اخلاص"))
    }

    @Test
    fun testAudioUrlBuilder() {
        val afs = DefaultReciters.getReciterById("afs")
        assertEquals("https://cdn.mp3quran.net/audio/mishary-alafasy/r1/001.mp3", afs.getAudioUrl(1))
        assertEquals("https://cdn.mp3quran.net/audio/mishary-alafasy/r1/114.mp3", afs.getAudioUrl(114))
    }

    @Test
    fun testPlaybackPositionIsolationAndZeroOverwriteGuard() {
        val positions = mutableMapOf<String, Long>()

        fun savePos(reciterId: String, surahId: Int, posMs: Long) {
            val key = "pos_${reciterId}_$surahId"
            val existing = positions[key] ?: 0L
            // Guard: do not overwrite valid position with zero or transient buffer value
            if (posMs <= 1000L && existing > 3000L) {
                return
            }
            positions[key] = posMs
        }

        fun getPos(reciterId: String, surahId: Int): Long {
            return positions["pos_${reciterId}_$surahId"] ?: 0L
        }

        // 1. Save position for Reciter 1 on Surah 18 (4m 32s = 272000 ms)
        savePos("afs", 18, 272000L)
        assertEquals(272000L, getPos("afs", 18))

        // 2. Position for different reciter or different surah must remain independent (0 initially)
        assertEquals(0L, getPos("sds", 18))
        assertEquals(0L, getPos("afs", 1))

        // 3. Save position for Reciter 2 on Surah 18 (1 min = 60000 ms)
        savePos("sds", 18, 60000L)
        assertEquals(60000L, getPos("sds", 18))
        assertEquals(272000L, getPos("afs", 18)) // afs position must not be affected!

        // 4. Test zero overwrite guard: transient 0 during buffering/unready state must NOT overwrite saved position
        savePos("afs", 18, 0L)
        assertEquals(272000L, getPos("afs", 18)) // still preserved!

        savePos("afs", 18, 500L)
        assertEquals(272000L, getPos("afs", 18)) // still preserved!

        // 5. Legitimate forward seek/progress updates correctly
        savePos("afs", 18, 310000L)
        assertEquals(310000L, getPos("afs", 18))

        // 6. Legitimate force reset on playback completion (force = true)
        fun savePosForced(reciterId: String, surahId: Int, posMs: Long, force: Boolean) {
            val key = "pos_${reciterId}_$surahId"
            val existing = positions[key] ?: 0L
            if (!force && posMs <= 1000L && existing > 3000L) {
                return
            }
            positions[key] = posMs
        }

        savePosForced("afs", 18, 0L, force = true)
        assertEquals(0L, getPos("afs", 18))
    }

    @Test
    fun testResumptionTargetCalculation() {
        // Test calculation logic of target seek position:
        // 1. Explicit seekToMs takes priority
        // 2. startFromBeginning forces 0L
        // 3. Near end (within 3 seconds of duration) resets to 0L
        // 4. Standard saved position is resumed accurately

        fun calculateTargetSeek(
            explicitSeekMs: Long?,
            startFromBeginning: Boolean,
            savedPos: Long,
            totalDuration: Long
        ): Long {
            return when {
                explicitSeekMs != null -> explicitSeekMs
                startFromBeginning -> 0L
                totalDuration > 0 && savedPos >= totalDuration - 3000L -> 0L
                else -> savedPos
            }
        }

        // Case A: Mid-surah saved position (e.g. 52345 ms in a 600000 ms surah)
        assertEquals(52345L, calculateTargetSeek(null, false, 52345L, 600000L))

        // Case B: Explicit seek requested by user (e.g. scrubbed to 120000 ms)
        assertEquals(120000L, calculateTargetSeek(120000L, false, 52345L, 600000L))

        // Case C: Explicit restart from beginning
        assertEquals(0L, calculateTargetSeek(null, true, 52345L, 600000L))

        // Case D: Previous session was at very end (within 3 seconds of 600000 ms)
        assertEquals(0L, calculateTargetSeek(null, false, 598500L, 600000L))

        // Case E: Brand new surah with no prior saved position
        assertEquals(0L, calculateTargetSeek(null, false, 0L, 0L))
    }

    @Test
    fun testPartialReciterAvailabilityDoesNotAllowUnavailableSurahs() {
        val partialCustomReciter = com.example.model.Reciter(
            id = "custom_1",
            name = "قارئ جزئي",
            serverUrl = "https://example.com/audio/",
            availableSurahsCount = 30,
            availableSurahsList = emptySet(),
            isCustom = true
        )
        assertTrue(partialCustomReciter.isSurahAvailable(1))
        assertTrue(partialCustomReciter.isSurahAvailable(30))
        assertFalse(partialCustomReciter.isSurahAvailable(31))
        assertFalse(partialCustomReciter.isSurahAvailable(114))
        assertFalse(partialCustomReciter.isSurahAvailable(0))

        val explicitSetReciter = com.example.model.Reciter(
            id = "custom_2",
            name = "قارئ محدد",
            serverUrl = "https://example.com/audio",
            availableSurahsCount = 2,
            availableSurahsList = setOf(1, 18),
            isCustom = true
        )
        assertTrue(explicitSetReciter.isSurahAvailable(1))
        assertTrue(explicitSetReciter.isSurahAvailable(18))
        assertFalse(explicitSetReciter.isSurahAvailable(2))
        assertEquals("https://example.com/audio/018.mp3", explicitSetReciter.getAudioUrl(18))
    }

    @Test
    fun testAbdulrazzaqAlDulaimiAudioUrlInArabicLocale() {
        val prevLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("ar", "EG"))
            val dl = DefaultReciters.getReciterById("abdulrazzaq_dlaimi")
            assertEquals("abdulrazzaq_dlaimi", dl.id)
            assertEquals("https://archive.org/download/mmmmmm002_20220618/001.mp3", dl.getAudioUrl(1))
            assertEquals("https://archive.org/download/mmmmmm002_20220618/018.mp3", dl.getAudioUrl(18))
        } finally {
            java.util.Locale.setDefault(prevLocale)
        }
    }

    @Test
    fun testMp3HeaderValidationRejectsHtmlAndAcceptsId3AndMpegSync() {
        val htmlPayload = "<!DOCTYPE html><html><body>Error</body></html>".toByteArray()
        assertFalse(com.example.download.DownloadManager.hasValidMp3HeaderBytes(htmlPayload))

        val jsonPayload = "{\"error\":\"rate_limited\"}".toByteArray()
        assertFalse(com.example.download.DownloadManager.hasValidMp3HeaderBytes(jsonPayload))

        val id3Payload = byteArrayOf(
            0x49, 0x44, 0x33, 0x03, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x0A,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
            0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00
        )
        assertTrue(com.example.download.DownloadManager.hasValidMp3HeaderBytes(id3Payload))

        // Corrupt ID3 payload claiming tag size larger than total file size must fail
        val truncatedId3Payload = byteArrayOf(
            0x49, 0x44, 0x33, 0x03, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
        assertFalse(com.example.download.DownloadManager.hasValidMp3HeaderBytes(truncatedId3Payload))

        // Zero-filled payload must fail
        assertFalse(com.example.download.DownloadManager.hasValidMp3HeaderBytes(ByteArray(64)))

        // Valid MPEG-1 Layer III frame header: 0xFF 0xFB 0x90 0x00
        val mpegSyncPayload = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        assertTrue(com.example.download.DownloadManager.hasValidMp3HeaderBytes(mpegSyncPayload))
    }

    @Test
    fun testId3OnlyFakeAndRandomTrailingBytesAreRejected() {
        // 1. Valid ID3v2.3 header (tagSize = 10) followed by 4 arbitrary non-MPEG bytes (was falsely accepted before!)
        val id3PlusRandomBytes = byteArrayOf(
            0x49, 0x44, 0x33, 0x03, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x0A,
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(),
            0x01, 0x02, 0x03, 0x04
        )
        assertFalse(com.example.download.DownloadManager.hasValidMp3HeaderBytes(id3PlusRandomBytes))

        // 2. Valid ID3v2 header followed only by zeros / padding without any MPEG audio frame
        val id3OnlyZeros = ByteArray(128).apply {
            this[0] = 0x49; this[1] = 0x44; this[2] = 0x33
            this[3] = 0x03; this[4] = 0x00; this[5] = 0x00
            this[9] = 0x10 // tagSize = 16 -> audioOffset = 26
        }
        assertFalse(com.example.download.DownloadManager.hasValidMp3HeaderBytes(id3OnlyZeros))

        // 3. Valid ID3v2 header followed by bogus sync bytes (e.g. Layer I / free bitrate / reserved sample rate)
        val id3PlusBogusSync = ByteArray(64).apply {
            this[0] = 0x49; this[1] = 0x44; this[2] = 0x33
            this[3] = 0x03
            this[9] = 0x0A // audioOffset = 20
            this[20] = 0xFF.toByte()
            this[21] = 0xE0.toByte() // reserved version + layer 00
            this[22] = 0x00
            this[23] = 0x00
        }
        assertFalse(com.example.download.DownloadManager.hasValidMp3HeaderBytes(id3PlusBogusSync))

        // 4. Corrupt ID3 header (invalid version 0x05)
        val corruptVersionId3 = byteArrayOf(
            0x49, 0x44, 0x33, 0x05, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00
        )
        assertFalse(com.example.download.DownloadManager.hasValidMp3HeaderBytes(corruptVersionId3))

        // 5. Corrupt ID3 header (non-synchsafe size bit 0x80 set)
        val corruptSynchsafeId3 = byteArrayOf(
            0x49, 0x44, 0x33, 0x03, 0x00, 0x00,
            0x00, 0x00, 0x80.toByte(), 0x0A,
            0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00
        )
        assertFalse(com.example.download.DownloadManager.hasValidMp3HeaderBytes(corruptSynchsafeId3))
    }

    @Test
    fun testValidId3WithPaddingAndHeaderlessMp3AndLargeId3FileAreAccepted() {
        // 1. Valid ID3v2.3 + padding zeros + valid MPEG-1 Layer III frame (0xFF 0xFB 0x92 0x00)
        val id3WithPaddingAndMpeg = ByteArray(1024).apply {
            this[0] = 0x49; this[1] = 0x44; this[2] = 0x33
            this[3] = 0x03
            this[9] = 0x20 // tagSize = 32 -> audioOffset = 42
            // Leave bytes 42..399 as padding 0x00, place MPEG frame at 400
            this[400] = 0xFF.toByte()
            this[401] = 0xFB.toByte()
            this[402] = 0x92.toByte()
            this[403] = 0x00
        }
        assertTrue(com.example.download.DownloadManager.hasValidMp3HeaderBytes(id3WithPaddingAndMpeg))

        // 2. Headerless MP3 (no ID3 header, leading zero padding then valid MPEG frame)
        val headerlessMp3 = ByteArray(256).apply {
            this[16] = 0xFF.toByte()
            this[17] = 0xFA.toByte() // MPEG-1 Layer III with CRC disabled=0 bit
            this[18] = 0x90.toByte()
            this[19] = 0x00
        }
        assertTrue(com.example.download.DownloadManager.hasValidMp3HeaderBytes(headerlessMp3))

        // 3. Large ~50KB ID3v2 tag (mimicking archive.org Abdulrazzaq Al-Dulaimi cover art) on disk
        val validLargeFile = java.io.File.createTempFile("valid_large_id3_", ".mp3")
        val fakeLargeFile = java.io.File.createTempFile("fake_large_id3_", ".mp3")
        try {
            val tagSize = 48_991 // same scale as 001.mp3 on archive.org
            val audioOffset = 10 + tagSize
            val bytes = ByteArray(audioOffset + 4096).apply {
                this[0] = 0x49; this[1] = 0x44; this[2] = 0x33
                this[3] = 0x03
                this[6] = ((tagSize shr 21) and 0x7F).toByte()
                this[7] = ((tagSize shr 14) and 0x7F).toByte()
                this[8] = ((tagSize shr 7) and 0x7F).toByte()
                this[9] = (tagSize and 0x7F).toByte()
                this[audioOffset] = 0xFF.toByte()
                this[audioOffset + 1] = 0xFB.toByte()
                this[audioOffset + 2] = 0x92.toByte()
                this[audioOffset + 3] = 0x00
            }
            validLargeFile.writeBytes(bytes)
            assertTrue(com.example.download.DownloadManager.isValidMp3File(validLargeFile))

            // Same large ID3v2 header without an MPEG frame at audioOffset must fail!
            bytes[audioOffset] = 0x00
            bytes[audioOffset + 1] = 0x00
            bytes[audioOffset + 2] = 0x00
            fakeLargeFile.writeBytes(bytes)
            assertFalse(com.example.download.DownloadManager.isValidMp3File(fakeLargeFile))
        } finally {
            validLargeFile.delete()
            fakeLargeFile.delete()
        }
    }

    @Test
    fun testCustomReciterHttpsValidation() {
        assertTrue(com.example.model.Reciter.isValidHttpsServerUrl("https://cdn.example.com/audio/"))
        assertTrue(com.example.model.Reciter.isValidHttpsServerUrl("https://archive.org/download/mmmmmm002_20220618"))
        assertFalse(com.example.model.Reciter.isValidHttpsServerUrl("http://cdn.example.com/audio/"))
        assertFalse(com.example.model.Reciter.isValidHttpsServerUrl("ftp://cdn.example.com/audio/"))
        assertFalse(com.example.model.Reciter.isValidHttpsServerUrl("https://"))
        assertFalse(com.example.model.Reciter.isValidHttpsServerUrl("https://invalid host/audio/"))
    }

    @Test
    fun testParseContentRangeHeader() {
        val valid = com.example.download.DownloadManager.parseContentRange("bytes 1024-2047/4096")
        assertNotNull(valid)
        assertEquals(1024L, valid!!.start)
        assertEquals(2047L, valid.end)
        assertEquals(4096L, valid.total)

        val unknownTotal = com.example.download.DownloadManager.parseContentRange("bytes 0-511/*")
        assertNotNull(unknownTotal)
        assertEquals(0L, unknownTotal!!.start)
        assertEquals(null, unknownTotal.total)

        assertEquals(null, com.example.download.DownloadManager.parseContentRange("items 0-10/20"))
        assertEquals(null, com.example.download.DownloadManager.parseContentRange(null))
    }
}


