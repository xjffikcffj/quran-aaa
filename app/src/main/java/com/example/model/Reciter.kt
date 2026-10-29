package com.example.model

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
        return if (availableSurahsCount == 114 || availableSurahsList.isEmpty()) {
            true
        } else {
            availableSurahsList.contains(surahId)
        }
    }

    fun getAudioUrl(surahId: Int): String {
        val paddedNumber = String.format("%03d", surahId)
        return if (serverUrl.endsWith("/")) {
            "$serverUrl$paddedNumber.mp3"
        } else {
            "$serverUrl/$paddedNumber.mp3"
        }
    }
}
