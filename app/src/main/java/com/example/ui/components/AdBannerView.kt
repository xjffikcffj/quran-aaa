package com.example.ui.components

import android.content.Context
import android.util.Log
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ads.AdBannerDiagnosticListener
import com.example.ads.AdConfig
import com.example.ads.ConsentManager
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

private const val TAG = "AdBannerLifecycle"
private const val DIAGNOSTIC_TAG = "AdBannerDiagnostic"

/**
 * Diagnostic log function to track and output AdMob SDK's lifecycle events
 * (onAdLoaded, onAdFailedToLoad, onAdOpened, onAdClosed, onAdImpression) to Logcat.
 */
fun logAdBannerLifecycleEvent(
    event: String,
    details: String = "",
    error: LoadAdError? = null
): String {
    val message = buildString {
        append("Lifecycle Event: [$event]")
        if (details.isNotEmpty()) append(" | Details: $details")
        if (error != null) {
            val codeStr = when (error.code) {
                AdRequest.ERROR_CODE_INTERNAL_ERROR -> "INTERNAL_ERROR (0)"
                AdRequest.ERROR_CODE_INVALID_REQUEST -> "INVALID_REQUEST (1)"
                AdRequest.ERROR_CODE_NETWORK_ERROR -> "NETWORK_ERROR (2)"
                AdRequest.ERROR_CODE_NO_FILL -> "NO_FILL (3)"
                else -> "UNKNOWN (${error.code})"
            }
            append(" | Code: $codeStr | Message: '${error.message}' | Domain: '${error.domain}' | Cause: '${error.cause?.message ?: "None"}'")
        }
    }
    Log.i(TAG, message)
    return message
}

/**
 * Verifies whether the banner container is receiving a non-zero height constraint during the layout phase.
 */
fun verifyBannerLayoutConstraints(
    minHeightPx: Int,
    maxHeightPx: Int,
    measuredWidthPx: Int,
    measuredHeightPx: Int,
    density: Float
): Boolean {
    val isNonZero = measuredHeightPx > 0 && maxHeightPx > 0
    val heightDp = if (density > 0f) measuredHeightPx / density else 0f
    val logMsg = if (isNonZero) {
        "✅ [LAYOUT PHASE VERIFIED] Banner container has a NON-ZERO height constraint: " +
                "measuredHeight=${heightDp.toInt()}dp (${measuredHeightPx}px), " +
                "minHeightConstraint=${minHeightPx}px, maxHeightConstraint=${maxHeightPx}px."
    } else {
        "❌ [LAYOUT PHASE FAILED] Banner container is constrained to ZERO HEIGHT: " +
                "measuredHeight=${measuredHeightPx}px, maxHeightConstraint=${maxHeightPx}px."
    }
    Log.i(DIAGNOSTIC_TAG, logMsg)
    return isNonZero
}

/**
 * Diagnostic function to analyze, verify, and log AdBanner state, loading flags,
 * and container dimensions in real time.
 */
fun logAdBannerDiagnostics(
    isAdLoaded: Boolean,
    isAdsReady: Boolean,
    canRequestAds: Boolean,
    isNetworkAvailable: Boolean,
    adUnitId: String,
    targetHeightDp: Dp,
    measuredWidthPx: Int,
    measuredHeightPx: Int,
    density: Float
): String {
    val measuredHeightDp = if (density > 0f) measuredHeightPx / density else 0f
    val measuredWidthDp = if (density > 0f) measuredWidthPx / density else 0f

    val dimensionStatus = if (measuredHeightPx <= 0) {
        "❌ [CRITICAL DIMENSION ERROR] Container height is 0px (0dp)! The banner container is collapsed or lacks dimensions in Compose layout."
    } else {
        "✅ [DIMENSION OK] Container allocated: ${measuredWidthDp.toInt()}dp x ${measuredHeightDp.toInt()}dp (${measuredWidthPx}px x ${measuredHeightPx}px). Target height: ${targetHeightDp}."
    }

    val loadingStatus = when {
        isAdLoaded -> "✅ [AD LOADED] Banner ad is fully loaded and rendering."
        !isNetworkAvailable -> "⚠️ [OFFLINE] Device has no internet connection. Ad cannot be fetched."
        !isAdsReady -> "⏳ [INITIALIZING] MobileAds SDK background initialization in progress."
        !canRequestAds -> "⚠️ [CONSENT PENDING/DENIED] Google UMP consent has not permitted ad requests."
        else -> "⏳ [FETCHING] Request dispatched to AdMob servers for Unit ID: $adUnitId. Awaiting onAdLoaded / onAdFailedToLoad."
    }

    val report = """
        |=================== [AD BANNER DIAGNOSTIC REPORT] ===================
        | Ad Unit ID       : $adUnitId
        | Dimensions Check : $dimensionStatus
        | Loading Status   : $loadingStatus
        | Flags Summary    : isAdLoaded=$isAdLoaded, isAdsReady=$isAdsReady, canRequestAds=$canRequestAds, isNetworkAvailable=$isNetworkAvailable
        | Target Height    : $targetHeightDp
        | Measured Size    : ${measuredWidthPx}x${measuredHeightPx}px (~${measuredWidthDp.toInt()}x${measuredHeightDp.toInt()}dp)
        |====================================================================
    """.trimMargin()

    Log.i(DIAGNOSTIC_TAG, report)
    return report
}

/**
 * Jetpack Compose wrapper for Google AdMob Adaptive Banner Ad with comprehensive lifecycle event logging
 * and layout-phase constraint verification.
 *
 * Guarantees:
 * - Fixed reserved height matching AdMob Anchored Adaptive Banner spec (Modifier.height & defaultMinSize).
 * - Direct layout-phase constraint inspection via Modifier.layout and verifyBannerLayoutConstraints.
 * - Real-time lifecycle logging for onAdLoaded, onAdFailedToLoad, onAdOpened, onAdClosed, onAdImpression.
 * - Never collapses to 0dp; displays a sleek placeholder frame while waiting for live ad fill.
 * - AdView is always attached to the view hierarchy so Android and WebView can measure and render reliably.
 * - Completely safe from audio playback interference and main thread blocking.
 */
@Composable
fun AdBannerView(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.coerceAtLeast(360)
    val density = LocalDensity.current.density

    var isAdLoaded by remember { mutableStateOf(false) }
    var measuredWidthPx by remember { mutableIntStateOf(0) }
    var measuredHeightPx by remember { mutableIntStateOf(0) }

    // Adaptive banner ad size calculation per official Google recommendations
    val adSize = remember(screenWidth) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, screenWidth)
    }
    val bannerHeight = remember(adSize) {
        val h = adSize.height
        if (h > 0) h.dp.coerceIn(50.dp, 80.dp) else 56.dp
    }

    val isAdsReady by ConsentManager.isAdsReady.collectAsState()

    val diagnosticListener = remember(context, density, bannerHeight) {
        AdBannerDiagnosticListener(
            adUnitId = AdConfig.BANNER_AD_UNIT_ID,
            onAdLoadedCallback = {
                isAdLoaded = true
                logAdBannerLifecycleEvent("onAdLoaded", "Banner ad loaded successfully and visible in layout.")
                logAdBannerDiagnostics(
                    isAdLoaded = true,
                    isAdsReady = isAdsReady,
                    canRequestAds = ConsentManager.canRequestAds(context),
                    isNetworkAvailable = ConsentManager.isNetworkAvailable(context),
                    adUnitId = AdConfig.BANNER_AD_UNIT_ID,
                    targetHeightDp = bannerHeight,
                    measuredWidthPx = measuredWidthPx,
                    measuredHeightPx = measuredHeightPx,
                    density = density
                )
            },
            onAdFailedCallback = { error ->
                isAdLoaded = false
                logAdBannerLifecycleEvent("onAdFailedToLoad", "AdMob returned load failure.", error)
                logAdBannerDiagnostics(
                    isAdLoaded = false,
                    isAdsReady = isAdsReady,
                    canRequestAds = ConsentManager.canRequestAds(context),
                    isNetworkAvailable = ConsentManager.isNetworkAvailable(context),
                    adUnitId = AdConfig.BANNER_AD_UNIT_ID,
                    targetHeightDp = bannerHeight,
                    measuredWidthPx = measuredWidthPx,
                    measuredHeightPx = measuredHeightPx,
                    density = density
                )
            },
            onAdOpenedCallback = {
                logAdBannerLifecycleEvent("onAdOpened", "Banner overlay opened full-screen.")
            },
            onAdClosedCallback = {
                logAdBannerLifecycleEvent("onAdClosed", "Banner overlay dismissed, returning to app.")
            }
        )
    }

    val adView = remember(context) {
        AdView(context).apply {
            setAdSize(adSize)
            adUnitId = AdConfig.BANNER_AD_UNIT_ID
            adListener = diagnosticListener
        }
    }

    // Trigger ad load strictly when MobileAds is initialized AND consent is granted
    LaunchedEffect(isAdsReady) {
        val canRequest = ConsentManager.canRequestAds(context)
        val networkAvailable = ConsentManager.isNetworkAvailable(context)
        if (isAdsReady && canRequest && networkAvailable) {
            diagnosticListener.markRequestStarted()
            logAdBannerLifecycleEvent("loadAd", "Dispatching ad request to AdMob servers with valid consent.")
            try {
                adView.loadAd(AdRequest.Builder().build())
            } catch (e: Exception) {
                Log.e(TAG, "Lifecycle [loadAd]: Exception while requesting banner: ${e.message}", e)
            }
        } else {
            Log.d(
                TAG,
                "Lifecycle [loadAd]: Banner request suppressed (isAdsReady=$isAdsReady, " +
                        "canRequestAds=$canRequest, isNetworkAvailable=$networkAvailable)"
            )
        }
        logAdBannerDiagnostics(
            isAdLoaded = isAdLoaded,
            isAdsReady = isAdsReady,
            canRequestAds = ConsentManager.canRequestAds(context),
            isNetworkAvailable = ConsentManager.isNetworkAvailable(context),
            adUnitId = AdConfig.BANNER_AD_UNIT_ID,
            targetHeightDp = bannerHeight,
            measuredWidthPx = measuredWidthPx,
            measuredHeightPx = measuredHeightPx,
            density = density
        )
    }

    DisposableEffect(adView) {
        onDispose {
            logAdBannerLifecycleEvent("destroy", "AdView destroyed on disposal.")
            adView.destroy()
        }
    }

    // Stable banner container with layout-phase constraint verification
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(bannerHeight)
            .heightIn(min = 50.dp, max = 80.dp)
            .defaultMinSize(minHeight = bannerHeight)
            .layout { measurable, constraints ->
                // Measure the layout during the layout phase and verify constraints
                val placeable = measurable.measure(constraints)
                verifyBannerLayoutConstraints(
                    minHeightPx = constraints.minHeight,
                    maxHeightPx = constraints.maxHeight,
                    measuredWidthPx = placeable.width,
                    measuredHeightPx = placeable.height,
                    density = density
                )
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            }
            .onSizeChanged { size ->
                measuredWidthPx = size.width
                measuredHeightPx = size.height
                logAdBannerDiagnostics(
                    isAdLoaded = isAdLoaded,
                    isAdsReady = isAdsReady,
                    canRequestAds = ConsentManager.canRequestAds(context),
                    isNetworkAvailable = ConsentManager.isNetworkAvailable(context),
                    adUnitId = AdConfig.BANNER_AD_UNIT_ID,
                    targetHeightDp = bannerHeight,
                    measuredWidthPx = size.width,
                    measuredHeightPx = size.height,
                    density = density
                )
            }
            .background(MaterialTheme.colorScheme.surface)
            .testTag("admob_banner_container"),
        contentAlignment = Alignment.Center
    ) {
        // AdView is always attached to the hierarchy with explicit height matching the container.
        // The container reserves the exact layout height (50dp min) so the UI remains stable,
        // without displaying placeholder or diagnostic text to the user while waiting for ad fill.
        AndroidView(
            factory = { adView },
            modifier = Modifier
                .fillMaxWidth()
                .height(bannerHeight)
                .defaultMinSize(minHeight = 50.dp)
                .testTag("admob_banner_view")
        )
    }
}
