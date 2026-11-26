package com.example.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

enum class BannerAdState {
    IDLE,
    LOADING,
    LOADED,
    FAILED,
    OFFLINE,
    CONSENT_DENIED
}

val BannerStateSemanticsKey = SemanticsPropertyKey<BannerAdState>("BannerAdState")
var SemanticsPropertyReceiver.bannerAdState by BannerStateSemanticsKey

internal fun resolveBannerHeightDp(adSizeHeightDp: Int): Dp {
    return if (adSizeHeightDp > 0) {
        adSizeHeightDp.dp.coerceIn(50.dp, 80.dp)
    } else {
        56.dp
    }
}

internal fun resolveAnchoredBannerAdSize(context: Context, screenWidthDp: Int): AdSize {
    val safeWidth = screenWidthDp.coerceAtLeast(320)
    return try {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, safeWidth)
    } catch (_: Exception) {
        AdSize.BANNER
    }
}

internal fun shouldRequestBannerAd(
    isAdsReady: Boolean,
    canRequestAds: Boolean,
    isNetworkAvailable: Boolean
): Boolean {
    return isAdsReady && canRequestAds && isNetworkAvailable
}

internal fun shouldAttachAdBannerView(
    isAdLoaded: Boolean,
    isAdsReady: Boolean,
    canRequestAds: Boolean
): Boolean {
    return canRequestAds && (isAdLoaded || isAdsReady)
}

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
    modifier: Modifier = Modifier,
    canRequestAdsProvider: (Context) -> Boolean = { ConsentManager.canRequestAds(it) },
    networkAvailableProvider: (Context) -> Boolean = { ConsentManager.isNetworkAvailable(it) },
    adsReadyOverride: Boolean? = null,
    bannerAdStateOverride: BannerAdState? = null,
    attachPlatformAdView: Boolean = true
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val screenWidth = configuration.screenWidthDp.coerceAtLeast(320)
    val orientation = configuration.orientation
    val density = LocalDensity.current.density

    var isAdLoaded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var hasLoadFailed by remember { mutableStateOf(false) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var measuredWidthPx by remember { mutableIntStateOf(0) }
    var measuredHeightPx by remember { mutableIntStateOf(0) }

    var canRequestAds by remember { mutableStateOf(canRequestAdsProvider(context)) }
    var isNetworkAvailable by remember { mutableStateOf(networkAvailableProvider(context)) }

    val adSize = remember(screenWidth, orientation) {
        resolveAnchoredBannerAdSize(context, screenWidth)
    }
    val bannerHeight = remember(adSize) {
        resolveBannerHeightDp(adSize.height)
    }

    val collectedAdsReady by ConsentManager.isAdsReady.collectAsState()
    val isAdsReady = adsReadyOverride ?: collectedAdsReady
    val currentAdsReady by rememberUpdatedState(isAdsReady)

    // Observe connectivity restoration so banner retries automatically when returning online
    DisposableEffect(context, bannerAdStateOverride) {
        if (bannerAdStateOverride != null) {
            return@DisposableEffect onDispose {}
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isNetworkAvailable = true
                if (!isAdLoaded) {
                    failedAttempts = 0
                    hasLoadFailed = false
                    retryTrigger++
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                isNetworkAvailable = connected
                if (connected && !isAdLoaded && hasLoadFailed) {
                    failedAttempts = 0
                    hasLoadFailed = false
                    retryTrigger++
                }
            }

            override fun onLost(network: Network) {
                isNetworkAvailable = networkAvailableProvider(context)
            }
        }
        val registered = try {
            cm?.registerDefaultNetworkCallback(callback)
            cm != null
        } catch (_: Exception) {
            false
        }
        onDispose {
            if (registered) {
                try {
                    cm?.unregisterNetworkCallback(callback)
                } catch (_: Exception) {}
            }
        }
    }

    val adView = if (attachPlatformAdView && bannerAdStateOverride == null) {
        remember(context, adSize) {
            isAdLoaded = false
            isLoading = false
            hasLoadFailed = false
            failedAttempts = 0
            val listener = AdBannerDiagnosticListener(
                adUnitId = AdConfig.BANNER_AD_UNIT_ID,
                onAdLoadedCallback = {
                    isAdLoaded = true
                    isLoading = false
                    hasLoadFailed = false
                    failedAttempts = 0
                    logAdBannerLifecycleEvent("onAdLoaded", "Banner ad loaded successfully and visible in layout.")
                    logAdBannerDiagnostics(
                        isAdLoaded = true,
                        isAdsReady = currentAdsReady,
                        canRequestAds = canRequestAdsProvider(context),
                        isNetworkAvailable = networkAvailableProvider(context),
                        adUnitId = AdConfig.BANNER_AD_UNIT_ID,
                        targetHeightDp = bannerHeight,
                        measuredWidthPx = measuredWidthPx,
                        measuredHeightPx = measuredHeightPx,
                        density = density
                    )
                },
                onAdFailedCallback = { error ->
                    isAdLoaded = false
                    isLoading = false
                    hasLoadFailed = true
                    failedAttempts++
                    logAdBannerLifecycleEvent("onAdFailedToLoad", "AdMob returned load failure (attempt $failedAttempts).", error)
                    logAdBannerDiagnostics(
                        isAdLoaded = false,
                        isAdsReady = currentAdsReady,
                        canRequestAds = canRequestAdsProvider(context),
                        isNetworkAvailable = networkAvailableProvider(context),
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
            AdView(context).apply {
                setAdSize(adSize)
                adUnitId = AdConfig.BANNER_AD_UNIT_ID
                adListener = listener
            }
        }
    } else {
        null
    }

    // Keep AdView lifecycle in sync and re-evaluate consent/network on resume
    DisposableEffect(lifecycleOwner, adView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    canRequestAds = canRequestAdsProvider(context)
                    isNetworkAvailable = networkAvailableProvider(context)
                    adView?.resume()
                    if (!isAdLoaded && !isLoading && shouldRequestBannerAd(currentAdsReady, canRequestAds, isNetworkAvailable)) {
                        retryTrigger++
                    }
                }
                Lifecycle.Event.ON_PAUSE -> adView?.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Retry with backoff when transient failure occurs
    LaunchedEffect(failedAttempts, isAdsReady, isNetworkAvailable, canRequestAds, adView) {
        if (bannerAdStateOverride != null || adView == null) return@LaunchedEffect
        if (!isAdLoaded && failedAttempts in 1..MAX_BANNER_RETRIES && isAdsReady) {
            delay(15_000L * failedAttempts)
            val refreshedConsent = canRequestAdsProvider(context)
            val refreshedNetwork = networkAvailableProvider(context)
            canRequestAds = refreshedConsent
            isNetworkAvailable = refreshedNetwork
            if (!isAdLoaded && refreshedConsent && refreshedNetwork) {
                retryTrigger++
            }
        }
    }

    // Trigger ad load when MobileAds is initialized AND consent + network allow
    LaunchedEffect(isAdsReady, retryTrigger, adView, canRequestAds, isNetworkAvailable) {
        if (bannerAdStateOverride != null || adView == null) return@LaunchedEffect
        val latestConsent = canRequestAdsProvider(context)
        val latestNetwork = networkAvailableProvider(context)
        canRequestAds = latestConsent
        isNetworkAvailable = latestNetwork

        if (shouldRequestBannerAd(isAdsReady, latestConsent, latestNetwork) && !isAdLoaded) {
            isLoading = true
            hasLoadFailed = false
            (adView.adListener as? AdBannerDiagnosticListener)?.markRequestStarted()
            logAdBannerLifecycleEvent("loadAd", "Dispatching ad request to AdMob servers with valid consent.")
            try {
                adView.loadAd(AdRequest.Builder().build())
            } catch (e: Exception) {
                isLoading = false
                hasLoadFailed = true
                Log.e(TAG, "Lifecycle [loadAd]: Exception while requesting banner: ${e.message}", e)
            }
        } else {
            isLoading = false
            Log.d(
                TAG,
                "Lifecycle [loadAd]: Banner request suppressed (isAdsReady=$isAdsReady, " +
                        "canRequestAds=$latestConsent, isNetworkAvailable=$latestNetwork)"
            )
        }
    }

    if (adView != null) {
        DisposableEffect(adView) {
            onDispose {
                logAdBannerLifecycleEvent("destroy", "AdView destroyed on disposal.")
                adView.destroy()
            }
        }
    }

    val effectiveState = bannerAdStateOverride ?: when {
        isAdLoaded -> BannerAdState.LOADED
        !canRequestAds -> BannerAdState.CONSENT_DENIED
        !isNetworkAvailable -> BannerAdState.OFFLINE
        isLoading -> BannerAdState.LOADING
        hasLoadFailed -> BannerAdState.FAILED
        else -> BannerAdState.IDLE
    }

    // Reserve fixed banner container height in ALL states (loaded, loading, failed, offline,
    // consent denied, and orientation changes) so MiniPlayerBar never jumps or shifts layout.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(bannerHeight)
            .heightIn(min = 50.dp, max = 80.dp)
            .defaultMinSize(minHeight = bannerHeight)
            .clipToBounds()
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
            .semantics { bannerAdState = effectiveState }
            .testTag("admob_banner_container"),
        contentAlignment = Alignment.Center
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
            modifier = Modifier.align(Alignment.TopCenter)
        )

        if (adView != null && shouldAttachAdBannerView(isAdLoaded, isAdsReady, canRequestAds)) {
            key(adView) {
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
    }
}
