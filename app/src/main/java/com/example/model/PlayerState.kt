package com.example.model

enum class RepeatMode {
    NONE,       // Stop after current surah
    ONE,        // Repeat current surah
    ALL         // Auto-advance to next surah (1 to 114)
}

enum class SleepTimerOption(val totalSeconds: Long, val label: String) {
    OFF(0L, "إيقاف مؤقت النوم"),
    END_OF_SURAH(-1L, "عند نهاية السورة"),
    MINUTES_5(5 * 60L, "5 دقائق"),
    MINUTES_10(10 * 60L, "10 دقائق"),
    MINUTES_15(15 * 60L, "15 دقيقة"),
    MINUTES_20(20 * 60L, "20 دقيقة"),
    MINUTES_30(30 * 60L, "30 دقيقة"),
    MINUTES_45(45 * 60L, "45 دقيقة"),
    HOUR_1(60 * 60L, "ساعة واحدة"),
    SECONDS_20_TEST(20L, "20 ثانية (اختبار)")
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentSurah: Surah? = null,
    val currentReciter: Reciter? = null,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val sleepTimerOption: SleepTimerOption = SleepTimerOption.OFF,
    val sleepTimerSecondsRemaining: Long = 0L,
    val isPlayingOffline: Boolean = false,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (totalDurationMs > 0) (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f
}
