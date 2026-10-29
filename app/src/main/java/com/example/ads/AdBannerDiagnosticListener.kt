package com.example.ads

import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError

/**
 * Diagnostic AdListener for AdMob Banner ad lifecycle inspection.
 *
 * Provides comprehensive real-time Logcat tracking for:
 * - Request start and latency measurement
 * - Successful loading and response metadata
 * - Detailed failure analysis with exact AdMob error codes, domains, and root causes
 * - Full-screen overlay opening, impression, click, and dismissal events
 */
class AdBannerDiagnosticListener(
    private val adUnitId: String = AdConfig.BANNER_AD_UNIT_ID,
    private val onAdLoadedCallback: () -> Unit = {},
    private val onAdFailedCallback: (LoadAdError) -> Unit = {},
    private val onAdOpenedCallback: () -> Unit = {},
    private val onAdClosedCallback: () -> Unit = {}
) : AdListener() {

    companion object {
        const val TAG = "AdBannerDiagnostic"
    }

    private var requestStartTimeMs: Long = 0L

    /**
     * Call when initiating loadAd() to track request round-trip latency.
     */
    fun markRequestStarted() {
        requestStartTimeMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            """
            |----------------- [BANNER AD REQUEST DISPATCHED] -----------------
            | Target AdUnitId : $adUnitId
            | Status           : Dispatched to Google Mobile Ads SDK...
            | Timestamp        : ${System.currentTimeMillis()}
            |------------------------------------------------------------------
            """.trimMargin()
        )
    }

    override fun onAdLoaded() {
        val durationMs = if (requestStartTimeMs > 0) SystemClock.elapsedRealtime() - requestStartTimeMs else 0L
        Log.i(
            TAG,
            """
            |================= [BANNER AD LOAD SUCCESS] =================
            | AdUnitId         : $adUnitId
            | Load Duration    : ${durationMs}ms
            | Status           : Ad is ready and rendering in the layout.
            | Structural State : VALID (Container allocated & ad content active)
            |============================================================
            """.trimMargin()
        )
        onAdLoadedCallback()
    }

    override fun onAdFailedToLoad(error: LoadAdError) {
        val durationMs = if (requestStartTimeMs > 0) SystemClock.elapsedRealtime() - requestStartTimeMs else 0L
        val (codeName, explanation) = when (error.code) {
            AdRequest.ERROR_CODE_INTERNAL_ERROR -> Pair(
                "INTERNAL_ERROR (Code 0)",
                "Internal error (e.g. invalid response from ad server or WebView issue)."
            )
            AdRequest.ERROR_CODE_INVALID_REQUEST -> Pair(
                "INVALID_REQUEST (Code 1)",
                "Ad request was invalid (e.g. ad unit ID was incorrect or account not approved)."
            )
            AdRequest.ERROR_CODE_NETWORK_ERROR -> Pair(
                "NETWORK_ERROR (Code 2)",
                "Network connection error (check device connectivity or DNS resolution)."
            )
            AdRequest.ERROR_CODE_NO_FILL -> Pair(
                "NO_FILL (Code 3)",
                "The ad server returned no inventory. This is normal for new ad units or in development/emulators without a registered test device ID."
            )
            else -> Pair(
                "UNKNOWN_CODE (${error.code})",
                "Unspecified AdMob error code."
            )
        }

        Log.w(
            TAG,
            """
            |***************** [BANNER AD LOAD FAILURE] *****************
            | AdUnitId         : $adUnitId
            | Error Code       : $codeName
            | Explanation      : $explanation
            | Error Message    : '${error.message}'
            | Error Domain     : '${error.domain}'
            | Response Info    : ${error.responseInfo}
            | Cause            : ${error.cause?.message ?: "None"}
            | Latency          : ${durationMs}ms
            | Root Cause Class : DELIVERY FAILURE (AdMob did not return an ad; structural layout is intact)
            |************************************************************
            """.trimMargin()
        )
        onAdFailedCallback(error)
    }

    override fun onAdOpened() {
        Log.i(TAG, "Lifecycle [onAdOpened]: User tapped banner ad. Full-screen overlay or web intent launched.")
        onAdOpenedCallback()
    }

    override fun onAdClicked() {
        Log.i(TAG, "Lifecycle [onAdClicked]: Click recorded by AdMob SDK.")
    }

    override fun onAdClosed() {
        Log.i(TAG, "Lifecycle [onAdClosed]: User dismissed overlay and returned to application.")
        onAdClosedCallback()
    }

    override fun onAdImpression() {
        Log.i(TAG, "Lifecycle [onAdImpression]: Ad impression successfully recorded.")
    }
}
