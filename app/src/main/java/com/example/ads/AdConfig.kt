package com.example.ads

/**
 * إعدادات ومعرّفات Google AdMob لتطبيق «القرآن الكريم الصوتي»
 *
 * المعرّفات المعتمدة للمشروع:
 * - App ID: ca-app-pub-8150114459606774~9089399753
 * - Banner Ad Unit ID: ca-app-pub-8150114459606774/6463236417
 * - Interstitial Ad Unit ID: ca-app-pub-8150114459606774/7655852403
 */
object AdConfig {

    /**
     * معرّف تطبيق AdMob الخاص بمشروع القرآن الكريم الصوتي
     * (مضبوط أيضًا في AndroidManifest.xml)
     */
    const val APP_ID = "ca-app-pub-8150114459606774~9089399753"

    /**
     * معرّف وحدة إعلان البانر المتكيّف (Banner Ad Unit ID)
     * يعرض أسفل واجهة التطبيق فوق شريط التنقل دون حجب أزرار التحكم أو مشغل التلاوة.
     */
    const val BANNER_AD_UNIT_ID = "ca-app-pub-8150114459606774/6463236417"

    /**
     * معرّف وحدة الإعلان البيني (Interstitial Ad Unit ID)
     * يعرض عند الانتقال إلى سورة جديدة، قبل بدء التلاوة مع تطبيق سياسة تحديد التكرار (Frequency Capping).
     */
    const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-8150114459606774/7655852403"

    // ضوابط تكرار الإعلانات البينية (Frequency Capping) لضمان تجربة استماع هادئة ومريحة:
    // الحد الأدنى للمدة الزمنية الفاصلة بين كل إعلان بيني والآخر (دقيقتان)
    const val INTERSTITIAL_MIN_INTERVAL_MS = 2 * 60 * 1000L

    // عدد السور الجديدة التي يختارها المستخدم قبل ظهور الإعلان البيني التالي
    const val INTERSTITIAL_SURAH_SELECTIONS_THRESHOLD = 3
}
