package com.imageresizer.app.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.imageresizer.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * AdMob rewarded + UMP. Contextual targeting only (no ATT prompt decision —
 * docs/ALGORITHM.md). Nothing here ever touches user content.
 */
class AdMobAds(private val context: Context) : AdServing {

    private var consentChecked = false
    private var cached: RewardedAd? = null
    private var loading = false

    override fun prepare(activity: Activity) {
        try {
            MobileAds.initialize(context)
        } catch (_: Exception) {
            return // ads unavailable → rewards just fail gracefully
        }
        ensureConsent(activity)
    }

    /** One-shot consent check; warms an ad afterwards. Never throws. */
    private fun ensureConsent(activity: Activity) {
        if (consentChecked) {
            warmLoad()
            return
        }
        consentChecked = true
        try {
            val info = UserMessagingPlatform.getConsentInformation(context)
            val params = ConsentRequestParameters.Builder().build()
            info.requestConsentInfoUpdate(
                activity,
                params,
                {
                    // Shows the form only where required (EEA/UK etc.), then loads.
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { warmLoad() }
                },
                { warmLoad() }, // offline → try serving anyway (may be limited)
            )
        } catch (_: Exception) {
            warmLoad()
        }
    }

    private fun warmLoad() {
        if (cached != null || loading) return
        loading = true
        try {
            RewardedAd.load(
                context,
                BuildConfig.REWARDED_AD_UNIT_ID,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        loading = false
                        cached = ad
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loading = false // next showRewarded() retries
                    }
                },
            )
        } catch (_: Exception) {
            loading = false
        }
    }

    override suspend fun showRewarded(activity: Activity): Boolean {
        warmLoad()
        val ad = cached ?: return false
        cached = null // single-use
        return withContext(Dispatchers.Main) { show(ad, activity) }
    }

    private suspend fun show(ad: RewardedAd, activity: Activity): Boolean =
        suspendCancellableCoroutine { continuation ->
            var earned = false
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    warmLoad() // refill for the next opportunity
                    if (continuation.isActive) continuation.resume(earned)
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            try {
                ad.show(activity, OnUserEarnedRewardListener { earned = true })
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
}
