package com.example.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ads.AdBannerDiagnosticListener
import com.example.ads.AdConfig
import com.example.ads.ConsentManager
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import kotlinx.coroutines.delay

private const val TAG = "AdBannerLifecycle"
private const val DIAGNOSTIC_TAG = "AdBannerDiagnostic"
private const val MAX_BANNER_RETRIES = 3

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

@Composable
fun AdBannerView(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.coerceAtLeast(360)
    val density = LocalDensity.current.density

    var isAdLoaded by remember { mutableStateOf(false) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var measuredWidthPx by remember { mutableIntStateOf(0) }
    var measuredHeightPx by remember { mutableIntStateOf(0) }

    val adSize = remember(screenWidth) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, screenWidth)
    }
    val bannerHeight = remember(adSize) {
        val h = adSize.height
        if (h > 0) h.dp.coerceIn(50.dp, 80.dp) else 56.dp
    }

    val isAdsReady by ConsentManager.isAdsReady.collectAsState()
    val currentAdsReady by rememberUpdatedState(isAdsReady)

    val diagnosticListener = remember(context, density, bannerHeight) {
        AdBannerDiagnosticListener(
            adUnitId = AdConfig.BANNER_AD_UNIT_ID,
            onAdLoadedCallback = {
                isAdLoaded = true
                failedAttempts = 0
                logAdBannerLifecycleEvent("onAdLoaded", "Banner ad loaded successfully and visible in layout.")
                logAdBannerDiagnostics(
                    isAdLoaded = true,
                    isAdsReady = currentAdsReady,
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
                failedAttempts++
                logAdBannerLifecycleEvent("onAdFailedToLoad", "AdMob returned load failure (attempt $failedAttempts).", error)
                logAdBannerDiagnostics(
                    isAdLoaded = false,
                    isAdsReady = currentAdsReady,
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

    // Retry with backoff when transient failure occurs
    LaunchedEffect(failedAttempts, isAdsReady) {
        if (!isAdLoaded && failedAttempts in 1..MAX_BANNER_RETRIES && isAdsReady) {
            delay(15_000L * failedAttempts)
            if (!isAdLoaded && ConsentManager.canRequestAds(context) && ConsentManager.isNetworkAvailable(context)) {
                retryTrigger++
            }
        }
    }

    // Trigger ad load when MobileAds is initialized AND consent + network allow
    LaunchedEffect(isAdsReady, retryTrigger) {
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
    }

    DisposableEffect(adView) {
        onDispose {
            logAdBannerLifecycleEvent("destroy", "AdView destroyed on disposal.")
            adView.destroy()
        }
    }

    val canRequest = ConsentManager.canRequestAds(context)
    val networkAvailable = ConsentManager.isNetworkAvailable(context)
    val shouldOccupySpace = isAdLoaded || (isAdsReady && canRequest && networkAvailable && failedAttempts <= MAX_BANNER_RETRIES)

    if (!shouldOccupySpace) {
        // Collapse blank gap cleanly when consent denied, offline, or exhausted retries
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(bannerHeight)
            .heightIn(min = 50.dp, max = 80.dp)
            .defaultMinSize(minHeight = bannerHeight)
            .layout { measurable, constraints ->
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
            }
            .background(MaterialTheme.colorScheme.surface)
            .testTag("admob_banner_container"),
        contentAlignment = Alignment.Center
    ) {
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
