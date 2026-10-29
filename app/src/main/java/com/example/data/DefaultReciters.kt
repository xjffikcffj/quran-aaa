package com.example.data

import com.example.model.Reciter

object DefaultReciters {

    val list: List<Reciter> = listOf(
        Reciter(
            id = "afs",
            name = "مشاري العفاسي",
            serverUrl = "https://cdn.mp3quran.net/audio/mishary-alafasy/r1/",
            availableSurahsCount = 114,
            subInfo = "رواية حفص عن عاصم - المصحف المرتل كاملاً"
        ),
        Reciter(
            id = "sds",
            name = "عبد الرحمن السديس",
            serverUrl = "https://cdn.mp3quran.net/audio/abdulrahman-sudais/r1/",
            availableSurahsCount = 114,
            subInfo = "رواية حفص عن عاصم - المصحف المرتل كاملاً"
        ),
        Reciter(
            id = "husr",
            name = "محمود خليل الحصري",
            serverUrl = "https://cdn.mp3quran.net/audio/mahmoud-husary/r1/",
            availableSurahsCount = 114,
            subInfo = "رواية حفص عن عاصم - المصحف المرتل كاملاً"
        ),
        Reciter(
            id = "basit",
            name = "عبد الباسط عبد الصمد",
            serverUrl = "https://cdn.mp3quran.net/audio/abdulbasit-abdulsamad/r1/",
            availableSurahsCount = 114,
            subInfo = "رواية حفص عن عاصم - المصحف المرتل كاملاً"
        ),
        Reciter(
            id = "ghamdi",
            name = "سعد الغامدي",
            serverUrl = "https://cdn.mp3quran.net/audio/saad-ghamdi/r1/",
            availableSurahsCount = 114,
            subInfo = "رواية حفص عن عاصم - المصحف المرتل كاملاً"
        ),
        Reciter(
            id = "shl",
            name = "سهل ياسين",
            serverUrl = "https://cdn.mp3quran.net/audio/sahl-yassin/r1/",
            availableSurahsCount = 114,
            subInfo = "رواية حفص عن عاصم - المصحف المرتل كاملاً"
        ),
        Reciter(
            id = "raad",
            name = "رعد الكردي",
            serverUrl = "https://cdn.mp3quran.net/audio/raad-kurdi/r1/",
            availableSurahsCount = 114,
            subInfo = "رواية حفص عن عاصم - المصحف المرتل كاملاً"
        ),
        Reciter(
            id = "abdulrazzaq_dlaimi",
            name = "عبد الرزاق الدليمي",
            serverUrl = "https://archive.org/download/mmmmmm002_20220618/",
            availableSurahsCount = 114,
            subInfo = "رواية حفص عن عاصم - المصحف المرتل كاملاً"
        )
    )

    val defaultReciter: Reciter = list.first()

    fun getReciterById(id: String): Reciter {
        return list.firstOrNull { it.id == id } ?: defaultReciter
    }
}
