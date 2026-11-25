package com.example.model

import java.util.Locale

data class Reciter(
    val id: String,
    val name: String,
    val serverUrl: String,
    val availableSurahsCount: Int = 114,
    val availableSurahsList: Set<Int> = emptySet(),
    val isCustom: Boolean = false,
    val subInfo: String = ""
) {
    fun isSurahAvailable(surahId: Int): Boolean {
        if (surahId !in 1..114) return false
        return when {
            availableSurahsList.isNotEmpty() -> availableSurahsList.contains(surahId)
            availableSurahsCount >= 114 -> true
            availableSurahsCount <= 0 -> false
            else -> surahId <= availableSurahsCount
        }
    }

    fun getAudioUrl(surahId: Int): String {
        val paddedNumber = String.format(Locale.US, "%03d", surahId)
        val trimmed = serverUrl.trim()
        return if (trimmed.endsWith("/")) {
            "$trimmed$paddedNumber.mp3"
        } else {
            "$trimmed/$paddedNumber.mp3"
        }
    }
}
