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

    companion object {
        fun isValidHttpsServerUrl(rawUrl: String): Boolean {
            val trimmed = rawUrl.trim()
            if (trimmed.length <= 8 || !trimmed.startsWith("https://", ignoreCase = true)) {
                return false
            }
            if (trimmed.any { it.isWhitespace() }) {
                return false
            }
            return try {
                val uri = java.net.URI(trimmed)
                uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }

        fun normalizeServerUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }
    }
}
