package com.imageresizer.app.ads

import android.app.Activity

/**
 * Seam (docs/ALGORITHM.md): UI depends only on this interface so the AdMob
 * implementation can be swapped (or faked in tests) without touching product code.
 */
interface AdServing {
    /**
     * Consent (UMP) + warm load. Called once with an Activity (consent flows
     * need one). Best effort: failures never throw, ads just may not serve.
     */
    fun prepare(activity: Activity)

    /**
     * Show a rewarded ad.
     * @return true only if the reward callback actually fired.
     */
    suspend fun showRewarded(activity: Activity): Boolean

    /**
     * Show a full-screen interstitial at a natural transition (batch → home).
     * Best effort: returns false immediately when nothing is loaded, so a
     * not-ready ad never delays the user.
     * @return true if an ad was actually shown.
     */
    suspend fun showInterstitial(activity: Activity): Boolean
}
