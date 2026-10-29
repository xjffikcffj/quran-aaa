package com.example.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages preloading and display of AdMob Interstitial Ads when a new Surah is selected.
 *
 * Guarantees:
 * - Strictly respects user consent and network availability before requesting or showing ads.
 * - Does not block the main UI thread or interfere with audio playback.
 * - Never shows over an actively playing surah unless switching to a different surah.
 * - Guarantees onProceed() is called exactly once.
 */
object InterstitialAdManager {

    private const val TAG = "InterstitialAdManager"

    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false
    private var isShowing = false
    private var lastShownTimeMs: Long = 0L
    private var surahSelectionCounter = 0

    /**
     * Preloads an interstitial ad in the background when consent and network are available.
     */
    fun loadAd(context: Context) {
        if (isLoading || interstitialAd != null) {
            return
        }

        // Guard against requesting ads when user has not consented or device is offline
        if (!ConsentManager.canRequestAds(context) || !ConsentManager.isNetworkAvailable(context)) {
            Log.d(TAG, "Skipping interstitial preload: consent not granted or network unavailable.")
            return
        }

        isLoading = true
        val adRequest = AdRequest.Builder().build()

        try {
            InterstitialAd.load(
                context.applicationContext,
                AdConfig.INTERSTITIAL_AD_UNIT_ID,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        Log.i(TAG, "Interstitial ad loaded successfully.")
                        interstitialAd = ad
                        isLoading = false
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        Log.w(TAG, "Interstitial ad failed to load: ${loadAdError.code} - ${loadAdError.message}")
                        interstitialAd = null
                        isLoading = false
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "InterstitialAd.load encountered exception: ${e.message}")
            isLoading = false
        }
    }

    /**
     * Shows an interstitial ad before playing a newly selected Surah, with strict safeguards:
     * - Does NOT show over an actively playing surah unless the user is explicitly switching to a different surah.
     * - Respects user consent status and network availability.
     * - Respects frequency capping (interval time and selection count).
     * - Guarantees onProceed() is invoked EXACTLY ONCE without audio interruption or delay.
     * - If ad is not ready, onProceed() executes immediately without any delay.
     */
    fun showAdBeforeSurah(
        activity: Activity,
        isNewSurah: Boolean,
        onProceed: () -> Unit
    ) {
        val proceedCalled = AtomicBoolean(false)
        fun safeProceed() {
            if (proceedCalled.compareAndSet(false, true)) {
                onProceed()
            }
        }

        // 1. If the user tapped on the currently loaded surah (e.g. toggling play/pause or opening sheet),
        // do NOT interrupt with an interstitial ad.
        if (!isNewSurah) {
            safeProceed()
            return
        }

        // 2. If consent is not given or network is offline, proceed directly to playback without waiting
        if (!ConsentManager.canRequestAds(activity) || !ConsentManager.isNetworkAvailable(activity)) {
            Log.d(TAG, "Bypassing interstitial ad: consent not granted or device offline.")
            safeProceed()
            return
        }

        surahSelectionCounter++
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastShownTimeMs

        val meetsFrequency = surahSelectionCounter >= AdConfig.INTERSTITIAL_SURAH_SELECTIONS_THRESHOLD ||
                (lastShownTimeMs > 0 && timeSinceLast >= AdConfig.INTERSTITIAL_MIN_INTERVAL_MS)

        val ad = interstitialAd
        if (ad == null || isShowing || !meetsFrequency) {
            Log.d(TAG, "Skipping interstitial ad (Ready: ${ad != null}, Count: $surahSelectionCounter, Interval: ${timeSinceLast}ms)")
            if (ad == null) {
                loadAd(activity)
            }
            safeProceed()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.i(TAG, "Interstitial ad dismissed by user.")
                interstitialAd = null
                isShowing = false
                lastShownTimeMs = System.currentTimeMillis()
                surahSelectionCounter = 0
                loadAd(activity)
                safeProceed()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.w(TAG, "Interstitial ad failed to show: ${adError.code} - ${adError.message}")
                interstitialAd = null
                isShowing = false
                loadAd(activity)
                safeProceed()
            }

            override fun onAdShowedFullScreenContent() {
                Log.i(TAG, "Lifecycle [onAdShowedFullScreenContent]: Interstitial displayed.")
                isShowing = true
            }

            override fun onAdImpression() {
                Log.i(TAG, "Lifecycle [onAdImpression]: Interstitial impression recorded.")
            }

            override fun onAdClicked() {
                Log.i(TAG, "Lifecycle [onAdClicked]: User tapped on the interstitial ad.")
            }
        }

        isShowing = true
        ad.show(activity)
    }
}
