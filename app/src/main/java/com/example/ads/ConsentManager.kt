package com.example.ads

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages Google User Messaging Platform (UMP) consent requests and MobileAds initialization.
 *
 * Guarantees:
 * - Offloads MobileAds SDK initialization to a background thread (Dispatchers.IO) to keep the main UI thread buttery smooth at app launch.
 * - Always ensures MobileAds is initialized even if UMP is not required in the current region or returns an error.
 * - Safely verifies active network connectivity before triggering ad requests.
 */
object ConsentManager {

    private const val TAG = "ConsentManager"
    private val isMobileAdsInitializeCalled = AtomicBoolean(false)

    private val _isAdsReady = MutableStateFlow(false)
    val isAdsReady: StateFlow<Boolean> = _isAdsReady.asStateFlow()

    /**
     * Checks whether the user has granted consent allowing ad requests according to Google UMP status.
     * Never permits ad requests if consent check fails or encounters an exception.
     */
    fun canRequestAds(context: Context): Boolean {
        return try {
            UserMessagingPlatform.getConsentInformation(context).canRequestAds()
        } catch (e: Exception) {
            Log.w(TAG, "Error checking canRequestAds: ${e.message}")
            false
        }
    }

    /**
     * Checks if the device has an active internet connection to prevent unnecessary socket overhead or playback interruption.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Request consent information update and show consent form if required.
     * When consent is gathered, initialize Google Mobile Ads SDK on a background thread.
     */
    fun gatherConsent(activity: Activity, onConsentGathered: () -> Unit) {
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()

        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)

        // If cached consent already permits requesting ads immediately, trigger initialization right away
        if (consentInformation.canRequestAds()) {
            initializeMobileAds(activity, onConsentGathered)
        }

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form error: ${formError.errorCode} - ${formError.message}")
                    }
                    if (consentInformation.canRequestAds()) {
                        initializeMobileAds(activity, onConsentGathered)
                    } else {
                        Log.i(TAG, "Consent form completed without granting ad consent. MobileAds not initialized.")
                    }
                }
            },
            { requestConsentError ->
                Log.w(TAG, "Consent info update failed: ${requestConsentError.errorCode} - ${requestConsentError.message}")
                // Do NOT automatically request ads or initialize MobileAds if consent verification fails and consent is not given
                if (consentInformation.canRequestAds()) {
                    initializeMobileAds(activity, onConsentGathered)
                } else {
                    Log.w(TAG, "Consent update failed and cannot request ads. Ads will NOT be initialized.")
                }
            }
        )
    }

    /**
     * Initializes Google Mobile Ads SDK on a background thread (Dispatchers.IO)
     * so it never blocks the main UI thread during startup.
     */
    fun initializeMobileAds(context: Context, onInitialized: () -> Unit) {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            if (_isAdsReady.value) {
                onInitialized()
            }
            return
        }

        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MobileAds.initialize(appContext) { initializationStatus ->
                    Log.i(TAG, "MobileAds initialized on background thread: $initializationStatus")
                    CoroutineScope(Dispatchers.Main).launch {
                        _isAdsReady.value = true
                        onInitialized()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MobileAds background initialization encountered an error: ${e.message}", e)
                CoroutineScope(Dispatchers.Main).launch {
                    _isAdsReady.value = false
                }
            }
        }
    }
}
