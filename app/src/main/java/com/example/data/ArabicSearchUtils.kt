package com.example.data

object ArabicSearchUtils {

    /**
     * Converts Eastern Arabic (Indic) numerals (٠-٩) to standard ASCII numerals (0-9).
     */
    fun normalizeDigits(input: String): String {
        val arabicIndicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        var result = input
        for (i in arabicIndicDigits.indices) {
            result = result.replace(arabicIndicDigits[i], ('0' + i))
        }
        return result
    }

    /**
     * Normalizes Arabic text for flexible search:
     * - Converts digits to standard ASCII.
     * - Strips Arabic diacritics / tashkeel (fatha, kasra, damma, sukoon, shadda, tanween) FIRST.
     * - Removes "سورة" or "سوره".
     * - Normalizes alef variations (أ, إ, آ -> ا).
     * - Normalizes taa marbuta (ة -> ه).
     * - Normalizes alif maqsura (ى -> ي).
     * - Trims excess whitespace.
     */
    fun normalizeArabicText(text: String): String {
        var str = normalizeDigits(text.trim())

        // 1. Remove Arabic diacritics first (Unicode range 0x064B - 0x0652, 0x0670)
        val diacriticsRegex = Regex("[\u064B-\u0652\u0670]")
        str = diacriticsRegex.replace(str, "")

        // 2. Remove the word "سورة" / "سوره" from anywhere in query
        str = str.replace("سورة", "")
        str = str.replace("سوره", "")

        // 3. Normalize letter shapes
        val builder = java.lang.StringBuilder()
        for (ch in str) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> builder.append('ا')
                'ة' -> builder.append('ه')
                'ى' -> builder.append('ي')
                'ؤ' -> builder.append('و')
                'ئ' -> builder.append('ي')
                else -> builder.append(ch)
            }
        }

        return builder.toString().trim()
    }

    /**
     * Matches a search query against a Surah:
     * Checks surah number (both Western and Arabic numerals) or normalized Arabic name or English name.
     */
    fun matchesQuery(surahId: Int, surahName: String, englishName: String, query: String): Boolean {
        if (query.isBlank()) return true

        val cleanQuery = normalizeArabicText(query)
        if (cleanQuery.isEmpty()) return true

        // Match by number (e.g. "18" or "١٨")
        val numberFromQuery = cleanQuery.toIntOrNull()
        if (numberFromQuery != null && numberFromQuery == surahId) {
            return true
        }

        // Match normalized Arabic name
        val normalizedSurahName = normalizeArabicText(surahName)
        if (normalizedSurahName.contains(cleanQuery, ignoreCase = true)) {
            return true
        }

        // Match English transliteration
        if (englishName.contains(cleanQuery, ignoreCase = true)) {
            return true
        }

        return false
    }
}
